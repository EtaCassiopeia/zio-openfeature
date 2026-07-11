package zio.openfeature

import zio._
import zio.stream._
import zio.openfeature.internal.{ClientEvaluator, ContextConverter, ErrorCodeConverter, FeatureFlagsState}
import dev.openfeature.sdk.{
  Client => OFClient,
  FeatureProvider => OFFeatureProvider,
  ProviderEvent => JavaProviderEvent,
  EventDetails,
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

  // Sentinel for "no swap has failed yet". Checked before computing the nanoTime difference so the
  // subtraction can never operate on the sentinel (nanoTime has an arbitrary origin, so unlike wall-clock
  // time there is no epoch value that is safely "long ago").
  private val NoSwapFailure = Long.MinValue

  // Records when a `setProvider` swap last failed (System.nanoTime, monotonic — wall-clock adjustments such as NTP
  // steps must not widen or defeat the guard). Used by the async PROVIDER_READY bridge to decide whether an incoming
  // Ready event is a real recovery signal or a stale event left over from a previous attach that's racing against the
  // explicit Error transition set by the failed swap. See `setProvider` and `readyHandler` below.
  private val recentSwapFailureAtNanos = new java.util.concurrent.atomic.AtomicLong(NoSwapFailure)

  // Mirror of `recentSwapFailureAtNanos` for the opposite case: records when a `setProvider` swap last
  // *succeeded*. Used by the async PROVIDER_ERROR bridge to recognize a stale Error event queued by a
  // previous, now-replaced provider (e.g. one whose `initialize()` failed) that gets dispatched on the
  // SDK's emitter executor *after* a subsequent swap has already taken over and moved the instance to
  // Ready. Without this guard such a stale event clobbers the new provider's Ready status. See
  // `setProvider` and `errorHandler` below.
  private val recentSwapSuccessAtNanos = new java.util.concurrent.atomic.AtomicLong(NoSwapFailure)

  // How long after a failed/successful swap an async PROVIDER_READY/PROVIDER_ERROR event should be ignored
  // as a likely stale signal. Real recovery/failure scenarios (provider genuinely transitions on its own,
  // well after the swap completed) happen on a much longer timescale than this; the race window we're
  // closing is the SDK's emitter executor dispatching a queued event that pre-dates our explicit transition.
  private val FailedSwapGuardNanos: Long = 500L * 1000000L

  // True while `setProvider` holds the swap lock and is re-registering the provider. The PROVIDER_READY bridge must
  // not transition NotReady => Ready in that window: a queued Ready event from the OLD provider's emitter would mark
  // the instance Ready while the swap is still in flight. The successful swap sets Ready explicitly at the end.
  private val swapInProgress = new java.util.concurrent.atomic.AtomicBoolean(false)

  // Bridge Java SDK provider events to ZIO event system
  private[openfeature] def startEventBridge: ZIO[Scope, Nothing, Unit] = {
    // Read provider name dynamically so events after a provider swap use the new name
    def currentMetadata(): ProviderMetadata = ProviderMetadata(providerNameRef.get())

    // Run an event-publish effect from a Java SDK thread. Failures are logged via the ZIO logger
    // rather than thrown, so the Java SDK's event dispatch thread is never killed by a defect.
    def runHandler(runtime: Runtime[Any], label: String)(effect: UIO[Unit]): Unit =
      Unsafe.unsafe { implicit u =>
        runtime.unsafe
          .run(effect.catchAllCause(c => ZIO.logErrorCause(s"event bridge: $label", c)))
          .getOrElse(_ => ())
      }

    ZIO.runtime[Any].flatMap { runtime =>
      def extractEventMetadata(details: EventDetails): FlagMetadata =
        try {
          val javaMeta = details.getEventMetadata
          if (javaMeta == null || javaMeta.isEmpty) FlagMetadata.empty
          else convertImmutableMetadata(javaMeta)
        } catch {
          case _: Exception => FlagMetadata.empty
        }

      val readyHandler: java.util.function.Consumer[EventDetails] = details => {
        val em = extractEventMetadata(details)
        runHandler(runtime, "PROVIDER_READY")(
          // Transition statusRef only from states where PROVIDER_READY is meaningful.
          // - `NotReady => Ready` is suppressed while a swap holds the lock (`swapInProgress`): a Ready event arriving
          //   then belongs to the OLD provider's emitter queue; the successful swap sets Ready explicitly at the end.
          // - The `Error => Ready` arrow is valid per the OpenFeature spec (recovery from a recoverable error) but is
          //   guarded by `FailedSwapGuardNanos`: if the most recent statusRef write was a failed-swap Error within that
          //   window, a Ready event arriving now is almost certainly a stale signal queued on the SDK's emitter
          //   executor before the swap, not a genuine recovery. Real recoveries happen on much longer timescales.
          state.statusRef.update {
            case ProviderStatus.NotReady =>
              if (swapInProgress.get()) ProviderStatus.NotReady else ProviderStatus.Ready
            case ProviderStatus.Stale => ProviderStatus.Ready
            case ProviderStatus.Error =>
              val stamp = recentSwapFailureAtNanos.get()
              val withinGuard =
                stamp != NoSwapFailure && (java.lang.System.nanoTime() - stamp) < FailedSwapGuardNanos
              if (withinGuard) ProviderStatus.Error else ProviderStatus.Ready
            case other => other
          } *>
            state.eventHub.publish(ProviderEvent.Ready(currentMetadata(), em)).unit
        )
        onReady.foreach(_.countDown())
      }

      val errorHandler: java.util.function.Consumer[EventDetails] = details => {
        val error     = new RuntimeException(Option(details.getMessage).getOrElse("Provider error"))
        val errorCode = Option(details.getErrorCode).map(ErrorCodeConverter.fromJava)
        val em        = extractEventMetadata(details)
        runHandler(runtime, "PROVIDER_ERROR")(
          // Don't let a stale Error event clobber a newer provider's status: suppress the statusRef
          // transition while a swap is in flight (the swap's own `tapError` already sets Error
          // synchronously for *its* failure) or within `FailedSwapGuardNanos` of a swap that just
          // succeeded (the event almost certainly predates that success). The event is still published
          // either way so observers relying on the event stream see it.
          ZIO
            .succeed {
              val stamp = recentSwapSuccessAtNanos.get()
              val withinGuard =
                stamp != NoSwapFailure && (java.lang.System.nanoTime() - stamp) < FailedSwapGuardNanos
              !swapInProgress.get() && !withinGuard
            }
            .flatMap(shouldTransition => state.statusRef.set(ProviderStatus.Error).when(shouldTransition)) *>
            state.eventHub
              .publish(ProviderEvent.Error(error, currentMetadata(), errorCode, Option(details.getMessage), em))
              .unit
        )
      }

      val staleHandler: java.util.function.Consumer[EventDetails] = details => {
        val reason = Option(details.getMessage).getOrElse("Provider stale")
        val em     = extractEventMetadata(details)
        runHandler(runtime, "PROVIDER_STALE")(
          state.statusRef.set(ProviderStatus.Stale) *>
            state.eventHub.publish(ProviderEvent.Stale(reason, currentMetadata(), em)).unit
        )
      }

      val configHandler: java.util.function.Consumer[EventDetails] = details => {
        val flags = Option(details.getFlagsChanged)
          .map(_.asScala.toSet)
          .getOrElse(Set.empty[String])
        val em = extractEventMetadata(details)
        runHandler(runtime, "PROVIDER_CONFIGURATION_CHANGED")(
          state.eventHub
            .publish(ProviderEvent.ConfigurationChanged(flags, currentMetadata(), em))
            .unit
        )
      }

      for {
        _ <- ZIO.succeed {
          client.on(JavaProviderEvent.PROVIDER_READY, readyHandler)
          client.on(JavaProviderEvent.PROVIDER_ERROR, errorHandler)
          client.on(JavaProviderEvent.PROVIDER_STALE, staleHandler)
          client.on(JavaProviderEvent.PROVIDER_CONFIGURATION_CHANGED, configHandler)
          ()
        }
        _ <- ZIO.addFinalizer(ZIO.attempt {
          client.removeHandler(JavaProviderEvent.PROVIDER_READY, readyHandler)
          client.removeHandler(JavaProviderEvent.PROVIDER_ERROR, errorHandler)
          client.removeHandler(JavaProviderEvent.PROVIDER_STALE, staleHandler)
          client.removeHandler(JavaProviderEvent.PROVIDER_CONFIGURATION_CHANGED, configHandler)
          ()
        }.ignore)
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
      // Error/Ready/Stale proceed: per OpenFeature spec 1.7.6/1.7.7 only NOT_READY and FATAL fail-fast. A provider in
      // ERROR is typically a transient, recoverable state and commonly still serves cached values — let the evaluation
      // reach it (the provider serves or errors on its own) rather than turning one PROVIDER_ERROR into a total outage.
      case _ => Exit.unit
    }

  // Context is already merged by evaluateWithDetails before entering the hook pipeline
  private def evaluateFlag[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    timeout: Option[Duration] = None
  ): IO[FeatureFlagError, FlagResolution[A]] =
    for {
      _       <- checkProviderStatus
      txState <- state.transactionRef.get
      result <- txState match {
        case Some(ts) => evaluateWithTransaction(key, default, context, ts, timeout)
        case None     => evaluateFromClient(key, default, context, timeout)
      }
    } yield result

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
        // Check for cached evaluation from previous call in this transaction
        txState.getCachedEvaluation(key).flatMap {
          case Some(cached) =>
            val flagType = FlagType[A]
            flagType.decode(cached.value) match {
              case Right(decoded) =>
                ZIO.succeed(FlagResolution.cached(key, decoded))
              case Left(_) =>
                // Type mismatch with cached value - re-evaluate from client
                evaluateAndCache(key, default, context, txState, timeout)
            }
          case None =>
            evaluateAndCache(key, default, context, txState, timeout)
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
    val ofContext = ContextConverter.toOpenFeature(context)

    def withTimeout[B](effect: Task[B]): Task[B] =
      timeout match {
        case Some(d) =>
          effect.disconnect
            .timeoutFail(new java.util.concurrent.TimeoutException(s"Evaluation of '$key' timed out after $d"))(d)
        case None => effect
      }

    val evaluation: IO[FeatureFlagError, FlagResolution[A]] =
      ClientEvaluator.evaluateStandard[A](flagType.typeName, client, key, default, ofContext) match {
        case Some(erased) =>
          withTimeout(erased.task)
            .mapError(e => FeatureFlagError.classify(e))
            .flatMap { details =>
              toFlagResolution(key, details).map(r => r.copy(value = erased.extract(details)))
            }

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
    // Per-call timeout overrides global; None means no timeout (backward compatible)
    val timeout = options.timeout.orElse(evaluationTimeout)
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
    for {
      current <- state.transactionRef.get
      _ <- ZIO.when(current.isDefined)(
        ZIO.fail(FeatureFlagError.NestedTransactionNotAllowed)
      )
      txState  <- TransactionState.make(overrides, context, cacheEvaluations)
      result   <- state.transactionRef.locally(Some(txState))(zio)
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

  // Force status back to Ready. Used by `fromAcquireAsync` when a hot-swap to the real provider fails: `setProvider`'s
  // rollback restores the still-live fallback to `providerRef` but leaves status `Error`. Evaluations still proceed in
  // `Error` (spec 1.7.6/1.7.7), but restoring `Ready` keeps `providerStatus` / `awaitReady` accurate for the usable
  // fallback (`Error` is not `canEvaluate`). Package-private — not part of the public API.
  private[openfeature] def forceReady: UIO[Unit] =
    state.statusRef.set(ProviderStatus.Ready)

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
  private def subscribeToEvent[A](
    immediateCondition: ProviderStatus => Boolean,
    immediatePayload: => A,
    collect: PartialFunction[ProviderEvent, A],
    handler: A => UIO[Unit]
  ): UIO[UIO[Unit]] =
    for {
      cancel <- consumeEvents(_.collect(collect).foreach(handler))
      status <- providerStatus
      _      <- ZIO.when(immediateCondition(status))(handler(immediatePayload))
    } yield cancel

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

  override def onConfigurationChanged(handler: (Set[String], ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] =
    // Configuration changed doesn't have an "associated state" so no immediate execution needed
    consumeEvents(
      _.collect { case ProviderEvent.ConfigurationChanged(flags, m, _) => (flags, m) }
        .foreach { case (flags, m) => handler(flags, m) }
    )

  override def on(eventType: ProviderEventType, handler: ProviderEvent => UIO[Unit]): UIO[UIO[Unit]] =
    eventType match {
      case ProviderEventType.Ready =>
        onProviderReady(m => handler(ProviderEvent.Ready(m)))
      case ProviderEventType.Error =>
        onProviderError((e, m) => handler(ProviderEvent.Error(e, m)))
      case ProviderEventType.Stale =>
        onProviderStale((reason, m) => handler(ProviderEvent.Stale(reason, m)))
      case ProviderEventType.ConfigurationChanged =>
        onConfigurationChanged((flags, m) => handler(ProviderEvent.ConfigurationChanged(flags, m)))
      case ProviderEventType.Reconnecting =>
        consumeEvents(_.filter(_.eventType == ProviderEventType.Reconnecting).foreach(handler))
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
        // 1. Transition to NOT_READY — new evaluations fail fast during swap. `swapInProgress` keeps the
        //    PROVIDER_READY bridge from flipping NotReady => Ready off a stale event from the old provider.
        _ <- ZIO.succeed(swapInProgress.set(true))
        _ <- state.statusRef.set(ProviderStatus.NotReady)
        // 2. Update refs BEFORE registering with Java SDK, so the event bridge
        //    (which fires PROVIDER_READY during setProviderAndWait) sees consistent metadata
        newName = Option(newProvider.getMetadata).map(_.getName).getOrElse("unknown")
        _ <- providerRef.set(newProvider)
        _ <- ZIO.succeed(providerNameRef.set(newName))
        // 3. Register new provider with Java SDK (shuts down old, initializes new)
        _ <- (domain match {
          case Some(d) => ZIO.attemptBlocking(api.setProviderAndWait(d, newProvider))
          case None    => ZIO.attemptBlocking(api.setProviderAndWait(newProvider))
        }).mapError(e => FeatureFlagError.ProviderInitializationFailed(e))
          .tapError(_ =>
            // Rollback refs and set Error status so the instance is in a diagnosable state. Stamp
            // `recentSwapFailureAtNanos` BEFORE the statusRef write so the async PROVIDER_READY handler sees a fresh
            // failure timestamp and skips overwriting Error within the guard window.
            ZIO.succeed(recentSwapFailureAtNanos.set(java.lang.System.nanoTime())) *>
              providerRef.set(oldProvider) *>
              ZIO.succeed(providerNameRef.set(oldName)) *>
              state.statusRef.set(ProviderStatus.Error)
          )
        // 4. Mark ready — the Java SDK event bridge will also fire PROVIDER_READY,
        //    but we set it explicitly for immediate visibility. Stamp `recentSwapSuccessAtNanos`
        //    first so a stale PROVIDER_ERROR from the just-replaced provider (see `errorHandler`)
        //    can't race in and immediately overwrite the Ready we're about to set.
        _ <- ZIO.succeed(recentSwapSuccessAtNanos.set(java.lang.System.nanoTime()))
        _ <- state.statusRef.set(ProviderStatus.Ready)
      } yield ()
      // The guard must drop on every exit (success, failure, interruption) or the bridge would
      // suppress legitimate NotReady => Ready transitions forever.
      swap.ensuring(ZIO.succeed(swapInProgress.set(false)))
    }

  // Shutdown API (spec 1.6.1, 1.6.2)

  override def shutdown: UIO[Unit] =
    // ShuttingDown rejects evaluations for the duration of the teardown (checkProviderStatus);
    // the terminal state after teardown is NotReady.
    state.statusRef.set(ProviderStatus.ShuttingDown) *>
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
      state.statusRef.set(ProviderStatus.NotReady)

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
            val ofContext = ContextConverter.toOpenFeature(merged)
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
      // int-range long → Integer (no precision loss, matches integer targeting); else Double (lossy beyond 2^53).
      case l: Long =>
        if (l.isValidInt) details.add(key, Integer.valueOf(l.toInt))
        else details.add(key, java.lang.Double.valueOf(l.toDouble))
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
