package zio.openfeature.simulation

import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider}
import zio._
import zio.openfeature._
import zio.openfeature.internal.{LifecycleProbe, ManagedProvider}
import zio.openfeature.testkit.{HookCall, RecordingHook, ScriptedInitDeadline, TraceProbe}
import zio.openfeature.testsupport.{LeakCheck, PropertyBudget}
import zio.test.{Spec => _, _}

/** Concurrent simulation: one random stack, evaluation workers racing a swapper and a mutator (flag changes, change
  * events, faults). It checks invariants, and each value against the recorded history ([[History]]):
  *   - every evaluation completes (bounded) with a value or a default plus an error code, never a defect;
  *   - hooks: every evaluation runs `before`, one of `after`/`error`, and `finally`, so the totals match;
  *   - each successful answer comes from a leaf of a stack installed during the evaluation, so an evaluation that
  *     starts after `setProvider` returned is never answered by a leaf of the replaced stack;
  *   - each successful value is one its leaf held during the evaluation, or for a cache hit no older than the last
  *     completed invalidation that reached that cache synchronously;
  *   - an evaluation answered by a leaf after a swap away from it raised its gate is never a success;
  *   - a swap to a stack whose `initialize()` fails fails with a typed error and rolls back: the failed stack's leaves
  *     are shut down exactly once and never answer; the previous stack serves again;
  *   - each replaced stack's leaves are shut down exactly once, and so are the last stack's when the scope closes; a
  *     stack a rollback lands on after its own `initialize()` failed is registered again, so each of its leaves is shut
  *     down at least once and at most once per `initialize()` call instead;
  *   - a shutdown issued mid-run (`shutdownAfterSwap`), during a swap or after the last one, waits for a swap in flight
  *     and leaves the status NotReady for good; every later swap is refused without its provider being touched, and
  *     every evaluation that starts after the shutdown returned fails; each stack is still shut down exactly once;
  *   - a swap whose stack stalls in `initialize()` times out when the run fires its deadline
  *     ([[ScriptedInitDeadline]]), with no rollback: its stack stays bound, and the stack it replaced is not shut down
  *     before the stalled call settles, which the plan releases at a chosen point (`releases`); a later swap that fails
  *     over a stack still initializing is not rolled back either; no leaf runs two `initialize()` calls at once;
  *   - no fiber or thread outlives the run (LeakCheck);
  *   - the lifecycle model's safety invariants hold on the live record at every lifecycle probe point and after every
  *     command ([[ModelInvariants]]).
  *
  * With `ZOF_TRACE=1` every plan's run, passed or failed, is also recorded for the lifecycle model's trace check
  * (`formal/lifecycle/validate.py`), with the plan beside it ([[SimulationTrace]]).
  *
  * Each run goes through a [[SchedulePerturber]] seeded by the plan's `scheduleSeed`: at every lifecycle and evaluation
  * probe point it passes, yields or sleeps briefly, which makes rare interleavings common. A failure prints the
  * schedule seed and the tail of the decision log; the history's ticks are on the same clock.
  */
object ConcurrentSimulationSpec extends ZIOSpecDefault {

  private val name = "ConcurrentSimulationSpec"

  final case class Plan(
    spec: Spec,
    home: Map[String, Kind],
    workers: List[List[Cmd.Eval]],
    swaps: List[Spec],
    muts: List[Cmd],
    scheduleSeed: Long,
    // A shutdown issued mid-run: once the swap at this index has bound its provider, so while it is in flight, or once
    // every swap has returned when the index is past the last. None: the instance shuts down only when its scope closes.
    shutdownAfterSwap: Option[Int],
    // For each swap, when the run lets its stalled initialize() go, if the swap timed out on it. None: at the end.
    releases: Option[List[ConcurrentSimulationSpec.Release]]
  )

  /** When the run lets a timed-out swap's stalled `initialize()` go: once that many more swaps have run (0: right after
    * its own), once every fiber has joined, or once the scope has closed.
    */
  sealed trait Release extends Product with Serializable
  object Release {
    final case class AfterSwaps(n: Int) extends Release
    case object AtEnd                   extends Release
    case object AfterClose              extends Release
  }

  /** Kept for a swap after the next one often, so a later swap meets a stack still initializing. */
  val genRelease: Gen[Any, Release] = Gen.weighted(
    Gen.const(Release.AfterSwaps(0)) -> 2.0,
    Gen.const(Release.AfterSwaps(1)) -> 3.0,
    Gen.const(Release.AtEnd)         -> 2.0,
    Gen.const(Release.AfterClose)    -> 1.0
  )

