package zio.openfeature

final case class ProviderMetadata(
  name: String,
  version: Option[String] = None
):
  override def toString: String = version.fold(name)(v => s"$name v$v")

object ProviderMetadata:
  def apply(name: String, version: String): ProviderMetadata =
    ProviderMetadata(name, Some(version))

enum ProviderEvent:
  case Ready(providerMetadata: ProviderMetadata)
  case Error(error: Throwable, providerMetadata: ProviderMetadata)
  case Stale(reason: String, providerMetadata: ProviderMetadata)
  case ConfigurationChanged(changedFlags: Set[String], providerMetadata: ProviderMetadata)
  case Reconnecting(providerMetadata: ProviderMetadata)

object ProviderEvent:
  extension (event: ProviderEvent)
    def metadata: ProviderMetadata = event match
      case Ready(m)                   => m
      case Error(_, m)                => m
      case Stale(_, m)                => m
      case ConfigurationChanged(_, m) => m
      case Reconnecting(m)            => m

    def isError: Boolean = event match
      case Error(_, _) => true
      case _           => false

    def isHealthy: Boolean = event match
      case Ready(_)                   => true
      case ConfigurationChanged(_, _) => true
      case _                          => false
