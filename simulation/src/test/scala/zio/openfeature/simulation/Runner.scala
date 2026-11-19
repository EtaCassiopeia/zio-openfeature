package zio.openfeature.simulation

import zio._
import zio.openfeature._
import zio.openfeature.testkit.{LifecycleModel, RecordingHook}
import zio.test.{Annotations, Live, TestClock}

/** Runs a command sequence against a live stack and the reference model, sequentially. Fails with a readable trace at
  * the first divergence.
  *
  * Time is the test's `TestClock`: a stalled evaluation is timed out (or not) by moving it, so stalls cost no real
  * time. Waits for something the SDK does on its own threads are bounded on the live clock.
  */
object Runner {

  private val MaxStates = 64

  /** `lifecycle` is the documented lifecycle model, stepped by each swap; `stacks` counts the stacks swapped to. */
  final case class World(
    global: Ctx,
    client: Ctx,
    states: List[StackState],
    built: Built,
    retired: List[Built],
    lifecycle: LifecycleModel.Model,
    stacks: Int
  )

  /** One model prediction for a provider-reaching read: the outcome, the state it leaves, and the stall it hits. */
  final case class Prediction(outcome: Outcome, state: StackState, stall: Option[StallMark])

  def mergedFor(global: Ctx, client: Ctx, tx: Option[Ctx], scoped: Option[Ctx], inv: Ctx): Ctx = {
    val withTx = tx.fold(global)(Ctx.merge(global, _))
    val lvls   = List(Some(client), scoped, Some(inv)).flatten
    lvls.foldLeft(withTx)(Ctx.merge)
  }

  def mergedFor(w: World, tx: Option[Ctx], scoped: Option[Ctx], inv: Ctx): Ctx =
    mergedFor(w.global, w.client, tx, scoped, inv)

  /** Every model prediction for one provider-reaching read.
    *
    * A read stalled past its bound with no breaker below the root to time it out ends at the core's evaluation timeout.
    * The call it walked away from is still running: once released, the leaf throws and the stack goes on from there (a
    * first-successful multi-provider tries its next child, a cache above it stores nothing), so the state it leaves is
    * the one evaluating with that leaf throwing once leaves.
    */
  def predict(w: World, key: String, kind: Kind, merged: Ctx): List[Prediction] = {
    val fp   = Ctx.rendered(merged)
    val fits = Harness.fitsInt(kind)
    w.states.flatMap { s0 =>
      val s = s0.settled
      Model.eval(s.spec, kind.wire, key, fp, fits, s).flatMap { case (r, s2) =>
        r match {
          case PResult.Stalled(leaf) =>
            Model
              .eval(s.spec, kind.wire, key, fp, fits, s.copy(leafFaults = s.leafFaults - leaf, abandoned = Some(leaf)))
              .collect {
                case (_, s3) if s3.abandoned.isEmpty => Prediction(Harness.coreTimeout, s3.settled, s2.stalled)
              }
          case _ => List(Prediction(Harness.lift(kind, r), s2.settled, s2.stalled))
        }
      }
    }
  }

  /** Runs on a fresh `TestClock`: every read leaves its timers behind in the clock's queue, and a clock shared by every
    * run would make each later move of it fire all of them.
    */
  def run(spec0: Spec, cmds: List[Cmd]): ZIO[Annotations with Live, String, Unit] =
    ZIO.scoped {
      TestClock
        .live(TestClock.Data(java.time.Instant.EPOCH, Nil, java.time.ZoneId.of("UTC")))
        .build
        .flatMap(env => ZIO.withClock(env.get[TestClock])(runOnClock(spec0, cmds)))
    }

  private def runOnClock(spec0: Spec, cmds: List[Cmd]): ZIO[Any, String, Unit] = {
    val spec                                         = Spec.number(spec0)
    val trace                                        = new StringBuilder(s"stack ${Spec.show(spec)}\n")
    def fail(msg: String): ZIO[Any, String, Nothing] = ZIO.fail(trace.append(s"!! $msg\n").toString)
    for {
      built <- Harness.build(spec, timed = true)
      last  <- Ref.make(built)
      _     <- runScoped(spec, built, cmds, trace, fail, last)
      b     <- last.get
      // Closing the scope shuts the installed stack down: each leaf exactly once.
      _ <- awaitShutdown(b).flatMap(ZIO.foreach(_)(p => fail(s"after scope close: $p")))
    } yield ()
  }

