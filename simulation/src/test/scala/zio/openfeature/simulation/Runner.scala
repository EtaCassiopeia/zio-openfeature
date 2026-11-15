package zio.openfeature.simulation

import zio._
import zio.openfeature._
import zio.openfeature.testkit.RecordingHook

/** Runs a command sequence against a live stack and the reference model, sequentially. Fails with a readable trace at
  * the first divergence.
  */
object Runner {

  private val MaxStates = 64

  final case class World(global: Ctx, client: Ctx, states: List[StackState], built: Built, retired: List[Built])

  def mergedFor(w: World, tx: Option[Ctx], scoped: Option[Ctx], inv: Ctx): Ctx = {
    val withTx = tx.fold(w.global)(Ctx.merge(w.global, _))
    val lvls   = List(Some(w.client), scoped, Some(inv)).flatten
    lvls.foldLeft(withTx)(Ctx.merge)
  }

  /** Every model prediction for one provider-reaching read, paired with the state it leaves. */
  def predict(w: World, key: String, kind: Kind, merged: Ctx): List[(Outcome, StackState)] = {
    val fp = Ctx.rendered(merged)
    w.states.flatMap { s =>
      Model.eval(s.spec, kind.wire, key, fp, Harness.fitsInt(kind), s).map { case (r, s2) =>
        Harness.lift(kind, r) -> s2
      }
    }
  }

  def run(spec0: Spec, cmds: List[Cmd]): ZIO[Any, String, Unit] = {
    val spec                                         = Spec.number(spec0)
    val built                                        = Harness.build(spec)
    val trace                                        = new StringBuilder(s"stack ${Spec.show(spec)}\n")
    def fail(msg: String): ZIO[Any, String, Nothing] = ZIO.fail(trace.append(s"!! $msg\n").toString)
    for {
      last <- Ref.make(built)
      _    <- runScoped(spec, built, cmds, trace, fail, last)
      b    <- last.get
      // Closing the scope shuts the installed stack down: each leaf exactly once.
      _ <- awaitShutdown(b).flatMap(ZIO.foreach(_)(p => fail(s"after scope close: $p")))
    } yield ()
  }

