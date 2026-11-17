package zio.openfeature.simulation

import zio._
import zio.test._
import zio.openfeature.testsupport.PropertyBudget

/** Sequential model-based simulation: random provider stacks driven by random command sequences, every answer checked
  * against [[Model]]. Scale with `ZOF_PBT_SCALE`, pin with `ZOF_PBT_SEED`; the seed is printed at the start.
  */
object SequentialSimulationSpec extends ZIOSpecDefault {

  val genHomeKinds: Gen[Any, Map[String, Kind]] =
    Gen.collectAll(Gens.keys.toList.map(k => Gens.genKind.map(k -> _))).map(_.toMap)

  val genRun: Gen[Any, (zio.openfeature.simulation.Spec, List[Cmd])] =
    for {
      home <- genHomeKinds
      spec <- Gens.genSpec(3, home)
      cmds <- Gen.listOfBounded(1, 40)(Gens.genCmd(7, (0 to 7).toList, home))
    } yield (spec, cmds)

  private val printSeed: TestAspect[Nothing, Any, Nothing, Any] =
    TestAspect.before(
      Console
        .printLine(
          s"simulation seed: ${PropertyBudget.seed.fold("zio-test default")(_.toString)} scale=${PropertyBudget.scale}"
        )
        .orDie
    )

  def spec = suite("SequentialSimulation")(
    test("every answer, hook stage and shutdown matches the model") {
      check(genRun) { case (spec, cmds) =>
        Runner.run(spec, cmds).either.map(r => assertTrue(r == Right(())))
      }
    } @@ PropertyBudget(200) @@ printSeed @@ TestAspect.after(Console.printLine(Stats.render).orDie)
  ) @@ TestAspect.withLiveClock @@ PropertyBudget.timeout(10.minutes)
}