  /** Polls `cond` on the live clock until it holds, failing with `what` after about 10 s. */
  private def waitForZ(cond: UIO[Boolean], what: String, fail: String => ZIO[Any, String, Nothing]) = {
    def loop(n: Int): ZIO[Any, String, Unit] =
      cond.flatMap { ok =>
        if (ok) ZIO.unit
        else if (n <= 0) fail(s"timed out waiting until $what")
        else ZIO.sleep(1.milli).withClock(Clock.ClockLive) *> loop(n - 1)
      }
    loop(10000)
  }

  private def waitFor(cond: => Boolean, what: String, fail: String => ZIO[Any, String, Nothing]) =
    waitForZ(ZIO.succeed(cond), what, fail)

  /** Waits until `TestClock.sleeps` has not changed for 20 ms (live). */
  private val settled: UIO[Unit] = {
    def loop(prev: List[java.time.Instant], n: Int): UIO[Unit] =
      ZIO.sleep(20.millis).withClock(Clock.ClockLive) *> TestClock.sleeps.flatMap { now =>
        if (now == prev || n <= 0) ZIO.unit else loop(now, n - 1)
      }
    TestClock.sleeps.flatMap(loop(_, 100))
  }

  /** Joins `fiber`, bounded on the live clock. */
  private def joinBounded[A](fiber: Fiber[Nothing, A], what: String, fail: String => ZIO[Any, String, Nothing]) =
    fiber.join
      .timeout(10.seconds)
      .withClock(Clock.ClockLive)
      .flatMap(_.fold(fail(s"$what did not complete"))(ZIO.succeed(_)))

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
      hook <- RecordingHook.make()
      _    <- ff.addHook(hook)
      ref  <- Ref.make(
        World(
          Ctx.empty,
          Ctx.empty,
          List(StackState.initial(spec)),
          built,
          Nil,
          LifecycleModel.Model.initial(LifecycleModel.Installed("S0", "")),
          0
        )
      )
      nextId <- Ref.make(Spec.maxId(spec) + 1)

      // A read that may stall runs forked, outside supervision. Once its leaf has blocked and every timer the read
      // starts on the way there is registered, the clock moves to just past (or just short of) the timeout that ends
      // the call, and the leaf is released. Returns the outcome and the stall it hit.
      read = (w: World, eff: UIO[Outcome], preds: List[Prediction]) =>
        preds.flatMap(_.stall).headOption match {
          case None       => eff.map(_ -> Option.empty[StallMark])
          case Some(mark) =>
            val gate = w.built.leaves(mark.leaf).stallGate.get()
            for {
              t0      <- Clock.instant
              sleeps0 <- TestClock.sleeps
              fiber   <- zio.SimulationSupervision.unsupervised(eff.fork)
              _       <- waitForZ(fiber.poll.map(done => gate.entered || done.isDefined), s"L${mark.leaf} stalls", fail)
              got     <-
                if (!gate.entered)
                  joinBounded(fiber, "a read that did not stall", fail).map(_ -> Option.empty[StallMark])
                else {
                  val (bound, core) = Harness.stallBound(w.built.spec, mark.leaf)
                  val abandon       = mark.pastBound && core
                  val deadline      = t0.plus(bound)
                  for {
                    // The timer that ends the call: its deadline is unique to it (the core's, or that breaker's), but an
                    // earlier read may have left one at the same instant, and a timer that lost its race to a fast call
                    // may never register. So: one more than before at that deadline, and then nothing new for 20 ms.
                    _ <- waitForZ(
                      TestClock.sleeps.map(_.count(_ == deadline) > sleeps0.count(_ == deadline)),
                      s"the timer of the read stalled at L${mark.leaf} has started",
                      fail
                    )
                    _ <- settled
                    _ <- TestClock.adjust(if (mark.pastBound) bound else bound.minus(1.milli))
                    _ <- ZIO.succeed(gate.release(abandon))
                    o <- joinBounded(fiber, s"a read stalled at L${mark.leaf}", fail)
                    _ <- waitFor(gate.exited, s"the call stalled at L${mark.leaf} returns", fail)
                    _ <- ZIO.when(abandon)(waitFor(Harness.abandonedCalls(ff) == 0, "the abandoned call returns", fail))
                    _ <- Stats.generated(
                      if (!mark.pastBound) "fault.stall.under"
                      else if (core) "fault.stall.past.core"
                      else "fault.stall.past.breaker"
                    )
                  } yield o -> Some(mark)
                }
            } yield got
        }

