package dev.openfeature.sdk

import dev.openfeature.sdk.internal.{AutoCloseableReentrantReadWriteLock, TriConsumer}

/** Access to package-private SDK members needed for hot-swap rollback and event forwarding.
  *
  * When `setProvider`'s registration of the new provider fails because the new provider's `initialize()` throws, the
  * SDK has already evicted the old provider's state manager from the domain/default slot but left the old
  * `EventProvider` `attach`ed. Re-registering the old provider then throws `IllegalStateException: already attached`
  * unless its attach state is first reset via the package-private `detach()`. This shim exposes that member.
  *
  * Compile-time checked rather than reflective: an SDK upgrade that changes these signatures fails compilation here
  * instead of surfacing as a runtime reflection error. Same package-shim pattern as `extras`' `EventProviderBridge`
  * (which reaches the package-private `EventProvider.attach`/`detach`).
  *
  * Phase 2 watch-point: re-verify this shim still applies once the OpenFeature Java SDK ships the spec-v0.9.0
  * provider-event marker — it may change how attach/detach state is managed.
  */
object EventProviderAccess {

  /** Forward every event `provider` emits to `onEvent`, which runs on the provider's emitter executor. No-op for
    * providers that don't extend `EventProvider` (they never emit). Used by `fromAcquireAsync`'s switchable provider,
    * whose delegate is never registered with an API, so the SDK never attaches to it. Same call shape as `extras`'
    * `EventProviderBridge.attach`: the lock is private to this attachment, since no API serializes against it. Throws
    * `IllegalStateException` if the provider is already attached.
    */
  def attach(provider: FeatureProvider, onEvent: (ProviderEvent, ProviderEventDetails) => Unit): Unit =
    provider match {
      case ep: EventProvider =>
        ep.attach(
          new TriConsumer[EventProvider, ProviderEvent, ProviderEventDetails] {
            override def accept(p: EventProvider, event: ProviderEvent, details: ProviderEventDetails): Unit =
              onEvent(event, details)
          },
          new AutoCloseableReentrantReadWriteLock()
        )
      case _ => ()
    }

  /** Reset an `EventProvider`'s attach state so it can be re-registered with an API instance. No-op for providers that
    * don't extend `EventProvider` (they never attach). The SDK's `detach()` is idempotent (a plain
    * `AtomicReference.set(null)`).
    */
  def detach(provider: FeatureProvider): Unit = provider match {
    case ep: EventProvider => ep.detach()
    case _                 => ()
  }

  /** Release a provider from the SDK's global provider registry (spec 1.8.4) — used only in the rollback-failure
    * cleanup path, mirroring what `ProviderRepository.shutDownOld` would have done on a successful rollback.
    */
  def deregisterGlobalProvider(api: OpenFeatureAPI, provider: FeatureProvider): Unit =
    api.deregisterGlobalProvider(provider)
}
