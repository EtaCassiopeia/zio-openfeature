package zio.openfeature

import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider, OpenFeatureAPI, ProviderStateAccess}
import zio._
import zio.openfeature.internal.{
  ApiInstances,
  FeatureFlagsFactories,
  InitDeadline,
  LifecycleProbe,
  ManagedProvider,
  ProviderRegistration
}

trait FeatureFlagRegistry {

  /** Get or create a cached client for the given domain. If no provider was explicitly registered for this domain, the
    * default provider is used.
    *
    * Provider initialization happens at most once per domain (concurrent callers share the same build) and does not
    * block clients of other domains. A failed initialization surfaces as
    * `FeatureFlagError.ProviderInitializationFailed` and is not cached — a subsequent call retries. So does a build on
    * a provider whose earlier `initialize()`, from a timed-out build or swap, still runs while no slot holds it: it is
    * refused before it binds, and a call after that `initialize()` returns builds it.
    */
  def getClient(domain: String): IO[FeatureFlagError, FeatureFlags]

  /** Register (or replace) a provider for a domain. If a client already exists for the domain, the provider is
    * hot-swapped via `FeatureFlagsAdmin.setProvider`.
    */
  def setProvider(domain: String, provider: OFFeatureProvider): IO[FeatureFlagError, Unit]

  /** Get or create the default (no-domain) client. Same initialization semantics as [[getClient]]. */
  def defaultClient: IO[FeatureFlagError, FeatureFlags]

  /** Register a ZIO API-level hook on all existing and future domain clients. */
  def addZioApiHook(hook: FeatureHook): UIO[Unit]
}

object FeatureFlagRegistry {

  // How often `awaitReady` re-reads a freshly registered provider's status.
  private[openfeature] val AwaitReadyPollInterval: Duration = 10.millis

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

  def fromProvider(defaultProvider: OFFeatureProvider): ZLayer[Any, Throwable, FeatureFlagRegistry] =
    fromProvider(defaultProvider, FeatureFlagsConfig.default)

  /** A registry whose every client takes `config`: version, initial hooks, evaluation and init timeouts, context
    * source, fallback logging, the abandoned-call cap, and init mode (`Async` returns a client before its provider is
    * ready). `domain` and `apiOwnership` do not apply: the registry names each client's domain and owns its private
    * API.
    */
  def fromProvider(
    defaultProvider: OFFeatureProvider,
    config: FeatureFlagsConfig
  ): ZLayer[Any, Throwable, FeatureFlagRegistry] =
    fromProvider(defaultProvider, config, _ => LifecycleProbe.none)

  /** [[fromProvider(OFFeatureProvider, FeatureFlagsConfig)]] whose client for each domain (`None` for the default
    * client) is built with `probe(domain)`, which also records the registry's own steps for that domain; `probe(None)`
    * records the finalizer's.
    */
  private[openfeature] def fromProvider(
    defaultProvider: OFFeatureProvider,
    config: FeatureFlagsConfig,
    probe: Option[String] => LifecycleProbe,
    initDeadline: InitDeadline = InitDeadline.default
  ): ZLayer[Any, Throwable, FeatureFlagRegistry] =
    ZLayer.scoped {
      for {
        api            <- ZIO.succeed(OpenFeatureAPI.createIsolated())
        scope          <- ZIO.service[Scope]
        clients        <- Ref.make(Map.empty[String, Promise[FeatureFlagError, FeatureFlagsLive]])
        providers      <- Ref.make(Map.empty[String, OFFeatureProvider])
        defaultRef     <- Ref.make(Option.empty[Promise[FeatureFlagError, FeatureFlagsLive]])
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
          zioApiHooksRef,
          config,
          probe,
          initDeadline
        )
        // The registry owns its API: its clients go NotReady with it rather than serve the SDK's No-op answers.
        _ <- ZIO.addFinalizer(
          probe(None).at(LifecycleProbe.Point.ApiShutdown) *> ApiInstances.retireAll(api) *>
            ProviderRegistration.boundedApiShutdown(api) *> ApiInstances.releaseLeftBehind(api)
        )
      } yield registry
    }
}

/** Registry implementation.
  *
  * Concurrency design: the single `lock` only ever guards map/ref bookkeeping — never provider initialization. Each
  * domain's client is memoized as a `Promise`; the first caller inserts the promise and forks its build in one step
  * under the lock that an interrupt cannot split, and the build runs outside the lock, so a slow provider for one
  * domain cannot stall `getClient` for other domains. Concurrent callers for the same domain await the same promise,
  * guaranteeing at most one build per domain. On build failure the promise is failed (typed) and removed, making the
  * failure visible to all waiters while allowing later retries.
  *
  * Hook bookkeeping: completing a build's promise and registering its accumulated API hooks happens in one lock
  * section, and `addZioApiHook` updates the hook list and notifies completed clients in another — so a hook is applied
  * to every client exactly once regardless of interleaving with in-flight builds.
  */
