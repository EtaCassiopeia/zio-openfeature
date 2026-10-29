package dev.openfeature.sdk

/** Access to the package-private provider state manager, so a registration whose initialization failed can be retried.
  *
  * The SDK reuses the state manager of a provider that is already registered and initializes it only while it is
  * `NOT_READY`. A failed `initialize()` leaves it `ERROR` and still bound, so registering the same provider again never
  * calls `initialize()`: it replays the error. The failure does clear the manager's initialized flag, though, so the
  * manager's own `initialize` runs the provider's `initialize()` again — without shutting it down first, which some
  * providers (an Optimizely provider that owns its client) cannot come back from.
  *
  * Compile-time checked rather than reflective, like [[EventProviderAccess]]: an SDK upgrade that changes these members
  * fails compilation here.
  */
object ProviderStateAccess {

  /** Run `provider`'s initialization again through its state manager in `domain`'s slot (the default slot when `domain`
    * is null), when that manager is `ERROR`. When it is `FATAL`, which the specification makes final, throw a
    * `FatalError` at once: the SDK replays no event for that state, so a client built on it would otherwise wait out
    * its whole init timeout. A no-op when the slot holds another provider or any other state, and when the provider
    * initialized successfully once (an `ERROR` it raised at run time is its own to recover from: the manager skips a
    * second initialization). Throws what `initialize()` throws, and blocks for as long as it runs.
    *
    * Concurrent retries of one manager are serialized: the manager keeps reporting `ERROR` while a retry runs, so a
    * second caller would otherwise skip it and register against that stale `ERROR`. The SDK emits no `PROVIDER_READY`
    * for a retry made this way; a client registered afterwards learns the state from the SDK's replay.
    */
  def retryFailedInit(api: OpenFeatureAPI, domain: String, provider: FeatureProvider): Unit = {
    val manager = api.getFeatureProviderStateManager(domain)
    if (manager.getProvider eq provider) {
      if (manager.getState == ProviderState.FATAL)
        throw new exceptions.FatalError("Provider is in a fatal state and is not initialized again")
      manager.synchronized {
        if (manager.getState == ProviderState.ERROR) manager.initialize(api.getEvaluationContext, domain)
      }
    }
  }
}