  val genPlan: Gen[Any, Plan] =
    for {
      // Drawn first and never shrunk, so shrinking the rest of the plan keeps the schedule that failed.
      scheduleSeed <- Gen.long.noShrink
      home         <- SequentialSimulationSpec.genHomeKinds
      spec         <- Gens.genSpec(3, home, Gens.leafFaults(Gens.noInitFault))
      workers      <- Gen.listOfBounded(2, 4)(Gen.listOfBounded(5, 30)(Gens.genEval(home)))
      swaps        <- Gen.listOfBounded(0, 3)(Gens.genSpec(2, home, Gens.leafFaults(swapInitFault)))
      muts         <- Gen.listOfBounded(0, 15)(
        Gens.genCmd(7, Nil, home).map {
          case c: Cmd.SetFlag  => c
          case c: Cmd.SetFault => c
          case _               => Cmd.SetFlag(0, "k0", None, emit = true)
        }
      )
      // Drawn over the swaps there are, and one past them: most mid-run shutdowns then land during a swap.
      shutdownAfterSwap <- Gen.weighted(Gen.const(None) -> 2.0, Gen.int(0, swaps.size).map(Some(_)) -> 1.0)
      releases          <- Gen.listOfN(swaps.size)(genRelease)
    } yield Plan(spec, home, workers, swaps, muts, scheduleSeed, shutdownAfterSwap, Some(releases))

  /** A failing `initialize()`, or one that stalls until the run releases it, past a deadline the run fires. */
  val swapInitFault: Gen[Any, Option[InitFault]] =
    Gen.weighted(
      Gen.const(None)                          -> 4.0,
      Gen.const(Some(InitFault.Fails))         -> 1.5,
      Gen.const(Some(InitFault.StallThenOk))   -> 1.0,
      Gen.const(Some(InitFault.StallThenFail)) -> 0.6
    )

  /** A timed-out swap's stack, still initializing, the stack it replaced, which waits for it to settle, and when the
    * run lets its stalled `initialize()` go.
    */
  final case class Pending(stack: Built, replaced: Built, release: Release)

  private def stalling(b: Built): List[SimLeaf] =
    Spec.leaves(b.spec).collect {
      case l if l.init.exists(f => f == InitFault.StallThenOk || f == InitFault.StallThenFail) => b.leaves(l.id)
    }

  private def isBound(sdk: OFFeatureProvider, b: Built): Boolean =
    (sdk eq b.provider) || ManagedProvider
      .existing(b.provider)
      .exists(m => ManagedProvider.existing(sdk).exists(_ eq m))

  private def initializing(b: Built): Boolean = ManagedProvider.existing(b.provider).exists(_.initsInFlightAnywhere > 0)

  private def isTimeout(c: Cause[Any]): Boolean = c.failureOption.exists {
    case FeatureFlagError.ProviderInitializationFailed(_: java.util.concurrent.TimeoutException) => true
    case _                                                                                       => false
  }

  /** The faults the generated cases must reach. */
  val faultKeys: List[String] = List(
    "crun.init.fails",
    "crun.swap.rolledBack",
    "crun.shutdown.throws",
    "crun.shutdown.midRun",
    "crun.shutdown.duringSwap",
    "crun.swap.timedOut",
    "crun.swap.failedOverInitializing",
    "crun.release.afterSwap",
    "crun.release.atEnd",
    "crun.release.afterClose"
  )

  private val evalBound = 10.seconds

