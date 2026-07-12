package zio.openfeature

import zio._
import zio.test._
import zio.test.TestAspect.{sequential, withLiveClock}
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  ProviderState,
  Value
}
import java.util.concurrent.atomic.AtomicReference

/** #252: per-evaluation context merging short-circuits the empty-layer case (identity, no allocation) and the
  * Scala->Java conversion is cached by identity — while still being correct (no stale context after
  * `setGlobalContext`).
  */
object ContextMergeAndCacheSpec extends ZIOSpecDefault {

  /** Records the targeting key the provider actually receives. */
  private class InspectingProvider(seen: AtomicReference[String]) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Inspecting" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = {
      seen.set(Option(c.getTargetingKey).getOrElse("none"))
      ProviderEvaluations.of[java.lang.Boolean](true, "STATIC")
    }
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  private def buildFF(seen: AtomicReference[String]): ZIO[Scope, Throwable, FeatureFlags] = {
    val api = OpenFeatureAPI.createIsolated()
    FeatureFlags.build(
      new InspectingProvider(seen),
      domain = Some(s"ctx-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(api),
      evaluationTimeout = Some(5.seconds)
    )
  }

  def spec = suite("ContextMergeAndCacheSpec")(
    test("merge with an empty context returns the same instance (identity short-circuit)") {
      val a = EvaluationContext("user").withAttribute("k", AttributeValue.StringValue("v"))
      assertTrue(
        a.merge(EvaluationContext.empty) eq a,
        EvaluationContext.empty.merge(a) eq a
      )
    },
    test("merge still combines non-empty contexts correctly (semantics preserved)") {
      val a = EvaluationContext("user").withAttribute("a", AttributeValue.StringValue("1"))
      val b = EvaluationContext.empty.withAttribute("b", AttributeValue.StringValue("2"))
      val m = a.merge(b)
      assertTrue(
        m.targetingKey.contains("user"),
        m.getString("a").contains("1"),
        m.getString("b").contains("2")
      )
    },
    test(
      "merge of a targeting-key-only context with an attributes-only context preserves both (mergeAttributes empty-base branch)"
    ) {
      val keyOnly = EvaluationContext("user-1") // targeting key, no attributes -> non-empty, so no merge short-circuit
      val attrsOnly = EvaluationContext.empty.withAttribute("b", AttributeValue.StringValue("1"))
      val m         = keyOnly.merge(attrsOnly)
      assertTrue(m.targetingKey.contains("user-1"), m.getString("b").contains("1"))
    },
    test("the Java-context conversion cache does not serve a stale context after setGlobalContext") {
      val seen = new AtomicReference[String]("unset")
      ZIO.scoped {
        buildFF(seen).flatMap { ff =>
          for {
            _ <- ff.setGlobalContext(EvaluationContext("A"))
            _ <- ff.boolean("flag", default = false)
            a <- ZIO.succeed(seen.get())
            _ <- ff.setGlobalContext(EvaluationContext("B"))
            _ <- ff.boolean("flag", default = false)
            b <- ZIO.succeed(seen.get())
          } yield assertTrue(a == "A", b == "B") // B, not a stale cached A
        }
      }
    }
  ) @@ sequential @@ withLiveClock
}
