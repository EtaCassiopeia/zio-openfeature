package zio.openfeature.extras

import zio.openfeature.internal.ProviderEvaluations

import dev.openfeature.sdk.{
  ErrorCode,
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  ImmutableContext,
  Metadata,
  Value
}
import dev.openfeature.sdk.exceptions.TypeMismatchError
import zio.test._
import java.util.concurrent.atomic.AtomicInteger

// The integer-vs-long route was picked from the caller's DEFAULT, so a stored long beyond Int range read with a
// small default went down the integer resolver and failed TYPE_MISMATCH (or was narrowed).
object IntegerWideningFallbackSpec extends ZIOSpecDefault {

  /** Pre-1.22 style provider (no getLongEvaluation override). "big" = 5e9, "small" = 7, "str" = a string flag. */
  private class LegacyLongProvider(throwOnMismatch: Boolean) extends FeatureProvider {
    val integerCalls = new AtomicInteger(0)
    val doubleCalls  = new AtomicInteger(0)

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata { def getName: String = "legacy" }

    override def getIntegerEvaluation(key: String, default: java.lang.Integer, ctx: OFEvaluationContext) = {
      integerCalls.incrementAndGet()
      key match {
        case "small"              => ProviderEvaluations.of[java.lang.Integer](Integer.valueOf(7), "STATIC")
        case _ if throwOnMismatch => throw new TypeMismatchError(s"$key is not an integer")
        case _ => ProviderEvaluations.error(default, ErrorCode.TYPE_MISMATCH, s"$key is not an integer")
      }
    }
    override def getDoubleEvaluation(key: String, default: java.lang.Double, ctx: OFEvaluationContext) = {
      doubleCalls.incrementAndGet()
      key match {
        case "big"   => ProviderEvaluations.of[java.lang.Double](java.lang.Double.valueOf(5e9), "STATIC")
        case "small" => ProviderEvaluations.of[java.lang.Double](java.lang.Double.valueOf(7.0), "STATIC")
        case _       => ProviderEvaluations.error(default, ErrorCode.TYPE_MISMATCH, s"$key is not a number")
      }
    }
    override def getBooleanEvaluation(key: String, default: java.lang.Boolean, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getStringEvaluation(key: String, default: String, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getObjectEvaluation(key: String, default: Value, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
  }

  private val ctx  = new ImmutableContext("user")
  private val zero = java.lang.Long.valueOf(0L)

  def spec = suite("IntegerWideningFallbackSpec")(
    test(
      "a stored long beyond Int range read with a small default falls back when the integer path returns TYPE_MISMATCH"
    ) {
      val result =
        IntegerWideningLongProvider(new LegacyLongProvider(throwOnMismatch = false)).getLongEvaluation("big", zero, ctx)
      assertTrue(result.getValue.longValue == 5000000000L, result.getErrorCode == null)
    },
    test("the same when the integer path throws TypeMismatchError") {
      val result =
        IntegerWideningLongProvider(new LegacyLongProvider(throwOnMismatch = true)).getLongEvaluation("big", zero, ctx)
      assertTrue(result.getValue.longValue == 5000000000L, result.getErrorCode == null)
    },
    test("a flag of a genuinely different type still reports TYPE_MISMATCH after the fallback") {
      val result =
        IntegerWideningLongProvider(new LegacyLongProvider(throwOnMismatch = false)).getLongEvaluation("str", zero, ctx)
      assertTrue(result.getErrorCode == ErrorCode.TYPE_MISMATCH, result.getValue.longValue == 0L)
    },
    test("an Int-range stored value is still one call to the integer resolver") {
      val provider = new LegacyLongProvider(throwOnMismatch = false)
      val result   = IntegerWideningLongProvider(provider).getLongEvaluation("small", zero, ctx)
      assertTrue(result.getValue.longValue == 7L, provider.integerCalls.get() == 1, provider.doubleCalls.get() == 0)
    }
  )
}
