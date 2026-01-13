package zio.openfeature

/** Metadata about the feature flag provider.
  *
  * @param name
  *   The name of the provider (e.g., "flagd", "LaunchDarkly")
  * @param version
  *   Optional version string of the provider
  */
final case class ProviderMetadata(
  name: String,
  version: Option[String] = None
):
  override def toString: String = version.fold(name)(v => s"$name v$v")

object ProviderMetadata:
  def apply(name: String, version: String): ProviderMetadata =
    ProviderMetadata(name, Some(version))

/** Metadata about the feature flags client.
  *
  * Per OpenFeature spec requirement 1.2.2, clients must have immutable metadata containing a domain field.
  *
  * @param domain
  *   Optional domain name for client isolation
  */
final case class ClientMetadata(
  domain: Option[String] = None
):
  /** Returns true if this client is bound to a specific domain. */
  def hasDomain: Boolean = domain.isDefined

  override def toString: String = domain.getOrElse("default")

object ClientMetadata:
  val default: ClientMetadata = ClientMetadata(None)

  def apply(domain: String): ClientMetadata =
    ClientMetadata(Some(domain))
