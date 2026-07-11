package zio.openfeature.testkit

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  FeatureProvider => OFFeatureProvider,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  TrackingEventDetails,
  Value
}

import java.util.concurrent.ConcurrentHashMap

/** A [[dev.openfeature.sdk.FeatureProvider]] decorator that reports the OpenFeature `CACHED` reason (spec 1.4.7) on the
  * second and subsequent evaluation of any given flag key, delegating everything else to the wrapped provider.
  *
  * The stock SDK `InMemoryProvider` derives its reason from flag presence only and never emits `CACHED`, so the
  * conformance suite's `@reason-codes-cached` scenario — which evaluates one key twice and asserts `CACHED` — cannot
  * pass against it directly. Wrapping the in-memory provider with this decorator makes that scenario exercise the real
  * `ResolutionReason.Cached` mapping end to end.
  *
  * Applying it globally to the conformance in-memory setup is safe: that scenario is the only one in the suite that
  * evaluates a key more than once, and each scenario gets a fresh provider instance, so no other scenario ever observes
  * a `CACHED` reason. Key tracking is thread-safe.
  *
  * Not to be confused with `zio.openfeature.extras.CachingProvider`, which caches evaluation *results*; this decorator
  * only rewrites the reason and always re-delegates the actual evaluation.
  */
final class CachingReasonProvider(delegate: OFFeatureProvider) extends EventProvider {

  // Keys evaluated at least once. `add` returns false when the key was already present, marking a repeat evaluation.
  private val evaluated: java.util.Set[String] = ConcurrentHashMap.newKeySet[String]()

  private def cached[T](key: String, result: ProviderEvaluation[T]): ProviderEvaluation[T] =
    if (evaluated.add(key)) result
    else
      // Repeat evaluation: preserve value/variant/error/metadata, override only the reason to CACHED (spec 1.4.7).
      new ProviderEvaluation[T](
        result.getValue,
        result.getVariant,
        "CACHED",
        result.getErrorCode,
        result.getErrorMessage,
        result.getFlagMetadata
      )

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] =
    cached(key, delegate.getBooleanEvaluation(key, defaultValue, context))

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    context: OFEvaluationContext
  ): ProviderEvaluation[String] =
    cached(key, delegate.getStringEvaluation(key, defaultValue, context))

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] =
    cached(key, delegate.getIntegerEvaluation(key, defaultValue, context))

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] =
    cached(key, delegate.getDoubleEvaluation(key, defaultValue, context))

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    context: OFEvaluationContext
  ): ProviderEvaluation[Value] =
    cached(key, delegate.getObjectEvaluation(key, defaultValue, context))

  override def getMetadata: Metadata = delegate.getMetadata

  override def getProviderHooks = delegate.getProviderHooks

  @scala.annotation.nowarn("msg=deprecated")
  override def getState: ProviderState = delegate.getState

  override def initialize(context: OFEvaluationContext): Unit = delegate.initialize(context)

  override def shutdown(): Unit = delegate.shutdown()

  override def track(eventName: String, context: OFEvaluationContext, details: TrackingEventDetails): Unit =
    delegate.track(eventName, context, details)
}
