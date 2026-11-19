package zio.openfeature.simulation

import zio.openfeature.testsupport.PropertyBudget
import zio.test.{Spec => _, _}

object PlanCodecSpec extends ZIOSpecDefault {

  private val golden: SeqPlan = SeqPlan(
    Spec.Cache(-1, Spec.Multi(true, List(Spec.Leaf(-1, Map("k0" -> Stored.SDbl(0.5), "echo" -> Stored.Echo))))),
    List(
      Cmd.SetFault(1, Some(LeafFault.ThrowGeneral)),
      Cmd.Eval("k0", Kind.KLong(5000000001L), Ctx(Some("u1"), Map("a" -> "1")), None)
    )
  )

  private val goldenJson: String =
    """{"cmds":[{"SetFault":{"fault":"ThrowGeneral","leaf":1}},""" +
      """{"Eval":{"invocation":{"attrs":{"a":"1"},"tk":"u1"},"key":"k0","kind":{"KLong":{"default":5000000001}},"scoped":null}}],""" +
      """"spec":{"Cache":{"id":-1,"inner":{"Multi":{"children":[{"Leaf":{"id":-1,"initial":{"echo":"Echo","k0":{"SDbl":{"v":"0.5"}}}}}],"firstSuccessful":true}}}}}"""

  private def failure[A: PlanCodec](text: String): String = PlanCodec.read[A](text).left.getOrElse("decoded")

  def spec = suite("PlanCodec")(
    test("a sequential plan reads back as itself") {
      check(SequentialSimulationSpec.genRun)(plan =>
        assertTrue(PlanCodec.read[SeqPlan](PlanCodec.write(plan)) == Right(plan))
      )
    } @@ PropertyBudget(200),
    test("a concurrent plan reads back as itself") {
      check(ConcurrentSimulationSpec.genPlan) { plan =>
        assertTrue(PlanCodec.read[ConcurrentSimulationSpec.Plan](PlanCodec.write(plan)) == Right(plan))
      }
    } @@ PropertyBudget(200),
    test("a plan is written as the checked-in text, and the text reads as the plan") {
      assertTrue(PlanCodec.write(golden) == goldenJson, PlanCodec.read[SeqPlan](goldenJson) == Right(golden))
    },
    test("a decode error names the path to the field") {
      val badId = failure[SeqPlan]("""{"cmds":[],"spec":{"Cache":{"id":"x","inner":{"Leaf":{"id":1,"initial":{}}}}}}""")
      val badLeaf = failure[SeqPlan]("""{"cmds":[],"spec":{"Leaf":{"id":1,"initial":{"k0":5}}}}""")
      val badSwap =
        failure[SeqPlan]("""{"cmds":[{"Swap":{"spec":{"Nope":{}}}}],"spec":{"Leaf":{"id":1,"initial":{}}}}""")
      assertTrue(
        badId.contains("spec: Cache.id"),
        badLeaf.contains("spec: Leaf.initial: k0"),
        badSwap.contains("cmds: Swap.spec: unknown case Nope")
      )
    },
    test("a missing Option field reads as None") {
      val text = """{"key":"k0","kind":"KStr","invocation":{"attrs":{}}}"""
      assertTrue(PlanCodec.read[Cmd.Eval](text) == Right(Cmd.Eval("k0", Kind.KStr, Ctx(None, Map.empty), None)))
    }
  )
}
