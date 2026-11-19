package zio.openfeature.simulation

import zio._
import zio.test._
import zio.openfeature.testsupport.PropertyBudget

/** One sequential run: a provider stack and the commands driven against it. */
final case class SeqPlan(spec: zio.openfeature.simulation.Spec, cmds: List[Cmd])

/** Sequential model-based simulation: random provider stacks driven by random command sequences, every answer checked
  * against [[Model]]. Scale with `ZOF_PBT_SCALE`, pin with `ZOF_PBT_SEED`; the seed is printed at the start.
  */
object SequentialSimulationSpec extends ZIOSpecDefault {

  private val name = "SequentialSimulationSpec"

  val genHomeKinds: Gen[Any, Map[String, Kind]] =
    Gen.collectAll(Gens.keys.toList.map(k => Gens.genKind.map(k -> _))).map(_.toMap)

  val genRun: Gen[Any, SeqPlan] =
    for {
      home <- genHomeKinds
      spec <- Gens.genSpec(3, home)
      cmds <- Gen.listOfBounded(1, 40)(Gens.genCmd(7, (0 to 7).toList, home))
    } yield SeqPlan(spec, cmds)

  private val printSeed: TestAspect[Nothing, Any, Nothing, Any] =
    TestAspect.before(
      Console
        .printLine(
          s"simulation seed: ${PropertyBudget.seed.fold("zio-test default")(_.toString)} scale=${PropertyBudget.scale}"
        )
        .orDie
    )

  def spec = suite("SequentialSimulation")(
    test("replays every regression corpus entry") {
      Corpus.replay[SeqPlan]("sequential")(p => Runner.run(p.spec, p.cmds)).map { failures =>
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
    } @@ PropertyBudget(200) @@ printSeed @@ TestAspect.after(Console.printLine(Stats.render).orDie)
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ PropertyBudget.timeout(10.minutes)
}
