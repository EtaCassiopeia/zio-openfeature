package zio.openfeature

import zio._
import zio.stream._
import zio.openfeature.internal.{
  EventBridge,
  Evaluator,
  FallbackLogLimiter,
  FeatureFlagsState,
  HookPipeline,
  ProviderRegistration,
  SwitchableProvider,
  TrackingConverter
}
import zio.openfeature.internal.ProviderStatusMachine.Signal
import dev.openfeature.sdk.{
  Client => OFClient,
  FeatureProvider => OFFeatureProvider,
  EventDetails,
  EventProviderAccess,
  OpenFeatureAPI,
  ProviderState => JavaProviderState
}

final private[openfeature] class FeatureFlagsLive(
  client: OFClient,
  providerRef: Ref[OFFeatureProvider],
  // AtomicReference (not Ref): the event bridge reads the name synchronously from Java SDK threads on
  // every event; a Ref would force a runtime.unsafe.run round-trip per event.
  providerNameRef: java.util.concurrent.atomic.AtomicReference[String],
  domain: Option[String],
  version: Option[String],
  state: FeatureFlagsState,
  api: OpenFeatureAPI,
  // True when this instance solely owns `api` (the sole-owner factories that also register the api-shutdown scope
  // finalizer). When false — a registry/domain client sharing an api (and possibly a provider) with sibling clients —
  // `shutdown` must NOT touch the shared api or provider; both belong to whatever owns the api's lifecycle.
  ownsApi: Boolean,
  swapLock: Semaphore,
  fallbackLogLimiter: FallbackLogLimiter,
  onReady: Option[java.util.concurrent.CountDownLatch] = None,
  evaluationTimeout: Option[Duration] = Some(FeatureFlags.DefaultEvaluationTimeout),
  // Pull-based ambient context, consulted per evaluation and per `track` in `effectiveContext`.
  contextSource: ContextSource = ContextSource.empty,
  // Bounds a hot swap's provider initialization, and its rollback re-registration, exactly as it bounds the build.
  initTimeout: Duration = FeatureFlags.DefaultInitTimeout,
  // Cap on provider calls abandoned by the evaluation timeout and still running; see `withTimeout`.
  maxAbandonedCalls: Int = FeatureFlags.DefaultMaxAbandonedCalls,
  // Whether `track` keeps a copy of each event and its merged context for `trackedEvents`. Only testkit-built
  // instances set it: a production instance must not retain contexts, which routinely hold PII.
  recordTracking: Boolean = false
) extends FeatureFlags {

  // The total tier's served-default breadcrumb, under this instance's `FallbackLogging` policy. Lives here, not
  // in `FeatureFlagsState`: the trait's `totalResolution` reaches state only through this hook.
  override protected def logFallback[A](
    key: String,
    resolution: FlagResolution[A],
    defect: Option[Cause[Nothing]]
  ): UIO[Unit] =
    fallbackLogLimiter.log(key, resolution, defect)

  // The collaborators this instance delegates to. It keeps lifecycle and state: construction, the provider
  // swap, shutdown and status.
  private val eventBridge  = new EventBridge(client, providerNameRef, state, onReady)
  private val hookPipeline = new HookPipeline(state, providerNameRef, domain, version)
  private val evaluator    = new Evaluator(
    client,
    state,
    contextSource,
    evaluationTimeout,
    maxAbandonedCalls,
    fallbackLogLimiter,
    hookPipeline
  )

  /** Abandoned provider calls still running — for tests. */
  private[openfeature] def abandonedCallCount: Int = evaluator.abandonedCallCount

  private def swapInProgress    = eventBridge.swapInProgress
  private def everReady         = eventBridge.everReady
  private def shutdownCompleted = eventBridge.shutdownCompleted
  private def bridgeDeregister  = eventBridge.bridgeDeregister

  private def applySignal(signal: Signal): UIO[Option[ProviderStatus]] = eventBridge.applySignal(signal)

  private def effectiveContext(invocation: EvaluationContext): UIO[EvaluationContext] =
    evaluator.effectiveContext(invocation)

  private def toJavaContext(context: EvaluationContext): dev.openfeature.sdk.EvaluationContext =
    evaluator.toJavaContext(context)

  private[openfeature] def onReadyEvent(details: EventDetails): UIO[Unit] = eventBridge.onReadyEvent(details)

  private[openfeature] def onErrorEvent(details: EventDetails): UIO[Unit] = eventBridge.onErrorEvent(details)

  private[openfeature] def onStaleEvent(details: EventDetails): UIO[Unit] = eventBridge.onStaleEvent(details)

  private[openfeature] def startEventBridge: ZIO[Scope, Nothing, Unit] = eventBridge.startEventBridge

  /** Replace the "unknown" fallback name with `name`; a real name is never overwritten. */
  private[openfeature] def refreshUnknownProviderName(name: String): Unit = {
    providerNameRef.compareAndSet(FeatureFlags.UnknownProviderName, name)
    ()
  }

  /** Remove the bridge's SDK handlers now, rather than at scope close: a failed build must leave none behind. The
    * registry builds into its own long-lived scope, so its finalizer alone would keep a failed client's handlers.
    */
  private[openfeature] def stopEventBridge: UIO[Unit] =
    // `removeHandler` for a handler that is absent is a no-op, and this runs on a failure path whose own error must
    // win, so a throw here is logged rather than raised.
    ZIO
      .attempt(bridgeDeregister.get()())
      .catchAllCause(c => ZIO.logWarningCause("event bridge: removing handlers after a failed build failed", c))

  // Detailed evaluation methods (one per type, with default parameters from trait)

  override def booleanDetails(
    key: String,
    default: Boolean,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Boolean]] =
    evaluator.evaluateWithDetails(key, default, ctx, options)

  override def stringDetails(
    key: String,
    default: String,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[String]] =
    evaluator.evaluateWithDetails(key, default, ctx, options)

  override def intDetails(
    key: String,
    default: Int,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Int]] =
    evaluator.evaluateWithDetails(key, default, ctx, options)

  override def longDetails(
    key: String,
    default: Long,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Long]] =
    evaluator.evaluateWithDetails(key, default, ctx, options)

  override def doubleDetails(
    key: String,
    default: Double,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Double]] =
    evaluator.evaluateWithDetails(key, default, ctx, options)

  override def objDetails(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Map[String, Any]]] =
    evaluator.evaluateWithDetails(key, default, ctx, options)

  override def valueDetails[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[A]] =
    evaluator.evaluateWithDetails(key, default, ctx, options)

  // The total tier serves a provider's error-coded answer itself, with its variant, flag metadata and message, rather
  // than a resolution rebuilt from the typed tier's failure.
  override private[openfeature] def resolutionForTotal[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext,
    options: EvaluationOptions
  )(typed: => IO[FeatureFlagError, FlagResolution[A]]): IO[FeatureFlagError, FlagResolution[A]] =
    evaluator.evaluateResolution(key, default, ctx, options)

  override def setGlobalContext(ctx: EvaluationContext): UIO[Unit] =
    state.globalContextRef.set(ctx)

  override def globalContext: UIO[EvaluationContext] =
    state.globalContextRef.get

  override def setClientContext(ctx: EvaluationContext): UIO[Unit] =
    state.clientContextRef.set(ctx)

  override def clientContext: UIO[EvaluationContext] =
    state.clientContextRef.get

  override def withContext[R, E, A](ctx: EvaluationContext)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    state.fiberContextRef.get.flatMap { current =>
      state.fiberContextRef.locally(current.merge(ctx))(zio)
    }

  override def transaction[R, E, A](
    overrides: Map[String, Any],
    context: EvaluationContext,
    cacheEvaluations: Boolean,
    nested: NestedPolicy
  )(zio: ZIO[R, E, A]): ZIO[R, Compat.OrError[E, FeatureFlagError], TransactionResult[A]] =
    // Built on the source-tagged form so the two never disagree; `Compat.merge` drops the tag back into `OrError`
    // (the raw `E | FeatureFlagError` union on Scala 3, `Any` on 2.13) — preserving the legacy error channel exactly.
    transactionEither(overrides, context, cacheEvaluations, nested)(zio).mapError(Compat.merge)

  override def transactionEither[R, E, A](
    overrides: Map[String, Any],
    context: EvaluationContext,
    cacheEvaluations: Boolean,
    nested: NestedPolicy
  )(zio: ZIO[R, E, A]): ZIO[R, Either[E, FeatureFlagError], TransactionResult[A]] =
    // Errors are tagged at their source: `Left` is the caller's own `E` from `zio`, `Right` is a transaction-machinery
    // error. This is the only place the two can be told apart — once merged into a single channel (as `transaction`
    // does) an `E` that happens to equal `FeatureFlagError` is indistinguishable from a machinery error.
    state.transactionRef.get.flatMap {
      case Some(enclosing) =>
        nested match {
          case NestedPolicy.Fail =>
            ZIO.fail(Right(FeatureFlagError.NestedTransactionNotAllowed): Either[E, FeatureFlagError])
          case NestedPolicy.Reuse =>
            // No new state and no `locally`: the body runs under the enclosing state as-is, which is what makes the
            // inner `overrides`/`context`/`cacheEvaluations` genuinely ignored rather than half-applied. The result
            // is the enclosing transaction as of the body's completion — the inner call opened nothing of its own.
            zio.mapError(Left(_): Either[E, FeatureFlagError]).flatMap(enclosing.toResult)
        }
      case None =>
        // Not nested: `nested` is irrelevant and this is exactly the plain, non-nested path.
        for {
          txState  <- TransactionState.make(overrides, context, cacheEvaluations)
          result   <- state.transactionRef.locally(Some(txState))(zio).mapError(Left(_): Either[E, FeatureFlagError])
          txResult <- txState.toResult(result)
        } yield txResult
    }

  override def inTransaction: UIO[Boolean] =
    state.transactionRef.get.map(_.isDefined)

  override def currentEvaluatedFlags: UIO[Map[String, zio.openfeature.FlagEvaluation[_]]] =
    state.transactionRef.get.flatMap {
      case Some(ts) => ts.getEvaluations
      case None     => Exit.succeed(Map.empty)
    }

  override def transactionEvaluations: UIO[Option[Map[String, zio.openfeature.FlagEvaluation[_]]]] =
    state.transactionRef.get.flatMap {
      case Some(ts) => ts.getEvaluations.map(Some(_))
      case None     => Exit.succeed(None)
    }

  override def events: ZStream[Any, Nothing, ProviderEvent] =
    ZStream.fromHub(state.eventHub)

  override def providerStatus: UIO[ProviderStatus] =
    state.statusRef.get

  /** Re-register `oldProvider` with the SDK iff the failed swap left the SDK routing to `newProvider`. Returns true iff
    * the SDK is verified to route to `oldProvider` afterwards.
    *
    * The Java SDK binds the new provider's state manager into the domain/default slot before calling its `initialize()`
    * and does not revert that binding when init throws, so after a failed swap the SDK client still routes evaluations
    * to the failed provider while our refs have already rolled back to the old one. Re-registering the old provider
    * reconciles the two — and, on success, triggers the SDK's own `shutDownOld` of the failed provider.
    *
    * Caveat: re-registration starts a fresh state manager for the old provider, so the SDK calls its `initialize()`
    * again (an Optimizely poller restarts, an OFREP client re-fetches, ...) — unless the old provider is still bound to
    * another domain of a shared API, whose READY manager is reused and skips re-init.
    */
  private def restoreSdkRegistration(
    oldProvider: OFFeatureProvider,
    newProvider: OFFeatureProvider
  ): Task[Boolean] =
    ZIO.attemptBlocking {
      val bound = domain.fold(api.getProvider())(d => api.getProvider(d))
      if (bound eq oldProvider) true // swap failed before binding (e.g. attach threw); already routing to old
      // A concurrent sibling swap (shared-api topology) won the slot: leave it alone and report no restore, so status
      // stays Error rather than us fighting the winner. The refs stay rolled back to oldProvider — a benign skew in
      // this rare topology, deliberately not reconciled here.
      else if (bound ne newProvider) false
      else {
        def register(): Unit = ProviderRegistration.registerAndAwaitBlocking(api, domain, oldProvider, initTimeout)
        try register()
        catch {
          case _: IllegalStateException =>
            // The "already attached" state a failed swap leaves an EventProvider in: its state manager was evicted from
            // the slot but its `attach` CAS was never reset, so re-registration throws. Detach and retry once; any
            // further failure propagates. This is precise for the case that matters — the ISE fires here only for an
            // old provider still bound to ANOTHER domain when its reused READY manager skips attach entirely (so the
            // detach is never called on a still-attached shared provider). The SDK's other ISE ("cannot set provider
            // while repository is shutting down") also lands here, but it throws before any rebind, and the detach is
            // moot then because the whole API is being torn down; the retry rethrows it and it propagates to cleanup.
            EventProviderAccess.detach(oldProvider)
            register()
        }
        true
      }
    }

  /** Best-effort teardown of the failed new provider when rollback itself failed and left it unbound. On a successful
    * rollback the SDK's `shutDownOld` already does this; this covers only the rollback-failure path. If the failed
    * provider is somehow still bound, do nothing.
    */
  private def cleanupUnboundFailedProvider(newProvider: OFFeatureProvider): UIO[Unit] =
    (for {
      bound <- ZIO.attemptBlocking(domain.fold(api.getProvider())(d => api.getProvider(d)))
      _     <- ZIO.when(bound ne newProvider)(
        ProviderRegistration.boundedProviderShutdown(
          newProvider,
          "setProvider (rollback failed)",
          ProviderRegistration.failedProviderShutdownTimeout(initTimeout)
        ) *>
          ZIO
            .attemptBlocking(EventProviderAccess.deregisterGlobalProvider(api, newProvider))
            .tapErrorCause(c =>
              ZIO.logWarningCause("setProvider (rollback failed): deregistering its events failed", c)
            )
            .ignore
      )
    } yield ())
      // Log if even the best-effort teardown's own precondition read fails, so a leaked provider/thread from this
      // doubly-rare path leaves a breadcrumb instead of vanishing silently.
      .tapErrorCause(c => ZIO.logWarningCause("cleanupUnboundFailedProvider: best-effort teardown failed", c))
      .ignore

  /** Async-init watchdog escalation. Only when the machine actually transitions (the provider never became usable — the
    * `everReady` gate) does it publish a terminal Error event with code PROVIDER_FATAL, release the onReady latch, and
    * shut the provider down, in that order: a provider stuck in `initialize()` often cannot shut down either, and the
    * event and the latch must not wait on it. A provider that was ever Ready is left running.
    */
  private[openfeature] def escalateInitTimeout(provider: OFFeatureProvider, initTimeout: Duration): UIO[Unit] =
    applySignal(Signal.InitTimeout).flatMap {
      case Some(_) =>
        val msg = s"Provider initialization exceeded $initTimeout"
        state.eventHub
          .publish(
            ProviderEvent.Error(
              new java.util.concurrent.TimeoutException(msg),
              ProviderMetadata(providerNameRef.get()),
              Some(ErrorCode.ProviderFatal),
              Some(msg)
            )
          )
          .unit *>
          ZIO.succeed(onReady.foreach(_.countDown())) *>
          ProviderRegistration.boundedProviderShutdown(provider, "init watchdog")
      case None => ZIO.unit
    }

  /** Seed the initial status from the sync build path, recording ever-readiness for the machine. */
  private[openfeature] def seedStatus(status: ProviderStatus): UIO[Unit] =
    ZIO.succeed {
      if (status == ProviderStatus.Ready || status == ProviderStatus.Stale) everReady.set(true)
    } *> state.statusRef.set(status)

  /** Every status transition, current first. Test observability only: lets a spec prove a status was never entered. */
  private[openfeature] def statusChanges: ZStream[Any, Nothing, ProviderStatus] = state.statusRef.changes

  override def awaitReady(within: Duration): UIO[ProviderStatus] =
    // `.changes` emits the current status first (so an already-Ready provider returns immediately) then every
    // subsequent transition — no polling. `runHead` completes on the first evaluable-or-Fatal status. On timeout
    // (`None`) or an exhausted stream we return the then-current status, so every path yields a real status.
    // A shut-down instance never becomes evaluable again, so ShuttingDown and the terminal NotReady stop the wait too
    // — otherwise `awaitReady()` on a shut-down instance would wait forever.
    state.statusRef.changes
      .filter(s =>
        s.canEvaluate || s == ProviderStatus.Fatal || s == ProviderStatus.ShuttingDown || shutdownCompleted.get()
      )
      .runHead
      .timeout(within)
      .flatMap {
        case Some(Some(status)) => ZIO.succeed(status)
        case _                  => state.statusRef.get
      }

  override def providerMetadata: UIO[ProviderMetadata] =
    ZIO.succeed(ProviderMetadata(providerNameRef.get()))

  override def clientMetadata: UIO[ClientMetadata] =
    ZIO.succeed(ClientMetadata(domain, version))

  override def onProviderReady(handler: ProviderMetadata => UIO[Unit]): UIO[UIO[Unit]] =
    eventBridge.onProviderReady(handler)

  override def onProviderError(handler: (Throwable, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] =
    eventBridge.onProviderError(handler)

  override def onProviderStale(handler: (String, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] =
    eventBridge.onProviderStale(handler)

  override def onConfigurationChanged(handler: (Set[String], ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] =
    eventBridge.onConfigurationChanged(handler)

  override def on(eventType: ProviderEventType, handler: ProviderEvent => UIO[Unit]): UIO[UIO[Unit]] =
    eventBridge.on(eventType, handler)

  override def addHook(hook: FeatureHook): UIO[Unit] =
    state.hooksRef.update(_ :+ hook)

  override def addHooks(hooks: List[FeatureHook]): UIO[Unit] =
    state.hooksRef.update(_ ++ hooks)

  override def clearHooks: UIO[Unit] =
    state.hooksRef.set(List.empty)

  override def hooks: UIO[List[FeatureHook]] =
    state.hooksRef.get

  override def addZioApiHook(hook: FeatureHook): UIO[Unit] =
    state.zioApiHooksRef.update(_ :+ hook)

  override def addZioApiHooks(hooks: List[FeatureHook]): UIO[Unit] =
    state.zioApiHooksRef.update(_ ++ hooks)

  override def clearZioApiHooks: UIO[Unit] =
    state.zioApiHooksRef.set(List.empty)

  override def zioApiHooks: UIO[List[FeatureHook]] =
    state.zioApiHooksRef.get

  override def addApiHook(hook: dev.openfeature.sdk.Hook[_]): UIO[Unit] =
    ZIO.succeed(api.addHooks(hook))

  override def clearApiHooks: UIO[Unit] =
    ZIO.succeed(api.clearHooks()) <*
      ZIO
        .logWarning(
          "clearApiHooks called from a Shared instance; API-level hooks of every sibling client were cleared"
        )
        .when(!ownsApi)
        .unit

  // Provider hot-swap

  // Note: we reuse the existing `client` object because the Java SDK's Client
  // delegates to the provider registered with the API at evaluation time,
  // not the provider that was active when the client was created.
  override def setProvider(newProvider: OFFeatureProvider): IO[FeatureFlagError, Unit] =
    swapLock.withPermit {
      val swap = for {
        // Save old state for rollback on failure
        oldProvider <- providerRef.get
        oldName     <- ZIO.succeed(providerNameRef.get())
        // 1. Raise the gate BEFORE the NotReady transition: from here bridge events cannot drive the machine, so a
        //    queued Ready event from the OLD provider cannot mark the instance Ready mid-swap.
        _ <- ZIO.succeed(swapInProgress.set(true))
        _ <- applySignal(Signal.SwapStarted)
        // 2. Update refs BEFORE registering with Java SDK, so the event bridge (which fires PROVIDER_READY during
        //    initialization) and the identity guard see the new provider's metadata.
        newName = Option(newProvider.getMetadata).map(_.getName).getOrElse(FeatureFlags.UnknownProviderName)
        _ <- providerRef.set(newProvider)
        _ <- ZIO.succeed(providerNameRef.set(newName))
        // 3. Register new provider with Java SDK (shuts down old once the new one is READY), bounded by initTimeout.
        //    Non-blocking registration plus a bounded wait: `setProviderAndWait` would hold the SDK's API write
        //    lock across `initialize()`, so a hanging provider would wedge this swap, the rollback below and
        //    `api.shutdown()`. Phase 2 watch-point: re-verify READY detection against the SDK's spec-v0.9.0
        //    provider-event marker once shipped.
        _ <- ProviderRegistration
          .registerAndAwait(api, domain, newProvider, initTimeout)
          .mapError(e => FeatureFlagError.ProviderInitializationFailed(e))
          .tapError {
            // A timed-out swap is NOT rolled back. The new provider's `initialize()` is still running on the SDK's
            // executor, and if it completes later the SDK shuts down the provider that slot held before it — via that
            // provider's original state manager, which a re-registration would not reuse. Re-registering the old
            // instance now would therefore get it shut down and detached under us while we report Ready. Leave the
            // new provider bound (refs keep pointing at it) with status Error: a late READY recovers to Ready through
            // the event bridge, and a later setProvider works because no API lock is held.
            case FeatureFlagError.ProviderInitializationFailed(_: java.util.concurrent.TimeoutException) =>
              applySignal(Signal.SwapFailed) *>
                ZIO.logWarning(
                  s"setProvider: '$newName' did not finish initializing within $initTimeout; status is Error and the " +
                    s"previous provider '$oldName' was not restored (it is shut down if '$newName' becomes ready later)"
                )
            case _ =>
              // Roll back refs FIRST so the identity guard again reflects the previous provider and record the failure
              // via the machine, THEN reconcile the SDK client routing: the SDK binds the new provider before
              // init and does not revert on a failed init, so evaluations would keep hitting the failed provider while
              // our refs point back at the old one. Re-registering the old provider restores routing; on success we
              // restore Ready (the previous provider is serving again), on failure we log loudly and leave status Error.
              providerRef.set(oldProvider) *>
                ZIO.succeed(providerNameRef.set(oldName)) *>
                applySignal(Signal.SwapFailed) *>
                restoreSdkRegistration(oldProvider, newProvider).foldZIO(
                  e =>
                    ZIO.logErrorCause(
                      s"setProvider rollback: failed to re-register previous provider '$oldName'; status remains Error " +
                        "and evaluations may still route to the failed provider until a later setProvider succeeds",
                      Cause.fail(e)
                    ) *> cleanupUnboundFailedProvider(newProvider),
                  restored =>
                    // restored == false only when a concurrent sibling swap already won the slot (see
                    // restoreSdkRegistration): leave status Error and log a breadcrumb, don't fight the winner.
                    if (restored) applySignal(Signal.RollbackSucceeded).unit
                    else
                      ZIO.logInfo(
                        s"setProvider rollback: slot no longer bound to the failed provider (concurrent swap?); " +
                          s"leaving status Error for '$oldName'"
                      )
                )
          }
        // 4. Mark ready via the machine — the Java SDK bridge also fires PROVIDER_READY, but we set it explicitly for
        //    immediate visibility.
        _ <- applySignal(Signal.SwapSucceeded)
      } yield ()
      // The swap runs to its outcome even if the caller is interrupted: an interrupt mid-registration would skip
      // both the success signal and the rollback, leaving status NotReady with the refs on a provider that may or may not
      // end up bound. The region is bounded — registration and rollback each wait at most `initTimeout` — and
      // waiting for `swapLock` above stays interruptible.
      // The gate must drop on every exit (success, failure, interruption) or bridge events would be suppressed forever.
      swap.uninterruptible
        .ensuring(ZIO.succeed(swapInProgress.set(false)))
        .tapError {
          // A READY that landed between the timeout and the gate dropping was suppressed; pick it up now.
          case FeatureFlagError.ProviderInitializationFailed(_: java.util.concurrent.TimeoutException) =>
            reconcileLateReady(newProvider)
          case _ => ZIO.unit
        }
    }

  /** `fromAcquireAsync`'s swap: flip the registered [[SwitchableProvider]] to `next`, an already-initialized provider,
    * without going through the SDK — so status never leaves `Ready` and the SDK never refuses an evaluation. Serialized
    * with [[setProvider]] by `swapLock`. Returns `false`, changing nothing, when `switch` is no longer the registered
    * provider (a user `setProvider` replaced it first); the caller then owns `next`. On `true` the replaced delegate
    * has been shut down (bounded).
    */
  private[openfeature] def switchProvider(switch: SwitchableProvider, next: OFFeatureProvider): Task[Boolean] =
    swapLock
      .withPermit(
        providerRef.get.flatMap { registered =>
          if (registered ne switch) ZIO.none
          else
            ZIO.attempt {
              // The name first: the SDK stamps the switch's (now `next`'s) name on every event, and the identity guard
              // compares that against this ref. Setting it before the flip drops a straggler event from the old
              // delegate rather than one from `next`.
              val oldName = providerNameRef.get()
              providerNameRef.set(Option(next.getMetadata).map(_.getName).getOrElse(FeatureFlags.UnknownProviderName))
              try Some(switch.switchTo(next))
              catch {
                case e: Throwable =>
                  providerNameRef.set(oldName)
                  throw e
              }
            }
        }
      )
      .uninterruptible
      .flatMap {
        case Some(old) =>
          ProviderRegistration.boundedProviderShutdown(old, "fromAcquireAsync (replaced fallback)").as(true)
        case None => ZIO.succeed(false)
      }

  /** The API's evaluation context, which the SDK passes to `initialize()`; `fromAcquireAsync` initializes its composed
    * stack itself and passes the same.
    */
  private[openfeature] def apiEvaluationContext: dev.openfeature.sdk.EvaluationContext = api.getEvaluationContext

  private def reconcileLateReady(newProvider: OFFeatureProvider): UIO[Unit] =
    ZIO
      .attemptBlocking {
        val bound = domain.fold(api.getProvider())(d => api.getProvider(d))
        (bound eq newProvider) && {
          val st = client.getProviderState
          st == JavaProviderState.READY || st == JavaProviderState.STALE
        }
      }
      .flatMap(ready => ZIO.when(ready)(applySignal(Signal.EventReady)).unit)
      .catchAllCause(c => ZIO.logWarningCause("setProvider: could not re-check a timed-out provider's state", c))

  /** Take the SDK's state for this instance's slot once `provider` is registered there. A registration that reuses an
    * existing one — the same provider already initialized, or failed, for another domain — sends no lifecycle event, so
    * without this the status would wait out the init timeout, and the watchdog would then shut down a provider other
    * domains are serving. A fresh registration is still `NOT_READY` here and is left to its events; a state that an
    * event also reports is applied twice, which the machine absorbs.
    */
  private[openfeature] def reconcileRegistration(provider: OFFeatureProvider): UIO[Unit] =
    ZIO
      .attemptBlocking {
        val bound = domain.fold(api.getProvider())(d => api.getProvider(d))
        // `==`, as the SDK matches a provider to its existing registration.
        if (bound == provider) Some(client.getProviderState) else None
      }
      .flatMap {
        // Status only: the onReady latch counts READY deliveries, which come from the bridge.
        case Some(JavaProviderState.READY) => applySignal(Signal.EventReady).unit
        case Some(JavaProviderState.STALE) => applySignal(Signal.EventStale).unit
        case Some(JavaProviderState.ERROR) => applySignal(Signal.EventError(false)).unit
        case Some(JavaProviderState.FATAL) =>
          applySignal(Signal.EventError(true)).flatMap(t =>
            ZIO.succeed(onReady.foreach(_.countDown())).when(t.contains(ProviderStatus.Fatal))
          )
        // NOT_READY: a fresh registration, which its own events will move on.
        case _ => ZIO.unit
      }
      .unit
      .catchAllCause(c => ZIO.logWarningCause("could not read the SDK state of a new registration", c))

  // Shutdown API (spec 1.6.1, 1.6.2)

  override def shutdown: UIO[Unit] =
    // ShuttingDown rejects evaluations for the duration of the teardown (checkProviderStatus); the terminal state
    // after teardown is NotReady. The machine keeps both terminal: while ShuttingDown no bridge event transitions,
    // and ShutdownCompleted makes the final NotReady stick against a late event from the dying provider.
    // Uninterruptible: an interrupt halfway would leave ShuttingDown for good, with the API half torn down. The
    // region is bounded — the API shutdown waits at most its own timeout.
    // Serialized with swaps by `swapLock`: a swap's outcome signals are never gated, so one that settled after the
    // teardown would move the status out of the final NotReady. A shutdown during a swap therefore waits for it to
    // settle (each swap is bounded by `initTimeout`); waiting for the permit stays interruptible, as in `setProvider`.
    swapLock.withPermit(
      (applySignal(Signal.ShutdownStarted) *>
        // Deregister the bridge handlers BEFORE tearing down the API so no emitter thread races the teardown (the scope
        // finalizer also calls this — the SDK's removeHandler is idempotent). It takes the SDK's lock, so off the core pool.
        ZIO.attemptBlocking(bridgeDeregister.get()()).ignore *>
        state.hooksRef.set(List.empty) *>
        state.zioApiHooksRef.set(List.empty) *>
        state.globalContextRef.set(EvaluationContext.empty) *>
        state.clientContextRef.set(EvaluationContext.empty) *>
        state.trackRecorder.set(Chunk.empty) *>
        state.eventHub.shutdown *>
        // Only tear down the provider/API when this instance solely owns the API (the sole-owner factories). A
        // non-owning instance — a registry/domain client that shares its API, and possibly its provider, with sibling
        // clients — must NOT shut the API or the provider: both belong to whatever owns the API (e.g. the registry,
        // which shuts every registered provider exactly once when its own scope closes). Shutting the API here would
        // kill sibling clients; shutting the provider directly would kill siblings that share it (the
        // registry falls back to one shared default provider) and double-shut a provider the API still has registered.
        // A non-owning `shutdown` therefore releases only this instance's own state (status/hooks/contexts/event hub,
        // torn down above); retire such clients via whatever owns the API (e.g. the registry).
        ZIO.when(ownsApi)(ProviderRegistration.boundedApiShutdown(api)) *>
        applySignal(Signal.ShutdownCompleted).unit).uninterruptible
    )

  // Tracking API

  override def track(eventName: String): IO[FeatureFlagError, Unit] =
    trackImpl(eventName, EvaluationContext.empty, None)

  override def track(eventName: String, context: EvaluationContext): IO[FeatureFlagError, Unit] =
    trackImpl(eventName, context, None)

  override def track(eventName: String, details: TrackingEventDetails): IO[FeatureFlagError, Unit] =
    trackImpl(eventName, EvaluationContext.empty, Some(details))

  override def track(
    eventName: String,
    context: EvaluationContext,
    details: TrackingEventDetails
  ): IO[FeatureFlagError, Unit] =
    trackImpl(eventName, context, Some(details))

  private def trackImpl(
    eventName: String,
    context: EvaluationContext,
    details: Option[TrackingEventDetails]
  ): IO[FeatureFlagError, Unit] =
    // Convert before anything is recorded or sent: an attribute the SDK cannot represent is a caller mistake, so it
    // fails here as InvalidContext rather than inside the SDK call, where `classify` would blame the provider.
    ZIO
      .foreach(details)(d =>
        ZIO.fromEither(TrackingConverter.toOpenFeature(d)).mapError(FeatureFlagError.InvalidContext(_))
      )
      .flatMap { ofDetails =>
        effectiveContext(context).flatMap { merged =>
          recordTrack(eventName, merged, details) *>
            ZIO
              .attemptBlocking {
                val ofContext = toJavaContext(merged)
                ofDetails match {
                  case Some(d) => client.track(eventName, ofContext, d)
                  case None    => client.track(eventName, ofContext)
                }
              }
              .mapError(e => FeatureFlagError.classify(e))
        }
      }

  // The recorder is bounded: when full, the oldest entries are dropped so long-running apps that
  // call `track` per request don't accumulate events (and their merged contexts) without limit.
  private def recordTrack(
    eventName: String,
    merged: EvaluationContext,
    details: Option[TrackingEventDetails]
  ): UIO[Unit] =
    ZIO
      .when(recordTracking)(state.trackRecorder.update { rec =>
        val appended = rec :+ ((eventName, merged, details))
        val overflow = appended.length - FeatureFlagsState.MaxTrackedEvents
        if (overflow > 0) appended.drop(overflow) else appended
      })
      .unit

  @scala.annotation.nowarn("cat=deprecation")
  override def trackedEvents: UIO[List[(String, EvaluationContext, Option[TrackingEventDetails])]] =
    state.trackRecorder.get.map(_.toList)

}

private[openfeature] object FeatureFlagsLive {

  // Per-call states for the abandoned-call cap, see `withTimeout`.
  private[openfeature] val CallRunning   = 0
  private[openfeature] val CallDone      = 1
  private[openfeature] val CallAbandoned = 2
}
