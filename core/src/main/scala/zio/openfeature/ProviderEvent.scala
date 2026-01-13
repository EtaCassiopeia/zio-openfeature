package zio.openfeature

/** Type of provider event for use with generic event handlers. */
enum ProviderEventType:
  case Ready
  case Error
  case Stale
  case ConfigurationChanged
  case Reconnecting

/** Events emitted by feature flag providers during their lifecycle.
  *
  * Per OpenFeature spec section 5, providers emit events when their state changes. These events can be used for
  * logging, monitoring, or triggering application-specific behavior.
  */
enum ProviderEvent:
  case Ready(providerMetadata: ProviderMetadata)
  case Error(error: Throwable, providerMetadata: ProviderMetadata)
  case Stale(reason: String, providerMetadata: ProviderMetadata)
  case ConfigurationChanged(changedFlags: Set[String], providerMetadata: ProviderMetadata)
  case Reconnecting(providerMetadata: ProviderMetadata)

  /** Get the event type for this event. */
  def eventType: ProviderEventType = this match
    case _: ProviderEvent.Ready                => ProviderEventType.Ready
    case _: ProviderEvent.Error                => ProviderEventType.Error
    case _: ProviderEvent.Stale                => ProviderEventType.Stale
    case _: ProviderEvent.ConfigurationChanged => ProviderEventType.ConfigurationChanged
    case _: ProviderEvent.Reconnecting         => ProviderEventType.Reconnecting

object ProviderEvent:
  extension (event: ProviderEvent)
    /** Extract the provider metadata from any event type. */
    def metadata: ProviderMetadata = event match
      case Ready(m)                   => m
      case Error(_, m)                => m
      case Stale(_, m)                => m
      case ConfigurationChanged(_, m) => m
      case Reconnecting(m)            => m

    /** Check if this is an error event. */
    def isError: Boolean = event match
      case Error(_, _) => true
      case _           => false

    /** Check if this event indicates a healthy provider state. */
    def isHealthy: Boolean = event match
      case Ready(_)                   => true
      case ConfigurationChanged(_, _) => true
      case _                          => false
