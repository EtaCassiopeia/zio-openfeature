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
  * default arguments, and forwards here (#445).
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

  /** [[build]], optionally keeping tracked events for `trackedEvents` (testkit only, #442). */
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
      _ <- ZIO.when(addShutdownFinalizer)(ZIO.addFinalizer(ProviderRegistration.boundedApiShutdown(api)))
      // Non-blocking registration plus a bounded wait (#433): `setProviderAndWait` would hold the SDK's API write
      // lock across `initialize()`, so after a timeout the finalizer's `api.shutdown()` would block on that lock for
      // as long as `initialize()` hangs. On any init failure the provider itself is shut down (best-effort) so it
      // doesn't keep threads/connections alive — this also covers the domain/registry paths where no API finalizer
      // is registered.
      verified <- (ProviderRegistration.registerAndAwait(api, domain, provider, initTimeout) *>
        verifyInitState(provider))
        .tapError(_ => ZIO.attemptBlocking(provider.shutdown()).ignore)
      client <- ZIO.attempt(api.getClient(domain.orNull, version.orNull))
      providerName = Option(provider.getMetadata).map(_.getName).getOrElse(UnknownProviderName)
      providerRef <- Ref.make(provider)
      providerNameRef = new java.util.concurrent.atomic.AtomicReference(providerName)
      swapLock  <- Semaphore.make(1)
      baseState <- FeatureFlagsState.make
      state = statusRef.fold(baseState)(ref => baseState.copy(statusRef = ref))
      _                  <- state.hooksRef.set(initialHooks)
      fallbackLogLimiter <- FallbackLogLimiter.make(fallbackLogging)
      ff = new FeatureFlagsLive(
        client,
        providerRef,
        providerNameRef,
        domain,
        version,
        state,
        api,
        // Owns the api iff we also register the api-shutdown scope finalizer — keeps explicit `shutdown` and
        // scope-close teardown consistent, so a shared-api (registry/domain) client never shuts the whole api.
        ownsApi = addShutdownFinalizer,
        swapLock,
        fallbackLogLimiter,
        onReady,
        evaluationTimeout,
        contextSource,
        initTimeout,
        maxAbandonedCalls,
        recordTracking
      )
      // Only seed status when the caller didn't hand us a shared ref (testkit shares one). Routed through
      // `seedStatus` so the machine's `everReady` flag records a Ready/Stale seed.
      _ <- statusRef.fold(ff.seedStatus(verified))(_ => ZIO.unit)
      _ <- ff.startEventBridge
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
        // package-private but compiled into the published testkit), so newer knobs like `maxAbandonedCalls` (#437)
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
        // These overloads exist for the testkit, whose instances keep tracked events for assertions (#442).
        recordTracking = true
      )
    )

  /** Shared initialization logic for async factory methods.
    *
    * Uses `setProvider` (non-blocking) instead of `setProviderAndWait`. The provider initializes in the background;
    * evaluations fail with `ProviderNotReady` until the event bridge receives a `PROVIDER_READY` event. To avoid a race
    * where the provider becomes ready before the event bridge is registered, we start the event bridge first and then
    * check the provider's actual state.
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

  /** [[buildAsync]], optionally keeping tracked events for `trackedEvents` (testkit only, #442). */
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
      // Register the API shutdown finalizer BEFORE the provider, so a failure later in the build
      // (e.g. getClient) still tears down the registered provider when the scope closes.
      _ <- ZIO.when(addShutdownFinalizer)(ZIO.addFinalizer(ProviderRegistration.boundedApiShutdown(api)))
      // Register provider FIRST so the client binds to it (not the NoOp default). Use `ZIO.attempt` (not
      // `ZIO.succeed`) so a synchronous throw from registration — a null provider, or a provider whose
      // `getMetadata` throws while the SDK reads its name — surfaces in the typed Throwable channel instead of
      // becoming a defect. As a defect it would escape `buildClient`'s `mapError` and the registry's build
      // fiber would die with its promise never settled, hanging every `getClient` for that domain forever.
      // `ZIO.attempt` (not `attemptBlocking`): `setProvider` is the non-blocking registration call — it submits
      // `initialize()` to the SDK's background executor and returns after cheap bookkeeping, so there is no
      // blocking I/O to move off the compute pool. (The sync `build` path uses `attemptBlocking` because
      // `ProviderRegistration` blocks a thread polling for the init outcome.)
      _ <- domain match {
        case Some(d) => ZIO.attempt(api.setProvider(d, provider))
        case None    => ZIO.attempt(api.setProvider(provider))
      }
      client <- ZIO.attempt(api.getClient(domain.orNull, version.orNull))
      // `ZIO.attempt` for the same reason as the registration above: a throwing `getMetadata` here must be a
      // typed failure, not a defect that strands the registry build fiber (see the `setProvider` note).
      providerName <- ZIO.attempt(Option(provider.getMetadata).map(_.getName).getOrElse(UnknownProviderName))
      providerRef  <- Ref.make(provider)
      providerNameRef = new java.util.concurrent.atomic.AtomicReference(providerName)
      swapLock  <- Semaphore.make(1)
      baseState <- FeatureFlagsState.make
      state = statusRef.fold(baseState)(ref => baseState.copy(statusRef = ref))
      _                  <- state.hooksRef.set(initialHooks)
      fallbackLogLimiter <- FallbackLogLimiter.make(fallbackLogging)
      ff = new FeatureFlagsLive(
        client,
        providerRef,
        providerNameRef,
        domain,
        version,
        state,
        api,
        // Owns the api iff we also register the api-shutdown scope finalizer — keeps explicit `shutdown` and
        // scope-close teardown consistent, so a shared-api (registry/domain) client never shuts the whole api.
        ownsApi = addShutdownFinalizer,
        swapLock,
        fallbackLogLimiter,
        onReady,
        evaluationTimeout,
        contextSource,
        initTimeout,
        maxAbandonedCalls,
        recordTracking
      )
      // Start event bridge — if provider is already ready, replay fires immediately
      _ <- ff.startEventBridge
      // Init watchdog (#244): after initTimeout, escalate to Fatal ONLY if the provider never became usable
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
        // These overloads exist for the testkit, whose instances keep tracked events for assertions (#442).
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
        // The one provider the SDK ever sees on this path (#512): it delegates to the fallback until the real provider
        // is initialized, then is flipped to the composed stack. No SDK swap, so no NotReady window.
        switch = new SwitchableProvider(firstFallback)
        state <- SubscriptionRef.make[AcquireState](AcquireState.Constructing)
        ff <- buildWithTracking(
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
            real <- restore(
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
        construct = for {
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
          _ <- ZIO.unless(switched) {
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
          val e = cause.squashWith(identity)
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
