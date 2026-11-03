package zio.openfeature.internal

import zio.openfeature.FeatureFlags
import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider}

/** The one way to read a provider's name. A provider may answer `getMetadata` with `null`, or with a metadata whose
  * `getName` is `null`, so neither call is safe on its own. Every read calls the provider, so a provider whose
  * `getMetadata` throws throws here too.
  */
private[openfeature] object ProviderNames {

  /** `provider`'s name, or `None` if it has none: for a caller that does nothing without one. */
  def optional(provider: OFFeatureProvider): Option[String] =
    Option(provider.getMetadata).flatMap(metadata => Option(metadata.getName))

  /** `provider`'s name, or `FeatureFlags.UnknownProviderName` if it has none. Never `null`. */
  def of(provider: OFFeatureProvider): String =
    optional(provider).getOrElse(FeatureFlags.UnknownProviderName)

  /** Read before `initialize()`: a provider with no name yet may get one. */
  def beforeInit(provider: OFFeatureProvider): ProviderName =
    optional(provider).fold[ProviderName](ProviderName.Pending)(ProviderName.Named(_))

  /** Read after `initialize()`: the name is final. */
  def afterInit(provider: OFFeatureProvider): ProviderName =
    optional(provider).fold[ProviderName](ProviderName.Nameless)(ProviderName.Named(_))
}
