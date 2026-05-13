package zio.openfeature

import dev.openfeature.sdk.multiprovider.{FirstMatchStrategy, FirstSuccessfulStrategy}

/** Built-in strategies for [[FeatureFlags.fromMultiProvider]] and [[FeatureFlags.fromMultiProviderAsync]].
  *
  * Re-exports the Java SDK's strategy implementations under stable Scala names so callers do not need to depend on the
  * `dev.openfeature.sdk.multiprovider` package directly.
  */
object MultiProviderStrategy {

  /** Alias for [[dev.openfeature.sdk.multiprovider.Strategy]]. */
  type Strategy = dev.openfeature.sdk.multiprovider.Strategy

  /** Returns the result of the first provider whose evaluation does not surface a default value. */
  val firstMatch: Strategy = new FirstMatchStrategy

  /** Returns the result of the first provider whose evaluation completes without an error. */
  val firstSuccessful: Strategy = new FirstSuccessfulStrategy
}
