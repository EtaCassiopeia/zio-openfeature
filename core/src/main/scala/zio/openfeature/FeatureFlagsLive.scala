package zio.openfeature

import zio._
import zio.stream._
import zio.openfeature.internal.{ClientEvaluator, ContextConverter, ErrorCodeConverter, FeatureFlagsState}
import dev.openfeature.sdk.{
  Client => OFClient,
  FeatureProvider => OFFeatureProvider,
  FlagEvaluationDetails,
  Reason => OFReason,
  ErrorCode => OFErrorCode,
  OpenFeatureAPI,
  MutableTrackingEventDetails,
  ProviderEvent => JavaProviderEvent,
  EventDetails,
  FlagValueType => JavaFlagValueType,
  HookContext => JavaHookContext
}
import scala.jdk.CollectionConverters._

final private[openfeature] class FeatureFlagsLive(
  client: OFClient,
  providerRef: Ref[OFFeatureProvider],
  providerNameRef: Ref[String],
  domain: Option[String],
  version: Option[String],
  state: FeatureFlagsState,
  api: OpenFeatureAPI,
  swapLock: Semaphore,
  onReady: Option[java.util.concurrent.CountDownLatch] = None,
  evaluationTimeout: Option[Duration] = None
) extends FeatureFlags {

  // Records when a `setProvider` swap last failed. Used by the async PROVIDER_READY bridge to decide whether an
  // incoming Ready event is a real recovery signal or a stale event left over from a previous attach that's racing
  // against the explicit Error transition set by the failed swap. See `setProvider` and `readyHandler` below.
  //
  // Initialised to `0L` (epoch). `currentTimeMillis() - 0L` will always be far beyond `FailedSwapGuardMillis`, so the
  // guard never trips before the first failed swap. (Using `Long.MinValue` instead would overflow the subtraction and
  // wrap negative, causing the guard to trip incorrectly and block legitimate Error → Ready recoveries.)
  private val recentSwapFailureAt = new java.util.concurrent.atomic.AtomicLong(0L)

  // How long after a failed swap an async PROVIDER_READY event should be ignored as a likely stale signal. Real
  // recovery scenarios (provider was in Error for an extended period and genuinely transitions back to Ready)
  // happen on a much longer timescale than this; the race window we're closing is the SDK's emitter executor
  // dispatching a queued event that pre-dates our explicit Error.
  private val FailedSwapGuardMillis: Long = 500L

  // Bridge Java SDK provider events to ZIO event system
  private[openfeature] def startEventBridge: ZIO[Scope, Nothing, Unit] = {
    // Read provider name dynamically so events after a provider swap use the new name
    def currentMetadata(runtime: Runtime[Any]): ProviderMetadata = {
      val name = Unsafe.unsafe { implicit u =>
        runtime.unsafe
          .run(
            providerNameRef.get.catchAllCause(c =>
              ZIO.logErrorCause("event bridge: providerNameRef.get", c).as("unknown")
            )
          )
          .getOrElse(_ => "unknown")
      }
      ProviderMetadata(name)
    }

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
        } catch { case _: Exception => FlagMetadata.empty }

      val readyHandler: java.util.function.Consumer[EventDetails] = details => {
        val em = extractEventMetadata(details)
        runHandler(runtime, "PROVIDER_READY")(
          // Transition statusRef only from states where PROVIDER_READY is meaningful. The `Error => Ready` arrow is
          // valid per the OpenFeature spec (recovery from a recoverable error) but is guarded by
          // `FailedSwapGuardMillis`: if the most recent statusRef write was a failed-swap Error within that window,
          // a Ready event arriving now is almost certainly a stale signal queued on the SDK's emitter executor before
          // the swap, not a genuine recovery. Real recoveries happen on timescales much longer than the guard.
          state.statusRef.update {
            case ProviderStatus.NotReady => ProviderStatus.Ready
            case ProviderStatus.Stale    => ProviderStatus.Ready
            case ProviderStatus.Error =>
              val sinceFailure = java.lang.System.currentTimeMillis() - recentSwapFailureAt.get()
              if (sinceFailure >= FailedSwapGuardMillis) ProviderStatus.Ready else ProviderStatus.Error
            case other => other
          } *>
            state.eventHub.publish(ProviderEvent.Ready(currentMetadata(runtime), em)).unit
        )
        onReady.foreach(_.countDown())
      }

      val errorHandler: java.util.function.Consumer[EventDetails] = details => {
        val error     = new RuntimeException(Option(details.getMessage).getOrElse("Provider error"))
        val errorCode = Option(details.getErrorCode).map(ErrorCodeConverter.fromJava)
        val em        = extractEventMetadata(details)
        runHandler(runtime, "PROVIDER_ERROR")(
          state.statusRef.set(ProviderStatus.Error) *>
            state.eventHub
              .publish(ProviderEvent.Error(error, currentMetadata(runtime), errorCode, Option(details.getMessage), em))
              .unit
        )
      }

      val staleHandler: java.util.function.Consumer[EventDetails] = details => {
        val reason = Option(details.getMessage).getOrElse("Provider stale")
        val em     = extractEventMetadata(details)
        runHandler(runtime, "PROVIDER_STALE")(
          state.statusRef.set(ProviderStatus.Stale) *>
            state.eventHub.publish(ProviderEvent.Stale(reason, currentMetadata(runtime), em)).unit
        )
      }

      val configHandler: java.util.function.Consumer[EventDetails] = details => {
        val flags = Option(details.getFlagsChanged)
          .map(_.asScala.toSet)
          .getOrElse(Set.empty[String])
        val em = extractEventMetadata(details)
        runHandler(runtime, "PROVIDER_CONFIGURATION_CHANGED")(
          state.eventHub
            .publish(ProviderEvent.ConfigurationChanged(flags, currentMetadata(runtime), em))
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
      currentHooks <- state.hooksRef.get
      provHooks    <- getProviderHooks
      pName        <- providerNameRef.get
      allHooks   = currentHooks ++ extraHooks ++ provHooks
      metadata   = ProviderMetadata(pName)
      clientMeta = ClientMetadata(domain, version)
      hookCtx = HookContext(
        flagKey = key,
        flagType = FlagValueType.fromFlagType[A],
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

    for {
      beforeResult <- composedHook.before(hookCtx, initialHints)
      (effectiveCtx, hints) = beforeResult match {
        case Some((hookCtx, h)) => (hookCtx, h)
        case None               => (context, initialHints)
      }
      resultRef <- Ref.make[Option[FlagResolution[_]]](None)
      result <- evaluate(effectiveCtx)
        .tap(res => resultRef.set(Some(res)))
        .tapBoth(
          err => composedHook.error(hookCtx, err, hints),
          res => composedHook.after(hookCtx, res, hints)
        )
        .ensuring(
          resultRef.get.flatMap(details => composedHook.finallyAfter(hookCtx, details, hints)).ignore
        )
    } yield result
  }

  private def checkProviderStatus: IO[FeatureFlagError, Unit] =
    providerStatus.flatMap {
      case ProviderStatus.Fatal    => ZIO.fail(FeatureFlagError.ProviderFatal)
      case ProviderStatus.NotReady => ZIO.fail(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady))
      case ProviderStatus.Error    => ZIO.fail(FeatureFlagError.ProviderNotReady(ProviderStatus.Error))
      case _                       => ZIO.unit
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
          val timedEval = timeout match {
            case Some(d) =>
              erased.task.disconnect
                .timeoutFail(new java.util.concurrent.TimeoutException(s"Evaluation of '$key' timed out after $d"))(d)
            case None => erased.task
          }
          timedEval
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
    Option(reason)
      .map(_.toUpperCase)
      .map {
        case "STATIC"          => ResolutionReason.Static
        case "DEFAULT"         => ResolutionReason.Default
        case "TARGETING_MATCH" => ResolutionReason.TargetingMatch
        case "SPLIT"           => ResolutionReason.Split
        case "CACHED"          => ResolutionReason.Cached
        case "DISABLED"        => ResolutionReason.Disabled
        case "STALE"           => ResolutionReason.Stale
        case "ERROR"           => ResolutionReason.Error
        case _                 => ResolutionReason.Unknown
      }
      .getOrElse(ResolutionReason.Unknown)

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
      case None     => ZIO.succeed(Map.empty)
    }

  override def events: ZStream[Any, Nothing, ProviderEvent] =
    ZStream.fromHub(state.eventHub)

  override def providerStatus: UIO[ProviderStatus] =
    state.statusRef.get

  override def providerMetadata: UIO[ProviderMetadata] =
    providerNameRef.get.map(ProviderMetadata(_))

  override def clientMetadata: UIO[ClientMetadata] =
    ZIO.succeed(ClientMetadata(domain, version))

  // Event Handlers - return cancellation effects per OpenFeature spec 5.2.7

  /** Per OpenFeature spec 5.3.3, handlers attached after the provider reaches an associated state MUST run immediately.
    */
  private def subscribeToEvent[A](
    immediateCondition: ProviderStatus => Boolean,
    immediatePayload: => A,
    collect: PartialFunction[ProviderEvent, A],
    handler: A => UIO[Unit]
  ): UIO[UIO[Unit]] =
    for {
      status <- providerStatus
      _      <- ZIO.when(immediateCondition(status))(handler(immediatePayload))
      fiber  <- events.collect(collect).foreach(handler).forkDaemon
    } yield fiber.interrupt.unit

  override def onProviderReady(handler: ProviderMetadata => UIO[Unit]): UIO[UIO[Unit]] =
    providerNameRef.get.flatMap { pName =>
      val metadata = ProviderMetadata(pName)
      subscribeToEvent(
        _ == ProviderStatus.Ready,
        metadata,
        { case ProviderEvent.Ready(m, _) => m },
        handler
      )
    }

  override def onProviderError(handler: (Throwable, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] =
    providerNameRef.get.flatMap { pName =>
      val metadata = ProviderMetadata(pName)
      subscribeToEvent(
        s => s == ProviderStatus.Error || s == ProviderStatus.Fatal,
        (new RuntimeException("Provider in error state"), metadata),
        { case ProviderEvent.Error(error, m, _, _, _) => (error, m) },
        (handler(_, _)).tupled
      )
    }

  override def onProviderStale(handler: (String, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] =
    providerNameRef.get.flatMap { pName =>
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
    events
      .collect { case ProviderEvent.ConfigurationChanged(flags, m, _) => (flags, m) }
      .foreach { case (flags, m) => handler(flags, m) }
      .forkDaemon
      .map(fiber => fiber.interrupt.unit)

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
        events
          .filter(_.eventType == ProviderEventType.Reconnecting)
          .foreach(handler)
          .forkDaemon
          .map(fiber => fiber.interrupt.unit)
    }

  override def addHook(hook: FeatureHook): UIO[Unit] =
    state.hooksRef.update(_ :+ hook)

  override def addHooks(hooks: List[FeatureHook]): UIO[Unit] =
    state.hooksRef.update(_ ++ hooks)

  override def clearHooks: UIO[Unit] =
    state.hooksRef.set(List.empty)

  override def hooks: UIO[List[FeatureHook]] =
    state.hooksRef.get

  override def addApiHook(hook: dev.openfeature.sdk.Hook[_]): UIO[Unit] =
    ZIO.succeed(api.addHooks(hook))

  override def clearApiHooks: UIO[Unit] =
    ZIO.succeed(api.clearHooks())

  // Provider hooks (spec: provider hooks included in hook pipeline)

  private def getProviderHooks: UIO[List[FeatureHook]] =
    providerRef.get.flatMap { p =>
      ZIO
        .attempt {
          val javaHooks = p.getProviderHooks
          if (javaHooks == null || javaHooks.isEmpty) Nil
          else javaHooks.asScala.toList.map(wrapJavaHook)
        }
        .catchAll(e => ZIO.logWarning(s"Failed to get provider hooks: ${e.getMessage}").as(Nil))
    }

  @scala.annotation.nowarn("msg=deprecated")
  private def toJavaHookContext(ctx: HookContext): JavaHookContext[Any] =
    JavaHookContext.from[Any](
      ctx.flagKey,
      toJavaFlagValueType(ctx.flagType),
      new dev.openfeature.sdk.ClientMetadata {
        def getDomain: String = domain.orNull
      },
      new dev.openfeature.sdk.Metadata {
        def getName: String = ctx.providerMetadata.name
      },
      ContextConverter.toOpenFeature(ctx.evaluationContext),
      ctx.defaultValue
    )

  private def toJavaFlagValueType(fvt: FlagValueType): JavaFlagValueType = fvt match {
    case FlagValueType.Boolean => JavaFlagValueType.BOOLEAN
    case FlagValueType.String  => JavaFlagValueType.STRING
    case FlagValueType.Int     => JavaFlagValueType.INTEGER
    case FlagValueType.Double  => JavaFlagValueType.DOUBLE
    case FlagValueType.Object  => JavaFlagValueType.OBJECT
  }

  private def toJavaFlagEvalDetails[A](res: FlagResolution[A]): FlagEvaluationDetails[Any] = {
    val details = new FlagEvaluationDetails[Any]()
    details.setFlagKey(res.flagKey)
    details.setValue(res.value)
    res.variant.foreach(details.setVariant)
    details.setReason(res.reason.toString)
    res.errorCode.foreach(ec => details.setErrorCode(ErrorCodeConverter.toJava(ec)))
    res.errorMessage.foreach(details.setErrorMessage)
    details
  }

  private def fromJavaEvaluationContext(ctx: dev.openfeature.sdk.EvaluationContext): EvaluationContext =
    if (ctx == null) EvaluationContext.empty
    else ContextConverter.fromOpenFeature(ctx)

  private def wrapJavaHook(javaHook: dev.openfeature.sdk.Hook[_]): FeatureHook = {
    val hook = javaHook.asInstanceOf[dev.openfeature.sdk.Hook[Any]]
    new FeatureHook {
      override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
        ZIO
          .attempt {
            val jCtx   = toJavaHookContext(ctx)
            val jHints = hints.values.map { case (k, v) => k -> v.asInstanceOf[Object] }.asJava
            val result = hook.before(jCtx, jHints)
            if (result != null && result.isPresent) {
              val newCtx = fromJavaEvaluationContext(result.get())
              Some((newCtx, hints))
            } else None
          }
          .catchAll(_ => ZIO.none)

      override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
        ZIO.attempt {
          val jCtx     = toJavaHookContext(ctx)
          val jDetails = toJavaFlagEvalDetails(details)
          val jHints   = hints.values.map { case (k, v) => k -> v.asInstanceOf[Object] }.asJava
          hook.after(jCtx, jDetails, jHints)
        }.ignore

      override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
        ZIO.attempt {
          val jCtx   = toJavaHookContext(ctx)
          val jHints = hints.values.map { case (k, v) => k -> v.asInstanceOf[Object] }.asJava
          val ex: Exception = err.cause match {
            case Some(e: Exception) => e
            case Some(t)            => new RuntimeException(t.getMessage, t)
            case None               => new RuntimeException(err.message)
          }
          hook.error(jCtx, ex, jHints)
        }.ignore

      override def finallyAfter(
        ctx: HookContext,
        details: Option[FlagResolution[_]],
        hints: HookHints
      ): UIO[Unit] =
        ZIO.attempt {
          val jCtx     = toJavaHookContext(ctx)
          val jHints   = hints.values.map { case (k, v) => k -> v.asInstanceOf[Object] }.asJava
          val jDetails = details.map(toJavaFlagEvalDetails(_)).getOrElse(new FlagEvaluationDetails[Any]())
          hook.finallyAfter(jCtx, jDetails, jHints)
        }.ignore
    }
  }

  // Provider hot-swap

  // Note: we reuse the existing `client` object because the Java SDK's Client
  // delegates to the provider registered with the API at evaluation time,
  // not the provider that was active when the client was created.
  override def setProvider(newProvider: OFFeatureProvider): IO[FeatureFlagError, Unit] =
    swapLock.withPermit {
      for {
        // Save old state for rollback on failure
        oldProvider <- providerRef.get
        oldName     <- providerNameRef.get
        // 1. Transition to NOT_READY — new evaluations fail fast during swap
        _ <- state.statusRef.set(ProviderStatus.NotReady)
        // 2. Update refs BEFORE registering with Java SDK, so the event bridge
        //    (which fires PROVIDER_READY during setProviderAndWait) sees consistent metadata
        newName = Option(newProvider.getMetadata).map(_.getName).getOrElse("unknown")
        _ <- providerRef.set(newProvider)
        _ <- providerNameRef.set(newName)
        // 3. Register new provider with Java SDK (shuts down old, initializes new)
        _ <- (domain match {
          case Some(d) => ZIO.attemptBlocking(api.setProviderAndWait(d, newProvider))
          case None    => ZIO.attemptBlocking(api.setProviderAndWait(newProvider))
        }).mapError(e => FeatureFlagError.ProviderInitializationFailed(e))
          .tapError(_ =>
            // Rollback refs and set Error status so the instance is in a diagnosable state. Stamp
            // `recentSwapFailureAt` BEFORE the statusRef write so the async PROVIDER_READY handler sees a fresh
            // failure timestamp and skips overwriting Error within the guard window.
            ZIO.succeed(recentSwapFailureAt.set(java.lang.System.currentTimeMillis())) *>
              providerRef.set(oldProvider) *>
              providerNameRef.set(oldName) *>
              state.statusRef.set(ProviderStatus.Error)
          )
        // 4. Mark ready — the Java SDK event bridge will also fire PROVIDER_READY,
        //    but we set it explicitly for immediate visibility
        _ <- state.statusRef.set(ProviderStatus.Ready)
      } yield ()
    }

  // Shutdown API (spec 1.6.1, 1.6.2)

  override def shutdown: UIO[Unit] =
    ZIO.collectAllParDiscard(
      List(
        state.statusRef.set(ProviderStatus.NotReady),
        state.hooksRef.set(List.empty),
        state.globalContextRef.set(EvaluationContext.empty),
        state.clientContextRef.set(EvaluationContext.empty),
        state.trackRecorder.set(List.empty)
      )
    ) *> state.eventHub.shutdown *> ZIO.attemptBlocking(api.shutdown()).ignore

  // Tracking API

  override def track(eventName: String): IO[FeatureFlagError, Unit] =
    effectiveContext(EvaluationContext.empty).flatMap { merged =>
      state.trackRecorder.update(_ :+ (eventName, merged, None)) *>
        ZIO
          .attemptBlocking {
            val ofContext = ContextConverter.toOpenFeature(merged)
            client.track(eventName, ofContext)
          }
          .mapError(e => FeatureFlagError.classify(e))
    }

  override def track(eventName: String, context: EvaluationContext): IO[FeatureFlagError, Unit] =
    effectiveContext(context).flatMap { merged =>
      state.trackRecorder.update(_ :+ (eventName, merged, None)) *>
        ZIO
          .attemptBlocking {
            val ofContext = ContextConverter.toOpenFeature(merged)
            client.track(eventName, ofContext)
          }
          .mapError(e => FeatureFlagError.classify(e))
    }

  override def track(eventName: String, details: TrackingEventDetails): IO[FeatureFlagError, Unit] =
    effectiveContext(EvaluationContext.empty).flatMap { merged =>
      state.trackRecorder.update(_ :+ (eventName, merged, Some(details))) *>
        ZIO
          .attemptBlocking {
            val ofContext = ContextConverter.toOpenFeature(merged)
            val ofDetails = toOpenFeatureDetails(details)
            client.track(eventName, ofContext, ofDetails)
          }
          .mapError(e => FeatureFlagError.classify(e))
    }

  override def track(
    eventName: String,
    context: EvaluationContext,
    details: TrackingEventDetails
  ): IO[FeatureFlagError, Unit] =
    effectiveContext(context).flatMap { merged =>
      state.trackRecorder.update(_ :+ (eventName, merged, Some(details))) *>
        ZIO
          .attemptBlocking {
            val ofContext = ContextConverter.toOpenFeature(merged)
            val ofDetails = toOpenFeatureDetails(details)
            client.track(eventName, ofContext, ofDetails)
          }
          .mapError(e => FeatureFlagError.classify(e))
    }

  override def trackedEvents: UIO[List[(String, EvaluationContext, Option[TrackingEventDetails])]] =
    state.trackRecorder.get

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
      case b: Boolean                 => details.add(key, b)
      case s: String                  => details.add(key, s)
      case i: Int                     => details.add(key, Integer.valueOf(i))
      case l: Long                    => details.add(key, java.lang.Double.valueOf(l.toDouble))
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
