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

  def fromProvider(defaultProvider: OFFeatureProvider): ZLayer[Scope, Throwable, FeatureFlagRegistry] =
    ZLayer.scoped {
      for {
        api   <- ZIO.succeed(OpenFeatureAPIFactory.create())
        _     <- ZIO.attemptBlocking(api.setProviderAndWait(defaultProvider))
        scope <- ZIO.service[Scope]
        registry <- ZIO.succeed(
          new FeatureFlagRegistryLive(
            defaultProvider = defaultProvider,
            api = api,
            scope = scope
          )
        )
        _ <- ZIO.addFinalizer(ZIO.attemptBlocking(api.shutdown()).ignore)
      } yield registry
    }
}

final private class FeatureFlagRegistryLive(
  defaultProvider: OFFeatureProvider,
  api: OpenFeatureAPI,
  scope: Scope
) extends FeatureFlagRegistry {

  private val clients: Ref[Map[String, FeatureFlags]]        = Unsafe.unsafe(implicit u => Ref.unsafe.make(Map.empty))
  private val providers: Ref[Map[String, OFFeatureProvider]] = Unsafe.unsafe(implicit u => Ref.unsafe.make(Map.empty))
  private val defaultRef: Ref[Option[FeatureFlags]]          = Unsafe.unsafe(implicit u => Ref.unsafe.make(None))
  private val lock: Semaphore                                = Unsafe.unsafe(implicit u => Semaphore.unsafe.make(1))

  override def getClient(domain: String): UIO[FeatureFlags] =
    lock.withPermit {
      clients.get.map(_.get(domain)).flatMap {
        case Some(c) => ZIO.succeed(c)
        case None    => createDomainClient(domain)
      }
    }

  override def setProvider(domain: String, provider: OFFeatureProvider): IO[FeatureFlagError, Unit] =
    lock.withPermit {
      for {
        _        <- providers.update(_ + (domain -> provider))
        existing <- clients.get.map(_.get(domain))
        _ <- existing match {
          case Some(client) => client.setProvider(provider)
          case None         => ZIO.unit
        }
      } yield ()
    }

  override def defaultClient: UIO[FeatureFlags] =
    lock.withPermit {
      defaultRef.get.flatMap {
        case Some(c) => ZIO.succeed(c)
        case None    => createDefaultClient
      }
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
      _ <- clients.update(_ + (domain -> client))
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
      _ <- defaultRef.set(Some(client))
    } yield client
}
