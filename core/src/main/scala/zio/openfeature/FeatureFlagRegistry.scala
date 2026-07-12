package zio.openfeature

import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider, OpenFeatureAPI}
import zio._

trait FeatureFlagRegistry {

  /** Get or create a cached client for the given domain. If no provider was explicitly registered for this domain, the
    * default provider is used.
    *
    * Provider initialization happens at most once per domain (concurrent callers share the same build) and does not
    * block clients of other domains. A failed initialization surfaces as
    * `FeatureFlagError.ProviderInitializationFailed` and is not cached — a subsequent call retries.
    */
  def getClient(domain: String): IO[FeatureFlagError, FeatureFlags]

  /** Register (or replace) a provider for a domain. If a client already exists for the domain, the provider is
    * hot-swapped via `FeatureFlags.setProvider`.
    */
  def setProvider(domain: String, provider: OFFeatureProvider): IO[FeatureFlagError, Unit]

  /** Get or create the default (no-domain) client. Same initialization semantics as [[getClient]]. */
  def defaultClient: IO[FeatureFlagError, FeatureFlags]

  /** Register a ZIO API-level hook on all existing and future domain clients. */
  def addZioApiHook(hook: FeatureHook): UIO[Unit]
}

object FeatureFlagRegistry {

  def getClient(domain: String): ZIO[FeatureFlagRegistry, FeatureFlagError, FeatureFlags] =
    ZIO.serviceWithZIO(_.getClient(domain))

  def setProvider(
    domain: String,
    provider: OFFeatureProvider
  ): ZIO[FeatureFlagRegistry, FeatureFlagError, Unit] =
    ZIO.serviceWithZIO(_.setProvider(domain, provider))

  def defaultClient: ZIO[FeatureFlagRegistry, FeatureFlagError, FeatureFlags] =
    ZIO.serviceWithZIO(_.defaultClient)

  def addZioApiHook(hook: FeatureHook): ZIO[FeatureFlagRegistry, Nothing, Unit] =
    ZIO.serviceWithZIO(_.addZioApiHook(hook))

