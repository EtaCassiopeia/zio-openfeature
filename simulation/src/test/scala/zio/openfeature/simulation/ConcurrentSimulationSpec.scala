package zio.openfeature.simulation

import zio._
import zio.openfeature._
import zio.openfeature.internal.LifecycleProbe
import zio.openfeature.testkit.{HookCall, RecordingHook, TraceProbe}
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
  *   - each replaced stack's leaves are shut down exactly once, and so are the last stack's when the scope closes;
  *   - no fiber or thread outlives the run (LeakCheck).
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
    scheduleSeed: Long
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
    } yield Plan(spec, home, workers, swaps, muts, scheduleSeed)

  /** Only a failing `initialize()`: this spec runs on the live clock and has no one to release a stall. */
  val swapInitFault: Gen[Any, Option[InitFault]] =
    Gen.weighted(Gen.const(None) -> 5.0, Gen.const(Some(InitFault.Fails)) -> 1.0)

  /** The faults the generated cases must reach. */
  val faultKeys: List[String] = List("crun.init.fails", "crun.swap.rolledBack", "crun.shutdown.throws")

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
      report = (p: String) => problems.update(_ :+ p)
      // A swap raises its gate before it binds the new provider: from then on the current stack's leaves are retiring.
      // A failed swap that binds the previous stack again makes it current once more: its leaves serve again. A cache in
      // that stack may still hold an answer a leaf gave while it was retiring; serving it from then on is correct.
      perturber = new SchedulePerturber(
        plan.scheduleSeed,
        {
          case LifecycleProbe.Point.SwapBind     => current.get.map(_.leaves.values.foreach(_.retiring.set(true)))
          case LifecycleProbe.Point.RollbackBind =>
            current.get.flatMap { b =>
              b.leaves.values.foreach(_.retiring.set(false))
              restored.update(_ ++ b.leaves.values.map(_.name))
            }
          case _ => ZIO.unit
        }
      )
      _ <- ZIO.succeed(
        Stats.declare(
          SchedulePerturber.declaredCounters ++ List("history.checked", "history.cachedFloored") ++ faultKeys
        )
      )
      // Recorded first, then perturbed: the perturber's delay comes after the point is recorded, and never runs under
      // the recorder's permit, so recording takes no interleaving away.
      trace <- TraceProbe.make
      _     <- ZIO.scoped {
        for {
          pair <- Harness
            .installProbed(built.provider, TraceProbe.alongside(perturber.lifecycle, trace), perturber.evaluation)
            .mapError(e => s"install failed for ${Spec.show(spec0)}: $e")
          (ff, ad) = pair
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
                _    <- evals.update(_ + 1)
                _    <- exit match {
                  case Exit.Success(None)    => report(s"eval $key:$kind did not complete within $evalBound")
                  case Exit.Failure(c)       => report(s"eval $key:$kind failed: ${c.prettyPrint.take(400)}")
                  case Exit.Success(Some(o)) =>
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

          swapper = ZIO.foreachDiscard(plan.swaps) { s =>
            for {
              from <- nextId.get
              ns = Spec.number(s, from)
              _ <- nextId.set(Spec.maxId(ns) + 1)
              _ <- ZIO.succeed(if (Spec.wrapsMulti(ns)) Stats.hit("crun.swap.wrapsMulti"))
              _ <- ZIO.foreachDiscard(Spec.leaves(ns).flatMap(_.shutdown))(_ => Stats.generated("crun.shutdown.throws"))
              nb <- Harness.build(ns, timed = false)
              fails = Spec.initOutcome(ns) == InitOutcome.Fails
              _  <- ZIO.when(fails)(Stats.generated("crun.init.fails"))
              _  <- perturber.note(s"swap to ${Spec.show(ns)} starts")
              t0 <- ZIO.succeed(perturber.tick())
              r  <- ad.setProvider(nb.provider).sandbox.either
              t1 <- ZIO.succeed(perturber.tick())
              _  <- r match {
                // Rolled back: the previous stack stays current. The failed one is retired, so it must never answer.
                case Left(c)
                    if fails && c.defects.isEmpty &&
                      c.failureOption.exists(_.isInstanceOf[FeatureFlagError.ProviderInitializationFailed]) =>
                  for {
                    _ <- perturber.note(s"swap to ${Spec.show(ns)} rolled back")
                    _ <- Stats.generated("crun.swap.rolledBack")
                    _ <- retired.update(nb :: _)
                    _ <- Runner.awaitShutdown(nb).flatMap(ZIO.foreachDiscard(_)(p => report(s"failed stack: $p")))
                  } yield ()
                case Left(c) =>
                  perturber.note(s"swap to ${Spec.show(ns)} failed") *>
                    report(s"setProvider to ${Spec.show(ns)} failed: ${c.prettyPrint.take(400)}")
                case Right(_) if fails =>
                  report(s"setProvider to ${Spec.show(ns)}, whose initialize() fails, succeeded")
                case Right(_) =>
                  for {
                    _   <- history.update(_ :+ Op.Swap(ns, t0, t1))
                    _   <- perturber.note(s"swap to ${Spec.show(ns)} returned")
                    old <- current.getAndSet(nb)
                    _   <- retired.update(old :: _)
                    _   <- Runner.awaitShutdown(old).flatMap(ZIO.foreachDiscard(_)(p => report(s"replaced stack: $p")))
                  } yield ()
              }
              _ <- ZIO.yieldNow
            } yield ()
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
            } *> ZIO.yieldNow
          }

          _     <- ZIO.collectAllParDiscard(swapper :: mutator :: plan.workers.map(worker))
          calls <- hook.calls
          n     <- evals.get
          count = (p: HookCall => Boolean) => calls.count(p)
          bs    = count(_.isInstanceOf[HookCall.Before])
          as    = count(_.isInstanceOf[HookCall.After])
          es    = count(_.isInstanceOf[HookCall.Error])
          fs    = count(_.isInstanceOf[HookCall.Finally])
          _ <- ZIO.when(bs != n || fs != n || as + es != n)(
            report(s"hooks: $n evaluations, before=$bs after=$as error=$es finally=$fs")
          )
        } yield ()
      }
      // Once the scope has closed, so its teardown is recorded too; before the verdict, so a failed run is written.
      _   <- ZIO.foreachDiscard(trace)(SimulationTrace.write(_, plan))
      ops <- history.get
      checked = History.check(spec0, ops)
      _ <- ZIO.succeed {
        Stats.add("history.checked", checked.checked)
        Stats.add("history.cachedFloored", checked.cachedFloored)
      }
      _    <- ZIO.foreachDiscard(checked.violations.take(5))(v => report(v.render))
      _    <- ZIO.when(checked.violations.size > 5)(report(s"history: ${checked.violations.size - 5} more violations"))
      last <- current.get
      _    <- Runner.awaitShutdown(last).flatMap(ZIO.foreachDiscard(_)(p => report(s"after scope close: $p")))
      olds <- retired.get
      _    <- ZIO.foreachDiscard((last :: olds).flatMap(_.leaves.values))(l =>
        ZIO.when(l.shutdowns.get() != 1)(report(s"${l.name} shut down ${l.shutdowns.get()} times"))
      )
      leaks <- LeakCheck.awaitNoLeaks(baseline, 5.seconds).withClock(Clock.ClockLive)
      _     <- ZIO.foreachDiscard(leaks)(l => report(s"leak: $l"))
      ps    <- problems.get
      _     <- ZIO.succeed(Stats.hit("crun")) *> ZIO.succeed(Stats.hit(s"crun.swaps${olds.size}"))
      _     <- ZIO.when(ps.nonEmpty)(
        ZIO.fail(
          (s"stack ${Spec.show(spec0)} swaps ${plan.swaps.map(s => Spec.show(Spec.number(s))).mkString(", ")}" +: ps)
            .mkString("\n") + "\n" + perturber.render(150)
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
