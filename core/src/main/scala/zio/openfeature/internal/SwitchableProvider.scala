package zio.openfeature.internal

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  EventProviderAccess,
  FeatureProvider => OFFeatureProvider,
  Hook => OFHook,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  TrackingEventDetails => OFTrackingEventDetails,
  Value
}

/** The one provider `fromAcquireAsync` registers with the SDK for the life of its layer.
  *
  * Swapping through the SDK's `setProvider` opens a window where every typed evaluation fails with `ProviderNotReady`:
  * the SDK binds the new provider to the slot before it initializes it, and refuses evaluations while the slot is
  * `NOT_READY`. This provider never leaves the slot. It starts out delegating to the fallback, which the SDK
  * initializes once, and is flipped to the already-initialized composed stack with [[switchTo]], so the slot stays
  * `READY` throughout.
  *
  * Everything delegates to the current delegate. The SDK attaches to this provider, not to the delegate, so the
  * delegate's events are re-emitted through this provider; events from a delegate that has since been replaced are
  * dropped.
  */
final private[openfeature] class SwitchableProvider(initial: OFFeatureProvider) extends EventProvider {

  private val current = new AtomicReference[OFFeatureProvider](initial)

  forwardEventsOf(initial)

  /** The provider currently answering. */
  def delegate: OFFeatureProvider = current.get()

  /** Route everything to `next`, which must already be initialized, and return the replaced delegate. The caller owns
    * the replaced delegate and shuts it down. Throws `IllegalStateException` if `next` is an `EventProvider` that is
    * already attached elsewhere; the delegate is then unchanged.
    */
  def switchTo(next: OFFeatureProvider): OFFeatureProvider = synchronized {
    forwardEventsOf(next)
    val old = current.getAndSet(next)
    EventProviderAccess.detach(old)
    old
  }

  private def forwardEventsOf(provider: OFFeatureProvider): Unit =
    EventProviderAccess.attach(
      provider,
      (event, details) =>
        if (current.get() eq provider)
          try { emit(event, details); () }
          catch {
            // Our emitter executor is already shut down: the layer is closing, so there is no one left to tell.
            case _: RejectedExecutionException => ()
          }
    )

  override def getMetadata: Metadata                       = current.get().getMetadata
  override def getProviderHooks: java.util.List[OFHook[_]] = current.get().getProviderHooks
  override def isDomainScoped: Boolean                     = current.get().isDomainScoped
  override def initialize(ctx: OFEvaluationContext): Unit  = current.get().initialize(ctx)
  override def initialize(ctx: OFEvaluationContext, domain: String): Unit =
    current.get().initialize(ctx, domain)

  @scala.annotation.nowarn("msg=deprecated")
  override def getState: ProviderState = current.get().getState

  override def track(eventName: String, ctx: OFEvaluationContext, details: OFTrackingEventDetails): Unit =
    current.get().track(eventName, ctx, details)

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] =
    current.get().getBooleanEvaluation(key, defaultValue, ctx)

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[String] =
    current.get().getStringEvaluation(key, defaultValue, ctx)

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] =
    current.get().getIntegerEvaluation(key, defaultValue, ctx)

  override def getLongEvaluation(
    key: String,
    defaultValue: java.lang.Long,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Long] =
    current.get().getLongEvaluation(key, defaultValue, ctx)

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] =
    current.get().getDoubleEvaluation(key, defaultValue, ctx)

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[Value] =
    current.get().getObjectEvaluation(key, defaultValue, ctx)

  /** Called by the SDK at API shutdown (or when a user `setProvider` replaces this provider). Reaches the current
    * delegate — after the flip, the composed stack, which shuts down its own children — and then this provider's
    * emitter executor.
    */
  override def shutdown(): Unit = {
    val d = current.get()
    EventProviderAccess.detach(d)
    try d.shutdown()
    finally super.shutdown()
  }
}
