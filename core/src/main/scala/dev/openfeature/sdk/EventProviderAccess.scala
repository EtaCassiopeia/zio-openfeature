package dev.openfeature.sdk

/** Access to package-private SDK members needed for hot-swap rollback (#282).
  *
  * When `setProvider`'s `setProviderAndWait(new)` fails because the new provider's `initialize()` throws, the SDK has
  * already evicted the old provider's state manager from the domain/default slot but left the old `EventProvider`
  * `attach`ed. Re-registering the old provider then throws `IllegalStateException: already attached` unless its attach
  * state is first reset via the package-private `detach()`. This shim exposes that member.
  *
  * Compile-time checked rather than reflective: an SDK upgrade that changes these signatures fails compilation here
  * instead of surfacing as a runtime reflection error. Same package-shim pattern as `extras`' `EventProviderBridge`
  * (which reaches the package-private `EventProvider.attach`/`detach`).
  */
object EventProviderAccess {

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
