package zio.openfeature

import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider, OpenFeatureAPI, OpenFeatureAPIFactory}
import zio._

trait FeatureFlagRegistry {

  /** Get or create a cached client for the given domain. If no provider was explicitly registered for this domain, the
    * default provider is used.
    */
  def getClient(domain: String): UIO[FeatureFlags]

  /** Register (or replace) a provider for a domain. If a client already exists for the domain, the provider is
    * hot-swapped via `FeatureFlags.setProvider`.
    */
  def setProvider(domain: String, provider: OFFeatureProvider): IO[FeatureFlagError, Unit]

  /** Get or create the default (no-domain) client. */
  def defaultClient: UIO[FeatureFlags]

  /** Register a ZIO API-level hook on all existing and future domain clients. */
  def addZioApiHook(hook: FeatureHook): UIO[Unit]
}

object FeatureFlagRegistry {

  def getClient(domain: String): ZIO[FeatureFlagRegistry, Nothing, FeatureFlags] =
    ZIO.serviceWithZIO(_.getClient(domain))

  def setProvider(
    domain: String,
    provider: OFFeatureProvider
  ): ZIO[FeatureFlagRegistry, FeatureFlagError, Unit] =
    ZIO.serviceWithZIO(_.setProvider(domain, provider))

  def defaultClient: ZIO[FeatureFlagRegistry, Nothing, FeatureFlags] =
    ZIO.serviceWithZIO(_.defaultClient)

  def addZioApiHook(hook: FeatureHook): ZIO[FeatureFlagRegistry, Nothing, Unit] =
    ZIO.serviceWithZIO(_.addZioApiHook(hook))

  def fromProvider(defaultProvider: OFFeatureProvider): ZLayer[Scope, Throwable, FeatureFlagRegistry] =
    ZLayer.scoped {
      for {
        api            <- ZIO.succeed(OpenFeatureAPIFactory.create())
        scope          <- ZIO.service[Scope]
        clients        <- Ref.make(Map.empty[String, FeatureFlags])
        providers      <- Ref.make(Map.empty[String, OFFeatureProvider])
        defaultRef     <- Ref.make(Option.empty[FeatureFlags])
        lock           <- Semaphore.make(1)
        zioApiHooksRef <- Ref.make(List.empty[FeatureHook])
        registry = new FeatureFlagRegistryLive(
          defaultProvider,
          api,
          scope,
          clients,
          providers,
          defaultRef,
          lock,
          zioApiHooksRef
        )
        _ <- ZIO.addFinalizer(ZIO.attemptBlocking(api.shutdown()).ignore)
      } yield registry
    }
}

final private class FeatureFlagRegistryLive(
  defaultProvider: OFFeatureProvider,
  api: OpenFeatureAPI,
  scope: Scope,
  clients: Ref[Map[String, FeatureFlags]],
  providers: Ref[Map[String, OFFeatureProvider]],
  defaultRef: Ref[Option[FeatureFlags]],
  lock: Semaphore,
  zioApiHooksRef: Ref[List[FeatureHook]]
) extends FeatureFlagRegistry {

  override def getClient(domain: String): UIO[FeatureFlags] =
    lock.withPermit {
      clients.get.flatMap(_.get(domain) match {
        case Some(c) => Exit.succeed(c)
        case None    => createDomainClient(domain)
      })
    }

  override def setProvider(domain: String, provider: OFFeatureProvider): IO[FeatureFlagError, Unit] =
    lock.withPermit {
      clients.get.flatMap(_.get(domain) match {
        case Some(client) =>
          client
            .setProvider(provider)
            .tap(_ => providers.update(_ + (domain -> provider)))
        case None =>
          providers.update(_ + (domain -> provider))
      })
    }

  override def defaultClient: UIO[FeatureFlags] =
    lock.withPermit {
      defaultRef.get.flatMap {
        case Some(c) => Exit.succeed(c)
        case None    => createDefaultClient
      }
    }

  override def addZioApiHook(hook: FeatureHook): UIO[Unit] =
    lock.withPermit {
      for {
        _               <- zioApiHooksRef.update(_ :+ hook)
        existingClients <- clients.get.map(_.values.toList)
        defaultC        <- defaultRef.get
        _               <- ZIO.foreachDiscard(existingClients ++ defaultC.toList)(_.addZioApiHook(hook))
      } yield ()
    }

  private def createDomainClient(domain: String): UIO[FeatureFlags] =
    for {
      pm <- providers.get
      provider = pm.getOrElse(domain, defaultProvider)
      client <- scope
        .extend[Any](
          FeatureFlags.build(
            provider,
            domain = Some(domain),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = false,
            apiOverride = Some(api)
          )
        )
        .orDie
      apiHooks <- zioApiHooksRef.get
      _        <- client.addZioApiHooks(apiHooks)
      _        <- clients.update(_ + (domain -> client))
    } yield client

  private def createDefaultClient: UIO[FeatureFlags] =
    for {
      client <- scope
        .extend[Any](
          FeatureFlags.build(
            defaultProvider,
            domain = None,
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = false,
            apiOverride = Some(api)
          )
        )
        .orDie
      apiHooks <- zioApiHooksRef.get
      _        <- client.addZioApiHooks(apiHooks)
      _        <- defaultRef.set(Some(client))
    } yield client
}
