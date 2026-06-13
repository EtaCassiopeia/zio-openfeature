package dev.openfeature.sdk

import dev.openfeature.sdk.internal.TriConsumer

/** Accessor for the Java SDK's package-private `EventProvider.attach`/`detach`.
  *
  * Provider wrappers (CachingProvider, CircuitBreakerProvider) hold a delegate that is never registered with an
  * `OpenFeatureAPI`, so events the delegate emits would otherwise go nowhere — the SDK only attaches to the provider it
  * registers (the wrapper). This object lives in the `dev.openfeature.sdk` package (same pattern as
  * `OpenFeatureAPIFactory`) to reach the package-private attach hook and forward delegate emissions to the wrapper.
  *
  * A delegate supports exactly one attachment; wrapping a provider takes ownership of its event channel. Registering
  * the same delegate instance directly with an API while it is wrapped is unsupported (the SDK's own attach would
  * fail).
  */
object EventProviderBridge {

  /** Forward every event emitted by `delegate` to `onEvent`. Runs on the delegate's emitter executor. */
  def attach(delegate: EventProvider, onEvent: (ProviderEvent, ProviderEventDetails) => Unit): Unit =
    delegate.attach(new TriConsumer[EventProvider, ProviderEvent, ProviderEventDetails] {
      override def accept(p: EventProvider, event: ProviderEvent, details: ProviderEventDetails): Unit =
        onEvent(event, details)
    })

  /** Remove the attachment installed by [[attach]]. */
  def detach(delegate: EventProvider): Unit = delegate.detach()
}
