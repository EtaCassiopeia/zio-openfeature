package zio.openfeature.internal

import zio._
import zio.stream.SubscriptionRef
import zio.openfeature._
import zio.openfeature.FeatureFlags.{
  DefaultEvaluationTimeout,
  DefaultInitTimeout,
  DefaultMaxAbandonedCalls,
  UnknownProviderName
}
import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider, OpenFeatureAPI, ProviderState}
import java.util.concurrent.TimeoutException

/** The bodies of the [[FeatureFlags]] factories. `object FeatureFlags` keeps every signature, its ScalaDoc and its
  * default arguments, and forwards here.
  */
private[openfeature] object FeatureFlagsFactories {

  /** Verify the provider reached a usable state after sync initialization. Anything outside `READY` / `STALE` causes
    * the layer build to fail so misconfiguration surfaces at startup, not at first evaluation. Returns the ZIO-level
    * `ProviderStatus` that should be reflected for a usable state.
    */
  @scala.annotation.nowarn("msg=deprecated")
  private def verifyInitState(provider: OFFeatureProvider): ZIO[Any, Throwable, ProviderStatus] =
    ZIO.attempt(provider.getState).flatMap {
      case ProviderState.READY => ZIO.succeed(ProviderStatus.Ready)
      case ProviderState.STALE => ZIO.succeed(ProviderStatus.Stale)
      case bad @ (ProviderState.ERROR | ProviderState.FATAL | ProviderState.NOT_READY) =>
        ZIO.fail(new IllegalStateException(s"Provider in $bad state after initialization"))
    }

  /** The instance both builds return, constructed before its provider is registered so its event bridge can be attached
    * first. The SDK client does not need a registered provider: it resolves the slot's provider per call.
    */
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
    recordTracking: Boolean,
    unreadableNameIsUnknown: Boolean
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    for {
      client <- ZIO.attempt(api.getClient(domain.orNull, version.orNull))
      // Read before `initialize()`, where a name may not exist yet: a MultiProvider has no metadata until it has
      // initialized, and a wrapper around one throws reading it. `ZIO.attempt` makes a throw a typed failure, never a
      // defect that would strand the registry's build fiber with its promise unsettled. The sync build tolerates it
      // (`unreadableNameIsUnknown`) and reads the name again, strictly, once the provider has initialized.
      read = ZIO.attempt(Option(provider.getMetadata).flatMap(m => Option(m.getName)).getOrElse(UnknownProviderName))
      providerName <- if (unreadableNameIsUnknown) read.orElseSucceed(UnknownProviderName) else read
      providerRef  <- Ref.make(provider)
      swapLock     <- Semaphore.make(1)
      baseState    <- FeatureFlagsState.make
      state = statusRef.fold(baseState)(ref => baseState.copy(statusRef = ref))
      _                  <- state.hooksRef.set(initialHooks)
      fallbackLogLimiter <- FallbackLogLimiter.make(fallbackLogging)
    } yield new FeatureFlagsLive(
      client,
      providerRef,
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
      recordTracking
    )

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

  /** Record the name of a provider whose metadata name only materializes in `initialize()`, after a sync init. Only a
    * still-"unknown" name is replaced, so a name the bridge already took from the READY event is kept.
    */
  private def refreshProviderName(provider: OFFeatureProvider, ff: FeatureFlagsLive): Task[Unit] =
    ZIO.attempt(Option(provider.getMetadata).flatMap(m => Option(m.getName))).map {
      case Some(name) => ff.refreshUnknownProviderName(name)
      case None       => ()
    }

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
    fallbackLogging: FallbackLogging = FallbackLogging.Default
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    buildWithTracking(
      provider = provider,
      domain = domain,
      version = version,
      initialHooks = initialHooks,
      statusRef = statusRef,
      addShutdownFinalizer = addShutdownFinalizer,
      apiOverride = apiOverride,
      evaluationTimeout = evaluationTimeout,
      initTimeout = initTimeout,
      onReady = onReady,
      contextSource = contextSource,
      fallbackLogging = fallbackLogging,
      recordTracking = false
    )

  /** [[build]], optionally keeping tracked events for `trackedEvents` (testkit only). */
  def buildWithTracking(
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
    recordTracking: Boolean = false
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    for {
      api <- ZIO.succeed(apiOverride.getOrElse(OpenFeatureAPI.getInstance()))
      // Register the API shutdown finalizer BEFORE initiating provider registration. If init times out,
      // the provider reports a bad state, or getClient fails, the scope close still tears down whatever
      // the registration managed to bind.
      _  <- ZIO.when(addShutdownFinalizer)(ZIO.addFinalizer(ProviderRegistration.boundedApiShutdown(api)))
      ff <- makeInstance(
        provider,
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
        recordTracking,
        unreadableNameIsUnknown = true
      )
      // The bridge is attached before the provider initializes, so exactly one PROVIDER_READY reaches it. See
      // `attachesBeforeRegistration`.
      early <- attachesBeforeRegistration(api, domain, provider)
      _     <- ZIO.when(early)(ff.startEventBridge)
      // Non-blocking registration plus a bounded wait: `setProviderAndWait` would hold the SDK's API write
      // lock across `initialize()`, so after a timeout the finalizer's `api.shutdown()` would block on that lock for
      // as long as `initialize()` hangs. On any init failure the provider itself is shut down (best-effort) so it
      // doesn't keep threads/connections alive — this also covers the domain/registry paths where no API finalizer
      // is registered — and the bridge's handlers are removed so a failed build leaves none behind.
      verified <- (ProviderRegistration.registerThenAwait(api, domain, provider, initTimeout)(
        ZIO.unless(early)(ff.startEventBridge).unit
      ) *> verifyInitState(provider) <*
        // A provider whose metadata name only exists once it has initialized (the SDK's MultiProvider) was stamped
        // with the "unknown" fallback; now that it has, record its real name.
        refreshProviderName(provider, ff))
        .tapError(_ => ff.stopEventBridge *> ZIO.attemptBlocking(provider.shutdown()).ignore)
      // Only seed status when the caller didn't hand us a shared ref (testkit shares one). Routed through
      // `seedStatus` so the machine's `everReady` flag records a Ready/Stale seed.
      _ <- statusRef.fold(ff.seedStatus(verified))(_ => ZIO.unit)
    } yield ff

  def fromProvider(
    provider: OFFeatureProvider,
    config: FeatureFlagsConfig,
    statusRef: Option[SubscriptionRef[ProviderStatus]],
    apiOverride: Option[OpenFeatureAPI] = None,
    onReady: Option[java.util.concurrent.CountDownLatch] = None
  ): ZLayer[Any, Throwable, FeatureFlags] =
    ZLayer.scoped {
      val evalTimeout = config.evaluationTimeout match {
        case EvaluationTimeout.Default  => Some(DefaultEvaluationTimeout)
        case EvaluationTimeout.Disabled => None
        case EvaluationTimeout.After(d) => Some(d)
      }
      val ownsApi = config.apiOwnership match {
        case ApiOwnership.Auto   => config.domain.isEmpty
        case ApiOwnership.Owned  => true
        case ApiOwnership.Shared => false
      }
      config.initMode match {
        // The `WithTracking` variants, not `build`/`buildAsync`: those keep their released signatures (they are
        // package-private but compiled into the published testkit), so newer knobs like `maxAbandonedCalls`
        // reach the instance only through these.
        case InitMode.Sync =>
          buildWithTracking(
            provider,
            config.domain,
            config.version,
            config.initialHooks,
            statusRef = statusRef,
            addShutdownFinalizer = ownsApi,
            apiOverride = apiOverride,
            evaluationTimeout = evalTimeout,
            initTimeout = config.initTimeout,
            onReady = onReady,
            contextSource = config.contextSource,
            fallbackLogging = config.fallbackLogging,
            maxAbandonedCalls = config.maxAbandonedCalls
          )
        case InitMode.Async =>
          buildAsyncWithTracking(
            provider,
            config.domain,
            config.version,
            config.initialHooks,
            statusRef = statusRef,
            addShutdownFinalizer = ownsApi,
            apiOverride = apiOverride,
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
  ): ZLayer[Any, Throwable, FeatureFlags] =
    ZLayer.scoped(
      buildWithTracking(
        provider,
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = Some(statusRef),
        addShutdownFinalizer = false,
        apiOverride = api,
        onReady = onReady,
        // These overloads exist for the testkit, whose instances keep tracked events for assertions.
        recordTracking = true
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
    fallbackLogging: FallbackLogging = FallbackLogging.Default
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    buildAsyncWithTracking(
      provider = provider,
      domain = domain,
      version = version,
      initialHooks = initialHooks,
      statusRef = statusRef,
      addShutdownFinalizer = addShutdownFinalizer,
      apiOverride = apiOverride,
      onReady = onReady,
      evaluationTimeout = evaluationTimeout,
      initTimeout = initTimeout,
      contextSource = contextSource,
      fallbackLogging = fallbackLogging,
      recordTracking = false
    )

  /** [[buildAsync]], optionally keeping tracked events for `trackedEvents` (testkit only). */
  def buildAsyncWithTracking(
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
    recordTracking: Boolean = false
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    for {
      api <- ZIO.succeed(apiOverride.getOrElse(OpenFeatureAPI.getInstance()))
      // Register the API shutdown finalizer BEFORE the provider, so a failure later in the build still tears down the
      // registered provider when the scope closes.
      _  <- ZIO.when(addShutdownFinalizer)(ZIO.addFinalizer(ProviderRegistration.boundedApiShutdown(api)))
      ff <- makeInstance(
        provider,
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
        recordTracking,
        unreadableNameIsUnknown = false
      )
      // As in the sync build: attach the bridge before the provider initializes, so exactly one PROVIDER_READY
      // reaches it — the post-init emission, or the SDK's replay for a provider that is already ready.
      early <- attachesBeforeRegistration(api, domain, provider)
      _     <- ZIO.when(early)(ff.startEventBridge)
      // `ZIO.attempt` (not `ZIO.succeed`) so a synchronous throw from registration — a null provider, or a provider
      // whose `getMetadata` throws while the SDK reads its name — surfaces in the typed Throwable channel instead of
      // becoming a defect. As a defect it would escape `buildClient`'s `mapError` and the registry's build fiber would
      // die with its promise never settled, hanging every `getClient` for that domain forever. `ZIO.attempt` (not
      // `attemptBlocking`): `setProvider` is the non-blocking registration call — it submits `initialize()` to the
      // SDK's background executor and returns after cheap bookkeeping, so there is no blocking I/O to move off the
      // compute pool. (The sync `build` path uses `attemptBlocking` because `ProviderRegistration` blocks a thread
      // polling for the init outcome.)
      _ <- (domain match {
        case Some(d) => ZIO.attempt(api.setProvider(d, provider))
        case None    => ZIO.attempt(api.setProvider(provider))
      }).tapError(_ => ff.stopEventBridge)
      _ <- ZIO.unless(early)(ff.startEventBridge)
      // Init watchdog: after initTimeout, escalate to Fatal ONLY if the provider never became usable
      // (`everReady` gate inside the machine), shutting it down and releasing the onReady latch. A provider that was
      // Ready and later dipped to a transient Error is left running. The fiber is forked into the layer's Scope, so
      // it is also interrupted when the layer is released.
      _ <- (ZIO.sleep(initTimeout) *> ff.escalateInitTimeout(provider, initTimeout)).forkScoped
    } yield ff

  /** Create a FeatureFlags layer with a named domain and shared status ref (non-blocking). */
  def fromProviderWithDomainAsync(
    provider: OFFeatureProvider,
    domain: String,
    statusRef: SubscriptionRef[ProviderStatus],
    api: Option[OpenFeatureAPI] = None,
    onReady: Option[java.util.concurrent.CountDownLatch] = None
  ): ZLayer[Any, Throwable, FeatureFlags] =
    ZLayer.scoped(
      buildAsyncWithTracking(
        provider,
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = Some(statusRef),
        addShutdownFinalizer = false,
        apiOverride = api,
        onReady = onReady,
        // These overloads exist for the testkit, whose instances keep tracked events for assertions.
        recordTracking = true
      )
    )

  def fromAcquireAsync(
    acquire: RIO[Scope, OFFeatureProvider],
    fallback: URIO[Scope, OFFeatureProvider],
    compose: (OFFeatureProvider, OFFeatureProvider) => OFFeatureProvider,
    constructionRetry: Schedule[Any, Throwable, Any],
    constructionTimeout: Duration,
    onConstructionError: Throwable => UIO[Unit],
    evaluationTimeout: Duration,
    initTimeout: Duration,
    contextSource: ContextSource,
    verify: OFFeatureProvider => Task[Unit],
    onSwapped: UIO[Unit],
    fallbackLogging: FallbackLogging,
    maxAbandonedCalls: Int
  ): URLayer[Any, FeatureFlags with AcquireStatus] = {
    require(maxAbandonedCalls >= 1, s"maxAbandonedCalls must be at least 1, got $maxAbandonedCalls")
    // `scopedEnvironment` rather than `scoped`: the layer emits two services, and only an explicit ZEnvironment can
    // carry an intersection.
    ZLayer.scopedEnvironment {
      for {
        // Build immediately on a fresh fallback. An in-memory fallback whose init fails is a programming bug, not a
        // recoverable condition, so a failed build is a defect (`orDie`) — this is what makes the layer a `URLayer`.
        firstFallback <- fallback
        // The one provider the SDK ever sees on this path: it delegates to the fallback until the real provider
        // is initialized, then is flipped to the composed stack. No SDK swap, so no NotReady window.
        switch = new SwitchableProvider(firstFallback)
        state <- SubscriptionRef.make[AcquireState](AcquireState.Constructing)
        ff    <- buildWithTracking(
          switch,
          domain = None,
          version = None,
          initialHooks = Nil,
          statusRef = None,
          addShutdownFinalizer = true,
          evaluationTimeout = Some(evaluationTimeout),
          initTimeout = initTimeout,
          contextSource = contextSource,
          fallbackLogging = fallbackLogging,
          maxAbandonedCalls = maxAbandonedCalls
        ).orDie
        // One attempt = acquire, then verify the candidate, inside a child scope of the layer scope. The child scope
        // exists so a REJECTED candidate (acquire failed after registering finalizers, verify failed, or the attempt
        // timed out) is released right away instead of lingering until layer close; the ACCEPTED candidate's scope is
        // handed to the layer scope, so a real provider acquired but not yet swapped is still torn down on layer
        // release. Interruption (layer release mid-attempt) takes the same close-the-child path via `onError`. The
        // mask keeps "close on failure" and "hand over on success" from being skipped by an interrupt landing between
        // them; `restore` keeps the attempt itself interruptible.
        attempt = ZIO.uninterruptibleMask { restore =>
          for {
            layerScope <- ZIO.scope
            candidate  <- Scope.make
            real       <- restore(
              candidate
                .extend[Any](acquire.flatMap(real => verify(real).as(real)))
                // `.disconnect` so the per-attempt timeout returns promptly even when `acquire` is an uninterruptible
                // `attemptBlocking(new Provider(...))` — the underlying constructor may keep running in the background,
                // but the retry advances instead of waiting on a hung attempt. A finalizer it registers late lands in
                // an already-closed child scope and runs immediately.
                .disconnect
                .timeoutFail(new TimeoutException(s"Provider construction exceeded $constructionTimeout"))(
                  constructionTimeout
                )
            ).onError(cause => candidate.close(Exit.failCause(cause)))
            _ <- layerScope.addFinalizerExit(candidate.close(_))
          } yield (candidate, real)
        }
        // Background construction + swap. The fiber is `forkScoped`, so an in-flight attempt is interrupted on
        // release. A terminal construction/verification/swap failure is caught, reported, and left on the fallback —
        // nothing escapes to the (already-built) layer.
        construct =
          for {
            accepted <- attempt.retry(constructionRetry)
            candidate = accepted._1
            freshFallback <- fallback
            composed = compose(accepted._2, freshFallback)
            // A composed stack that does not make it into service is dead: shut it down (which reaches the real provider
            // and the fresh fallback) and release the candidate now rather than at layer close. `Scope.close` is
            // idempotent, so the layer-scope finalizer is then a no-op.
            discard = (cause: Cause[Any]) =>
              ProviderRegistration.boundedProviderShutdown(composed, "fromAcquireAsync (discarded provider)") *>
                candidate.close(Exit.failCause(cause))
            // Initialize the composed stack ourselves, where the SDK's `setProvider` used to, bounded the same way. The
            // fallback keeps serving throughout. `.disconnect` lets the timeout fire even though a blocking
            // `initialize()` cannot be interrupted; it may keep running in the background, and the shutdown above is
            // best-effort against it.
            _ <- ZIO
              .attemptBlocking(composed.initialize(ff.apiEvaluationContext))
              .disconnect
              .timeoutFail(new TimeoutException(s"Provider initialization exceeded $initTimeout"))(initTimeout)
              .zipRight(verifyInitState(composed))
              .mapError(e => ProviderSwapFailed(FeatureFlagError.ProviderInitializationFailed(e)))
              .onError(discard)
            switched <- ff.switchProvider(switch, composed).onError(discard)
            _        <- ZIO.unless(switched) {
              val e = new IllegalStateException(
                "provider replaced by setProvider before the acquired provider was swapped in"
              )
              discard(Cause.fail(e)) *> ZIO.fail(e)
            }
            // State flips before the callback so a probe reading `AcquireStatus.get` from inside it already sees Live.
            _ <- state.set(AcquireState.Live) *> onSwapped
          } yield ()
        // Same ordering on the failure side: Failed is observable before `onConstructionError` runs. Failed is
        // terminal — the schedule is exhausted (or the swap failed) and nothing restarts construction. The whole
        // Cause is handled, not just typed failures: a defect (say `acquire = ZIO.succeed(new Provider(...))` whose
        // constructor throws) would otherwise kill the fiber with the state stuck at Constructing and no callback —
        // a probe waiting for the outcome would hang. A defect is still re-raised after being recorded, so it keeps
        // whatever visibility an unhandled fiber defect had before; interruption (layer release) is not an outcome.
        _ <- construct.catchAllCause { cause =>
          val e      = cause.squashWith(identity)
          val record =
            if (cause.isInterruptedOnly) ZIO.unit
            else
              state.get.flatMap {
                case AcquireState.Constructing => state.set(AcquireState.Failed(e)) *> onConstructionError(e)
                case _                         => ZIO.unit // a defect in `onSwapped` must not retract Live
              }
          record *> ZIO.when(cause.isDie || cause.isInterrupted)(ZIO.refailCause(cause))
        }.forkScoped
      } yield ZEnvironment[FeatureFlags](ff).add[AcquireStatus](AcquireStatus.fromRef(state))
    }
  }
}
