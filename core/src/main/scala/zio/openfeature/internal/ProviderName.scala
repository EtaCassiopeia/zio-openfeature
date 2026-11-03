package zio.openfeature.internal

import zio.openfeature.FeatureFlags

/** What an instance knows of the installed provider's name. The two states without a name need opposite treatment: an
  * event carrying a name cannot be attributed while the name is [[ProviderName.Pending]], and is another provider's
  * once it is [[ProviderName.Nameless]].
  */
sealed private[openfeature] trait ProviderName {

  /** What `ProviderMetadata.name` and the hook context report: the name, or `FeatureFlags.UnknownProviderName`. */
  def render: String
}

private[openfeature] object ProviderName {

  /** The provider's metadata name. */
  final case class Named(name: String) extends ProviderName {
    def render: String = name
  }

  /** Read once the provider had initialized: it has no name. The SDK stamps a null name on its events, so an event with
    * a name is another provider's.
    */
  case object Nameless extends ProviderName {
    def render: String = FeatureFlags.UnknownProviderName
  }

  /** No name before `initialize()`: read again once the provider has initialized. Events cannot be attributed until
    * then.
    */
  case object Pending extends ProviderName {
    def render: String = FeatureFlags.UnknownProviderName
  }
}