  private def runScoped(
    spec: Spec,
    built: Built,
    cmds: List[Cmd],
    trace: StringBuilder,
    fail: String => ZIO[Any, String, Nothing],
    last: Ref[Built]
  ): ZIO[Any, String, Unit] = ZIO.scoped {

    for {
      pair <- Harness.install(built.provider).mapError(e => s"install failed for ${Spec.show(spec)}: $e")
      (ff, ad) = pair
      hook   <- RecordingHook.make()
      _      <- ff.addHook(hook)
      ref    <- Ref.make(World(Ctx.empty, Ctx.empty, List(StackState.initial(spec)), built, Nil))
      nextId <- Ref.make(Spec.maxId(spec) + 1)

      evalOne = (key: String, kind: Kind, inv: Ctx, scoped: Option[Ctx]) =>
        for {
          w <- ref.get
          _ <- hook.clear
          eff = Harness.read(ff, key, kind, inv.toEvaluationContext)
          got   <- scoped.fold(eff)(c => ff.withContext(c.toEvaluationContext)(eff))
          calls <- hook.calls
          merged = mergedFor(w, None, scoped, inv)
          preds  = predict(w, key, kind, merged)
          ok     = preds.filter(_._1 == got)
          _ <- ZIO.succeed(trace.append(s"eval $key:$kind ctx=${Ctx.rendered(merged)} -> $got\n"))
          _ <- ZIO.when(ok.isEmpty)(fail(s"expected one of ${preds.map(_._1).distinct.mkString(" | ")}"))
          _ <- ZIO.when(Harness.stages(calls) != Harness.expectedStages(got))(
            fail(s"hook stages ${Harness.stages(calls)} for $got")
          )
          _ <- ZIO.succeed(record(w.states, ok.map(_._2), got))
          _ <- ref.set(w.copy(states = ok.map(_._2).distinct.take(MaxStates)))
        } yield ()

      _ <- ZIO.foreachDiscard(cmds) {
        case Cmd.Eval(key, kind, inv, scoped) => evalOne(key, kind, inv, scoped)

        case Cmd.SetGlobal(c) =>
          ZIO.succeed(trace.append(s"global ${Ctx.rendered(c)}\n")) *>
            ff.setGlobalContext(c.toEvaluationContext) *> ref.update(_.copy(global = c))

        case Cmd.SetClient(c) =>
          ZIO.succeed(trace.append(s"client ${Ctx.rendered(c)}\n")) *>
            ff.setClientContext(c.toEvaluationContext) *> ref.update(_.copy(client = c))

        case Cmd.Tx(overrides, txCtx, cacheEvals, evals) =>
          for {
            _ <- ZIO.succeed(
              trace.append(
                s"tx overrides=${overrides.keys.toList.sorted} ctx=${Ctx.rendered(txCtx)} cache=$cacheEvals\n"
              )
            )
            txc <- Ref.make(Map.empty[String, Outcome])
            res <- ff
              .transaction(
                overrides.map { case (k, v) => k -> Harness.overrideValue(v) },
                txCtx.toEvaluationContext,
                cacheEvals
              ) {
                ZIO.foreachDiscard(evals) { case Cmd.Eval(key, kind, inv, _) =>
                  for {
                    w     <- ref.get
                    _     <- hook.clear
                    got   <- Harness.read(ff, key, kind, inv.toEvaluationContext)
                    calls <- hook.calls
                    cache <- txc.get
                    merged = mergedFor(w, Some(txCtx), None, inv)
                    _ <- ZIO.succeed(trace.append(s"  tx eval $key:$kind ctx=${Ctx.rendered(merged)} -> $got\n"))
                    _ <- overrides.get(key) match {
                      case Some(ov) =>
                        val exp = Harness.overrideOutcome(kind, ov)
                        ZIO.when(got != exp)(fail(s"override: expected $exp")) *>
                          // docs/hooks.md: values served by an override run hooks too.
                          ZIO.when(Harness.stages(calls) != Harness.expectedStages(got))(
                            fail(s"override hook stages ${Harness.stages(calls)}")
                          )
                      case None =>
                        cache.get(key).filter(_ => cacheEvals) match {
                          case Some(hit) =>
                            val exp = hit.copy(reason = "CACHED")
                            ZIO.when(got != exp)(fail(s"tx cache: expected $exp")) *>
                              ZIO.when(Harness.stages(calls) != Harness.expectedStages(got))(
                                fail(s"tx cache hook stages ${Harness.stages(calls)}")
                              )
                          case None =>
                            val preds = predict(w, key, kind, merged)
                            val ok    = preds.filter(_._1 == got)
                            ZIO.when(ok.isEmpty)(
                              fail(s"expected one of ${preds.map(_._1).distinct.mkString(" | ")}")
                            ) *>
                              ZIO.when(Harness.stages(calls) != Harness.expectedStages(got))(
                                fail(s"hook stages ${Harness.stages(calls)} for $got")
                              ) *>
                              ref.set(w.copy(states = ok.map(_._2).distinct.take(MaxStates))) *>
                              ZIO.when(got.isSuccess)(txc.update(_.updated(key, got)))
                        }
                    }
                  } yield ()
                }
              }
              .either
            _ <- res match {
              case Left(e: String) => ZIO.fail(e)
              case Left(other)     => fail(s"transaction failed: $other")
              case Right(_)        => ZIO.unit
            }
          } yield ()

        case Cmd.SetFlag(idx, key, value, emit) =>
          for {
            w <- ref.get
            ids  = w.built.leaves.keys.toList.sorted
            id   = ids(idx % ids.size)
            leaf = w.built.leaves(id)
            _ <- ZIO.succeed(trace.append(s"set L$id.$key = $value emit=$emit\n"))
            _ <- ZIO.attemptBlocking {
              value.fold(leaf.flags.remove(key): Unit)(v => leaf.flags.put(key, v): Unit)
              if (emit) leaf.configurationChanged()
            }.orDie
            states = w.states.map { s =>
              val s2 = s.copy(leafFlags =
                s.leafFlags.updated(
                  id,
                  value.fold(s.leafFlags.getOrElse(id, Map.empty) - key)(v =>
                    s.leafFlags.getOrElse(id, Map.empty).updated(key, v)
                  )
                )
              )
              if (emit) Model.notify(s2, id) else s2
            }
            _ <- ref.set(w.copy(states = states.distinct))
          } yield ()

        case Cmd.SetFault(idx, fault) =>
          for {
            w <- ref.get
            ids = w.built.leaves.keys.toList.sorted
            id  = ids(idx % ids.size)
            _ <- ZIO.succeed(trace.append(s"fault L$id = $fault\n"))
            _ <- ZIO.succeed(w.built.leaves(id).fault.set(fault))
            _ <- ref.set(w.copy(states = w.states.map { s =>
              s.copy(leafFaults = fault.fold(s.leafFaults - id)(f => s.leafFaults.updated(id, f)))
            }.distinct))
          } yield ()

        case Cmd.Invalidate(idx) =>
          for {
            w <- ref.get
            ids = w.built.caches.keys.toList.sorted
            _ <- ZIO.when(ids.nonEmpty) {
              val id = ids(idx % ids.size)
              ZIO.succeed(trace.append(s"invalidate Cache$id\n")) *>
                w.built.caches(id).invalidateAll *>
                ref.set(
                  w.copy(states = w.states.map(s => s.copy(cacheEntries = s.cacheEntries.updated(id, Map.empty))))
                )
            }
          } yield ()

        case Cmd.Track(name) =>
          for {
            w <- ref.get
            before = w.built.leaves.view.mapValues(_.tracked.get()).toMap
            r <- ff.track(name).either
            after = w.built.leaves.view.mapValues(_.tracked.get()).toMap
            _ <- ZIO.succeed(
              trace.append(
                s"track $name -> $r, leaves ${after.toList.sorted.map { case (i, n) => s"L$i+${n - before(i)}" }.mkString(" ")}\n"
              )
            )
            _ <- ZIO.when(r.isLeft)(fail(s"track failed: $r"))
            _ <- ZIO.when(after.exists { case (i, n) => n - before(i) != 1 })(
              fail("track did not reach every leaf exactly once")
            )
          } yield ()

        case Cmd.Swap(newSpec0) =>
          for {
            _    <- ZIO.succeed(Stats.hit("swap"))
            w    <- ref.get
            from <- nextId.get
            ns = Spec.number(newSpec0, from)
            _ <- nextId.set(Spec.maxId(ns) + 1)
            _ <-
              if (Spec.nameThrowsBeforeInit(ns))
                ZIO.succeed {
                  Stats.hit("swap.skippedNameThrows")
                  trace.append(s"swap to ${Spec.show(ns)} skipped (name read throws)\n")
                }.unit
              else {
                val nb = Harness.build(ns)
                for {
                  _ <- ZIO.succeed(trace.append(s"swap to ${Spec.show(ns)}\n"))
                  r <- ad.setProvider(nb.provider).sandbox.either
                  _ <- ZIO.when(r.isLeft)(fail(s"setProvider failed: $r"))
                  _ <- ref.set(World(w.global, w.client, List(StackState.initial(ns)), nb, w.built :: w.retired))
                  _ <- last.set(nb)
                  // The replaced stack is shut down: each of its leaves exactly once, and none answers afterwards.
                  _ <- awaitShutdown(w.built).flatMap(ZIO.foreach(_)(fail))
                } yield ()
              }
          } yield ()
      }
      w <- ref.get
      _ <- ZIO.foreachDiscard((w.built :: w.retired).flatMap(_.leaves.values)) { l =>
        ZIO.when(l.callsAfterShutdown.get() > 0)(
          fail(s"${l.name} answered ${l.callsAfterShutdown.get()} calls after shutdown")
        )
      }
    } yield ()
  }

