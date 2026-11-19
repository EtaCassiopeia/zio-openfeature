package zio.openfeature.simulation

import zio._
import zio.test._
import zio.openfeature.testsupport.PropertyBudget

/** One sequential run: a provider stack and the commands driven against it. */
final case class SeqPlan(spec: zio.openfeature.simulation.Spec, cmds: List[Cmd])

/** Sequential model-based simulation: random provider stacks driven by random command sequences, every answer checked
  * against [[Model]]. Scale with `ZOF_PBT_SCALE`, pin with `ZOF_PBT_SEED`; the seed is printed at the start.
  *
  * It runs on the `TestClock`: a stalled evaluation times out (or not) when the runner moves the clock, so the gray
  * faults cost no real time. `PropertyBudget.timeout` is zio-test's own timeout, on the live clock.
  */
object SequentialSimulationSpec extends ZIOSpecDefault {

  private val name = "SequentialSimulationSpec"

  val genHomeKinds: Gen[Any, Map[String, Kind]] =
    Gen.collectAll(Gens.keys.toList.map(k => Gens.genKind.map(k -> _))).map(_.toMap)

  val genRun: Gen[Any, SeqPlan] =
    for {
      home <- genHomeKinds
      spec <- Gens.genSpec(3, home, Gens.leafFaults(Gens.noInitFault))
      cmds <- Gen.listOfBounded(1, 40)(
        Gens.genCmd(7, (0 to 7).toList, home, swapInit = Gens.swapInitFault, stalls = true)
      )
    } yield SeqPlan(spec, cmds)

  private val printSeed: TestAspect[Nothing, Any, Nothing, Any] =
    TestAspect.before(
      Console
        .printLine(
          s"simulation seed: ${PropertyBudget.seed.fold("zio-test default")(_.toString)} scale=${PropertyBudget.scale}"
        )
        .orDie
    )

  /** The gray faults the generated cases must reach: a stall just short of its bound, one past a breaker's timeout and
    * one past the core's, each init fault on a swap target, the rollback after a failed one, and a throwing shutdown.
    */
  val faultKeys: List[String] = List(
    "fault.stall.under",
    "fault.stall.past.breaker",
    "fault.stall.past.core",
    "init.fails",
    "init.stallOk",
    "init.stallFail",
    "swap.rolledBack",
    "shutdown.throws"
  )

  def spec = suite("SequentialSimulation")(
    test("replays every regression corpus entry") {
      Corpus.replay[Annotations with Live, SeqPlan]("sequential")(p => Runner.run(p.spec, p.cmds)).map { failures =>
        assertTrue(failures.isEmpty) ?? failures.mkString("\n")
      }
    },
    test("every answer, hook stage and shutdown matches the model") {
      check(genRun) { plan =>
        Runner.run(plan.spec, plan.cmds).either.flatMap { r =>
          val json = PlanCodec.write(plan)
          ZIO.when(r.isLeft)(Corpus.record(name, json)).as(assertTrue(r == Right(())) ?? s"corpus entry ($name): $json")
        }
      }
    } @@ PropertyBudget(200) @@ printSeed @@ TestAspect.before(ZIO.succeed(Stats.declare(faultKeys))) @@
      TestAspect.after(Console.printLine(Stats.render).orDie),
    // Last, after the generated cases: the PR seed is fixed, so this is deterministic. A zio-test upgrade that moves it
    // below a floor fails here; retune the generator weights then.
    test("the generated cases reach every gray fault") {
      val counts = faultKeys.map(k => k -> Stats.get(k))
      Console.printLine(s"gray faults: ${counts.map { case (k, n) => s"$k=$n" }.mkString(" ")}").orDie.as {
        assertTrue(counts.forall(_._2 > 0)) ?? counts.filter(_._2 == 0).map(_._1).mkString("never reached: ", ", ", "")
      }
    }
  ) @@ TestAspect.sequential @@ PropertyBudget.timeout(10.minutes)
}
