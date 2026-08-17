package zio.openfeature

import dev.openfeature.sdk.multiprovider.{FirstMatchStrategy, FirstSuccessfulStrategy}

/** Built-in strategies for [[FeatureFlags.multiProvider]].
  *
  * Re-exports the Java SDK's strategy implementations under stable Scala names so callers do not need to depend on the
  * `dev.openfeature.sdk.multiprovider` package directly.
  */
object MultiProviderStrategy {

  /** Alias for [[dev.openfeature.sdk.multiprovider.Strategy]]. */
  type Strategy = dev.openfeature.sdk.multiprovider.Strategy

  /** Returns the result of the first provider that reports the flag as found.
    *
    * A provider signals "I do not have this flag" either by returning the `FLAG_NOT_FOUND` error code or by throwing
    * `FlagNotFoundError`, and only those make the chain move on. A result carrying `reason = DEFAULT` and no error code
    * does '''not''' — it is taken as an answer and ends the chain, which is why a provider must report `FLAG_NOT_FOUND`
    * for an absent key rather than a default-reason value (#355). Any other evaluation error aborts the chain; use
    * [[firstSuccessful]] if you want errors to fall through as well. When every provider reports not-found, the chain
    * answers `FLAG_NOT_FOUND` and the SDK client substitutes the caller's default.
    *
    * A fresh instance is returned each call to stay safe if the upstream class ever gains internal state.
    */
  def firstMatch: Strategy = new FirstMatchStrategy

  /** Returns the result of the first provider whose evaluation completes without an error. Errors fall through to the
    * next provider; default-reason results do not, since they count as success.
    *
    * Note `FLAG_NOT_FOUND` is an error here, so a provider that does not hold the key falls through as well — and if no
    * provider holds it, the chain surfaces `GENERAL` with the aggregated per-provider errors rather than a clean
    * default. That is the shape to expect from the common "primary provider plus a small override provider" pattern
    * when the primary is failing and the key is not in the override set (#355).
    *
    * A fresh instance is returned each call.
    */
  def firstSuccessful: Strategy = new FirstSuccessfulStrategy
}
