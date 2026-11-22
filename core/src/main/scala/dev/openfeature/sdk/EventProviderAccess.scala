package dev.openfeature.sdk

import dev.openfeature.sdk.internal.{AutoCloseableReentrantReadWriteLock, TriConsumer}

/** Access to package-private SDK members needed to rebind a provider and to forward events.
  *
  * The SDK resets an `EventProvider`'s attachment only when the registration that replaced it initializes. One whose
  * registration ended otherwise (a failed swap evicted it, another failure or a user's binding replaced it, or its API
  * shut down) stays attached, and binding it again throws `IllegalStateException: ... is already attached.` unless its
  * attach state is first reset via the package-private `detach()`. This shim exposes that member, which
  * `ProviderStateAccess.bindReclaiming` and the release paths use.
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
    * providers that don't extend `EventProvider` (they never emit). Used by `ManagedProvider`, whose delegate is never
    * registered with an API, so the SDK never attaches to it. Same call shape as `extras`'
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

  /** Whether the SDK holds `provider` attached to a registration: the SDK attaches a provider when it binds one no slot
    * of the API holds, and detaches it when its replacement initializes, so a provider a slot holds is attached. Read
    * by attaching and detaching at once, the only way the SDK offers: call it under the API's write lock, which the SDK
    * takes to attach. A provider that does not extend `EventProvider` is never attached.
    */
  def attached(provider: FeatureProvider): Boolean = provider match {
    case ep: EventProvider =>
      try {
        ep.attach(
          new TriConsumer[EventProvider, ProviderEvent, ProviderEventDetails] {
            override def accept(p: EventProvider, event: ProviderEvent, details: ProviderEventDetails): Unit = ()
          },
          new AutoCloseableReentrantReadWriteLock()
        )
        ep.detach()
        false
      } catch { case _: IllegalStateException => true }
    case _ => false
  }

  /** Release a provider from the SDK's global provider registry (spec 1.8.4) — used only in the rollback-failure
    * cleanup path, mirroring what `ProviderRepository.shutDownOld` would have done on a successful rollback.
    */
  def deregisterGlobalProvider(api: OpenFeatureAPI, provider: FeatureProvider): Unit =
    api.deregisterGlobalProvider(provider)
}
