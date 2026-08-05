package dev.openfeature.sdk

import dev.openfeature.sdk.internal.{AutoCloseableReentrantReadWriteLock, TriConsumer}

/** Accessor for the Java SDK's package-private `EventProvider.attach`/`detach`.
  *
  * Provider wrappers (CachingProvider, CircuitBreakerProvider) hold a delegate that is never registered with an
  * `OpenFeatureAPI`, so events the delegate emits would otherwise go nowhere — the SDK only attaches to the provider it
  * registers (the wrapper). This object lives in the `dev.openfeature.sdk` package (the same package-shim pattern as
  * core's `EventProviderAccess`) to reach the package-private attach hook and forward delegate emissions to the
  * wrapper.
  *
  * A delegate supports exactly one attachment; wrapping a provider takes ownership of its event channel. Registering
  * the same delegate instance directly with an API while it is wrapped is unsupported (the SDK's own attach would
  * fail).
  *
  * Phase 2 watch-point (#340): re-verify this bridge still applies once the OpenFeature Java SDK ships the spec-v0.9.0
  * provider-event marker — it may change how event attachment/emission works.
  */
object EventProviderBridge {

  /** Forward every event emitted by `delegate` to `onEvent`. Runs on the delegate's emitter executor.
    *
    * Since 1.21.0, `attach` takes a read/write lock that `EventProvider.emit` holds for the duration of the callback.
    * The real `OpenFeatureAPI` uses this to serialize emission against its own state; our delegate is never registered
    * with an API, so a private lock owned solely by this attachment is sufficient.
    */
  def attach(delegate: EventProvider, onEvent: (ProviderEvent, ProviderEventDetails) => Unit): Unit =
    delegate.attach(
      new TriConsumer[EventProvider, ProviderEvent, ProviderEventDetails] {
        override def accept(p: EventProvider, event: ProviderEvent, details: ProviderEventDetails): Unit =
          onEvent(event, details)
      },
      new AutoCloseableReentrantReadWriteLock()
    )

  /** Remove the attachment installed by [[attach]]. */
  def detach(delegate: EventProvider): Unit = delegate.detach()
}
