package zio.openfeature.simulation

import zio._
import zio.openfeature._
import zio.openfeature.testkit.{HookCall, RecordingHook}
import zio.openfeature.testsupport.{LeakCheck, PropertyBudget}
import zio.test.{Spec => _, _}

/** Concurrent simulation: one random stack, evaluation workers racing a swapper and a mutator (flag changes, change
  * events, faults). It checks invariants, not exact answers:
  *   - every evaluation completes (bounded) with a value or a default plus an error code, never a defect;
  *   - hooks: every evaluation runs `before`, one of `after`/`error`, and `finally`, so the totals match;
  *   - an evaluation that starts after `setProvider` returned is never answered by a leaf of the replaced stack;
  *   - each replaced stack's leaves are shut down exactly once, and so are the last stack's when the scope closes;
  *   - no fiber or thread outlives the run (LeakCheck).
  */
object ConcurrentSimulationSpec extends ZIOSpecDefault {

  final case class Plan(
    spec: Spec,
    home: Map[String, Kind],
    workers: List[List[Cmd.Eval]],
    swaps: List[Spec],
    muts: List[Cmd]
  )

  val genPlan: Gen[Any, Plan] =
    for {
      home    <- SequentialSimulationSpec.genHomeKinds
      spec    <- Gens.genSpec(3, home)
      workers <- Gen.listOfBounded(2, 4)(Gen.listOfBounded(5, 30)(Gens.genEval(home)))
      swaps   <- Gen.listOfBounded(0, 3)(Gens.genSpec(2, home))
      muts    <- Gen.listOfBounded(0, 15)(
        Gens.genCmd(7, Nil, home).map {
          case c: Cmd.SetFlag  => c
          case c: Cmd.SetFault => c
          case _               => Cmd.SetFlag(0, "k0", None, emit = true)
        }
      )
    } yield Plan(spec, home, workers, swaps, muts)

  private val evalBound = 10.seconds

  def run(plan: Plan): ZIO[Any, String, Unit] = {
    val spec0 = Spec.number(plan.spec)
    val built = Harness.build(spec0)
    for {
      baseline <- LeakCheck.snapshot
      current  <- Ref.make(built)
      retired  <- Ref.make(List.empty[Built])
      problems <- Ref.make(Chunk.empty[String])
      evals    <- Ref.make(0)
      report = (p: String) => problems.update(_ :+ p)
      _ <- ZIO.scoped {
        for {
          pair <- Harness.install(built.provider).mapError(e => s"install failed for ${Spec.show(spec0)}: $e")
          (ff, ad) = pair
          hook   <- RecordingHook.make()
          _      <- ff.addHook(hook)
          nextId <- Ref.make(Spec.maxId(spec0) + 1)

          worker = (cmds: List[Cmd.Eval]) =>
            ZIO.foreachDiscard(cmds) { case Cmd.Eval(key, kind, inv, scoped) =>
              for {
                gone <- retired.get.map(_.flatMap(_.leaves.values.map(_.name)).toSet)
                eff = Harness.read(ff, key, kind, inv.toEvaluationContext)
                exit <- scoped.fold(eff)(c => ff.withContext(c.toEvaluationContext)(eff)).timeout(evalBound).exit
                _    <- evals.update(_ + 1)
                _    <- exit match {
                  case Exit.Success(None)    => report(s"eval $key:$kind did not complete within $evalBound")
                  case Exit.Failure(c)       => report(s"eval $key:$kind failed: ${c.prettyPrint.take(400)}")
                  case Exit.Success(Some(o)) =>
                    val from = o.variant.map(_.takeWhile(_ != ':'))
                    ZIO.when(from.exists(gone.contains))(
                      report(s"eval $key:$kind started after its leaf ${from.get} was replaced, and got $o")
                    )
                }
              } yield ()
            }

          swapper = ZIO.foreachDiscard(plan.swaps) { s =>
            for {
              from <- nextId.get
              ns = Spec.number(s, from)
              _ <- nextId.set(Spec.maxId(ns) + 1)
              _ <- ZIO.succeed(if (Spec.wrapsMulti(ns)) Stats.hit("crun.swap.wrapsMulti"))
              nb = Harness.build(ns)
              _ <- ad.setProvider(nb.provider).sandbox.either.flatMap {
                case Left(c)  => report(s"setProvider to ${Spec.show(ns)} failed: ${c.prettyPrint.take(400)}")
                case Right(_) =>
                  for {
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
                  val leaf = b.leaves(ids(idx % ids.size))
                  ZIO
                    .attemptBlocking {
                      value.fold(leaf.flags.remove(key): Unit)(v => leaf.flags.put(key, v): Unit)
                      if (emit) leaf.configurationChanged()
                    }
                    .catchAll {
                      // A swap may have shut this leaf down since it was picked; the SDK's event executor then rejects the emit.
                      case _: java.util.concurrent.RejectedExecutionException if leaf.shutdowns.get() > 0 =>
                        ZIO.succeed(Stats.hit("crun.emitAfterShutdown"))
                      case e => report(s"mutation failed: $e")
                    }
                case Cmd.SetFault(idx, f) => ZIO.succeed(b.leaves(ids(idx % ids.size)).fault.set(f))
                case _                    => ZIO.unit
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
            .mkString("\n")
        )
      )
    } yield ()
  }

  def spec = suite("ConcurrentSimulation")(
    test("evaluations, hooks, swaps and shutdowns keep their invariants under concurrency") {
      check(genPlan)(plan => run(plan).either.map(r => assertTrue(r == Right(()))))
    } @@ PropertyBudget(40) @@ TestAspect.after(Console.printLine(Stats.render).orDie)
  ) @@ TestAspect.withLiveClock @@ PropertyBudget.timeout(10.minutes)
}
