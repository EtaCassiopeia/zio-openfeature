package zio.openfeature.internal

import zio.openfeature.FeatureFlags
import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider}

/** The one way to read a provider's name. A provider may answer `getMetadata` with `null`, or with a metadata whose
  * `getName` is `null`, so neither call is safe on its own. Both reads call the provider, so a provider whose
  * `getMetadata` throws throws here too.
  */
private[openfeature] object ProviderNames {

  /** `provider`'s name, or `None` if it has none: for a caller that does nothing without one. */
  def optional(provider: OFFeatureProvider): Option[String] =
    Option(provider.getMetadata).flatMap(metadata => Option(metadata.getName))

  /** `provider`'s name, or `FeatureFlags.UnknownProviderName` if it has none. Never `null`. */
  def of(provider: OFFeatureProvider): String =
    optional(provider).getOrElse(FeatureFlags.UnknownProviderName)
}