  def run(plan: Plan): ZIO[Any, String, Unit] = {
    val spec0 = Spec.number(plan.spec)
    for {
      built    <- Harness.build(spec0, timed = false)
      baseline <- LeakCheck.snapshot
      current  <- Ref.make(built)
      retired  <- Ref.make(List.empty[Built])
      problems <- Ref.make(Chunk.empty[String])
      evals    <- Ref.make(0)
      history  <- Ref.make(Chunk.empty[Op])
      restored <- Ref.make(Set.empty[String])
      // The mid-run shutdown's ticks: when it was called, and when it returned.
      shutAt   <- Ref.make(Option.empty[(Long, Long)])
      bound    <- Ref.make(0)
      trigger  <- Promise.make[Nothing, Unit]
      inFlight <- Ref.make(false)
      unused   <- Ref.make(List.empty[Built])
      pending  <- Ref.make(List.empty[Pending])
      everPending = new java.util.concurrent.atomic.AtomicReference(List.empty[Built])
      // The leaf shutdowns of each stack once its own initialize() returned (a stalled one's once released): a
      // multi-provider whose initialize() fails shuts its members down itself, before anything the library does.
      settledShuts <- Ref.make(List.empty[(Built, Map[Int, Int])])
      // Stacks a failed swap rolled back onto while their own initialize() had failed: such a rollback registers the
      // stack again, and a registration that initializes it again (a wrapper may not) fails again and is shut down.
      failedRollbacks <- Ref.make(List.empty[Built])
      // A rollback did not restore the previous stack: the failed one stays routed, status Error, until a swap succeeds.
      // Only a successful setProvider is promised to recover that state, so the run makes no other swap until then.
      degraded <- Ref.make(false)
      // Times a swap's wait for initialize() out when the run fires it, never by itself: the instance keeps the default
      // init timeout, so no healthy swap races a short one.
      deadline = new ScriptedInitDeadline
      // Every evaluation's start tick and outcome, and every successful swap's start tick, judged against the mid-run
      // shutdown once every fiber has joined, when its ticks are final.
      evalLog <- Ref.make(Chunk.empty[(String, Long, Outcome)])
      swapLog <- Ref.make(Chunk.empty[(String, Long)])
      report = (p: String) => problems.update(_ :+ p)
      watch  = new ModelInvariants.Watch
      // A swap raises its gate before it binds the new provider: from then on the current stack's leaves are retiring.
      // A failed swap that binds the previous stack again makes it current once more: its leaves serve again. A cache in
      // that stack may still hold an answer a leaf gave while it was retiring; serving it from then on is correct.
      rollbackBinds <- Ref.make(0)
      perturber = new SchedulePerturber(
        plan.scheduleSeed,
        point =>
          watch.check(point.name, Some(point)) *> (point match {
            case LifecycleProbe.Point.SwapBind =>
              current.get.map(_.leaves.values.foreach(_.retiring.set(true))) *>
                bound
                  .updateAndGet(_ + 1)
                  .flatMap(n => ZIO.when(plan.shutdownAfterSwap.contains(n - 1))(trigger.succeed(())))
                  .unit
            case LifecycleProbe.Point.RollbackBind =>
              rollbackBinds.update(_ + 1) *> current.get.flatMap { b =>
                b.leaves.values.foreach(_.retiring.set(false))
                restored.update(_ ++ b.leaves.values.map(_.name))
              }
            case _ => ZIO.unit
          })
      )
      _ <- ZIO.succeed(
        Stats.declare(
          SchedulePerturber.declaredCounters ++ List("invariant.checked", "history.checked", "history.cachedFloored") ++
            faultKeys
        )
      )
      // Lets a timed-out swap's stalled initialize() go, and waits until the wrapper has seen it return. The stack that
      // swap replaced is shut down only once that call settles: until then it must not be, unless the instance was
      // shut down or closed meanwhile (`closed`), which shuts it down itself.
      release = (p: Pending, closed: Boolean) =>
        for {
          base <- settledShuts.get.map(_.collectFirst { case (b, m) if b eq p.replaced => m }.getOrElse(Map.empty))
          early = p.replaced.leaves.toList.collect {
            case (id, l) if l.shutdowns.get() != base.getOrElse(id, 0) => l.name
          }
          // Read after the counts: a shutdown that started meanwhile shut the replaced stack down itself.
          sa <- shutAt.get
          _  <- ZIO.foreachDiscard(early)(n =>
            ZIO.when(!closed && sa.isEmpty)(
              report(s"$n was shut down before the timed-out swap to ${Spec.show(p.stack.spec)} settled")
            )
          )
          _ <- perturber.note(s"release the stalled initialize() of ${Spec.show(p.stack.spec)}")
          _ <- ZIO.succeed(stalling(p.stack).foreach(_.initGate.release(abandon = false)))
          _ <- pending.update(_.filterNot(_ eq p))
          _ <- Stats.generated(p.release match {
            case Release.AfterSwaps(_) => "crun.release.afterSwap"
            case Release.AtEnd         => "crun.release.atEnd"
            case Release.AfterClose    => "crun.release.afterClose"
          })
          _ <- (ZIO.sleep(5.millis) *> ZIO.succeed(
            initializing(p.stack) || stalling(p.stack).exists(l => l.initGate.entered && !l.initGate.exited)
          ))
            .repeatUntil(!_)
            .timeout(5.seconds)
            .flatMap(r => ZIO.when(r.isEmpty)(report(s"${Spec.show(p.stack.spec)}: initialize() did not return")))
            .withClock(Clock.ClockLive)
          _ <- settledShuts.update((p.stack, p.stack.leaves.map { case (id, l) => id -> l.shutdowns.get() }) :: _)
        } yield ()
      // Recorded first, then perturbed: the perturber's delay comes after the point is recorded, and never runs under
      // the recorder's permit, so recording takes no interleaving away.
      trace <- TraceProbe.make
      _     <- ZIO.scoped {
        for {
          pair <- Harness
            .installProbed(
              built.provider,
              TraceProbe.alongside(perturber.lifecycle, trace),
              perturber.evaluation,
              deadline
            )
            .mapError(e => s"install failed for ${Spec.show(spec0)}: $e")
          ff = pair
          ad = pair
          _      <- watch.attach(pair)
          _      <- ZIO.foreachDiscard(trace)(_.begin(built.provider))
          hook   <- RecordingHook.make()
          _      <- ff.addHook(hook)
          nextId <- Ref.make(Spec.maxId(spec0) + 1)

          worker = (cmds: List[Cmd.Eval]) =>
            ZIO.foreachDiscard(cmds) { case Cmd.Eval(key, kind, inv, scoped) =>
              val eff = Harness.read(ff, key, kind, inv.toEvaluationContext)
              // The context the leaf sees: this spec never sets a global or client context.
              val fp =
                Ctx.rendered(Runner.mergedFor(Ctx.empty, Ctx.empty, None, scoped, inv))
              for {
                t0   <- ZIO.succeed(perturber.tick())
                exit <- scoped.fold(eff)(c => ff.withContext(c.toEvaluationContext)(eff)).timeout(evalBound).exit
                t1   <- ZIO.succeed(perturber.tick())
                _    <- watch.check(s"after eval $key", None)
                _    <- evals.update(_ + 1)
                _    <- exit match {
                  case Exit.Success(None)    => report(s"eval $key:$kind did not complete within $evalBound")
                  case Exit.Failure(c)       => report(s"eval $key:$kind failed: ${c.prettyPrint.take(400)}")
                  case Exit.Success(Some(o)) =>
                    evalLog.update(_ :+ ((s"$key:$kind", t0, o))) *>
                      ZIO.when(o.isSuccess)(history.update(_ :+ Op.Eval(key, kind, fp, t0, t1, o))) *>
                      restored.get.flatMap { back =>
                        val late = o.variant.filter(_.endsWith(SimLeaf.LateSuffix))
                        val kept = late.exists(v => o.reason == "CACHED" && back.contains(v.takeWhile(_ != ':')))
                        ZIO.when(o.isSuccess && late.nonEmpty && !kept)(
                          report(s"eval $key:$kind got $o, answered after a swap away from its leaf had started")
                        )
                      }
                }
              } yield ()
            }

          swapOnce = (s: Spec, index: Int) =>
            for {
              from <- nextId.get
              ns = Spec.number(s, from)
              _ <- nextId.set(Spec.maxId(ns) + 1)
              _ <- ZIO.succeed(if (Spec.wrapsMulti(ns)) Stats.hit("crun.swap.wrapsMulti"))
              _ <- ZIO.foreachDiscard(Spec.leaves(ns).flatMap(_.shutdown))(_ => Stats.generated("crun.shutdown.throws"))
              nb <- Harness.build(ns, timed = false)
              outcome = Spec.initOutcome(ns)
              fails   = outcome == InitOutcome.Fails
              stalls  = outcome.isInstanceOf[InitOutcome.Stalls]
              when    = plan.releases.flatMap(_.lift(index)).getOrElse(Release.AtEnd)
              _   <- ZIO.when(fails)(Stats.generated("crun.init.fails"))
              cur <- current.get
              // The installed stack is a timed-out swap's that is still initializing: a failed swap has nothing to roll
              // back to, since initializing it again would run a second initialize() while the first is running.
              prevInitializing <- pending.get.map(_.exists(_.stack eq cur))
              rb0              <- rollbackBinds.get
              _                <- perturber.note(s"swap to ${Spec.show(ns)} starts")
              t0               <- ZIO.succeed(perturber.tick())
              _                <- inFlight.set(true)
              sw               <- ad.setProvider(nb.provider).sandbox.either.fork
              // A stalling stack's initialize() never returns by itself: once the swap's wait for it is armed, time that
              // wait out. A swap refused or failed before it arms one is left to end by itself.
              _ <- ZIO.when(stalls)(
                (ZIO.sleep(2.millis) *> sw.poll.map(done =>
                  done.isDefined ||
                    (deadline.armed.contains(nb.provider) && stalling(nb).forall(_.initGate.entered))
                ))
                  .repeatUntil(identity)
                  .timeout(10.seconds)
                  .withClock(Clock.ClockLive) *> ZIO.succeed(deadline.fire(nb.provider))
              )
              r <- sw.join
              // Its initialize() has returned unless it stalls: what it shut down of itself is its own doing.
              _ <- ZIO.unless(stalls)(
                settledShuts.update((nb, nb.leaves.map { case (id, l) => id -> l.shutdowns.get() }) :: _)
              )
              _  <- inFlight.set(false)
              t1 <- ZIO.succeed(perturber.tick())
              _  <- ZIO.when(r.isRight)(swapLog.update(_ :+ ((Spec.show(ns), t0))))
              sa <- shutAt.get
              _  <- r match {
                // Refused once a shutdown has started: the provider is never touched.
                case Left(c)
                    if sa.exists(_._1 < t1) && c.defects.isEmpty &&
                      c.failureOption.contains(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady)) =>
                  for {
                    _ <- perturber.note(s"swap to ${Spec.show(ns)} refused after shutdown")
                    _ <- ZIO.succeed(Stats.hit("crun.swap.refusedAfterShutdown"))
                    _ <- unused.update(nb :: _)
                  } yield ()
                // Timed out: no rollback. The new stack stays bound and settles later; the one it replaced waits for it.
                case Left(c) if stalls && isTimeout(c) && c.defects.isEmpty =>
                  for {
                    _   <- perturber.note(s"swap to ${Spec.show(ns)} timed out")
                    _   <- Stats.generated("crun.swap.timedOut")
                    _   <- history.update(_ :+ Op.Swap(ns, t0, t1))
                    old <- current.getAndSet(nb)
                    _   <- retired.update(old :: _)
                    _   <- pending.update(_ :+ Pending(nb, old, when))
                    _   <- ZIO.succeed(everPending.updateAndGet(nb :: _))
                  } yield ()
                case Left(c) if isTimeout(c) =>
                  report(s"setProvider to ${Spec.show(ns)}, whose initialize() does not stall, timed out")
                // Failed while the installed stack still initializes: that stack is replaced, not restored.
                case Left(c)
                    if fails && prevInitializing && c.defects.isEmpty &&
                      c.failureOption.exists(_.isInstanceOf[FeatureFlagError.ProviderInitializationFailed]) =>
                  for {
                    _   <- perturber.note(s"swap to ${Spec.show(ns)} failed over a stack still initializing")
                    rb1 <- rollbackBinds.get
                    _   <- ZIO.when(rb1 != rb0)(
                      report(s"setProvider to ${Spec.show(ns)} rolled back onto a stack still initializing")
                    )
                    _   <- Stats.generated("crun.swap.failedOverInitializing")
                    _   <- history.update(_ :+ Op.Swap(ns, t0, t1))
                    old <- current.getAndSet(nb)
                    _   <- retired.update(old :: _)
                  } yield ()
                // Rolled back: the previous stack stays current. The failed one is retired, so it must never answer.
                case Left(c)
                    if fails && c.defects.isEmpty &&
                      c.failureOption.exists(_.isInstanceOf[FeatureFlagError.ProviderInitializationFailed]) =>
                  for {
                    _ <- perturber.note(s"swap to ${Spec.show(ns)} rolled back")
                    ontoFailed = Spec.initOutcome(cur.spec).match {
                      case InitOutcome.Fails | InitOutcome.Stalls(true) => true
                      case _                                            => false
                    } && !prevInitializing
                    _ <- ZIO.when(ontoFailed)(
                      failedRollbacks.update(cur :: _) *> ZIO.succeed(Stats.hit("crun.swap.rolledBackOntoFailed"))
                    )
                    _ <- ZIO.succeed(stalling(nb).foreach(_.initGate.release(abandon = false)))
                    _ <- Stats.generated("crun.swap.rolledBack")
                    // A rollback onto a stack whose initialize() failed may fail to restore it: the SDK slot then
                    // still binds the failed stack, which serves with status Error until a later swap succeeds.
                    routed = isBound(ad.sdkBound, nb)
                    _ <-
                      if (ontoFailed && routed)
                        for {
                          _ <- perturber.note(s"rollback to ${Spec.show(cur.spec)} not restored")
                          _ <- ZIO.succeed(Stats.hit("crun.swap.rollbackUnrestored"))
                          _ <- ZIO.foreachDiscard(nb.leaves.values.toList)(l =>
                            ZIO.when(l.shutdowns.get() != 0)(
                              report(s"${l.name} was shut down while its stack stays routed after a failed rollback")
                            )
                          )
                          _   <- history.update(_ :+ Op.Swap(ns, t0, t1))
                          _   <- ZIO.succeed(cur.leaves.values.foreach(_.retiring.set(true)))
                          old <- current.getAndSet(nb)
                          _   <- retired.update(old :: _)
                          _   <- degraded.set(true)
                        } yield ()
                      else
                        ZIO.when(routed)(
                          report(s"setProvider to ${Spec.show(ns)} rolled back, but the SDK still routes to it")
                        ) *>
                          retired.update(nb :: _) *>
                          Runner.awaitShutdown(nb).flatMap(ZIO.foreachDiscard(_)(p => report(s"failed stack: $p")))
                  } yield ()
                case Left(c) =>
                  perturber.note(s"swap to ${Spec.show(ns)} failed") *>
                    report(s"setProvider to ${Spec.show(ns)} failed: ${c.prettyPrint.take(400)}")
                case Right(_) if fails || stalls =>
                  report(s"setProvider to ${Spec.show(ns)}, whose initialize() fails or stalls, succeeded")
                case Right(_) =>
                  for {
                    _   <- degraded.set(false)
                    _   <- history.update(_ :+ Op.Swap(ns, t0, t1))
                    _   <- perturber.note(s"swap to ${Spec.show(ns)} returned")
                    old <- current.getAndSet(nb)
                    _   <- retired.update(old :: _)
                    // A replaced stack still initializing (a timed-out swap's) may be shut down only once that call
                    // returns, as a DeferredProvider defers it; the end of the run checks it was, exactly once.
                    stillOn <- pending.get.map(_.exists(_.stack eq old))
                    _       <- ZIO.unless(stillOn)(
                      Runner.awaitShutdown(old).flatMap(ZIO.foreachDiscard(_)(p => report(s"replaced stack: $p")))
                    )
                  } yield ()
              }
              _ <- watch.check(s"after swap to ${Spec.show(ns)}", None)
              // The stalled initialize() calls whose turn has come settle now; AfterSwaps(n) counts the swaps that
              // returned after its own.
              ps        <- pending.get
              closedNow <- shutAt.get.map(_.isDefined)
              _         <- ZIO.foreachDiscard(ps) { p =>
                p.release match {
                  case Release.AfterSwaps(n) if n <= 0 => release(p, closedNow)
                  case Release.AfterSwaps(n)           =>
                    pending.update(_.map(q => if (q eq p) q.copy(release = Release.AfterSwaps(n - 1)) else q))
                  case _ => ZIO.unit
                }
              }
              _ <- ZIO.yieldNow
            } yield ()

          swapper = ZIO.foreachDiscard(plan.swaps.zipWithIndex) { case (s, index) =>
            degraded.get.flatMap { d =>
              if (d && Spec.initOutcome(s) != InitOutcome.Ok)
                perturber.note(s"swap to ${Spec.show(s)} skipped: a rollback was not restored") *>
                  ZIO.succeed(Stats.hit("crun.swap.skippedWhileUnrestored"))
              else swapOnce(s, index)
            }
          }

          mutator = ZIO.foreachDiscard(plan.muts) { m =>
            current.get.flatMap { b =>
              val ids = b.leaves.keys.toList.sorted
              m match {
                case Cmd.SetFlag(idx, key, value, emit) =>
                  val id   = ids(idx % ids.size)
                  val leaf = b.leaves(id)
                  for {
                    _           <- perturber.note(s"setFlag ${leaf.name} $key=${value.getOrElse("-")} emit=$emit")
                    t0          <- ZIO.succeed(perturber.tick())
                    invalidated <- ZIO
                      .attemptBlocking {
                        value.fold(leaf.flags.remove(key): Unit)(v => leaf.flags.put(key, v): Unit)
                        if (emit) leaf.configurationChanged()
                      }
                      .as(emit)
                      .catchAll {
                        // A swap may have shut this leaf down since it was picked; the SDK's event executor then rejects
                        // the emit, which then invalidates nothing.
                        case _: java.util.concurrent.RejectedExecutionException if leaf.shutdowns.get() > 0 =>
                          ZIO.succeed(Stats.hit("crun.emitAfterShutdown")).as(false)
                        case e => report(s"mutation failed: $e").as(false)
                      }
                    t1 <- ZIO.succeed(perturber.tick())
                    _  <- history.update(_ :+ Op.Write(id, key, value, invalidated, t0, t1))
                  } yield ()
                case Cmd.SetFault(idx, f) =>
                  val leaf = b.leaves(ids(idx % ids.size))
                  perturber.note(s"setFault ${leaf.name} ${f.getOrElse("-")}") *> ZIO.succeed(leaf.fault.set(f))
                case _ => ZIO.unit
              }
            } *> watch.check("after mutation", None) *> ZIO.yieldNow
          }

          swapperDone <- Promise.make[Nothing, Unit]
          shutdowner = ZIO.foreachDiscard(plan.shutdownAfterSwap) { _ =>
            for {
              _      <- trigger.await.race(swapperDone.await)
              during <- inFlight.get
              _      <- Stats.generated("crun.shutdown.midRun")
              _      <- ZIO.when(during)(Stats.generated("crun.shutdown.duringSwap"))
              _      <- perturber.note(s"shutdown starts (swap in flight: $during)")
              t0     <- ZIO.succeed(perturber.tick())
              _      <- shutAt.set(Some((t0, Long.MaxValue)))
              _      <- ad.shutdown
              t1     <- ZIO.succeed(perturber.tick())
              _      <- shutAt.set(Some((t0, t1)))
              _      <- perturber.note("shutdown returned")
              st     <- ff.providerStatus
              _      <- ZIO.when(st != ProviderStatus.NotReady)(report(s"status after shutdown returned: $st"))
            } yield ()
          }
          _ <- ZIO.collectAllParDiscard(
            (swapper *> swapperDone.succeed(())) :: shutdowner :: mutator :: plan.workers.map(worker)
          )
          // The rest settle once every fiber has joined, but those held until the scope has closed.
          late <- pending.get
          sd   <- shutAt.get
          _    <- ZIO.foreachDiscard(late.filterNot(_.release == Release.AfterClose))(release(_, sd.isDefined))
          // Once shut down, NotReady for good, whatever settled afterwards. An evaluation or a swap that started after the
          // shutdown returned is refused: the evaluation as not ready, the swap by failing.
          sa <- shutAt.get
          es <- evalLog.get
          ws <- swapLog.get
          _  <- ZIO.foreachDiscard(sa.map(_._2).toList) { done =>
            ZIO.foreachDiscard(es.filter(_._2 > done)) { case (what, _, o) =>
              ZIO.when(!o.code.contains("PROVIDER_NOT_READY"))(
                report(s"eval $what started after shutdown returned and got $o, not PROVIDER_NOT_READY")
              )
            } *> ZIO.foreachDiscard(ws.filter(_._2 > done)) { case (spec, _) =>
              report(s"setProvider to $spec started after shutdown returned and succeeded")
            }
          }
          st <- ff.providerStatus
          _  <- ZIO.when(sa.isDefined && st != ProviderStatus.NotReady)(
            report(s"status at the end, after shutdown: $st")
          )
          calls <- hook.calls
          n     <- evals.get
          count = (p: HookCall => Boolean) => calls.count(p)
          bs    = count(_.isInstanceOf[HookCall.Before])
          as    = count(_.isInstanceOf[HookCall.After])
          es    = count(_.isInstanceOf[HookCall.Error])
          fs    = count(_.isInstanceOf[HookCall.Finally])
          // A shutdown clears this instance's hooks: evaluations from then on run none.
          _ <- ZIO.when(if (sa.isEmpty) bs != n || fs != n || as + es != n else bs > n || fs != bs || as + es != bs)(
            report(s"hooks: $n evaluations, before=$bs after=$as error=$es finally=$fs")
          )
        } yield ()
      }
      // Still stalled when the scope closed: the close shut the stacks they replaced down; let them go now.
      left <- pending.get
      _    <- ZIO.foreachDiscard(left)(release(_, true))
      // Once the scope has closed, so its teardown is recorded too; before the verdict, so a failed run is written.
      _   <- ZIO.foreachDiscard(trace)(SimulationTrace.write(_, plan))
      _   <- ZIO.foreachDiscard(watch.violations.take(5))(v => report(s"model invariant: $v"))
      ops <- history.get
      checked = History.check(spec0, ops)
      _ <- ZIO.succeed {
        Stats.add("history.checked", checked.checked)
        Stats.add("history.cachedFloored", checked.cachedFloored)
      }
      _     <- ZIO.foreachDiscard(checked.violations.take(5))(v => report(v.render))
      _     <- ZIO.when(checked.violations.size > 5)(report(s"history: ${checked.violations.size - 5} more violations"))
      last  <- current.get
      _     <- Runner.awaitShutdown(last).flatMap(ZIO.foreachDiscard(_)(p => report(s"after scope close: $p")))
      olds  <- retired.get
      again <- failedRollbacks.get
      // A stack that was pending may be shut down only once its stalled call returned, after the run let it go: wait.
      _ <- ZIO.foreachDiscard(everPending.get()) { b =>
        (ZIO.sleep(20.millis) *> ZIO.succeed(b.leaves.values.forall(_.shutdowns.get() >= 1)))
          .repeatUntil(identity)
          .timeout(5.seconds)
          .withClock(Clock.ClockLive)
      }
      _ <- ZIO.foreachDiscard(last :: olds) { b =>
        // Once. A stack rolled back onto after its initialize() failed is registered again, and the shutdown of a
        // registration whose rollback failed is not settled yet: at least once, and at most once per initialize().
        val reregistered = again.exists(_ eq b)
        val n            = (l: SimLeaf) => l.shutdowns.get()
        ZIO.foreachDiscard(b.leaves.values)(l =>
          ZIO.when(if (reregistered) n(l) < 1 || n(l) > (l.inits.get() max 1) else n(l) != 1)(
            report(
              s"${l.name} shut down ${l.shutdowns.get()} times, initialized ${l.inits.get()} times" +
                (if (reregistered) " (rolled back onto after its initialize() failed)" else "")
            )
          ) *>
            ZIO.when(l.concurrentInits.get() > 0)(
              report(s"${l.name}: initialize() ran while another call was running")
            )
        )
      }
      // A stack a refused swap never installed: the library must not have touched it. The test then releases it.
      never <- unused.get
      _     <- ZIO.foreachDiscard(never.flatMap(_.leaves.values))(l =>
        ZIO.when(l.shutdowns.get() != 0 || l.inits.get() != 0)(
          report(s"${l.name}, never installed: inits=${l.inits.get()} shutdowns=${l.shutdowns.get()}")
        )
      )
      _     <- ZIO.foreachDiscard(never)(b => ZIO.attemptBlocking(b.provider.shutdown()).ignore)
      leaks <- LeakCheck.awaitNoLeaks(baseline, 5.seconds).withClock(Clock.ClockLive)
      _     <- ZIO.foreachDiscard(leaks)(l => report(s"leak: $l"))
      ps    <- problems.get
      _     <- ZIO.succeed(Stats.hit("crun")) *> ZIO.succeed(Stats.hit(s"crun.swaps${olds.size}"))
      _     <- ZIO.when(ps.nonEmpty)(
        ZIO.fail(
          ((s"stack ${Spec.show(spec0)} swaps ${plan.swaps.map(s => Spec.show(Spec.number(s))).mkString(", ")}" +
            plan.shutdownAfterSwap.fold("")(k => s" shutdown after swap $k")) +: ps)
            .mkString("\n") + "\nharness events:\n" + perturber.events + "\n" + perturber.render(150)
        )
      )
    } yield ()
  }

  def spec = suite("ConcurrentSimulation")(
    test("replays every regression corpus entry") {
      Corpus
        .replay[Any, Plan]("concurrent")(run)
        .map(failures => assertTrue(failures.isEmpty) ?? failures.mkString("\n"))
    },
    test("evaluations, hooks, swaps and shutdowns keep their invariants under concurrency") {
      check(genPlan) { plan =>
        run(plan).either.flatMap { r =>
          val json = PlanCodec.write(plan)
          ZIO.when(r.isLeft)(Corpus.record(name, json)).as(assertTrue(r == Right(())) ?? s"corpus entry ($name): $json")
        }
      }
    } @@ PropertyBudget(40) @@ TestAspect.after(Console.printLine(Stats.render).orDie),
    // Last, after the generated cases: deterministic under the PR seed; retune the weights if an upgrade moves it.
    test("the generated cases reach every lifecycle fault") {
      val counts = faultKeys.map(k => k -> Stats.get(k))
      Console.printLine(s"lifecycle faults: ${counts.map { case (k, n) => s"$k=$n" }.mkString(" ")}").orDie.as {
        assertTrue(counts.forall(_._2 > 0)) ?? counts.filter(_._2 == 0).map(_._1).mkString("never reached: ", ", ", "")
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ PropertyBudget.timeout(10.minutes)
}
