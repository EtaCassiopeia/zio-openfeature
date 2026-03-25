package zio.openfeature

/** Metadata about the feature flag provider.
  */
final case class ProviderMetadata(
  name: String,
  version: Option[String] = None
) {
  override def toString: String = version.fold(name)(v => s"$name v$v")
}

object ProviderMetadata {
  def apply(name: String, version: String): ProviderMetadata =
    ProviderMetadata(name, Some(version))
}

/** Metadata about the feature flags client.
  *
  * Per OpenFeature spec requirement 1.2.2, clients must have immutable metadata containing a domain field.
  */
final case class ClientMetadata(
  domain: Option[String] = None
) {
  /** Returns true if this client is bound to a specific domain. */
  def hasDomain: Boolean = domain.isDefined

  override def toString: String = domain.getOrElse("default")
}

object ClientMetadata {
  val default: ClientMetadata = ClientMetadata(None)

  def apply(domain: String): ClientMetadata =
    ClientMetadata(Some(domain))
}

/** Type of provider event for use with generic event handlers. */
sealed trait ProviderEventType extends Product with Serializable
object ProviderEventType {
  case object Ready                extends ProviderEventType
  case object Error                extends ProviderEventType
  case object Stale                extends ProviderEventType
  case object ConfigurationChanged extends ProviderEventType
  case object Reconnecting         extends ProviderEventType
}

sealed trait ProviderEvent extends Product with Serializable {
  /** Get the event type for this event. */
  def eventType: ProviderEventType = this match {
    case _: ProviderEvent.Ready                => ProviderEventType.Ready
    case _: ProviderEvent.Error                => ProviderEventType.Error
    case _: ProviderEvent.Stale                => ProviderEventType.Stale
    case _: ProviderEvent.ConfigurationChanged => ProviderEventType.ConfigurationChanged
    case _: ProviderEvent.Reconnecting         => ProviderEventType.Reconnecting
  }
}

object ProviderEvent {
  final case class Ready(providerMetadata: ProviderMetadata, eventMetadata: FlagMetadata = FlagMetadata.empty)
      extends ProviderEvent
  final case class Error(
    error: Throwable,
    providerMetadata: ProviderMetadata,
    errorCode: Option[ErrorCode] = None,
    errorMessage: Option[String] = None,
    eventMetadata: FlagMetadata = FlagMetadata.empty
  ) extends ProviderEvent
  final case class Stale(
    reason: String,
    providerMetadata: ProviderMetadata,
    eventMetadata: FlagMetadata = FlagMetadata.empty
  ) extends ProviderEvent
  final case class ConfigurationChanged(
    changedFlags: Set[String],
    providerMetadata: ProviderMetadata,
    eventMetadata: FlagMetadata = FlagMetadata.empty
  ) extends ProviderEvent
  final case class Reconnecting(providerMetadata: ProviderMetadata, eventMetadata: FlagMetadata = FlagMetadata.empty)
      extends ProviderEvent

  implicit class ProviderEventOps(val event: ProviderEvent) extends AnyVal {
    def metadata: ProviderMetadata = event match {
      case Ready(m, _)                   => m
      case Error(_, m, _, _, _)          => m
      case Stale(_, m, _)                => m
      case ConfigurationChanged(_, m, _) => m
      case Reconnecting(m, _)            => m
    }

    def eventMeta: FlagMetadata = event match {
      case Ready(_, em)                   => em
      case Error(_, _, _, _, em)          => em
      case Stale(_, _, em)                => em
      case ConfigurationChanged(_, _, em) => em
      case Reconnecting(_, em)            => em
    }

    def isError: Boolean = event match {
      case _: Error => true
      case _        => false
    }

    def isHealthy: Boolean = event match {
      case _: Ready                => true
      case _: ConfigurationChanged => true
      case _                       => false
    }
  }
}