final private class FeatureFlagRegistryLive(
  defaultProvider: OFFeatureProvider,
  api: OpenFeatureAPI,
  scope: Scope,
  clients: Ref[Map[String, Promise[FeatureFlagError, FeatureFlagsLive]]],
  providers: Ref[Map[String, OFFeatureProvider]],
  defaultRef: Ref[Option[Promise[FeatureFlagError, FeatureFlagsLive]]],
  lock: Semaphore,
  zioApiHooksRef: Ref[List[FeatureHook]],
  config: FeatureFlagsConfig,
  probe: Option[String] => LifecycleProbe,
  initDeadline: InitDeadline = InitDeadline.default
) extends FeatureFlagRegistry {

  /** The registry's private API and the domains holding a promise (`None`: the default client), for a model replay. */
  private[openfeature] def replayView: UIO[(OpenFeatureAPI, Set[Option[String]])] =
    clients.get.zipWith(defaultRef.get)((cs, d) => (api, cs.keySet.map(Option(_)) ++ d.map(_ => None).toSet))

  override def getClient(domain: String): IO[FeatureFlagError, FeatureFlags] =
    memoizedClient(
      Some(domain),
      get = clients.get.map(_.get(domain)),
      put = p => clients.update(_ + (domain -> p)),
      remove = clients.update(_ - domain)
    )

  override def defaultClient: IO[FeatureFlagError, FeatureFlags] =
    memoizedClient(
      None,
      get = defaultRef.get,
      put = p => defaultRef.set(Some(p)),
      remove = defaultRef.set(None)
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
        case Some(promise) =>
          promise.await.flatMap(_.setProvider(provider))
        case None => ZIO.unit
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

  /** Get-or-insert the memoized promise under the lock, the first caller forking its build in the same uninterruptible
    * step, then await it.
    */
  private def memoizedClient(
    domain: Option[String],
    get: UIO[Option[Promise[FeatureFlagError, FeatureFlagsLive]]],
    put: Promise[FeatureFlagError, FeatureFlagsLive] => UIO[Unit],
    remove: UIO[Unit]
  ): IO[FeatureFlagError, FeatureFlags] =
    for {
      // The lookup, the insert and the fork of the build that settles the promise are one uninterruptible section, so
      // an interrupted caller never leaves a promise in the map that no build completes. Only the wait for the permit,
      // before the section, and the wait on the promise, after it, can be interrupted.
      promise <- lock.withPermit {
        ZIO.uninterruptible {
          get.flatMap {
            case Some(p) => ZIO.succeed(p)
            case None    =>
              Promise.make[FeatureFlagError, FeatureFlagsLive].tap { p =>
                // The build runs on its own fiber in the registry scope: interrupting the caller that happened to
                // trigger it must not strand the other fibers awaiting the same promise.
                put(p) *> probe(domain).at(LifecycleProbe.Point.RegistryInsert(domain)) *>
                  runBuild(domain, p, remove).forkIn(scope)
              }
          }
        }
      }
      client <- promise.await
    } yield client

  /** Run a build and settle its promise. If the registry scope closes mid-build, the promise is interrupted so awaiting
    * fibers unblock. The build itself is bounded by the init timeout.
    */
  private def runBuild(
    domain: Option[String],
    promise: Promise[FeatureFlagError, FeatureFlagsLive],
    remove: UIO[Unit]
  ): UIO[Unit] =
    // Only the build itself is interruptible: fibers it forks, like buildAsync's init watchdog, must not inherit the
    // uninterruptible section that forked it, or they would block scope close. Settling the promise is not.
    buildClient(domain).interruptible
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
            zioApiHooksRef.get.flatMap(client.addZioApiHooks) *>
              probe(domain).at(LifecycleProbe.Point.RegistryComplete(domain)) *> promise.succeed(client)
          }.unit
      )
      .onInterrupt(promise.interrupt)

  // Registers the provider asynchronously and awaits readiness here. The Java SDK's `setProviderAndWait`
  // holds a STATIC write lock for the whole blocking initialization, serializing provider startup across
  // every domain (and every OpenFeatureAPI instance in the JVM) — the async `setProvider` only holds it
  // for the registration itself, so domains genuinely initialize in parallel.
  //
  // A provider whose last initialization failed is initialized again first: registering it would otherwise reuse the
  // SDK's failed registration and replay its error. Each build then owns a child of the registry scope. A successful
  // client lives until the registry closes; a failed one is released at once, since its event bridge and init watchdog
  // would otherwise outlive it, and the watchdog would later shut down the provider a retry brought up.
  private def buildClient(domain: Option[String]): IO[FeatureFlagError, FeatureFlagsLive] =
    for {
      provider <- domain match {
        case Some(d) => providers.get.map(_.getOrElse(d, defaultProvider))
        case None    => ZIO.succeed(defaultProvider)
      }
      _ <- ProviderRegistration
        .retryFailedInitialization(api, domain, provider, config.initTimeout)
        .mapError(t => FeatureFlagError.ProviderInitializationFailed(t))
      // A retry with another provider replaces the domain's last failed provider in its slot, and only the retry's
      // initialize() holds it then: the SDK shuts it down if that call returns, and the release below runs if the
      // build fails. A registry close interrupts the build before either, so it is left for the close as well: the
      // library's wrapper only, when the slot holds it, as a provider registered outside the library stays the SDK's.
      _ <- ZIO.succeed(Option(lastFailed.get(domain)).foreach { case (previous, _) =>
        if (!(previous eq provider))
          ManagedProvider
            .existing(previous)
            .filter(ProviderStateAccess.boundIn(api, domain.orNull, _))
            .foreach(ApiInstances.leaveBehind(api, _))
      })
      child  <- scope.fork
      client <- child
        .extend[Any](
          FeatureFlagsFactories.buildAsync(
            provider,
            domain = domain,
            version = config.version,
            initialHooks = config.initialHooks,
            statusRef = None,
            addShutdownFinalizer = false,
            apiOverride = Some(api),
            evaluationTimeout = FeatureFlagsFactories.evaluationTimeoutOf(config),
            initTimeout = config.initTimeout,
            contextSource = config.contextSource,
            fallbackLogging = config.fallbackLogging,
            maxAbandonedCalls = config.maxAbandonedCalls,
            probe = probe(domain),
            initDeadline = initDeadline
          )
        )
        .mapError(t => FeatureFlagError.ProviderInitializationFailed(t))
        // An async registry hands the client out at once; it reports its own status while the provider starts.
        .tap(client =>
          ZIO
            .unless(config.initMode == InitMode.Async)(awaitReady(client))
            .tapError(_ =>
              releaseReplacedFailure(domain, provider) *> ZIO.succeed(lastFailed.put(domain, (provider, client)))
            )
        )
        .onError(cause => child.close(Exit.failCause(cause)))
      _ <- ZIO.succeed(lastFailed.remove(domain))
    } yield client

  // The provider and client of each domain's last failed build. A failed provider is left running, so that a later
  // `getClient` can initialize it again: a provider that owns its client cannot come back from a shutdown. The SDK
  // shuts it down once another provider in that slot initializes; when that one fails too, `releaseReplacedFailure`
  // does, or the registry's close when it interrupts that build first.
  private val lastFailed =
    new java.util.concurrent.ConcurrentHashMap[Option[String], (OFFeatureProvider, FeatureFlagsLive)]()

  /** After a build of `provider` failed: release the domain's earlier failed provider, if `provider` replaced it. The
    * failed client decides, as a failed build does, so a provider registered outside the registry, or one another
    * instance serves, is left alone.
    */
  private def releaseReplacedFailure(domain: Option[String], provider: OFFeatureProvider): UIO[Unit] =
    Option(lastFailed.get(domain)) match {
      case Some((previous, client)) if !(previous eq provider) =>
        client
          .shutDownFailedBuild(
            "registry build (failed provider replaced by one that failed too)",
            ProviderRegistration.failedProviderShutdownTimeout(config.initTimeout)
          )
          .unit
      case _ => ZIO.unit
    }

  /** Wait for the freshly registered provider to become usable, failing typed on error states or timeout.
    *
    * Polls on the live clock rather than the environment's: registry construction must stay bounded inside zio-test
    * suites whose TestClock is frozen. The loop is bounded by the init timeout.
    */
  private def awaitReady(client: FeatureFlags): IO[FeatureFlagError, Unit] = {
    val pollMillis = FeatureFlagRegistry.AwaitReadyPollInterval.toMillis
    val maxPolls   = math.max(1L, config.initTimeout.toMillis / pollMillis)

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
        case Some(_)                => ZIO.unit
        case None if remaining <= 0 =>
          ZIO.fail(
            FeatureFlagError.ProviderInitializationFailed(
              new java.util.concurrent.TimeoutException(
                s"Provider initialization exceeded ${config.initTimeout}"
              )
            )
          )
        case None =>
          ZIO.withClock(Clock.ClockLive)(ZIO.sleep(FeatureFlagRegistry.AwaitReadyPollInterval)) *> loop(remaining - 1)
      }

    loop(maxPolls)
  }
}
