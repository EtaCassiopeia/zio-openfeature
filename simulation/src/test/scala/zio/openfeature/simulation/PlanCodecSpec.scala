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
      """"spec":{"Cache":{"id":-1,"inner":{"Multi":{"children":[{"Leaf":{"id":-1,"init":null,"initial":{"echo":"Echo","k0":{"SDbl":{"v":"0.5"}}},"shutdown":null}}],"firstSuccessful":true}}}}}"""

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
    },
    test("a concurrent plan written before it had a mid-run shutdown reads as one without it") {
      val text =
        """{"home":{},"muts":[],"scheduleSeed":7,"spec":{"Leaf":{"id":0,"initial":{}}},"swaps":[],"workers":[]}"""
      assertTrue(
        PlanCodec.read[ConcurrentSimulationSpec.Plan](text) ==
          Right(ConcurrentSimulationSpec.Plan(Spec.Leaf(0, Map.empty, None, None), Map.empty, Nil, Nil, Nil, 7L, None))
      )
    },
    test("a mid-run shutdown is written as the swap index it follows") {
      val plan =
        ConcurrentSimulationSpec.Plan(Spec.Leaf(0, Map.empty, None, None), Map.empty, Nil, Nil, Nil, 7L, Some(1))
      assertTrue(
        PlanCodec.write(plan).contains(""""shutdownAfterSwap":1"""),
        PlanCodec.read[ConcurrentSimulationSpec.Plan](PlanCodec.write(plan)) == Right(plan)
      )
    },
    test("a leaf written before it had lifecycle faults reads as one without them") {
      assertTrue(
        PlanCodec.read[Spec]("""{"Leaf":{"id":2,"initial":{}}}""") == Right(Spec.Leaf(2, Map.empty, None, None))
      )
    },
    test("lifecycle and stall faults read back as themselves") {
      val leaf  = Spec.Leaf(1, Map.empty, Some(InitFault.StallThenFail), Some(ShutdownFault.Throws))
      val fault = Cmd.SetFault(0, Some(LeafFault.Stall(pastBound = true)))
      assertTrue(
        PlanCodec.write[Spec](leaf) == """{"Leaf":{"id":1,"init":"StallThenFail","initial":{},"shutdown":"Throws"}}""",
        PlanCodec.read[Spec](PlanCodec.write[Spec](leaf)) == Right(leaf),
        PlanCodec.write[Cmd](fault) == """{"SetFault":{"fault":{"Stall":{"pastBound":true}},"leaf":0}}""",
        PlanCodec.read[Cmd](PlanCodec.write[Cmd](fault)) == Right(fault)
      )
    }
  )
}