  /** Coverage: what one read exercised, judged by how the surviving model states differ from the ones before it. */
  private def record(before: List[StackState], after: List[StackState], got: Outcome): Unit = {
    Stats.hit("eval")
    if (got.isSuccess) Stats.hit(s"ok.${got.reason}") else Stats.hit(s"err.${got.code.getOrElse("?")}")
    if (after.size > 1) Stats.hit("ambiguous")
    if (after.exists(_.breakerOpen.nonEmpty)) Stats.hit("breakerOpen")
    if (after.exists(a => before.forall(_.breakerOpen.size < a.breakerOpen.size))) Stats.hit("breakerOpened")
    if (after.exists(_.uncertain.nonEmpty)) Stats.hit("cacheUncertain")
    after.headOption.foreach(s => Stats.hit(s"stack.depth${depth(s.spec)}"))
  }

  private def depth(s: Spec): Int = s match {
    case _: Spec.Leaf      => 0
    case Spec.Cache(_, in) => 1 + depth(in)
    case b: Spec.Breaker   => 1 + depth(b.inner)
    case Spec.Deferred(in) => 1 + depth(in)
    case Spec.Widen(in)    => 1 + depth(in)
    case Spec.Multi(_, cs) => 1 + cs.map(depth).maxOption.getOrElse(0)
  }

  /** Wait (bounded, live clock) until every leaf of `b` is shut down; `Some(problem)` if one is not, or twice. */
  def awaitShutdown(b: Built): UIO[Option[String]] = {
    val check = ZIO.succeed {
      val bad = b.leaves.values.filter(_.shutdowns.get() != 1)
      if (bad.isEmpty) None else Some(bad.map(l => s"${l.name} shutdowns=${l.shutdowns.get()}").mkString(", "))
    }
    def loop(n: Int): UIO[Option[String]] =
      check.flatMap {
        case None           => ZIO.none
        case some if n <= 0 => ZIO.succeed(some)
        case _              => ZIO.sleep(20.millis) *> loop(n - 1)
      }
    loop(250).withClock(Clock.ClockLive)
  }
}