  def fromProvider(defaultProvider: OFFeatureProvider): ZLayer[Scope, Throwable, FeatureFlagRegistry] =
    ZLayer.scoped {
      for {
        api            <- ZIO.succeed(OpenFeatureAPI.createIsolated())
        scope          <- ZIO.service[Scope]
        clients        <- Ref.make(Map.empty[String, Promise[FeatureFlagError, FeatureFlags]])
        providers      <- Ref.make(Map.empty[String, OFFeatureProvider])
        defaultRef     <- Ref.make(Option.empty[Promise[FeatureFlagError, FeatureFlags]])
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

/** Registry implementation.
  *
  * Concurrency design: the single `lock` only ever guards map/ref bookkeeping — never provider initialization. Each
  * domain's client is memoized as a `Promise`; the first caller inserts the promise (under the lock) and runs the build
  * outside the lock, so a slow provider for one domain cannot stall `getClient` for other domains. Concurrent callers
  * for the same domain await the same promise, guaranteeing at most one build per domain. On build failure the promise
  * is failed (typed) and removed, making the failure visible to all waiters while allowing later retries.
  *
  * Hook bookkeeping: completing a build's promise and registering its accumulated API hooks happens in one lock
  * section, and `addZioApiHook` updates the hook list and notifies completed clients in another — so a hook is applied
  * to every client exactly once regardless of interleaving with in-flight builds.
  */
final private class FeatureFlagRegistryLive(
  defaultProvider: OFFeatureProvider,
  api: OpenFeatureAPI,
  scope: Scope,
  clients: Ref[Map[String, Promise[FeatureFlagError, FeatureFlags]]],
  providers: Ref[Map[String, OFFeatureProvider]],
  defaultRef: Ref[Option[Promise[FeatureFlagError, FeatureFlags]]],
  lock: Semaphore,
  zioApiHooksRef: Ref[List[FeatureHook]]
) extends FeatureFlagRegistry {

  override def getClient(domain: String): IO[FeatureFlagError, FeatureFlags] =
    memoizedClient(
      get = clients.get.map(_.get(domain)),
      put = p => clients.update(_ + (domain -> p)),
      remove = clients.update(_ - domain),
      buildClient = buildClient(Some(domain))
    )

  override def defaultClient: IO[FeatureFlagError, FeatureFlags] =
    memoizedClient(
      get = defaultRef.get,
      put = p => defaultRef.set(Some(p)),
      remove = defaultRef.set(None),
      buildClient = buildClient(None)
    )

  override def setProvider(domain: String, provider: OFFeatureProvider): IO[FeatureFlagError, Unit] =
    for {
      existing <- lock.withPermit {
        providers.update(_ + (domain -> provider)) *> clients.get.map(_.get(domain))
      }
      // Hot-swap outside the lock: setProviderAndWait can take up to the init timeout and must not
      // block unrelated registry calls. If a build for this domain is in flight, wait for it and swap
      // the resulting client (the build may already have picked up the new provider; swapping to the
      // same provider is harmless).
      _ <- existing match {
        case Some(promise) => promise.await.flatMap(_.setProvider(provider))
        case None          => ZIO.unit
      }
    } yield ()

  override def addZioApiHook(hook: FeatureHook): UIO[Unit] =
    lock.withPermit {
      for {
        _        <- zioApiHooksRef.update(_ :+ hook)
        domainPs <- clients.get.map(_.values.toList)
        defaultP <- defaultRef.get
        // Only clients whose build already completed (successfully) get the hook here; in-flight builds
        // read the updated hook list when they complete (both happen under this lock).
        _ <- ZIO.foreachDiscard(domainPs ++ defaultP.toList) { p =>
          p.poll.flatMap {
            case Some(io) => io.foldZIO(_ => ZIO.unit, _.addZioApiHook(hook))
            case None     => ZIO.unit
          }
        }
      } yield ()
    }

  /** Get-or-insert the memoized promise under the lock, build outside it (first caller only), await. */
  private def memoizedClient(
    get: UIO[Option[Promise[FeatureFlagError, FeatureFlags]]],
    put: Promise[FeatureFlagError, FeatureFlags] => UIO[Unit],
    remove: UIO[Unit],
    buildClient: IO[FeatureFlagError, FeatureFlags]
  ): IO[FeatureFlagError, FeatureFlags] =
    for {
      inserted <- lock.withPermit {
        get.flatMap {
          case Some(p) => ZIO.succeed((p, false))
          case None    => Promise.make[FeatureFlagError, FeatureFlags].flatMap(p => put(p).as((p, true)))
        }
      }
      promise = inserted._1
      isOwner = inserted._2
      // The build runs on its own fiber in the registry scope: interrupting the caller that happened to
      // trigger the build must not strand the other fibers awaiting the same promise. (Making the build
      // uninterruptible on the caller fiber is not an option — fibers forked inside it, like buildAsync's
      // init watchdog, would inherit uninterruptibility and block scope close.)
      _      <- ZIO.when(isOwner)(runBuild(buildClient, promise, remove).forkIn(scope))
      client <- promise.await
    } yield client

  /** Run a build and settle its promise. If the registry scope closes mid-build, the promise is interrupted so awaiting
    * fibers unblock. The build itself is bounded by the init timeout.
    */
  private def runBuild(
    buildClient: IO[FeatureFlagError, FeatureFlags],
    promise: Promise[FeatureFlagError, FeatureFlags],
    remove: UIO[Unit]
  ): UIO[Unit] =
    buildClient
      // `foldCauseZIO`, not `foldZIO`: a *defect* (a throwing SDK call that escaped the typed channel) is not a
      // typed failure, so `foldZIO` would let it kill this fiber with the promise never settled and the entry never
      // removed — every current and future `getClient` for this domain would then block on `promise.await` forever.
      // Handling the full cause guarantees the promise is always completed and the entry evicted so callers retry.
      .foldCauseZIO(
        cause =>
          cause.failureOrCause match {
            // Typed failure (e.g. ProviderInitializationFailed): settle and evict so callers retry.
            case Left(err) => lock.withPermit(remove *> promise.fail(err)).unit
            // Interruption only (registry scope closing mid-build): unblock awaiters; teardown handles eviction.
            case Right(c) if c.isInterruptedOnly => promise.interrupt.unit
            // Defect: convert to a typed error, settle, and evict — never leave the promise uncompleted.
            case Right(c) =>
              val defect = c.dieOption.getOrElse(new RuntimeException(c.prettyPrint))
              lock.withPermit(remove *> promise.fail(FeatureFlagError.ProviderInitializationFailed(defect))).unit
          },
        client =>
          lock.withPermit {
            zioApiHooksRef.get.flatMap(client.addZioApiHooks) *> promise.succeed(client)
          }.unit
      )
      .onInterrupt(promise.interrupt)

  // Registers the provider asynchronously and awaits readiness here. The Java SDK's `setProviderAndWait`
  // holds a STATIC write lock for the whole blocking initialization, serializing provider startup across
  // every domain (and every OpenFeatureAPI instance in the JVM) — the async `setProvider` only holds it
  // for the registration itself, so domains genuinely initialize in parallel.
  private def buildClient(domain: Option[String]): IO[FeatureFlagError, FeatureFlags] =
    for {
      provider <- domain match {
        case Some(d) => providers.get.map(_.getOrElse(d, defaultProvider))
        case None    => ZIO.succeed(defaultProvider)
      }
      client <- scope
        .extend[Any](
          FeatureFlags.buildAsync(
            provider,
            domain = domain,
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = false,
            apiOverride = Some(api)
          )
        )
        .mapError(t => FeatureFlagError.ProviderInitializationFailed(t))
      _ <- awaitReady(client)
    } yield client

  /** Wait for the freshly registered provider to become usable, failing typed on error states or timeout.
    *
    * Deliberately polls with blocking-pool sleeps instead of `ZIO.sleep`/`Schedule`: registry construction must work
    * inside zio-test suites where the TestClock is frozen, exactly like the previous blocking `setProviderAndWait` did.
    * The loop is bounded by the default init timeout.
    */
  private def awaitReady(client: FeatureFlags): IO[FeatureFlagError, Unit] = {
    val pollMillis = 10L
    val maxPolls   = math.max(1L, FeatureFlags.DefaultInitTimeout.toMillis / pollMillis)

    val check: IO[FeatureFlagError, Option[Unit]] = client.providerStatus.flatMap {
      case ProviderStatus.Ready | ProviderStatus.Stale => ZIO.some(())
      case ProviderStatus.Fatal | ProviderStatus.Error =>
        ZIO.fail(
          FeatureFlagError.ProviderInitializationFailed(
            new IllegalStateException("Provider entered an error state during initialization")
          )
        )
      case _ => ZIO.none
    }

    def loop(remaining: Long): IO[FeatureFlagError, Unit] =
      check.flatMap {
        case Some(_) => ZIO.unit
        case None if remaining <= 0 =>
          ZIO.fail(
            FeatureFlagError.ProviderInitializationFailed(
              new java.util.concurrent.TimeoutException(
                s"Provider initialization exceeded ${FeatureFlags.DefaultInitTimeout}"
              )
            )
          )
        case None =>
          ZIO.attemptBlocking(Thread.sleep(pollMillis)).orDie *> loop(remaining - 1)
      }

    loop(maxPolls)
  }
}
