package zio.openfeature.extras

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  ImmutableContext,
  Metadata,
  ProviderEvaluation,
  Value
}
import zio.test._

/** `IntegerWideningLongProvider` restores pre-1.22.0 Long routing for third-party providers that never overrode
  * `getLongEvaluation`. Without it, such a provider inherits the SDK's double-backed default, so an integer-stored flag
  * is resolved by its *double* resolver — which is exactly what the old hand-rolled routing in `ClientEvaluator`
  * existed to avoid (#333).
  */
object IntegerWideningLongProviderSpec extends ZIOSpecDefault {

  private val ctx = new ImmutableContext("user")

  /** Stands in for a pre-1.22.0 third-party provider: distinct values per resolver, and no `getLongEvaluation`
    * override, so the SDK's default would route Long through `getDoubleEvaluation`.
    */
  // Every builder use below is split across statements and cast at the end, never chained: the SDK's
  // SuperBuilder-style self-type defeats Scala 2.13's existential resolution past the second fluent call, and this
  // spec is cross-built. The production wrapper carries the same note for the same reason.
  private class LegacyProvider extends FeatureProvider {
    override def getMetadata: Metadata = new Metadata { def getName: String = "Legacy" }
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = {
      val b = ProviderEvaluation.builder[java.lang.Boolean]()
      b.value(d)
      b.build().asInstanceOf[ProviderEvaluation[java.lang.Boolean]]
    }
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) = {
      val b = ProviderEvaluation.builder[String]()
      b.value(d)
      b.build().asInstanceOf[ProviderEvaluation[String]]
    }
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) = {
      val b = ProviderEvaluation.builder[java.lang.Integer]()
      b.value(Integer.valueOf(7))
      b.reason("STATIC")
      b.variant("int-variant")
      b.build().asInstanceOf[ProviderEvaluation[java.lang.Integer]]
    }
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) = {
      val b = ProviderEvaluation.builder[java.lang.Double]()
      b.value(java.lang.Double.valueOf(99.0))
      b.reason("STATIC")
      b.build().asInstanceOf[ProviderEvaluation[java.lang.Double]]
    }
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = {
      val b = ProviderEvaluation.builder[Value]()
      b.value(d)
      b.build().asInstanceOf[ProviderEvaluation[Value]]
    }
  }

  def spec = suite("IntegerWideningLongProvider")(
    test("an int-range default is resolved by the wrapped provider's INTEGER resolver") {
      val p      = IntegerWideningLongProvider(new LegacyProvider)
      val result = p.getLongEvaluation("flag", java.lang.Long.valueOf(0L), ctx)
      assertTrue(result.getValue.longValue == 7L)
    },
    test("resolver metadata is preserved through the widening") {
      val p      = IntegerWideningLongProvider(new LegacyProvider)
      val result = p.getLongEvaluation("flag", java.lang.Long.valueOf(0L), ctx)
      assertTrue(result.getReason == "STATIC", result.getVariant == "int-variant")
    },
    test("without the wrapper the same provider resolves Long via the DOUBLE resolver") {
      // Pins the problem the wrapper exists to solve; if this ever returns 7 the wrapper is redundant.
      val bare = new LegacyProvider
      assertTrue(bare.getLongEvaluation("flag", java.lang.Long.valueOf(0L), ctx).getValue.longValue == 99L)
    },
    test("an out-of-Int-range default defers to the wrapped provider's long path") {
      val p      = IntegerWideningLongProvider(new LegacyProvider)
      val result = p.getLongEvaluation("flag", java.lang.Long.valueOf(5000000000L), ctx)
      // Falls through to the SDK default on the delegate, i.e. the double resolver — NOT a truncated int read.
      assertTrue(result.getValue.longValue == 99L)
    },
    test("a null default does not throw") {
      val p = IntegerWideningLongProvider(new LegacyProvider)
      assertTrue(p.getLongEvaluation("flag", null, ctx).getValue.longValue == 99L)
    }
  )
}
