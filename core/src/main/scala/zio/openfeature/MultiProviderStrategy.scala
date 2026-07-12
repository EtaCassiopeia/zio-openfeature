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

  /** Returns the result of the first provider whose evaluation does not surface a default value. An evaluation error
    * from any provider aborts the chain; use [[firstSuccessful]] if you want errors to fall through to the next
    * provider. A fresh instance is returned each call to stay safe if the upstream class ever gains internal state.
    */
  def firstMatch: Strategy = new FirstMatchStrategy

  /** Returns the result of the first provider whose evaluation completes without an error. Errors fall through to the
    * next provider; default-reason results do not. A fresh instance is returned each call.
    */
  def firstSuccessful: Strategy = new FirstSuccessfulStrategy
}
