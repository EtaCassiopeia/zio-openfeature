package zio.openfeature

import zio._
import zio.stream._
import zio.openfeature.internal.{
  ClientEvaluator,
  ContextConverter,
  ErrorCodeConverter,
  FeatureFlagsState,
  ProviderStatusMachine
}
import zio.openfeature.internal.ProviderStatusMachine.Signal
import dev.openfeature.sdk.{
  Client => OFClient,
  FeatureProvider => OFFeatureProvider,
  ProviderEvent => JavaProviderEvent,
  EventDetails,
  EventProviderAccess,
  FlagEvaluationDetails,
  MutableTrackingEventDetails,
  OpenFeatureAPI
}

import scala.jdk.CollectionConverters._

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
  onReady: Option[java.util.concurrent.CountDownLatch] = None,
  evaluationTimeout: Option[Duration] = Some(FeatureFlags.DefaultEvaluationTimeout)
) extends FeatureFlags {

  // True while `setProvider` holds the swap lock and is re-registering the provider. Bridge events must not drive
  // the status machine during that window: a queued event from the OLD provider would clobber the swap's own
  // outcome. The successful swap sets Ready explicitly at the end.
  private val swapInProgress = new java.util.concurrent.atomic.AtomicBoolean(false)

  // Set once the provider has ever been usable (Ready/Stale). Gates the init watchdog: a provider that was Ready and
  // later dipped to a transient Error must never be escalated to Fatal (the core #244 bug).
  private val everReady = new java.util.concurrent.atomic.AtomicBoolean(false)

  // Set when an explicit `shutdown` has run to completion: the final NotReady is terminal for bridge events; only
  // setProvider (which installs a new provider) clears it.
  private val shutdownCompleted = new java.util.concurrent.atomic.AtomicBoolean(false)

  // Set by startEventBridge; invoked by `shutdown` BEFORE teardown (so no emitter thread races it) and by the scope
  // finalizer (idempotent — removeHandler on an already-removed handler is a no-op in the SDK, so double-invocation
  // is safe).
  private val bridgeDeregister =
    new java.util.concurrent.atomic.AtomicReference[() => Unit](() => ())

  /** The single door to statusRef (#244). Every status write except the sync-build seed and the testkit's shared-ref
    * control surface goes through here. `SubscriptionRef` is a `Ref.Synchronized`, so `modify` serializes
    * read-decide-write against every other signal. Returns Some(next) iff a transition happened, so callers can attach
    * transition-gated side effects (watchdog shutdown, latch release).
    */
  private def applySignal(signal: Signal): UIO[Option[ProviderStatus]] =
    state.statusRef.modify { current =>
      val ctx = ProviderStatusMachine.Context(everReady.get(), swapInProgress.get(), shutdownCompleted.get())
      ProviderStatusMachine.transition(current, signal, ctx) match {
        case Some(next) =>
          // Update the linearization-point flags INSIDE the serialized modify (SubscriptionRef is a
          // Ref.Synchronized, so the function runs under exclusive access). The writes are idempotent and depend only
          // on `signal`/`next`, so there is no read-stale window for a concurrent applySignal — closing the tap-lag
          // gap where a pending `everReady` write could let the watchdog Fatal an already-Ready provider.
          if (next == ProviderStatus.Ready || next == ProviderStatus.Stale) everReady.set(true)
          signal match {
            case Signal.ShutdownCompleted => shutdownCompleted.set(true)
            case Signal.SwapStarted       => shutdownCompleted.set(false)
            case _                        => ()
          }
          (Some(next), next)
        case None => (None, current)
      }
    }

  // The SDK stamps every dispatched event with the emitting provider's metadata name — or NULL when the provider has
  // no metadata/name (verified sdk-1.21.0, OpenFeatureAPI.runHandlersForProvider). An event whose name differs from
  // the CURRENT provider's is a stale event from a replaced/failed provider still queued on the SDK's emitter
  // executor: it must not drive the status machine. We drop ONLY when both identities are known and differ. A null
  // event name (unnamed provider) OR an indeterminate current name fails open — we cannot attribute the event to a
  // *different* provider, so it must be allowed to drive status. The current name is indeterminate when it is the
  // "unknown" fallback the factories stamp for metadata-less providers, or for a provider like the SDK's MultiProvider
  // whose metadata name only materializes inside initialize() (so the async build captured "unknown", while the
  // provider's own later events carry the real name). Same-named swaps are indistinguishable — see "Known limitation"
  // in #244.
  //
  // Phase 2 watch-point (#340): re-verify this identity-guard behaviour once the OpenFeature Java SDK ships the
  // spec-v0.9.0 provider-event marker — it may offer a more direct way to attribute an event to its emitting provider.
  private def fromCurrentProvider(details: EventDetails): Boolean = {
    val eventName   = details.getProviderName
    val currentName = providerNameRef.get()
    eventName == null ||
    currentName == null ||
    currentName == FeatureFlags.UnknownProviderName ||
    eventName == currentName
  }

  // Truthful payloads: publish under the name the SDK stamped on the event (the actual emitter), falling back to the
  // current provider only when the event carries none.
  private def eventProviderMetadata(details: EventDetails): ProviderMetadata = {
    val name = details.getProviderName
    ProviderMetadata(if (name == null) providerNameRef.get() else name)
  }

  private def extractEventMetadata(details: EventDetails): FlagMetadata =
    try {
      val javaMeta = details.getEventMetadata
      if (javaMeta == null || javaMeta.isEmpty) FlagMetadata.empty
      else convertImmutableMetadata(javaMeta)
    } catch {
      case _: Exception => FlagMetadata.empty
    }

  // Bridge event bodies as pure effects (#244), so tests can drive them directly with a builder-constructed
  // EventDetails. The Java-side Consumers registered in startEventBridge are thin wrappers that run these via
  // `runHandler`. Events are ALWAYS published for observers; identity/machine gating applies only to the status
  // transition and the onReady latch.

  private[openfeature] def onReadyEvent(details: EventDetails): UIO[Unit] = {
    val em      = extractEventMetadata(details)
    val current = fromCurrentProvider(details)
    // Refresh a still-"unknown" provider name from the event's stamped name when the READY is from the current
    // provider. A lazy-metadata provider (notably the SDK's MultiProvider) only builds its metadata name inside
    // initialize(), so the async build captured the "unknown" fallback; the provider's own READY carries the real
    // name. Refreshing restores accurate providerMetadata and re-enables the event-identity guard for such providers
    // (#297). `compareAndSet` from the fallback only: it never clobbers a real name set by a concurrent swap.
    val refreshName = ZIO.succeed {
      val eventName = details.getProviderName
      if (current && eventName != null)
        providerNameRef.compareAndSet(FeatureFlags.UnknownProviderName, eventName)
      ()
    }
    refreshName *>
      (if (current) applySignal(Signal.EventReady) else ZIO.none) *>
      state.eventHub.publish(ProviderEvent.Ready(eventProviderMetadata(details), em)).unit *>
      ZIO.succeed(if (current) onReady.foreach(_.countDown()))
  }

  private[openfeature] def onErrorEvent(details: EventDetails): UIO[Unit] = {
    val error     = new RuntimeException(Option(details.getMessage).getOrElse("Provider error"))
    val errorCode = Option(details.getErrorCode).map(ErrorCodeConverter.fromJava)
    val em        = extractEventMetadata(details)
    val fatal     = errorCode.contains(ErrorCode.ProviderFatal)
    (if (fromCurrentProvider(details)) applySignal(Signal.EventError(fatal)) else ZIO.none).flatMap { transitioned =>
      // A Fatal transition must release latch waiters (registry handshake, awaitReady seeds) so they observe Fatal
      // instead of stalling the full timeout.
      ZIO.succeed(onReady.foreach(_.countDown())).when(transitioned.contains(ProviderStatus.Fatal))
    } *>
      state.eventHub
        .publish(ProviderEvent.Error(error, eventProviderMetadata(details), errorCode, Option(details.getMessage), em))
        .unit
  }

  private[openfeature] def onStaleEvent(details: EventDetails): UIO[Unit] = {
    val reason = Option(details.getMessage).getOrElse("Provider stale")
    val em     = extractEventMetadata(details)
    (if (fromCurrentProvider(details)) applySignal(Signal.EventStale) else ZIO.none) *>
      state.eventHub.publish(ProviderEvent.Stale(reason, eventProviderMetadata(details), em)).unit
  }

  // Cache the Scala->Java context conversion by object identity. In the common case every per-call context layer
  // (transaction/client/fiber-local/invocation) is empty, so the merged context IS the (unchanged) global context
  // object — converting it once and reusing it avoids a fresh MutableContext plus one Value per attribute on every
  // call. Keyed on identity: a different context (after `setGlobalContext`, or a non-empty invocation layer) misses and
  // is re-converted, so no explicit invalidation is needed. Reuse is safe because the OpenFeature contract treats the
  // context passed to a provider as read-only.
  private val javaContextCache =
    new java.util.concurrent.atomic.AtomicReference[(EvaluationContext, dev.openfeature.sdk.EvaluationContext)](null)

  private def toJavaContext(context: EvaluationContext): dev.openfeature.sdk.EvaluationContext = {
    val cached = javaContextCache.get()
    if (cached != null && (cached._1 eq context)) cached._2
    else {
      val converted = ContextConverter.toOpenFeature(context)
      javaContextCache.set((context, converted))
      converted
    }
  }

  // Bridge Java SDK provider events to ZIO event system
  private[openfeature] def startEventBridge: ZIO[Scope, Nothing, Unit] = {
    // Run an event-publish effect from a Java SDK thread. Failures are logged via the ZIO logger
    // rather than thrown, so the Java SDK's event dispatch thread is never killed by a defect.
    def runHandler(runtime: Runtime[Any], label: String)(effect: UIO[Unit]): Unit =
      Unsafe.unsafe { implicit u =>
        runtime.unsafe
          .run(effect.catchAllCause(c => ZIO.logErrorCause(s"event bridge: $label", c)))
          .getOrElse(_ => ())
      }

    ZIO.runtime[Any].flatMap { runtime =>
      // `suspendSucceed` defers the handler bodies (metadata extraction, identity check, payload construction) into the
      // effect so a synchronous throw while building them on the Java emitter thread becomes a defect caught by
      // `runHandler`'s `catchAllCause`, rather than escaping to kill the SDK's dispatch thread.
      val readyHandler: java.util.function.Consumer[EventDetails] =
        details => runHandler(runtime, "PROVIDER_READY")(ZIO.suspendSucceed(onReadyEvent(details)))

      val errorHandler: java.util.function.Consumer[EventDetails] =
        details => runHandler(runtime, "PROVIDER_ERROR")(ZIO.suspendSucceed(onErrorEvent(details)))

      val staleHandler: java.util.function.Consumer[EventDetails] =
        details => runHandler(runtime, "PROVIDER_STALE")(ZIO.suspendSucceed(onStaleEvent(details)))

      val configHandler: java.util.function.Consumer[EventDetails] = details =>
        runHandler(runtime, "PROVIDER_CONFIGURATION_CHANGED")(
          ZIO.suspendSucceed {
            val flags = Option(details.getFlagsChanged)
              .map(_.asScala.toSet)
              .getOrElse(Set.empty[String])
            val em = extractEventMetadata(details)
            state.eventHub
              .publish(ProviderEvent.ConfigurationChanged(flags, eventProviderMetadata(details), em))
              .unit
          }
        )

      // Deregistration thunk stored on the instance so `shutdown` can remove the handlers BEFORE tearing down the API
      // (no emitter thread can then race the teardown); the scope finalizer keeps calling it too (idempotent).
      val deregister: () => Unit = () => {
        client.removeHandler(JavaProviderEvent.PROVIDER_READY, readyHandler)
        client.removeHandler(JavaProviderEvent.PROVIDER_ERROR, errorHandler)
        client.removeHandler(JavaProviderEvent.PROVIDER_STALE, staleHandler)
        client.removeHandler(JavaProviderEvent.PROVIDER_CONFIGURATION_CHANGED, configHandler)
        ()
      }

      for {
        _ <- ZIO.succeed {
          client.on(JavaProviderEvent.PROVIDER_READY, readyHandler)
          client.on(JavaProviderEvent.PROVIDER_ERROR, errorHandler)
          client.on(JavaProviderEvent.PROVIDER_STALE, staleHandler)
          client.on(JavaProviderEvent.PROVIDER_CONFIGURATION_CHANGED, configHandler)
          bridgeDeregister.set(deregister)
          ()
        }
        _ <- ZIO.addFinalizer(ZIO.attempt(deregister()).ignore)
      } yield ()
    }
  }

  // Context merges per OpenFeature spec: API (global) -> Transaction -> Client -> FiberLocal -> Invocation
  private def effectiveContext(invocation: EvaluationContext): UIO[EvaluationContext] =
    for {
      global      <- state.globalContextRef.get
      clientCtx   <- state.clientContextRef.get
      fiberLocal  <- state.fiberContextRef.get
      transaction <- state.transactionRef.get
      txContext = transaction.map(_.context).getOrElse(EvaluationContext.empty)
    } yield global
      .merge(txContext)
      .merge(clientCtx)
      .merge(fiberLocal)
      .merge(invocation)

  private def runWithHooks[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    evaluate: EvaluationContext => IO[FeatureFlagError, FlagResolution[A]],
    extraHooks: List[FeatureHook] = Nil,
    initialHints: HookHints = HookHints.empty
  ): IO[FeatureFlagError, FlagResolution[A]] =
    for {
      apiHooks     <- state.zioApiHooksRef.get
      currentHooks <- state.hooksRef.get
      pName        <- ZIO.succeed(providerNameRef.get())
      flagType = FlagValueType.fromFlagType[A]
      // Order per spec §4.4.1: API -> Client -> Invocation. Provider hooks run inside the Java SDK call.
      // Pre-filtering by flag type (spec 4.4.2.1) lets evaluations with no applicable hooks skip the
      // pipeline entirely; the composed hook's own per-stage filter then has nothing left to drop.
      allHooks   = (apiHooks ++ currentHooks ++ extraHooks).filter(_.supportedFlagTypes.contains(flagType))
      metadata   = ProviderMetadata(pName)
      clientMeta = ClientMetadata(domain, version)
      hookCtx = HookContext(
        flagKey = key,
        flagType = flagType,
        defaultValue = default,
        evaluationContext = context,
        clientMetadata = clientMeta,
        providerMetadata = metadata
      )
      result <-
        if (allHooks.isEmpty) evaluate(context)
        else runHookPipeline(hookCtx, allHooks, context, initialHints, evaluate)
    } yield result

  private def runHookPipeline[A](
    hookCtx: HookContext,
    hooks: List[FeatureHook],
    context: EvaluationContext,
    initialHints: HookHints,
    evaluate: EvaluationContext => IO[FeatureFlagError, FlagResolution[A]]
  ): IO[FeatureFlagError, FlagResolution[A]] = {
    val composedHook = FeatureHook.compose(hooks)

    // `finallyAfter` must run on EVERY exit — including an interruption of `before` itself — so it is attached as one
    // outer `onExit` wrapping the whole pipeline (an `onExit` nested inside the foldCauseZIO branches would be skipped
    // when `before` is interrupted before that branch's effect is even entered). Per spec §4.3.5-4.3.8 the finally
    // stage must observe the context as modified by the before hooks; that modified `(ctx, hints)` is recorded in a Ref
    // once `before` succeeds, so the outer finalizer uses it, falling back to the original context when `before` never
    // completed.
    Ref.make((hookCtx, initialHints)).flatMap { finallyCtxRef =>
      composedHook
        .before(hookCtx, initialHints)
        .foldCauseZIO(
          // `before` is a UIO, so a non-success cause is a Die or an Interrupt. Only a Die is abnormal execution that
          // runs the `error` stage (spec §4.3.8/§4.4.7) — a pure interruption (timeout / scope-close) is a cancellation,
          // not a hook failure, so it must not be reported to `error`. The original cause is always preserved: even if
          // the `error` stage itself defects, its cause is combined with (never replaces) `beforeCause`.
          beforeCause =>
            beforeCause.dieOption match {
              case Some(defect) =>
                composedHook
                  .error(hookCtx, FeatureFlagError.ProviderError(defect), initialHints)
                  .foldCauseZIO(
                    errCause => ZIO.refailCause(beforeCause ++ errCause),
                    _ => ZIO.refailCause(beforeCause)
                  )
              case None => ZIO.refailCause(beforeCause)
            },
          beforeResult => {
            val effectiveCtx = beforeResult.getOrElse(context)
            val hints        = initialHints
            val stageCtx     = hookCtx.copy(evaluationContext = effectiveCtx)
            finallyCtxRef.set((stageCtx, hints)) *>
              evaluate(effectiveCtx)
                .tapBoth(
                  err => composedHook.error(stageCtx, err, hints),
                  // A returned resolution can still be *abnormal* execution — it carries an error code (FLAG_NOT_FOUND,
                  // TYPE_MISMATCH, ...) that the Java SDK surfaces as a default value plus a code rather than a throw.
                  // Per spec §4.3.6/§4.4.6 that is the `error` stage's domain, NOT `after`: run `error` for an
                  // error-code resolution and `after` only for a clean one.
                  res =>
                    res.errorCode match {
                      case Some(code) =>
                        composedHook.error(stageCtx, errorFromResolution(res.flagKey, code, res.errorMessage), hints)
                      case None =>
                        composedHook.after(stageCtx, res, hints)
                    }
                )
          }
        )
        .onExit { exit =>
          finallyCtxRef.get.flatMap { case (ctx, hints) =>
            val details: Option[FlagResolution[_]] = exit.foldExit(_ => None, res => Some(res))
            composedHook.finallyAfter(ctx, details, hints)
          }
        }
    }
  }

  /** Reconstruct a typed error from a resolution that carries an error code, so the `error` hook stage can observe a
    * provider-reported failure (FLAG_NOT_FOUND, TYPE_MISMATCH, ...) that was returned as a default value rather than
    * thrown. `ProviderNotReady`/`ProviderFatal` codes never reach here — they fail the effect upstream.
    */
  private def errorFromResolution(key: String, code: ErrorCode, message: Option[String]): FeatureFlagError =
    code match {
      case ErrorCode.FlagNotFound => FeatureFlagError.FlagNotFound(key)
      case ErrorCode.TypeMismatch => FeatureFlagError.TypeMismatch(key, "unknown", message.getOrElse("unknown"))
      case ErrorCode.ParseError =>
        FeatureFlagError.ParseError(key, new RuntimeException(message.getOrElse("parse error")))
      case ErrorCode.TargetingKeyMissing => FeatureFlagError.TargetingKeyMissing(key)
      case ErrorCode.InvalidContext      => FeatureFlagError.InvalidContext(message.getOrElse("invalid context"))
      case ErrorCode.ProviderNotReady    => FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady)
      case ErrorCode.ProviderFatal       => FeatureFlagError.ProviderFatal
      case ErrorCode.General => FeatureFlagError.ProviderError(new RuntimeException(message.getOrElse("general error")))
    }

  private def checkProviderStatus: IO[FeatureFlagError, Unit] =
    providerStatus.flatMap {
      case ProviderStatus.Fatal    => ZIO.fail(FeatureFlagError.ProviderFatal)
      case ProviderStatus.NotReady => ZIO.fail(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady))
      case ProviderStatus.ShuttingDown =>
        ZIO.fail(FeatureFlagError.ProviderNotReady(ProviderStatus.ShuttingDown))
      // Error/Ready/Stale proceed: fast-failing only NOT_READY and FATAL is this library's deliberate policy — spec
      // v0.9.0 removed the requirements that used to mandate this and renumbered the equivalent scenarios to
      // @spec-2.2.7 (+ @spec-1.4.10) as *permitted*, not required, behaviour. A provider in ERROR is typically a
      // transient, recoverable state and commonly still serves cached values — let the evaluation reach it (the
      // provider serves or errors on its own) rather than turning one PROVIDER_ERROR into a total outage.
      case _ => Exit.unit
    }

  // Context is already merged by evaluateWithDetails before entering the hook pipeline
  private def evaluateFlag[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    timeout: Option[Duration] = None
  ): IO[FeatureFlagError, FlagResolution[A]] =
    // The provider-readiness gate (`checkProviderStatus`) applies only where the value must actually come from the
    // provider. A transaction override or a cached evaluation resolves purely locally and MUST still succeed while the
    // provider is NotReady / Fatal / shutting down — that fallback role is the whole point of overrides (spec:
    // deterministic tests without a live provider, and forcing known-safe values while a provider is down). So the gate
    // is pushed down onto the provider-hitting paths (`evaluateFromClient` here, `evaluateAndCache` in
    // `evaluateWithTransaction`) rather than run up front.
    state.transactionRef.get.flatMap {
      case Some(ts) => evaluateWithTransaction(key, default, context, ts, timeout)
      case None     => checkProviderStatus *> evaluateFromClient(key, default, context, timeout)
    }

  private def evaluateWithTransaction[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    txState: TransactionState,
    timeout: Option[Duration] = None
  ): IO[FeatureFlagError, FlagResolution[A]] =
    // First check for explicit overrides
    txState.getOverride(key) match {
      case Some(overrideValue) =>
        val flagType = FlagType[A]
        // NOTE: `decode` is used here against a value the CALLER supplied, not a provider wire value. For the built-ins
        // those coincide, but for a custom type whose wire form differs from its domain form (see `FlagType.wireType`)
        // an override must be given as the WIRE value — `withOverride(key, "dual_write")`, not the domain enum.
        // Pre-existing for object-backed custom types; #356 makes it reachable for scalar-backed ones too.
        flagType.decode(overrideValue) match {
          case Right(decoded) =>
            val resolution = FlagResolution.cached(key, decoded)
            FlagEvaluation.overridden(key, decoded).flatMap { eval =>
              txState.record(eval).as(resolution)
            }
          case Left(_) =>
            ZIO.fail(
              FeatureFlagError.OverrideTypeMismatch(
                key,
                flagType.typeName,
                overrideValue.getClass.getSimpleName
              )
            )
        }

      case None =>
        // Check for cached evaluation from previous call in this transaction. A usable cache hit resolves locally; only
        // the paths that must reach the provider (`evaluateAndCache`) run behind the readiness gate.
        txState.getCachedEvaluation(key).flatMap {
          case Some(cached) =>
            val flagType = FlagType[A]
            // `cached.value` is the DOMAIN value (see `FlagEvaluation.evaluated`), so this decode round-trips a
            // domain value rather than a wire value. For the built-ins those coincide; for a custom type whose wire
            // form differs it returns Left and the evaluation falls through to the provider below — i.e. the
            // in-transaction cache does not hit for such types. Pre-existing (object-backed custom types have the
            // same behaviour); #356 makes it reachable for scalar-backed types too. Correctness is unaffected —
            // a re-read still returns the right value — only the caching optimisation is lost.
            flagType.decode(cached.value) match {
              case Right(decoded) =>
                ZIO.succeed(FlagResolution.cached(key, decoded))
              case Left(_) =>
                // Type mismatch with cached value - re-evaluate from client
                checkProviderStatus *> evaluateAndCache(key, default, context, txState, timeout)
            }
          case None =>
            checkProviderStatus *> evaluateAndCache(key, default, context, txState, timeout)
        }
    }

  private def evaluateAndCache[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    txState: TransactionState,
    timeout: Option[Duration] = None
  ): IO[FeatureFlagError, FlagResolution[A]] =
    for {
      resolution <- evaluateFromClient(key, default, context, timeout)
      eval       <- zio.openfeature.FlagEvaluation.evaluated(key, resolution)
      _          <- txState.record(eval)
    } yield resolution

  private def evaluateFromClient[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    timeout: Option[Duration] = None
  ): IO[FeatureFlagError, FlagResolution[A]] = {
    val flagType  = FlagType[A]
    val ofContext = toJavaContext(context)

    def withTimeout[B](effect: Task[B]): Task[B] =
      timeout match {
        case Some(d) =>
          effect.disconnect
            .timeoutFail(new java.util.concurrent.TimeoutException(s"Evaluation of '$key' timed out after $d"))(d)
        case None => effect
      }

    val evaluation: IO[FeatureFlagError, FlagResolution[A]] =
      ClientEvaluator.evaluateStandard[A](flagType, client, key, default, ofContext) match {
        case Some(erased) =>
          withTimeout(erased.task)
            .mapError(e => FeatureFlagError.classify(e))
            .flatMap { details =>
              erased.extract(details) match {
                case Right(value) =>
                  toFlagResolution(key, details).map(r => r.copy(value = value))
                case Left(message) =>
                  // The provider answered, but its value is not a valid `A` — e.g. an unknown enum variant on a
                  // string-backed type. A typed failure, not a silent fall back to the default: the total tier
                  // (`*OrDefault`) still absorbs it upstream for callers who opted into that.
                  ZIO.fail(FeatureFlagError.TypeMismatch(key, flagType.typeName, message))
              }
            }

        // Deliberately `typeName`, not `wireType`: this branch casts the default to `Map[String, Any]`, which is only
        // sound for the built-in object instance. A *custom* type whose wire form is an object has a domain `typeName`
        // and must fall through to the decode branch below instead.
        case None if flagType.typeName == "Object" =>
          withTimeout(
            ZIO.attemptBlocking {
              val defaultValue = new dev.openfeature.sdk.Value(
                dev.openfeature.sdk.Structure.mapToStructure(
                  default.asInstanceOf[Map[String, Any]].map { case (k, v) => k -> anyToObject(v) }.asJava
                )
              )
              client.getObjectDetails(key, defaultValue, ofContext)
            }
          ).mapError(e => FeatureFlagError.classify(e))
            .flatMap { details =>
              val value = valueToMap(details.getValue)
              toFlagMetadata(details.getFlagMetadata).map { metadata =>
                FlagResolution(
                  value = value.asInstanceOf[A],
                  variant = Option(details.getVariant),
                  reason = toResolutionReason(details.getReason),
                  metadata = metadata,
                  flagKey = key,
                  errorCode = Option(details.getErrorCode).map(ErrorCodeConverter.fromJava),
                  errorMessage = Option(details.getErrorMessage)
                )
              }
            }

        case None =>
          // Custom type - try to decode from object
          withTimeout(
            ZIO.attemptBlocking {
              client.getObjectDetails(key, new dev.openfeature.sdk.Value(), ofContext)
            }
          ).mapError(e => FeatureFlagError.classify(e))
            .flatMap { details =>
              valueToAny(details.getValue) match {
                case Some(rawValue) =>
                  flagType.decode(rawValue) match {
                    case Right(decoded) =>
                      toFlagMetadata(details.getFlagMetadata).map { metadata =>
                        FlagResolution(
                          value = decoded,
                          variant = Option(details.getVariant),
                          reason = toResolutionReason(details.getReason),
                          metadata = metadata,
                          flagKey = key,
                          errorCode = Option(details.getErrorCode).map(ErrorCodeConverter.fromJava),
                          errorMessage = Option(details.getErrorMessage)
                        )
                      }
                    case Left(_) =>
                      ZIO.fail(FeatureFlagError.TypeMismatch(key, flagType.typeName, "Object"))
                  }
                case None =>
                  ZIO.fail(FeatureFlagError.TypeMismatch(key, flagType.typeName, "null"))
              }
            }
      }

    // Check resolution error codes for provider-level failures (handles TOCTOU race
    // where checkProviderStatus passes but the Java SDK's internal state is stale)
    evaluation.flatMap { resolution =>
      resolution.errorCode match {
        case Some(ErrorCode.ProviderNotReady) =>
          ZIO.fail(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady))
        case Some(ErrorCode.ProviderFatal) =>
          ZIO.fail(FeatureFlagError.ProviderFatal)
        case _ =>
          ZIO.succeed(resolution)
      }
    }
  }

  private def toFlagResolution[A](key: String, details: FlagEvaluationDetails[A]): UIO[FlagResolution[A]] =
    toFlagMetadata(details.getFlagMetadata).map { metadata =>
      FlagResolution(
        value = details.getValue,
        variant = Option(details.getVariant),
        reason = toResolutionReason(details.getReason),
        metadata = metadata,
        flagKey = key,
        errorCode = Option(details.getErrorCode).map(ErrorCodeConverter.fromJava),
        errorMessage = Option(details.getErrorMessage)
      )
    }

  private def toResolutionReason(reason: String): ResolutionReason =
    Option(reason) match {
      // `Unknown` is reserved for a genuinely absent reason. A non-null but unrecognized reason is a provider-specific
      // one and must be passed through verbatim (spec 1.4.7), not collapsed — otherwise Optimizely/flagd custom reasons
      // are lost to hooks, analytics, and debugging.
      case None => ResolutionReason.Unknown
      case Some(r) =>
        r.toUpperCase match {
          case "STATIC"          => ResolutionReason.Static
          case "DEFAULT"         => ResolutionReason.Default
          case "TARGETING_MATCH" => ResolutionReason.TargetingMatch
          case "SPLIT"           => ResolutionReason.Split
          case "CACHED"          => ResolutionReason.Cached
          case "DISABLED"        => ResolutionReason.Disabled
          case "STALE"           => ResolutionReason.Stale
          case "ERROR"           => ResolutionReason.Error
          case _                 => ResolutionReason.Other(r)
        }
    }

  private def convertImmutableMetadata(javaMeta: dev.openfeature.sdk.ImmutableMetadata): FlagMetadata = {
    val javaMap = javaMeta.asUnmodifiableMap()
    if (javaMap == null || javaMap.isEmpty) FlagMetadata.empty
    else {
      val entries = javaMap.asScala.collect {
        case (k, v: java.lang.Boolean) => k -> MetadataValue.BooleanValue(v.booleanValue())
        case (k, v: java.lang.Integer) => k -> MetadataValue.IntValue(v.intValue())
        case (k, v: java.lang.Long)    => k -> MetadataValue.LongValue(v.longValue())
        case (k, v: java.lang.Float)   => k -> MetadataValue.FloatValue(v.floatValue())
        case (k, v: java.lang.Double)  => k -> MetadataValue.DoubleValue(v.doubleValue())
        case (k, v: String)            => k -> MetadataValue.StringValue(v)
        case (k, v) if v != null       => k -> MetadataValue.StringValue(v.toString)
      }.toMap
      FlagMetadata(entries)
    }
  }

  private def toFlagMetadata(metadata: dev.openfeature.sdk.ImmutableMetadata): UIO[FlagMetadata] =
    if (metadata == null || metadata.isEmpty) ZIO.succeed(FlagMetadata.empty)
    else
      ZIO
        .attempt(convertImmutableMetadata(metadata))
        .catchAll(e => ZIO.logWarning(s"Failed to parse flag metadata: ${e.getMessage}").as(FlagMetadata.empty))

  private def anyToObject(value: Any): Object = value match {
    case b: Boolean    => java.lang.Boolean.valueOf(b)
    case s: String     => s
    case i: Int        => java.lang.Integer.valueOf(i)
    case l: Long       => java.lang.Long.valueOf(l)
    case d: Double     => java.lang.Double.valueOf(d)
    case f: Float      => java.lang.Float.valueOf(f)
    case list: List[_] => list.map(anyToObject).asJava
    case map: Map[_, _] =>
      map.asInstanceOf[Map[String, Any]].map { case (k, v) => k -> anyToObject(v) }.asJava
    case null  => null
    case other => other.toString
  }

  private def valueToMap(value: dev.openfeature.sdk.Value): Map[String, Any] =
    if (value == null || !value.isStructure) Map.empty
    else
      value
        .asStructure()
        .asMap()
        .asScala
        .flatMap { case (k, v) => valueToAny(v).map(k -> _) }
        .toMap

  private def valueToAny(value: dev.openfeature.sdk.Value): Option[Any] =
    if (value == null) None
    else if (value.isBoolean) Some(value.asBoolean())
    else if (value.isString) Some(value.asString())
    else if (value.isNumber) Some(value.asDouble())
    else if (value.isList) Some(value.asList().asScala.flatMap(v => valueToAny(v)).toList)
    else if (value.isStructure) Some(valueToMap(value))
    else if (value.isInstant) Some(value.asInstant())
    else None

  // Detailed evaluation methods (one per type, with default parameters from trait)

  override def booleanDetails(
    key: String,
    default: Boolean,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Boolean]] =
    evaluateWithDetails(key, default, ctx, options)

  override def stringDetails(
    key: String,
    default: String,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[String]] =
    evaluateWithDetails(key, default, ctx, options)

  override def intDetails(
    key: String,
    default: Int,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Int]] =
    evaluateWithDetails(key, default, ctx, options)

  override def longDetails(
    key: String,
    default: Long,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Long]] =
    evaluateWithDetails(key, default, ctx, options)

  override def doubleDetails(
    key: String,
    default: Double,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Double]] =
    evaluateWithDetails(key, default, ctx, options)

  override def objDetails(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Map[String, Any]]] =
    evaluateWithDetails(key, default, ctx, options)

  override def valueDetails[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[A]] =
    evaluateWithDetails(key, default, ctx, options)

  private def evaluateWithDetails[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[A]] = {
    // Resolve the per-call selection against the instance's global timeout. `After` and `Disabled` override the global;
    // `Default` defers to it. The result is `Option[Duration]` (None => skip the timeout scaffolding entirely).
    val timeout = options.timeout match {
      case EvaluationTimeout.After(d) => Some(d)
      case EvaluationTimeout.Disabled => None
      case EvaluationTimeout.Default  => evaluationTimeout
    }
    effectiveContext(ctx).flatMap { effectCtx =>
      runWithHooks(
        key,
        default,
        effectCtx,
        c => evaluateFlag(key, default, c, timeout),
        options.hooks,
        options.hookHints
      )
    }
  }

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
    cacheEvaluations: Boolean
  )(zio: ZIO[R, E, A]): ZIO[R, Compat.OrError[E, FeatureFlagError], TransactionResult[A]] =
    // Built on the source-tagged form so the two never disagree; `Compat.merge` drops the tag back into `OrError`
    // (the raw `E | FeatureFlagError` union on Scala 3, `Any` on 2.13) — preserving the legacy error channel exactly.
    transactionEither(overrides, context, cacheEvaluations)(zio).mapError(Compat.merge)

  override def transactionEither[R, E, A](
    overrides: Map[String, Any],
    context: EvaluationContext,
    cacheEvaluations: Boolean
  )(zio: ZIO[R, E, A]): ZIO[R, Either[E, FeatureFlagError], TransactionResult[A]] =
    // Errors are tagged at their source: `Left` is the caller's own `E` from `zio`, `Right` is a transaction-machinery
    // error. This is the only place the two can be told apart — once merged into a single channel (as `transaction`
    // does) an `E` that happens to equal `FeatureFlagError` is indistinguishable from a machinery error.
    for {
      current <- state.transactionRef.get
      _ <- ZIO.when(current.isDefined)(
        ZIO.fail(Right(FeatureFlagError.NestedTransactionNotAllowed): Either[E, FeatureFlagError])
      )
      txState  <- TransactionState.make(overrides, context, cacheEvaluations)
      result   <- state.transactionRef.locally(Some(txState))(zio).mapError(Left(_): Either[E, FeatureFlagError])
      txResult <- txState.toResult(result)
    } yield txResult

  override def inTransaction: UIO[Boolean] =
    state.transactionRef.get.map(_.isDefined)

  override def currentEvaluatedFlags: UIO[Map[String, zio.openfeature.FlagEvaluation[_]]] =
    state.transactionRef.get.flatMap {
      case Some(ts) => ts.getEvaluations
      case None     => Exit.succeed(Map.empty)
    }

  override def events: ZStream[Any, Nothing, ProviderEvent] =
    ZStream.fromHub(state.eventHub)

  override def providerStatus: UIO[ProviderStatus] =
    state.statusRef.get

  /** Re-register `oldProvider` with the SDK iff the failed swap left the SDK routing to `newProvider` (#282). Returns
    * true iff the SDK is verified to route to `oldProvider` afterwards.
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
        def register(): Unit = domain match {
          case Some(d) => api.setProviderAndWait(d, oldProvider)
          case None    => api.setProviderAndWait(oldProvider)
        }
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

  /** Best-effort teardown of the failed new provider when rollback itself failed and left it unbound (#282). On a
    * successful rollback the SDK's `shutDownOld` already does this; this covers only the rollback-failure path. If the
    * failed provider is somehow still bound, do nothing.
    */
  private def cleanupUnboundFailedProvider(newProvider: OFFeatureProvider): UIO[Unit] =
    (for {
      bound <- ZIO.attemptBlocking(domain.fold(api.getProvider())(d => api.getProvider(d)))
      _ <- ZIO.when(bound ne newProvider)(
        ZIO.attemptBlocking(newProvider.shutdown()).ignore *>
          ZIO.attemptBlocking(EventProviderAccess.deregisterGlobalProvider(api, newProvider)).ignore
      )
    } yield ())
      // Log if even the best-effort teardown's own precondition read fails, so a leaked provider/thread from this
      // doubly-rare path leaves a breadcrumb instead of vanishing silently.
      .tapErrorCause(c => ZIO.logWarningCause("cleanupUnboundFailedProvider: best-effort teardown failed", c))
      .ignore

  /** Async-init watchdog escalation (#244). Only when the machine actually transitions (the provider never became
    * usable — the `everReady` gate) does it shut the provider down, publish a terminal Error event with code
    * PROVIDER_FATAL, and release the onReady latch. A provider that was ever Ready is left running.
    */
  private[openfeature] def escalateInitTimeout(provider: OFFeatureProvider, initTimeout: Duration): UIO[Unit] =
    applySignal(Signal.InitTimeout).flatMap {
      case Some(_) =>
        val msg = s"Provider initialization exceeded $initTimeout"
        ZIO.attemptBlocking(provider.shutdown()).ignore *>
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
          ZIO.succeed(onReady.foreach(_.countDown()))
      case None => ZIO.unit
    }

  /** Seed the initial status from the sync build path, recording ever-readiness for the machine. */
  private[openfeature] def seedStatus(status: ProviderStatus): UIO[Unit] =
    ZIO.succeed {
      if (status == ProviderStatus.Ready || status == ProviderStatus.Stale) everReady.set(true)
    } *> state.statusRef.set(status)

  override def awaitReady(within: Duration): UIO[ProviderStatus] =
    // `.changes` emits the current status first (so an already-Ready provider returns immediately) then every
    // subsequent transition — no polling. `runHead` completes on the first evaluable-or-Fatal status. On timeout
    // (`None`) or an exhausted stream we return the then-current status, so every path yields a real status.
    state.statusRef.changes
      .filter(s => s.canEvaluate || s == ProviderStatus.Fatal)
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

  // Event Handlers - return cancellation effects per OpenFeature spec 5.2.7

  /** Fork a daemon fiber consuming hub events, guaranteeing the hub subscription is established before this method
    * returns. Without the handshake, events published between handler registration and the forked stream's first pull
    * would be silently lost. The returned effect cancels the subscription.
    */
  private def consumeEvents(consume: ZStream[Any, Nothing, ProviderEvent] => UIO[Unit]): UIO[UIO[Unit]] =
    for {
      subscribed <- Promise.make[Nothing, Unit]
      fiber <- ZIO.scoped {
        state.eventHub.subscribe.flatMap { queue =>
          subscribed.succeed(()) *> consume(ZStream.fromQueue(queue))
        }
      }.forkDaemon
      _ <- subscribed.await
    } yield fiber.interrupt.unit

  /** Per OpenFeature spec 5.3.3, handlers attached after the provider reaches an associated state MUST run immediately.
    *
    * The subscription is established first, then the current status is checked: no event can fall between the two. The
    * cost is at-least-once delivery — an event arriving in that window may invoke the handler via both the immediate
    * check and the stream.
    */
  // Isolate a handler invocation so a defect does NOT kill the delivery fiber — the subscription is retained and the
  // handler keeps receiving subsequent events (spec 5.2.5). `suspendSucceed` is essential: it captures a *synchronous*
  // throw while `handler(a)` is producing its effect (e.g. a raw exception thrown in the handler body before it returns
  // a ZIO) as a defect, not just an already-suspended `ZIO.die`. Interruption is re-raised (not logged) so cancelling
  // the subscription still tears the fiber down.
  private def isolate[A](handler: A => UIO[Unit]): A => UIO[Unit] =
    a =>
      ZIO.suspendSucceed(handler(a)).catchAllCause { cause =>
        cause.dieOption match {
          case Some(_) => ZIO.logErrorCause("event handler failed; subscription retained", cause)
          case None    => ZIO.refailCause(cause)
        }
      }

  private def subscribeToEvent[A](
    immediateCondition: ProviderStatus => Boolean,
    immediatePayload: => A,
    collect: PartialFunction[ProviderEvent, A],
    handler: A => UIO[Unit]
  ): UIO[UIO[Unit]] = {
    val safe = isolate(handler)
    for {
      cancel <- consumeEvents(_.collect(collect).foreach(safe))
      status <- providerStatus
      _      <- ZIO.when(immediateCondition(status))(safe(immediatePayload))
    } yield cancel
  }

  override def onProviderReady(handler: ProviderMetadata => UIO[Unit]): UIO[UIO[Unit]] =
    ZIO.succeed(providerNameRef.get()).flatMap { pName =>
      val metadata = ProviderMetadata(pName)
      subscribeToEvent(
        _ == ProviderStatus.Ready,
        metadata,
        { case ProviderEvent.Ready(m, _) => m },
        handler
      )
    }

  override def onProviderError(handler: (Throwable, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] =
    ZIO.succeed(providerNameRef.get()).flatMap { pName =>
      val metadata = ProviderMetadata(pName)
      subscribeToEvent(
        s => s == ProviderStatus.Error || s == ProviderStatus.Fatal,
        (new RuntimeException("Provider in error state"), metadata),
        { case ProviderEvent.Error(error, m, _, _, _) => (error, m) },
        (handler(_, _)).tupled
      )
    }

  override def onProviderStale(handler: (String, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] =
    ZIO.succeed(providerNameRef.get()).flatMap { pName =>
      val metadata = ProviderMetadata(pName)
      subscribeToEvent(
        _ == ProviderStatus.Stale,
        ("Provider in stale state", metadata),
        { case ProviderEvent.Stale(reason, m, _) => (reason, m) },
        (handler(_, _)).tupled
      )
    }

  override def onConfigurationChanged(handler: (Set[String], ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] = {
    // Configuration changed doesn't have an "associated state" so no immediate execution needed.
    val safe = isolate[(Set[String], ProviderMetadata)] { case (flags, m) => handler(flags, m) }
    consumeEvents(_.collect { case ProviderEvent.ConfigurationChanged(flags, m, _) => (flags, m) }.foreach(safe))
  }

  // The generic `on` delivers the ORIGINAL event from the stream (preserving every payload field — errorCode,
  // errorMessage, eventMetadata, ...), not a narrowed reconstruction. Associated-state events (Ready/Error/Stale) still
  // fire immediately when the provider is already in that state (spec 5.3.3), using a synthetic event that reflects the
  // current state (there is no original event to replay for an already-reached state). All invocations are isolated.
  override def on(eventType: ProviderEventType, handler: ProviderEvent => UIO[Unit]): UIO[UIO[Unit]] = {
    val metadata = ProviderMetadata(providerNameRef.get())
    eventType match {
      case ProviderEventType.Ready =>
        subscribeToEvent[ProviderEvent](
          _ == ProviderStatus.Ready,
          ProviderEvent.Ready(metadata),
          { case e: ProviderEvent.Ready => e },
          handler
        )
      case ProviderEventType.Error =>
        subscribeToEvent[ProviderEvent](
          s => s == ProviderStatus.Error || s == ProviderStatus.Fatal,
          ProviderEvent.Error(new RuntimeException("Provider in error state"), metadata),
          { case e: ProviderEvent.Error => e },
          handler
        )
      case ProviderEventType.Stale =>
        subscribeToEvent[ProviderEvent](
          _ == ProviderStatus.Stale,
          ProviderEvent.Stale("Provider in stale state", metadata),
          { case e: ProviderEvent.Stale => e },
          handler
        )
      case ProviderEventType.ConfigurationChanged =>
        consumeEvents(_.filter(_.eventType == ProviderEventType.ConfigurationChanged).foreach(isolate(handler)))
      case ProviderEventType.Reconnecting =>
        consumeEvents(_.filter(_.eventType == ProviderEventType.Reconnecting).foreach(isolate(handler)))
    }
  }

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
    ZIO.succeed(api.clearHooks())

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
        //    setProviderAndWait) and the identity guard see the new provider's metadata.
        newName = Option(newProvider.getMetadata).map(_.getName).getOrElse(FeatureFlags.UnknownProviderName)
        _ <- providerRef.set(newProvider)
        _ <- ZIO.succeed(providerNameRef.set(newName))
        // 3. Register new provider with Java SDK (shuts down old, initializes new). Assumes `setProviderAndWait`
        //    returning normally means READY was reached (Phase 2 watch-point #340: re-verify against the SDK's
        //    spec-v0.9.0 provider-event marker once shipped).
        _ <- (domain match {
          case Some(d) => ZIO.attemptBlocking(api.setProviderAndWait(d, newProvider))
          case None    => ZIO.attemptBlocking(api.setProviderAndWait(newProvider))
        }).mapError(e => FeatureFlagError.ProviderInitializationFailed(e))
          .tapError(_ =>
            // Roll back refs FIRST so the identity guard again reflects the previous provider and record the failure
            // via the machine, THEN reconcile the SDK client routing (#282): the SDK binds the new provider before
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
          )
        // 4. Mark ready via the machine — the Java SDK bridge also fires PROVIDER_READY, but we set it explicitly for
        //    immediate visibility.
        _ <- applySignal(Signal.SwapSucceeded)
      } yield ()
      // The gate must drop on every exit (success, failure, interruption) or bridge events would be suppressed forever.
      swap.ensuring(ZIO.succeed(swapInProgress.set(false)))
    }

  // Shutdown API (spec 1.6.1, 1.6.2)

  override def shutdown: UIO[Unit] =
    // ShuttingDown rejects evaluations for the duration of the teardown (checkProviderStatus); the terminal state
    // after teardown is NotReady. The machine keeps both terminal: while ShuttingDown no bridge event transitions,
    // and ShutdownCompleted makes the final NotReady stick against a late event from the dying provider.
    applySignal(Signal.ShutdownStarted) *>
      // Deregister the bridge handlers BEFORE tearing down the API so no emitter thread races the teardown (the scope
      // finalizer also calls this — the SDK's removeHandler is idempotent).
      ZIO.succeed(bridgeDeregister.get()()) *>
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
      // kill sibling clients (the #243 bug); shutting the provider directly would kill siblings that share it (the
      // registry falls back to one shared default provider) and double-shut a provider the API still has registered.
      // A non-owning `shutdown` therefore releases only this instance's own state (status/hooks/contexts/event hub,
      // torn down above); retire such clients via whatever owns the API (e.g. the registry).
      ZIO.when(ownsApi)(ZIO.attemptBlocking(api.shutdown()).ignore) *>
      applySignal(Signal.ShutdownCompleted).unit

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
    effectiveContext(context).flatMap { merged =>
      recordTrack(eventName, merged, details) *>
        ZIO
          .attemptBlocking {
            val ofContext = toJavaContext(merged)
            details match {
              case Some(d) => client.track(eventName, ofContext, toOpenFeatureDetails(d))
              case None    => client.track(eventName, ofContext)
            }
          }
          .mapError(e => FeatureFlagError.classify(e))
    }

  // The recorder is bounded: when full, the oldest entries are dropped so long-running apps that
  // call `track` per request don't accumulate events (and their merged contexts) without limit.
  private def recordTrack(
    eventName: String,
    merged: EvaluationContext,
    details: Option[TrackingEventDetails]
  ): UIO[Unit] =
    state.trackRecorder.update { rec =>
      val appended = rec :+ ((eventName, merged, details))
      val overflow = appended.length - FeatureFlagsState.MaxTrackedEvents
      if (overflow > 0) appended.drop(overflow) else appended
    }

  override def trackedEvents: UIO[List[(String, EvaluationContext, Option[TrackingEventDetails])]] =
    state.trackRecorder.get.map(_.toList)

  private def toOpenFeatureDetails(details: TrackingEventDetails): MutableTrackingEventDetails = {
    val result = details.value match {
      case Some(v) => new MutableTrackingEventDetails(v)
      case None    => new MutableTrackingEventDetails()
    }
    details.attributes.foreach { case (k, v) =>
      addAttributeToDetails(result, k, v)
    }
    result
  }

  private def addAttributeToDetails(details: MutableTrackingEventDetails, key: String, value: Any): Unit =
    value match {
      case b: Boolean => details.add(key, b)
      case s: String  => details.add(key, s)
      case i: Int     => details.add(key, Integer.valueOf(i))
      // int-range long → Integer (no precision loss, and matches integer targeting); anything larger goes through
      // `Value`'s native Long support (SDK 1.22.0) rather than the old Double fallback, which was silently lossy
      // beyond 2^53.
      case l: Long =>
        if (l.isValidInt) details.add(key, Integer.valueOf(l.toInt))
        else details.add(key, new dev.openfeature.sdk.Value(java.lang.Long.valueOf(l)))
      case d: Double                  => details.add(key, d)
      case f: Float                   => details.add(key, java.lang.Double.valueOf(f.toDouble))
      case instant: java.time.Instant => details.add(key, instant)
      case list: List[_] =>
        val values = list.map(v => new dev.openfeature.sdk.Value(anyToObject(v))).asJava
        details.add(key, values)
      case map: Map[_, _] =>
        val structure = dev.openfeature.sdk.Structure.mapToStructure(
          map.asInstanceOf[Map[String, Any]].map { case (k, v) => k -> anyToObject(v) }.asJava
        )
        details.add(key, structure)
      case null  => () // Skip null values
      case other => details.add(key, other.toString)
    }
}
