package snippets

import zio.*
import zio.openfeature.*

/** The code on the "Lifecycle and events" page (docs/lifecycle.md). */
object Lifecycle:

  // --8<-- [start:readiness]
  // A /ready probe: waits up to 5 seconds for Ready or Stale, returns early on Fatal
  val readiness: URIO[FeatureFlags, Boolean] =
    ZIO.serviceWithZIO[FeatureFlags](_.awaitReady(5.seconds)).map(_.canEvaluate)

  // The status right now, without waiting
  val status: URIO[FeatureFlags, ProviderStatus] =
    FeatureFlags.providerStatus
  // --8<-- [end:readiness]

  // --8<-- [start:not-ready]
  // While the provider starts, serve the default instead of failing
  val checkout: ZIO[FeatureFlags, FeatureFlagError, Boolean] =
    FeatureFlags.boolean("new-checkout", default = false).catchSome { case _: FeatureFlagError.ProviderNotReady =>
      ZIO.succeed(false)
    }
  // --8<-- [end:not-ready]

  // --8<-- [start:handlers]
  val watchProvider: URIO[FeatureFlagsAdmin, UIO[Unit]] =
    for
      onReady  <- FeatureFlagsAdmin.onProviderReady(meta => ZIO.logInfo(s"${meta.name} is ready"))
      onError  <- FeatureFlagsAdmin.onProviderError((error, meta) => ZIO.logError(s"${meta.name}: ${error.getMessage}"))
      onStale  <- FeatureFlagsAdmin.onProviderStale((reason, meta) => ZIO.logWarning(s"${meta.name} is stale: $reason"))
      onChange <- FeatureFlagsAdmin.onConfigurationChanged((flags, _) => ZIO.logInfo(s"changed: ${flags.mkString(", ")}"))
      onAny    <- FeatureFlagsAdmin.on(ProviderEventType.Error, event => ZIO.logDebug(s"event: $event"))
    yield onReady *> onError *> onStale *> onChange *> onAny // run it to unsubscribe all five
  // --8<-- [end:handlers]

  // --8<-- [start:scoped-handler]
  // The handler is unsubscribed when the scope closes, even if the body fails
  def readyWhileScoped(onReady: ProviderMetadata => UIO[Unit]): ZIO[FeatureFlagsAdmin, Nothing, Unit] =
    ZIO.scoped {
      ZIO.acquireRelease(FeatureFlagsAdmin.onProviderReady(onReady))(cancel => cancel) *>
        ZIO.logInfo("handling events") // the work that needs the handler goes here
    }
  // --8<-- [end:scoped-handler]

  // --8<-- [start:events]
  val logEvents: ZIO[FeatureFlags & Scope, Nothing, Unit] =
    FeatureFlags.events
      .foreach {
        case ProviderEvent.Ready(meta, _)                   => ZIO.logInfo(s"$meta ready")
        case ProviderEvent.ConfigurationChanged(keys, _, _) => ZIO.logInfo(s"changed: $keys")
        case ProviderEvent.Stale(reason, _, _)              => ZIO.logWarning(s"stale: $reason")
        case ProviderEvent.Error(_, _, code, message, _)    => ZIO.logError(s"$code ${message.getOrElse("")}")
        case ProviderEvent.Reconnecting(_, _)               => ZIO.unit
      }
      .forkScoped // runs until the scope closes or the instance shuts down
      .unit
  // --8<-- [end:events]

  // --8<-- [start:shutdown]
  // Also runs on its own when the layer's scope closes
  val stop: URIO[FeatureFlagsAdmin, Unit] = FeatureFlagsAdmin.shutdown
  // --8<-- [end:shutdown]
