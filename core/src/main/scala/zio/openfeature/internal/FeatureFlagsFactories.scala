package zio.openfeature.internal

import zio._
import zio.stream.SubscriptionRef
import zio.openfeature._
import zio.openfeature.FeatureFlags.{DefaultEvaluationTimeout, DefaultInitTimeout, DefaultMaxAbandonedCalls}
import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider, NoOpProvider, OpenFeatureAPI, ProviderState}
import dev.openfeature.sdk.exceptions.{FatalError, GeneralError}
import java.util.concurrent.TimeoutException

/** The bodies of the [[FeatureFlags]] factories. `object FeatureFlags` keeps every signature, its ScalaDoc and its
  * default arguments, and forwards here.
  */
private[openfeature] object FeatureFlagsFactories {

  /** Verify the provider reached a usable state after sync initialization. Anything outside `READY` / `STALE` causes
    * the layer build to fail so misconfiguration surfaces at startup, not at first evaluation. Returns the ZIO-level
    * `ProviderStatus` that should be reflected for a usable state.
    */
  /** The state a provider reports through its own (deprecated) `getState`: what a provider whose `initialize()` this
    * library ran itself, outside the SDK, is checked by, and what the build also refuses to serve when it is not
    * `READY` or `STALE`.
    */
  @scala.annotation.nowarn("msg=deprecated")
  private def verifyProviderState(provider: OFFeatureProvider): ZIO[Any, Throwable, ProviderStatus] =
    ZIO.attempt(provider.getState).flatMap {
      case ProviderState.READY => ZIO.succeed(ProviderStatus.Ready)
      case ProviderState.STALE => ZIO.succeed(ProviderStatus.Stale)
      case bad @ (ProviderState.ERROR | ProviderState.FATAL | ProviderState.NOT_READY) =>
        ZIO.fail(new IllegalStateException(s"Provider in $bad state after initialization"))
    }

  /** The build serves only a provider that settled `READY` or `STALE`. One that initialized and then reported an error
    * fails the build with that error's message, as one whose `initialize()` threw does: the build has nothing to fall
    * back to, and that path shuts down a registration of the library's own.
    */
  private def verifyInitState(settled: ProviderRegistration.Settled): IO[Throwable, ProviderStatus] = settled match {
    case ProviderRegistration.Settled.Ready              => ZIO.succeed(ProviderStatus.Ready)
    case ProviderRegistration.Settled.Stale              => ZIO.succeed(ProviderStatus.Stale)
    case ProviderRegistration.Settled.Error(msg, reused) =>
      ZIO.fail(new GeneralError(failedMessage(msg, reused, "ERROR")))
    case ProviderRegistration.Settled.Fatal(msg, reused) =>
      ZIO.fail(new FatalError(failedMessage(msg, reused, "FATAL")))
  }

  private def failedMessage(msg: String, reused: Boolean, state: String): String =
    if (reused) s"$msg; it was left running for the domain it serves"
    else if (msg.isEmpty) s"Provider entered $state during initialization"
    else msg

  private def isReused(settled: ProviderRegistration.Settled): Boolean = settled match {
    case ProviderRegistration.Settled.Error(_, reused) => reused
    case ProviderRegistration.Settled.Fatal(_, reused) => reused
    case _                                             => false
  }

  /** The instance both builds return, constructed before its provider is registered so its event bridge can be attached
    * first. The SDK client does not need a registered provider: it resolves the slot's provider per call.
    */
  /** The scope finalizer of every instance. It takes the swap lock, as `shutdown` does, so a swap in flight (and any
    * queued ahead of it) finishes first and a timed-out one records its orphan before `resolveAll`; it marks the
    * instance closed, so a later `setProvider` is refused. An instance that owns its API then tears it down, which
    * settles or interrupts a timed-out swap's pending initialization, and resolves the orphans; one that shares its API
    * leaves the API, and every provider on it, to the API's owner.
    */
  private def scopeFinalizer(
    ownsApi: Boolean,
    api: OpenFeatureAPI,
    orphans: OrphanedProviders,
    probe: LifecycleProbe,
    swapLock: Semaphore,
    self: java.util.concurrent.atomic.AtomicReference[FeatureFlagsLive]
  ): UIO[Unit] =
    // An owner retires the other instances on the API first, as `shutdown` does, and before this one's lock.
    ZIO.when(ownsApi)(ApiInstances.retireSiblings(api, self.get())) *>
      swapLock.withPermit(
        ZIO.suspendSucceed(ZIO.foreachDiscard(Option(self.get()))(_.markClosed)) *>
          probe.at(LifecycleProbe.Point.ScopeClose) *>
          ZIO.when(ownsApi)(ProviderRegistration.boundedApiShutdown(api) *> orphans.resolveAll).unit
      )

  /** Warn when the slot this build binds already holds another provider: the SDK shuts that one down once the new one
    * is ready, under whichever instance was using it. Never fails the build.
    */
  private def warnIfSlotBound(api: OpenFeatureAPI, domain: Option[String], provider: OFFeatureProvider): UIO[Unit] =
    ZIO
      .attempt {
        val default = api.getProvider()
        // The SDK answers an unbound domain with the default slot's provider.
        val bound = domain.fold(default)(d => api.getProvider(d)) match {
          case p if domain.isDefined && (p eq default) => null
          case p                                       => p
        }
        Option(bound).filter(b => !(b eq provider) && !b.isInstanceOf[NoOpProvider]).map { b =>
          val slot = domain.fold("the default slot")(d => s"domain '$d'")
          s"$slot of this OpenFeatureAPI already holds provider '${ProviderNames.of(b)}'; binding " +
            s"'${ProviderNames.of(provider)}' makes the SDK " +
            "shut it down once the new one is ready, under whatever was using it. Use FeatureFlagsConfig.withIsolatedApi, " +
            "a domain, or FeatureFlagRegistry to keep instances apart."
        }
      }
      .flatMap(ZIO.foreachDiscard(_)(ZIO.logWarning(_)))
      .catchAll(e => ZIO.logDebug(s"Could not read the slot's provider before binding: ${e.getMessage}"))

  private def makeInstance(
    provider: OFFeatureProvider,
    api: OpenFeatureAPI,
    domain: Option[String],
    version: Option[String],
    initialHooks: List[FeatureHook],
    statusRef: Option[SubscriptionRef[ProviderStatus]],
    ownsApi: Boolean,
    onReady: Option[java.util.concurrent.CountDownLatch],
    evaluationTimeout: Option[Duration],
    contextSource: ContextSource,
    fallbackLogging: FallbackLogging,
    initTimeout: Duration,
    maxAbandonedCalls: Int,
    unreadableNameIsUnknown: Boolean,
    orphans: OrphanedProviders,
    probe: LifecycleProbe,
    swapLock: Semaphore,
    evaluationProbe: EvaluationProbe = EvaluationProbe.none
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    for {
      client <- ZIO.attempt(api.getClient(domain.orNull, version.orNull))
      // Read before `initialize()`, where a name may not exist yet: a MultiProvider has no metadata until it has
      // initialized, and a wrapper around one throws reading it. `ZIO.attempt` makes a throw a typed failure, never a
      // defect that would strand the registry's build fiber with its promise unsettled. The sync build tolerates it
      // (`unreadableNameIsUnknown`) and reads the name again, strictly, once the provider has initialized.
      read = ZIO.attempt(ProviderNames.beforeInit(provider))
      providerName <- if (unreadableNameIsUnknown) read.orElseSucceed(ProviderName.Pending) else read
      baseState    <- FeatureFlagsState.make
      state = statusRef.fold(baseState)(ref => baseState.copy(statusRef = ref))
      _                  <- state.hooksRef.set(initialHooks)
      fallbackLogLimiter <- FallbackLogLimiter.make(fallbackLogging)
      ff = new FeatureFlagsLive(
        client,
        provider,
        new java.util.concurrent.atomic.AtomicReference(providerName),
        domain,
        version,
        state,
        api,
        // Owns the api iff we also register the api-shutdown scope finalizer — keeps explicit `shutdown` and scope-close
        // teardown consistent, so a shared-api (registry/domain) client never shuts the whole api.
        ownsApi = ownsApi,
        swapLock,
        fallbackLogLimiter,
        onReady,
        evaluationTimeout,
        contextSource,
        initTimeout,
        maxAbandonedCalls,
        orphans = orphans,
        probe = probe,
        evaluationProbe = evaluationProbe
      )
      _ <- Quiescence.register(ff.endState)
    } yield ff

  /** Whether the event bridge can be attached before `provider` is registered.
    *
    * The SDK reaches a client handler with a provider's PROVIDER_READY two ways: the emission after `initialize()`
    * returns, and a replay when the handler is added while the slot's provider is already READY (`addHandler`). The
    * replay and the emission both run on a cached thread pool, so a bridge attached after initialization got one or two
    * READYs in any order — and a second one, applied as recovery, overwrote a status set in between. Attached before
    * registration, the bridge gets exactly one: the post-init emission, or, for a provider already initialized in this
    * slot (a registry client reusing the default provider, where no `initialize()` runs), the replay.
    *
    * That holds while the slot is unserved (`NOT_READY`) or served by `provider` itself. Before registration, an
    * unbound domain is served by the default provider; if that is a different provider in a READY/STALE/ERROR state,
    * attaching early would replay that provider's state as ours. Then the bridge is attached right after the slot is
    * bound to `provider` instead, where nothing can be replayed unless `provider` has already finished initializing.
    */
  private def attachesBeforeRegistration(
    api: OpenFeatureAPI,
    domain: Option[String],
    provider: OFFeatureProvider
  ): Task[Boolean] =
    ZIO.attempt {
      val serving = domain.fold(api.getProvider())(d => api.getProvider(d))
      // A client reads the state of whatever provider currently serves its slot.
      val state = domain.fold(api.getClient())(d => api.getClient(d)).getProviderState
      // `==` (equals), as the SDK matches a provider to an existing registration.
      serving == provider || state == ProviderState.NOT_READY
    }

  /** Record the name of a provider after a sync init: it may only exist once `initialize()` has run, and a provider
    * that has none by now has none. Only a `Pending` name is replaced.
    */
  private def refreshProviderName(provider: OFFeatureProvider, ff: FeatureFlagsLive): Task[Unit] =
    ZIO.attempt(ProviderNames.afterInit(provider)).flatMap(ff.recordProviderName(provider, _))

  /** Shared initialization logic for all factory methods. */
  def build(
    provider: OFFeatureProvider,
    domain: Option[String],
    version: Option[String],
    initialHooks: List[FeatureHook],
    statusRef: Option[SubscriptionRef[ProviderStatus]],
    addShutdownFinalizer: Boolean,
    apiOverride: Option[OpenFeatureAPI] = None,
    evaluationTimeout: Option[Duration] = Some(DefaultEvaluationTimeout),
    initTimeout: Duration = DefaultInitTimeout,
    onReady: Option[java.util.concurrent.CountDownLatch] = None,
    contextSource: ContextSource = ContextSource.empty,
    fallbackLogging: FallbackLogging = FallbackLogging.Default,
    maxAbandonedCalls: Int = DefaultMaxAbandonedCalls,
    probe: LifecycleProbe = LifecycleProbe.none,
    evaluationProbe: EvaluationProbe = EvaluationProbe.none
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    for {
      api <- ZIO.succeed(apiOverride.getOrElse(OpenFeatureAPI.getInstance()))
      // The SDK registers the library's wrapper, which observes the provider's initialization, shutdown and events; or
      // the provider itself, when this API already holds it.
      bound <- ZIO.attempt(Bound.of(api, domain, provider, probe))
      managed = bound.sdk
      // Register the API shutdown finalizer BEFORE initiating provider registration. If init times out,
      // the provider reports a bad state, or getClient fails, the scope close still tears down whatever
      // the registration managed to bind.
      orphans = OrphanedProviders(api, probe)
      // Created before the finalizer, which takes the lock and sets the flag (see `scopeFinalizer`).
      swapLock <- Semaphore.make(1)
      self = new java.util.concurrent.atomic.AtomicReference[FeatureFlagsLive](null)
      // Every build, owning its API or not: the close marks the instance closed (see `scopeFinalizer`).
      _  <- ZIO.addFinalizer(scopeFinalizer(addShutdownFinalizer, api, orphans, probe, swapLock, self))
      ff <- makeInstance(
        managed,
        api,
        domain,
        version,
        initialHooks,
        statusRef,
        addShutdownFinalizer,
        onReady,
        evaluationTimeout,
        contextSource,
        fallbackLogging,
        initTimeout,
        maxAbandonedCalls,
        unreadableNameIsUnknown = true,
        orphans,
        probe,
        swapLock,
        evaluationProbe
      )
      // Known to the instance that later tears this API down, which then retires this one; and the slot checked
      // before it is bound.
      _ <- ZIO.succeed { self.set(ff); ApiInstances.register(api, ff, domain) }
      _ <- warnIfSlotBound(api, domain, managed)
      // The bridge is attached before the provider initializes, so exactly one PROVIDER_READY reaches it. See
      // `attachesBeforeRegistration`.
      early <- attachesBeforeRegistration(api, domain, managed)
      _     <- ZIO.when(early)(ff.startEventBridge)
      // Non-blocking registration plus a bounded wait: `setProviderAndWait` would hold the SDK's API write
      // lock across `initialize()`, so after a timeout the finalizer's `api.shutdown()` would block on that lock for
      // as long as `initialize()` hangs. On any init failure a registration of the library's own is shut down
      // (best-effort, bounded, since a provider stuck in `initialize()` often cannot shut down either) so it doesn't
      // keep threads/connections alive — this also covers the domain/registry paths where no API finalizer is
      // registered, and `shutDownFailedRegistration` decides what to leave alone — and the bridge's
      // handlers are removed so a failed build leaves none behind. The status records the failure first: the wait ends
      // when `initialize()` throws, before the provider's own ERROR, or the SDK's, reaches the bridge.
      failedBuild = (failure: Throwable) =>
        ff.markInitFailed(fatal = failure.isInstanceOf[FatalError]) *> ff.stopEventBridge *>
          ff.shutDownFailedRegistration(
            bound,
            "build (failed initialization)",
            ProviderRegistration.failedProviderShutdownTimeout(initTimeout)
          )
      settled <- ProviderRegistration
        .registerThenAwait(api, domain, bound, initTimeout)(
          ZIO.unless(early)(ff.startEventBridge).unit
        )
        .tapError(failedBuild)
      // A provider reused from another domain of this API is serving there: the build fails without shutting it down.
      verified <- ((verifyInitState(settled) <* verifyProviderState(managed)) <*
        // A provider whose metadata name only exists once it has initialized (the SDK's MultiProvider) is still
        // `Pending`; now that it has, its name is final.
        refreshProviderName(managed, ff))
        .tapError(e => if (isReused(settled)) ff.stopEventBridge else failedBuild(e))
      // Only seed status when the caller didn't hand us a shared ref (testkit shares one). Routed through
      // `seedStatus` so the machine's `everReady` flag records a Ready/Stale seed.
      _ <- statusRef.fold(ff.seedStatus(verified))(_ => ZIO.unit)
    } yield ff

  // How a `FeatureFlagsConfig` reads on every config-driven path (`fromProvider`, `fromAcquireAsync`), in one place.

  private[openfeature] def evaluationTimeoutOf(config: FeatureFlagsConfig): Option[Duration] =
    config.evaluationTimeout match {
      case EvaluationTimeout.Default  => Some(DefaultEvaluationTimeout)
      case EvaluationTimeout.Disabled => None
      case EvaluationTimeout.After(d) => Some(d)
    }

  private def ownsApiOf(config: FeatureFlagsConfig): Boolean = config.apiOwnership match {
    case ApiOwnership.Auto     => config.domain.isEmpty
    case ApiOwnership.Owned    => true
    case ApiOwnership.Shared   => false
    case ApiOwnership.Isolated => true
  }

  // An isolated instance gets a private API of its own, unless the package-private override names one.
  private def apiOverrideOf(config: FeatureFlagsConfig, apiOverride: Option[OpenFeatureAPI]): Option[OpenFeatureAPI] =
    config.apiOwnership match {
      case ApiOwnership.Isolated => apiOverride.orElse(Some(OpenFeatureAPI.createIsolated()))
      case _                     => apiOverride
    }

  // One instance as both services: `scopedEnvironment` rather than `scoped`, since a layer emits two services only through
  // an explicit ZEnvironment.
  private def bothServices(
    instance: ZIO[Scope, Throwable, FeatureFlagsLive]
  ): ZLayer[Any, Throwable, FeatureFlags with FeatureFlagsAdmin] =
    ZLayer.scopedEnvironment(instance.map(ff => ZEnvironment[FeatureFlags](ff).add[FeatureFlagsAdmin](ff)))

  def fromProvider(
    provider: OFFeatureProvider,
    config: FeatureFlagsConfig,
    statusRef: Option[SubscriptionRef[ProviderStatus]],
    apiOverride: Option[OpenFeatureAPI] = None,
    onReady: Option[java.util.concurrent.CountDownLatch] = None
  ): ZLayer[Any, Throwable, FeatureFlags with FeatureFlagsAdmin] =
    bothServices {
      val evalTimeout = evaluationTimeoutOf(config)
      val resolvedApi = apiOverrideOf(config, apiOverride)
      val ownsApi     = ownsApiOf(config)
      config.initMode match {
        case InitMode.Sync =>
          build(
            provider,
            config.domain,
            config.version,
            config.initialHooks,
            statusRef = statusRef,
            addShutdownFinalizer = ownsApi,
            apiOverride = resolvedApi,
            evaluationTimeout = evalTimeout,
            initTimeout = config.initTimeout,
            onReady = onReady,
            contextSource = config.contextSource,
            fallbackLogging = config.fallbackLogging,
            maxAbandonedCalls = config.maxAbandonedCalls
          )
        case InitMode.Async =>
          buildAsync(
            provider,
            config.domain,
            config.version,
            config.initialHooks,
            statusRef = statusRef,
            addShutdownFinalizer = ownsApi,
            apiOverride = resolvedApi,
            onReady = onReady,
            evaluationTimeout = evalTimeout,
            initTimeout = config.initTimeout,
            contextSource = config.contextSource,
            fallbackLogging = config.fallbackLogging,
            maxAbandonedCalls = config.maxAbandonedCalls
          )
      }
    }

  /** Create a FeatureFlags layer with a named domain/client and a shared status ref. */
  def fromProviderWithDomain(
    provider: OFFeatureProvider,
    domain: String,
    statusRef: SubscriptionRef[ProviderStatus],
    api: Option[OpenFeatureAPI] = None,
    onReady: Option[java.util.concurrent.CountDownLatch] = None
  ): ZLayer[Any, Throwable, FeatureFlags with FeatureFlagsAdmin] =
    bothServices(
      build(
        provider,
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = Some(statusRef),
        addShutdownFinalizer = false,
        apiOverride = api,
        onReady = onReady
      )
    )

  /** Shared initialization logic for async factory methods.
    *
    * Uses `setProvider` (non-blocking) instead of `setProviderAndWait`. The provider initializes in the background;
    * evaluations fail with `ProviderNotReady` until the event bridge receives a `PROVIDER_READY` event. The bridge is
    * attached before the provider is registered, so that event cannot be missed and arrives exactly once.
    */
  def buildAsync(
    provider: OFFeatureProvider,
    domain: Option[String],
    version: Option[String],
    initialHooks: List[FeatureHook],
    statusRef: Option[SubscriptionRef[ProviderStatus]],
    addShutdownFinalizer: Boolean,
    apiOverride: Option[OpenFeatureAPI] = None,
    onReady: Option[java.util.concurrent.CountDownLatch] = None,
    evaluationTimeout: Option[Duration] = Some(DefaultEvaluationTimeout),
    initTimeout: Duration = DefaultInitTimeout,
    contextSource: ContextSource = ContextSource.empty,
    fallbackLogging: FallbackLogging = FallbackLogging.Default,
    maxAbandonedCalls: Int = DefaultMaxAbandonedCalls,
    probe: LifecycleProbe = LifecycleProbe.none
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    for {
      api <- ZIO.succeed(apiOverride.getOrElse(OpenFeatureAPI.getInstance()))
      // The SDK registers the library's wrapper, which observes the provider's initialization, shutdown and events; or
      // the provider itself, when this API already holds it.
      bound <- ZIO.attempt(Bound.of(api, domain, provider, probe))
      managed = bound.sdk
      // Register the API shutdown finalizer BEFORE the provider, so a failure later in the build still tears down the
      // registered provider when the scope closes.
      orphans = OrphanedProviders(api, probe)
      // Created before the finalizer, which takes the lock and sets the flag (see `scopeFinalizer`).
      swapLock <- Semaphore.make(1)
      self = new java.util.concurrent.atomic.AtomicReference[FeatureFlagsLive](null)
      // Every build, owning its API or not: the close marks the instance closed (see `scopeFinalizer`).
      _  <- ZIO.addFinalizer(scopeFinalizer(addShutdownFinalizer, api, orphans, probe, swapLock, self))
      ff <- makeInstance(
        managed,
        api,
        domain,
        version,
        initialHooks,
        statusRef,
        addShutdownFinalizer,
        onReady,
        evaluationTimeout,
        contextSource,
        fallbackLogging,
        initTimeout,
        maxAbandonedCalls,
        unreadableNameIsUnknown = false,
        orphans,
        probe,
        swapLock
      )
      // Known to the instance that later tears this API down, which then retires this one; and the slot checked
      // before it is bound.
      _ <- ZIO.succeed { self.set(ff); ApiInstances.register(api, ff, domain) }
      _ <- warnIfSlotBound(api, domain, managed)
      // As in the sync build: attach the bridge before the provider initializes, so exactly one PROVIDER_READY
      // reaches it — the post-init emission, or the SDK's replay for a provider that is already ready.
      early <- attachesBeforeRegistration(api, domain, managed)
      _     <- ZIO.when(early)(ff.startEventBridge)
      // `ZIO.attempt` (not `ZIO.succeed`) so a synchronous throw from registration — a null provider, or a provider
      // whose `getMetadata` throws while the SDK reads its name — surfaces in the typed Throwable channel instead of
      // becoming a defect. As a defect it would escape `buildClient`'s `mapError` and the registry's build fiber would
      // die with its promise never settled, hanging every `getClient` for that domain forever. `ZIO.attempt` (not
      // `attemptBlocking`): `setProvider` is the non-blocking registration call — it submits `initialize()` to the
      // SDK's background executor and returns after cheap bookkeeping, so there is no blocking I/O to move off the
      // compute pool. (The sync `build` path uses `attemptBlocking` because `ProviderRegistration` blocks a thread
      // polling for the init outcome.)
      _ <- ZIO.attempt(ProviderRegistration.bind(api, domain, bound)).tapError(_ => ff.stopEventBridge)
      _ <- ZIO.unless(early)(ff.startEventBridge)
      _ <- ff.reconcileRegistration(managed)
      // Init watchdog: after initTimeout, escalate to Fatal ONLY if the provider never became usable
      // (`everReady` gate inside the machine): publish the Error event, release the onReady latch, then shut the
      // provider down, bounded. A provider that was Ready and later dipped to a transient Error is left running. The
      // fiber is forked into the layer's Scope, so it is also interrupted when the layer is released.
      _ <- (ZIO.sleep(initTimeout) *> probe.at(LifecycleProbe.Point.Watchdog) *>
        ff.escalateInitTimeout(managed, initTimeout)).forkScoped
    } yield ff

  /** Create a FeatureFlags layer with a named domain and shared status ref (non-blocking). */
  def fromProviderWithDomainAsync(
    provider: OFFeatureProvider,
    domain: String,
    statusRef: SubscriptionRef[ProviderStatus],
    api: Option[OpenFeatureAPI] = None,
    onReady: Option[java.util.concurrent.CountDownLatch] = None
  ): ZLayer[Any, Throwable, FeatureFlags with FeatureFlagsAdmin] =
    bothServices(
      buildAsync(
        provider,
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = Some(statusRef),
        addShutdownFinalizer = false,
        apiOverride = api,
        onReady = onReady
      )
    )

  // What has become of the provider one attempt's `acquire` returns. It can arrive after the attempt was abandoned.
  sealed private trait Arrival
  private object Arrival {
    case object Waiting                               extends Arrival
    final case class Arrived(real: OFFeatureProvider) extends Arrival
    case object Abandoned                             extends Arrival
  }

  /** `effect.disconnect`, so the per-attempt timeout returns promptly even when `acquire` is a blocking
    * `attemptBlocking(new Provider(...))`, except that the interrupt waits for a blocking call to return. Interrupting
    * during the call discards the value it returns, and that value is a provider nobody could then shut down. A fiber
    * suspended where an interrupt lands holds no such value, so it is interrupted at once. The caller waits for that,
    * so its finalizers usually finish first, but only up to a bound: a suspended fiber can be waiting for a child stuck
    * in a blocking call, and a hung attempt must not hang the timeout or the layer's release.
    */
  private def abandonable[A](effect: Task[A]): Task[A] =
    ZIO.uninterruptibleMask { restore =>
      restore(effect).forkDaemon.flatMap { fiber =>
        restore(fiber.join).onInterrupt(
          fiber.status.flatMap {
            case status: Fiber.Status.Suspended if RuntimeFlags.interruption(status.runtimeFlags) =>
              ZIO
                .withClock(Clock.ClockLive)(
                  Timeouts.boundedFinalizer(fiber.interrupt, AbandonWait)(
                    ZIO.logWarning(s"fromAcquireAsync: an abandoned attempt did not stop within $AbandonWait")
                  )
                )
                .unit
            case _ => interruptOnceSuspended(fiber).forkDaemon.unit
          }
        )
      }
    }

  // `status` reads `Running` throughout a blocking call, and an uninterruptible region keeps a queued interrupt until it
  // ends, when it would discard the region's value. The clock is the live one, so that a test clock doesn't hold this
  // up. A fiber looping forever without suspending is left to loop, as it would be in an uninterruptible region.
  private def interruptOnceSuspended(fiber: Fiber.Runtime[Any, Any]): UIO[Unit] =
    fiber.status.flatMap {
      case Fiber.Status.Done                                                                => ZIO.unit
      case status: Fiber.Status.Suspended if RuntimeFlags.interruption(status.runtimeFlags) => fiber.interrupt.unit
      case _                                                                                =>
        ZIO.withClock(Clock.ClockLive)(ZIO.sleep(AbandonPoll)) *> interruptOnceSuspended(fiber)
    }

  private val AbandonPoll = 100.millis
  private val AbandonWait = 1.second

  private def shutDownOwned(provider: OFFeatureProvider): UIO[Unit] =
    ProviderRegistration.boundedProviderShutdown(provider, "fromAcquireAsync (not in service)")

  /** One attempt = acquire, then verify the candidate, inside a child scope of the layer scope. The child scope exists
    * so a REJECTED candidate is released right away instead of lingering until layer close. Whatever `acquire` returned
    * is the library's to shut down from the moment it arrives, before the scope closes: `arrival` holds it until the
    * attempt is accepted. An attempt abandoned before it arrives leaves both to the abandoned fiber, because the
    * constructor still running may be using what the scope holds. The mask keeps "shut down and close on failure" and
    * "take ownership on success" from being skipped by an interrupt landing between them; `restore` keeps the attempt
    * itself interruptible.
    */
  private def acquireAttempt(
    acquire: RIO[Scope, OFFeatureProvider],
    verify: OFFeatureProvider => Task[Unit],
    constructionTimeout: Duration,
    owned: Ref[Option[OFFeatureProvider]],
    candidate: Ref[Option[Scope.Closeable]]
  ): Task[OFFeatureProvider] =
    ZIO.uninterruptibleMask { restore =>
      for {
        scope   <- Scope.make
        arrival <- Ref.make[Arrival](Arrival.Waiting)
        release = (real: OFFeatureProvider, cause: Cause[Any]) =>
          shutDownOwned(real) *> scope.close(Exit.failCause(cause))
        real <- restore(
          Timeouts.failAfter(
            abandonable(
              ZIO.uninterruptibleMask { inner =>
                inner(scope.extend[Any](acquire))
                  .onError(cause => scope.close(Exit.failCause(cause)))
                  .flatMap { real =>
                    arrival.modify {
                      case Arrival.Abandoned =>
                        (release(real, Cause.empty) *> ZIO.interrupt, Arrival.Abandoned)
                      case _ => (inner(verify(real)).as(real), Arrival.Arrived(real))
                    }.flatten
                  }
              }
            ),
            constructionTimeout,
            new TimeoutException(s"Provider construction exceeded $constructionTimeout")
          )
        ).onError { cause =>
          arrival.modify {
            case Arrival.Arrived(real) => (release(real, cause), Arrival.Abandoned)
            case _                     => (ZIO.unit, Arrival.Abandoned)
          }.flatten
        }
        _ <- owned.set(Some(real)) *> candidate.set(Some(scope))
      } yield real
    }

  /** A provider that does not make it into service is dead: shut it down (a composed stack reaches the real provider
    * and the fresh fallback) and release the candidate now rather than at layer close. With nothing owned there is
    * nothing to discard: construction never got that far, or the provider is serving and its candidate has to outlive
    * it.
    */
  private def discardOwned(
    owned: Ref[Option[OFFeatureProvider]],
    candidate: Ref[Option[Scope.Closeable]]
  )(cause: Cause[Any]): UIO[Unit] =
    owned
      .getAndSet(None)
      .flatMap(ZIO.foreachDiscard(_) { provider =>
        shutDownOwned(provider) *>
          candidate.getAndSet(None).flatMap(ZIO.foreachDiscard(_)(_.close(Exit.failCause(cause))))
      })
      .uninterruptible

  /** Background construction + swap. The fiber is `forkScoped`, so an in-flight attempt is interrupted on release. A
    * terminal construction/verification/swap failure is caught, reported, and left on the fallback — nothing escapes to
    * the (already-built) layer.
    */
  private def constructAndSwitch(
    attempt: Task[OFFeatureProvider],
    fallback: URIO[Scope, OFFeatureProvider],
    compose: (OFFeatureProvider, OFFeatureProvider) => OFFeatureProvider,
    ff: FeatureFlagsLive,
    switch: ManagedProvider,
    state: SubscriptionRef[AcquireState],
    owned: Ref[Option[OFFeatureProvider]],
    initTimeout: Duration,
    onSwapped: UIO[Unit]
  ): ZIO[Scope, Throwable, Unit] =
    for {
      real <- attempt
      // The fresh fallback is owned from the moment it exists: the stack holds it, or a compose that threw leaves
      // it to be shut down here. `compose` is synchronous, so a release waits for it.
      composed <- ZIO.uninterruptibleMask { restore =>
        restore(fallback).flatMap { freshFallback =>
          ZIO
            .succeed(compose(real, freshFallback))
            .tapErrorCause(_ => shutDownOwned(freshFallback))
            .tap(stack => owned.set(Some(stack)))
        }
      }
      // Initialize the composed stack ourselves, where the SDK's `setProvider` used to, bounded the same way. The
      // fallback keeps serving throughout; a shutdown of the stack is best-effort against an init still running.
      _ <- ProviderRegistration
        .boundedInitialize(composed, ff.apiEvaluationContext, initTimeout)
        .zipRight(verifyProviderState(composed))
        .mapError(e => ProviderSwapFailed(FeatureFlagError.ProviderInitializationFailed(e)))
      switched <- ff.switchProvider(switch, composed).tap(ok => owned.set(None).when(ok)).uninterruptible
      _        <- ZIO.unless(switched) {
        ZIO.fail(
          new IllegalStateException(
            "provider replaced by setProvider before the acquired provider was swapped in"
          )
        )
      }
      // State flips before the callback so a probe reading `AcquireStatus.get` from inside it already sees Live.
      _ <- state.set(AcquireState.Live) *> onSwapped
    } yield ()

  /** Same ordering on the failure side: Failed is observable before `onConstructionError` runs. Failed is terminal —
    * the schedule is exhausted (or the swap failed) and nothing restarts construction. The whole Cause is handled, not
    * just typed failures: a defect (say `acquire = ZIO.succeed(new Provider(...))` whose constructor throws) would
    * otherwise kill the fiber with the state stuck at Constructing and no callback — a probe waiting for the outcome
    * would hang. A defect is still re-raised after being recorded, so it keeps whatever visibility an unhandled fiber
    * defect had before; interruption (layer release) is not an outcome, and the release's finalizer takes care of what
    * is owned.
    */
  private def recordConstructionOutcome(
    cause: Cause[Throwable],
    discard: Cause[Any] => UIO[Unit],
    state: SubscriptionRef[AcquireState],
    onConstructionError: Throwable => UIO[Unit]
  ): Task[Unit] = {
    val e      = cause.squashWith(identity)
    val record =
      if (cause.isInterruptedOnly) ZIO.unit
      else
        // A finalizer that dies must not keep the failure from being recorded.
        discard(cause).catchAllCause(c => ZIO.logWarningCause("fromAcquireAsync: discarding a provider failed", c)) *>
          state.get.flatMap {
            case AcquireState.Constructing => state.set(AcquireState.Failed(e)) *> onConstructionError(e)
            case _                         => ZIO.unit // a defect in `onSwapped` must not retract Live
          }
    record *> ZIO.when(cause.isDie || cause.isInterrupted)(ZIO.refailCause(cause)).unit
  }

  /** `config.initMode` does not apply: the fallback is installed at once and the real provider always arrives later. */
  def fromAcquireAsync(
    acquire: RIO[Scope, OFFeatureProvider],
    fallback: URIO[Scope, OFFeatureProvider],
    config: FeatureFlagsConfig,
    acquireConfig: AcquireConfig
  ): URLayer[Any, FeatureFlags with FeatureFlagsAdmin with AcquireStatus] = {
    val compose             = acquireConfig.compose
    val constructionRetry   = acquireConfig.constructionRetry
    val constructionTimeout = acquireConfig.constructionTimeout
    val onConstructionError = acquireConfig.onConstructionError
    val verify              = acquireConfig.verify
    val onSwapped           = acquireConfig.onSwapped
    val initTimeout         = config.initTimeout
    val maxAbandonedCalls   = config.maxAbandonedCalls
    require(maxAbandonedCalls >= 1, s"maxAbandonedCalls must be at least 1, got $maxAbandonedCalls")
    // `scopedEnvironment` rather than `scoped`: the layer emits two services, and only an explicit ZEnvironment can
    // carry an intersection.
    ZLayer.scopedEnvironment {
      for {
        // What the library must shut down and is not serving, and the scope of the provider `acquire` returned. One
        // finalizer releases both, registered before `build` so it runs after the fiber's interrupt and the API's
        // shutdown: a serving provider stops before its resources go away, and the fiber cannot swap in a provider whose
        // resources are gone.
        owned     <- Ref.make(Option.empty[OFFeatureProvider])
        candidate <- Ref.make(Option.empty[Scope.Closeable])
        _         <- ZIO.addFinalizerExit { exit =>
          owned.getAndSet(None).flatMap(ZIO.foreachDiscard(_)(shutDownOwned)) *>
            candidate.getAndSet(None).flatMap(ZIO.foreachDiscard(_)(_.close(exit)))
        }
        // Build immediately on a fresh fallback. An in-memory fallback whose init fails is a programming bug, not a
        // recoverable condition, so a failed build is a defect (`orDie`) — this is what makes the layer a `URLayer`.
        firstFallback <- fallback
        // The one provider the SDK ever sees on this path: it delegates to the fallback until the real provider
        // is initialized, then is flipped to the composed stack. No SDK swap, so no NotReady window.
        switch = ManagedProvider.switchable(firstFallback)
        state <- SubscriptionRef.make[AcquireState](AcquireState.Constructing)
        ff    <- build(
          switch,
          domain = config.domain,
          version = config.version,
          initialHooks = config.initialHooks,
          statusRef = None,
          addShutdownFinalizer = ownsApiOf(config),
          apiOverride = apiOverrideOf(config, None),
          evaluationTimeout = evaluationTimeoutOf(config),
          initTimeout = initTimeout,
          contextSource = config.contextSource,
          fallbackLogging = config.fallbackLogging,
          maxAbandonedCalls = maxAbandonedCalls
        ).orDie
        attempt = acquireAttempt(acquire, verify, constructionTimeout, owned, candidate)
        _ <- constructAndSwitch(
          attempt.retry(constructionRetry),
          fallback,
          compose,
          ff,
          switch,
          state,
          owned,
          initTimeout,
          onSwapped
        ).catchAllCause(recordConstructionOutcome(_, discardOwned(owned, candidate), state, onConstructionError))
          .forkScoped
      } yield ZEnvironment[FeatureFlags](ff).add[FeatureFlagsAdmin](ff).add[AcquireStatus](AcquireStatus.fromRef(state))
    }
  }
}