      evalChecked = (w: World, key: String, kind: Kind, eff: UIO[Outcome], merged: Ctx, indent: String) =>
        for {
          _ <- hook.clear
          preds = predict(w, key, kind, merged)
          res   <- read(w, eff, preds)
          calls <- hook.calls
          (got, stall) = res
          ok           = preds.filter(p => p.outcome == got && p.stall.isDefined == stall.isDefined)
          _ <- ZIO.succeed(
            trace.append(
              s"${indent}eval $key:$kind ctx=${Ctx.rendered(merged)} -> $got${stall
                  .fold("")(m => s" (stalled at L${m.leaf}${if (m.pastBound) " past its bound" else ""})")}\n"
            )
          )
          _ <- ZIO.when(ok.isEmpty)(
            fail(
              s"expected one of ${preds.map(p => p.outcome.toString + p.stall.fold("")(_ => " stalled")).distinct.mkString(" | ")}"
            )
          )
          _ <- ZIO.when(Harness.stages(calls) != Harness.expectedStages(got))(
            fail(s"hook stages ${Harness.stages(calls)} for $got")
          )
        } yield (preds, ok, got)

      evalOne = (key: String, kind: Kind, inv: Ctx, scoped: Option[Ctx]) =>
        for {
          w <- ref.get
          eff    = Harness.read(ff, key, kind, inv.toEvaluationContext)
          merged = mergedFor(w, None, scoped, inv)
          res <- evalChecked(
            w,
            key,
            kind,
            scoped.fold(eff)(c => ff.withContext(c.toEvaluationContext)(eff)),
            merged,
            ""
          )
          (preds, ok, got) = res
          _ <- ZIO.succeed(record(preds.map(_.state), ok.map(_.state), got))
          _ <- ref.set(w.copy(states = ok.map(_.state).distinct.take(MaxStates)))
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
                    cache <- txc.get
                    eff    = Harness.read(ff, key, kind, inv.toEvaluationContext)
                    merged = mergedFor(w, Some(txCtx), None, inv)
                    served = overrides
                      .get(key)
                      .map(ov => "override" -> Harness.overrideOutcome(kind, ov))
                      .orElse(
                        cache.get(key).filter(_ => cacheEvals).map(hit => "tx cache" -> hit.copy(reason = "CACHED"))
                      )
                    _ <- served match {
                      // docs/hooks.md: values served by an override or the transaction cache run hooks too.
                      case Some((what, exp)) =>
                        for {
                          _     <- hook.clear
                          got   <- eff
                          calls <- hook.calls
                          _ <- ZIO.succeed(trace.append(s"  tx eval $key:$kind ctx=${Ctx.rendered(merged)} -> $got\n"))
                          _ <- ZIO.when(got != exp)(fail(s"$what: expected $exp"))
                          _ <- ZIO.when(Harness.stages(calls) != Harness.expectedStages(got))(
                            fail(s"$what hook stages ${Harness.stages(calls)}")
                          )
                        } yield ()
                      case None =>
                        for {
                          res <- evalChecked(w, key, kind, eff, merged, "  tx ")
                          (_, ok, got) = res
                          _ <- ref.set(w.copy(states = ok.map(_.state).distinct.take(MaxStates)))
                          _ <- ZIO.when(got.isSuccess)(txc.update(_.updated(key, got)))
                        } yield ()
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
            // At most one stall is pending at a time, so a read stalls at most once: a new stall clears any other.
            cleared = fault match {
              case Some(_: LeafFault.Stall) =>
                ids.filter(i => i != id && w.built.leaves(i).fault.get().exists(_.isInstanceOf[LeafFault.Stall]))
              case _ => Nil
            }
            _ <- ZIO.succeed(trace.append(s"fault L$id = $fault\n"))
            _ <- ZIO.succeed {
              cleared.foreach(i => w.built.leaves(i).fault.set(None))
              if (fault.exists(_.isInstanceOf[LeafFault.Stall])) w.built.leaves(id).stallGate.set(new StallGate)
              w.built.leaves(id).fault.set(fault)
            }
            _ <- ref.set(w.copy(states = w.states.map { s =>
              val s2 = s.copy(leafFaults = s.leafFaults -- cleared)
              s2.copy(leafFaults = fault.fold(s2.leafFaults - id)(f => s2.leafFaults.updated(id, f)))
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
            _  <- nextId.set(Spec.maxId(ns) + 1)
            _  <- ZIO.succeed(if (Spec.wrapsMulti(ns)) Stats.hit("swap.wrapsMulti"))
            nb <- Harness.build(ns, timed = true)
            _  <- ZIO.succeed(trace.append(s"swap to ${Spec.show(ns)}\n"))
            outcome  = Spec.initOutcome(ns)
            stackNo  = w.stacks + 1
            incoming = LifecycleModel.Installed(s"S$stackNo", "")
            kind     = outcome match {
              case InitOutcome.Ok               => LifecycleModel.Kind.Good
              case InitOutcome.Fails            => LifecycleModel.Kind.FailingInit
              case InitOutcome.Stalls(thenFail) =>
                if (thenFail) LifecycleModel.Kind.HangThenFail else LifecycleModel.Kind.HangingInit
            }
            started = w.lifecycle.step(LifecycleModel.Action.SetProvider(kind), incoming)
            _ <- ZIO.foreachDiscard(Spec.leaves(ns).flatMap(_.shutdown))(_ => Stats.generated("shutdown.throws"))
            r <- outcome match {
              case InitOutcome.Stalls(thenFail) =>
                // A fixed protocol: the swap waits in `initialize()` until every stalling leaf has started; meanwhile
                // the status is the lifecycle model's (NotReady) and a read is rejected; then the leaves go on.
                val stalling = Spec.leaves(ns).collect {
                  case l if l.init.exists(f => f == InitFault.StallThenOk || f == InitFault.StallThenFail) =>
                    nb.leaves(l.id)
                }
                for {
                  _      <- Stats.generated(if (thenFail) "init.stallFail" else "init.stallOk")
                  fiber  <- ad.setProvider(nb.provider).sandbox.either.fork
                  _      <- waitFor(stalling.forall(_.initGate.entered), "every stalling leaf is initializing", fail)
                  status <- ff.providerStatus
                  _      <- ZIO.when(status != started.status)(
                    fail(s"while the swap's initialize() stalls: providerStatus is $status, expected ${started.status}")
                  )
                  probe <- Harness.read(ff, "k0", Kind.KBool, EvaluationContext.empty)
                  _     <- ZIO.succeed(trace.append(s"  initialize() stalled: status $status, a read -> $probe\n"))
                  _     <- ZIO.when(!started.serving && probe.code != Some("PROVIDER_NOT_READY"))(
                    fail(s"a read while the swap's initialize() stalls gave $probe, expected ERR(PROVIDER_NOT_READY)")
                  )
                  _ <- ZIO.succeed(stalling.foreach(_.initGate.release(abandon = false)))
                  r <- joinBounded(fiber, "a swap whose initialize() was released", fail)
                } yield r
              case InitOutcome.Fails => Stats.generated("init.fails") *> ad.setProvider(nb.provider).sandbox.either
              case InitOutcome.Ok    => ad.setProvider(nb.provider).sandbox.either
            }
            lifecycle = outcome match {
              case _: InitOutcome.Stalls => started.step(LifecycleModel.Action.ReleaseHangs, incoming)
              case _                     => started
            }
            fails = outcome == InitOutcome.Fails || outcome == InitOutcome.Stalls(thenFail = true)
            _ <- ZIO.succeed(trace.append(s"  -> ${r.fold(c => s"failed: ${c.squash}", _ => "ok")}\n"))
            _ <- (fails, r) match {
              case (true, Left(c))
                  if c.failureOption.exists(
                    _.isInstanceOf[FeatureFlagError.ProviderInitializationFailed]
                  ) && c.defects.isEmpty =>
                ZIO.unit
              case (true, _) =>
                fail(s"setProvider to a stack whose initialize() fails: expected ProviderInitializationFailed, got $r")
              case (false, Left(_)) => fail(s"setProvider failed: $r")
              case (false, _)       => ZIO.unit
            }
            status <- ff.providerStatus
            _      <- ZIO.when(status != lifecycle.status)(
              fail(s"after the swap: providerStatus is $status, expected ${lifecycle.status}")
            )
            _ <-
              if (!fails)
                for {
                  _ <- ref.set(
                    World(
                      w.global,
                      w.client,
                      List(StackState.initial(ns)),
                      nb,
                      w.built :: w.retired,
                      lifecycle,
                      stackNo
                    )
                  )
                  _ <- last.set(nb)
                  // The replaced stack is shut down: each of its leaves exactly once, and none answers afterwards.
                  _ <- awaitShutdown(w.built).flatMap(ZIO.foreach(_)(fail))
                } yield ()
              else
                for {
                  // Rolled back: the previous stack serves again, re-initialized with its state; the failed stack is
                  // shut down, each leaf exactly once, and never answers again.
                  _ <- Stats.generated("swap.rolledBack")
                  _ <- ref.set(w.copy(retired = nb :: w.retired, lifecycle = lifecycle, stacks = stackNo))
                  _ <- awaitShutdown(nb).flatMap(ZIO.foreach(_)(p => fail(s"failed stack: $p")))
                  _ <- ZIO.foreachDiscard(w.built.leaves.values)(l =>
                    ZIO.when(l.shutdowns.get() != 0)(fail(s"${l.name} of the restored stack was shut down"))
                  )
                } yield ()
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
