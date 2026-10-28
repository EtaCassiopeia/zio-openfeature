package zio.openfeature.extras

import dev.openfeature.sdk.exceptions.TypeMismatchError
import dev.openfeature.sdk.{
  ErrorCode,
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Hook,
  Metadata,
  ProviderEvaluation,
  TrackingEventDetails,
  Value
}

/** Escape hatch for third-party `FeatureProvider`s that predate SDK 1.22.0 and so do not override `getLongEvaluation`:
  * the interface's default implementation routes it through `getDoubleEvaluation`, which TYPE_MISMATCHes an
  * integer-stored flag whenever the provider's double resolution doesn't also accept an integer-typed value. Wrapping
  * such a provider here resolves `Long` evaluations through its existing `getIntegerEvaluation` path instead, for
  * defaults that fit in an `Int`. If that path reports `TYPE_MISMATCH` (returned or thrown) — e.g. the stored value is
  * a long beyond `Int` range — the evaluation is retried on the provider's own long path. A provider that silently
  * narrows such a value instead of reporting a mismatch cannot be detected here.
  *
  * The providers bundled with this library (`HoconProvider`, `EnvVarProvider`, `TestFeatureProvider`,
  * `OptimizelyFeatureProvider`) already implement `getLongEvaluation` natively and never need this wrapper.
  *
  * `getState` is not forwarded: it is deprecated in 1.22.0 (the SDK owns provider status now), so the interface's own
  * default applies rather than this wrapper adding a fresh use of a deprecated API. The delegation law
  * (`WrapperDelegationSpec`) lists it as handled by the wrapper for the same reason.
  */
final class IntegerWideningLongProvider(underlying: FeatureProvider) extends FeatureProvider {

  override def getMetadata: Metadata = underlying.getMetadata

  override def getProviderHooks: java.util.List[Hook[_]] = underlying.getProviderHooks

  override def track(eventName: String, context: OFEvaluationContext, details: TrackingEventDetails): Unit =
    underlying.track(eventName, context, details)

  override def initialize(context: OFEvaluationContext): Unit = underlying.initialize(context)

  override def initialize(context: OFEvaluationContext, domain: String): Unit =
    underlying.initialize(context, domain)

  override def isDomainScoped(): Boolean = underlying.isDomainScoped()

  override def shutdown(): Unit = underlying.shutdown()

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] = underlying.getBooleanEvaluation(key, defaultValue, context)

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    context: OFEvaluationContext
  ): ProviderEvaluation[String] = underlying.getStringEvaluation(key, defaultValue, context)

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] = underlying.getIntegerEvaluation(key, defaultValue, context)

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] = underlying.getDoubleEvaluation(key, defaultValue, context)

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    context: OFEvaluationContext
  ): ProviderEvaluation[Value] = underlying.getObjectEvaluation(key, defaultValue, context)

  override def getLongEvaluation(
    key: String,
    defaultValue: java.lang.Long,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Long] =
    // Null-guarded: the SDK passes a null default through to the provider (its own default implementation
    // null-checks before range-testing, and upstream pins that in `LongDefaultDelegationTest`). Unboxing first
    // would NPE on a path the interface contract allows.
    if (defaultValue != null && defaultValue.longValue().isValidInt) {
      // The default says nothing about the stored value: a long beyond Int range read with default 0L reaches
      // the integer resolver too. On TYPE_MISMATCH — returned or thrown — retry on the long path, which resolves it.
      val asInt =
        try Some(underlying.getIntegerEvaluation(key, java.lang.Integer.valueOf(defaultValue.intValue()), context))
        catch { case _: TypeMismatchError => None }
      asInt.filterNot(_.getErrorCode == ErrorCode.TYPE_MISMATCH) match {
        case Some(intResult) => widen(intResult, defaultValue)
        case None            => underlying.getLongEvaluation(key, defaultValue, context)
      }
    } else underlying.getLongEvaluation(key, defaultValue, context)

  private def widen(
    intResult: ProviderEvaluation[java.lang.Integer],
    defaultValue: java.lang.Long
  ): ProviderEvaluation[java.lang.Long] = {
    // `underlying` is third-party by definition — the whole reason this wrapper exists — so it may hand back an
    // evaluation with no value on an error path. Fall back to the caller's default rather than NPE on unboxing.
    val widened = Option(intResult.getValue).map(i => java.lang.Long.valueOf(i.longValue())).getOrElse(defaultValue)
    // Builder calls are split across statements (not chained) — the SDK's SuperBuilder-style self-type confuses
    // Scala 2.13's existential resolution past the second fluent call.
    val builder = ProviderEvaluation.builder[java.lang.Long]()
    builder.value(widened)
    builder.variant(intResult.getVariant)
    builder.reason(intResult.getReason)
    builder.errorCode(intResult.getErrorCode)
    builder.errorMessage(intResult.getErrorMessage)
    builder.flagMetadata(intResult.getFlagMetadata)
    builder.build().asInstanceOf[ProviderEvaluation[java.lang.Long]]
  }
}

object IntegerWideningLongProvider {
  def apply(underlying: FeatureProvider): IntegerWideningLongProvider = new IntegerWideningLongProvider(underlying)
}
