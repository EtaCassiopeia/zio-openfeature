package zio.openfeature.extras

import dev.openfeature.sdk.{
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
  * defaults that fit in an `Int`.
  *
  * The providers bundled with this library (`HoconProvider`, `EnvVarProvider`, `TestFeatureProvider`,
  * `OptimizelyFeatureProvider`) already implement `getLongEvaluation` natively and never need this wrapper.
  *
  * `getState` is not forwarded: it is deprecated in 1.22.0 (the SDK owns provider status now, see #332), so the
  * interface's own default applies rather than this wrapper adding a fresh use of a deprecated API. The
  * wrapper-completeness spec exempts it for the same reason.
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
      val intResult = underlying.getIntegerEvaluation(key, java.lang.Integer.valueOf(defaultValue.intValue()), context)
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
    } else underlying.getLongEvaluation(key, defaultValue, context)
}

object IntegerWideningLongProvider {
  def apply(underlying: FeatureProvider): IntegerWideningLongProvider = new IntegerWideningLongProvider(underlying)
}
