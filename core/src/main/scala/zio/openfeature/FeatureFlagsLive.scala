package zio.openfeature

import zio.*
import zio.stream.*
import zio.openfeature.internal.ContextConverter
import dev.openfeature.sdk.{
  Client as OFClient,
  FeatureProvider as OFFeatureProvider,
  FlagEvaluationDetails,
  Reason as OFReason,
  ErrorCode as OFErrorCode,
  ProviderState,
  MutableTrackingEventDetails
}
import scala.jdk.CollectionConverters.*

final private[openfeature] class FeatureFlagsLive(
  client: OFClient,
  provider: OFFeatureProvider,
  providerName: String,
  globalContextRef: Ref[EvaluationContext],
  fiberContextRef: FiberRef[EvaluationContext],
  transactionRef: FiberRef[Option[TransactionState]],
  hooksRef: Ref[List[FeatureHook]],
  eventHub: Hub[ProviderEvent]
) extends FeatureFlags:

  private def effectiveContext(invocation: EvaluationContext): UIO[EvaluationContext] =
    for
      global      <- globalContextRef.get
      fiberLocal  <- fiberContextRef.get
      transaction <- transactionRef.get
      txContext = transaction.map(_.context).getOrElse(EvaluationContext.empty)
    yield global
      .merge(fiberLocal)
      .merge(txContext)
      .merge(invocation)

  private def runWithHooks[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    evaluate: EvaluationContext => IO[FeatureFlagError, FlagResolution[A]]
  ): IO[FeatureFlagError, FlagResolution[A]] =
    for
      currentHooks <- hooksRef.get
      metadata = ProviderMetadata(providerName)
      hookCtx = HookContext(
        flagKey = key,
        flagType = FlagValueType.fromFlagType[A],
        defaultValue = default,
        evaluationContext = context,
        providerMetadata = metadata
      )
      result <-
        if currentHooks.isEmpty then evaluate(context)
        else runHookPipeline(hookCtx, currentHooks, context, evaluate)
    yield result

  private def runHookPipeline[A](
    hookCtx: HookContext,
    hooks: List[FeatureHook],
    context: EvaluationContext,
    evaluate: EvaluationContext => IO[FeatureFlagError, FlagResolution[A]]
  ): IO[FeatureFlagError, FlagResolution[A]] =
    val composedHook = FeatureHook.compose(hooks)

    for
      beforeResult <- composedHook.before(hookCtx, HookHints.empty)
      (effectiveCtx, hints) = beforeResult.getOrElse((context, HookHints.empty))
      result <- evaluate(effectiveCtx)
        .tapBoth(
          err => composedHook.error(hookCtx, err, hints),
          res => composedHook.after(hookCtx, res, hints)
        )
        .ensuring(composedHook.finallyAfter(hookCtx, hints).ignore)
    yield result

  private def evaluateFlag[A: FlagType](
    key: String,
    default: A,
    invocationContext: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[A]] =
    for
      txState   <- transactionRef.get
      effectCtx <- effectiveContext(invocationContext)
      result <- txState match
        case Some(state) => evaluateWithTransaction(key, default, effectCtx, state)
        case None        => evaluateFromClient(key, default, effectCtx)
    yield result

  private def evaluateWithTransaction[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    state: TransactionState
  ): IO[FeatureFlagError, FlagResolution[A]] =
    // First check for explicit overrides
    state.getOverride(key) match
      case Some(overrideValue) =>
        val flagType = FlagType[A]
        flagType.decode(overrideValue) match
          case Right(decoded) =>
            val resolution = FlagResolution.cached(key, decoded)
            FlagEvaluation.overridden(key, decoded).flatMap { eval =>
              state.record(eval).as(resolution)
            }
          case Left(error) =>
            ZIO.fail(
              FeatureFlagError.OverrideTypeMismatch(
                key,
                flagType.typeName,
                overrideValue.getClass.getSimpleName
              )
            )

      case None =>
        // Check for cached evaluation from previous call in this transaction
        state.getCachedEvaluation(key).flatMap {
          case Some(cached) =>
            val flagType = FlagType[A]
            flagType.decode(cached.value) match
              case Right(decoded) =>
                ZIO.succeed(FlagResolution.cached(key, decoded))
              case Left(_) =>
                // Type mismatch with cached value - re-evaluate from client
                evaluateAndCache(key, default, context, state)
          case None =>
            evaluateAndCache(key, default, context, state)
        }

  private def evaluateAndCache[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    state: TransactionState
  ): IO[FeatureFlagError, FlagResolution[A]] =
    for
      resolution <- evaluateFromClient(key, default, context)
      eval       <- zio.openfeature.FlagEvaluation.evaluated(key, resolution)
      _          <- state.record(eval)
    yield resolution

  private def evaluateFromClient[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[A]] =
    val flagType  = FlagType[A]
    val ofContext = ContextConverter.toOpenFeature(context)

    val evaluation: IO[FeatureFlagError, FlagResolution[A]] = flagType.typeName match
      case "Boolean" =>
        ZIO
          .attemptBlocking {
            client.getBooleanDetails(key, default.asInstanceOf[Boolean], ofContext)
          }
          .mapError(e => FeatureFlagError.ProviderError(e))
          .map(details => toFlagResolution(key, details).asInstanceOf[FlagResolution[A]])

      case "String" =>
        ZIO
          .attemptBlocking {
            client.getStringDetails(key, default.asInstanceOf[String], ofContext)
          }
          .mapError(e => FeatureFlagError.ProviderError(e))
          .map(details => toFlagResolution(key, details).asInstanceOf[FlagResolution[A]])

      case "Int" =>
        ZIO
          .attemptBlocking {
            client.getIntegerDetails(key, Integer.valueOf(default.asInstanceOf[Int]), ofContext)
          }
          .mapError(e => FeatureFlagError.ProviderError(e))
          .map { details =>
            val resolution = toFlagResolution(key, details)
            resolution.copy(value = details.getValue.intValue()).asInstanceOf[FlagResolution[A]]
          }

      case "Long" =>
        ZIO
          .attemptBlocking {
            client.getIntegerDetails(key, Integer.valueOf(default.asInstanceOf[Long].toInt), ofContext)
          }
          .mapError(e => FeatureFlagError.ProviderError(e))
          .map { details =>
            val resolution = toFlagResolution(key, details)
            resolution.copy(value = details.getValue.longValue()).asInstanceOf[FlagResolution[A]]
          }

      case "Float" =>
        ZIO
          .attemptBlocking {
            client.getDoubleDetails(key, java.lang.Double.valueOf(default.asInstanceOf[Float].toDouble), ofContext)
          }
          .mapError(e => FeatureFlagError.ProviderError(e))
          .map { details =>
            val resolution = toFlagResolution(key, details)
            resolution.copy(value = details.getValue.floatValue()).asInstanceOf[FlagResolution[A]]
          }

      case "Double" =>
        ZIO
          .attemptBlocking {
            client.getDoubleDetails(key, java.lang.Double.valueOf(default.asInstanceOf[Double]), ofContext)
          }
          .mapError(e => FeatureFlagError.ProviderError(e))
          .map(details => toFlagResolution(key, details).asInstanceOf[FlagResolution[A]])

      case "Object" =>
        ZIO
          .attemptBlocking {
            val defaultValue = new dev.openfeature.sdk.Value(
              dev.openfeature.sdk.Structure.mapToStructure(
                default.asInstanceOf[Map[String, Any]].map { case (k, v) => k -> anyToObject(v) }.asJava
              )
            )
            client.getObjectDetails(key, defaultValue, ofContext)
          }
          .mapError(e => FeatureFlagError.ProviderError(e))
          .map { details =>
            val value = valueToMap(details.getValue)
            FlagResolution(
              value = value.asInstanceOf[A],
              variant = Option(details.getVariant),
              reason = toResolutionReason(details.getReason),
              metadata = toFlagMetadata(details.getFlagMetadata),
              flagKey = key,
              errorCode = Option(details.getErrorCode).map(toErrorCode),
              errorMessage = Option(details.getErrorMessage)
            )
          }

      case _ =>
        // Custom type - try to decode from object
        ZIO
          .attemptBlocking {
            client.getObjectDetails(key, new dev.openfeature.sdk.Value(), ofContext)
          }
          .mapError(e => FeatureFlagError.ProviderError(e))
          .flatMap { details =>
            val rawValue = valueToAny(details.getValue)
            flagType.decode(rawValue) match
              case Right(decoded) =>
                ZIO.succeed(
                  FlagResolution(
                    value = decoded,
                    variant = Option(details.getVariant),
                    reason = toResolutionReason(details.getReason),
                    metadata = toFlagMetadata(details.getFlagMetadata),
                    flagKey = key,
                    errorCode = Option(details.getErrorCode).map(toErrorCode),
                    errorMessage = Option(details.getErrorMessage)
                  )
                )
              case Left(error) =>
                ZIO.fail(FeatureFlagError.TypeMismatch(key, flagType.typeName, "Object"))
          }

    evaluation

  private def toFlagResolution[A](key: String, details: FlagEvaluationDetails[A]): FlagResolution[A] =
    FlagResolution(
      value = details.getValue,
      variant = Option(details.getVariant),
      reason = toResolutionReason(details.getReason),
      metadata = toFlagMetadata(details.getFlagMetadata),
      flagKey = key,
      errorCode = Option(details.getErrorCode).map(toErrorCode),
      errorMessage = Option(details.getErrorMessage)
    )

  private def toResolutionReason(reason: String): ResolutionReason =
    if reason == null then ResolutionReason.Unknown
    else
      reason.toUpperCase match
        case "STATIC"          => ResolutionReason.Static
        case "DEFAULT"         => ResolutionReason.Default
        case "TARGETING_MATCH" => ResolutionReason.TargetingMatch
        case "SPLIT"           => ResolutionReason.Split
        case "CACHED"          => ResolutionReason.Cached
        case "DISABLED"        => ResolutionReason.Disabled
        case "STALE"           => ResolutionReason.Stale
        case "ERROR"           => ResolutionReason.Error
        case _                 => ResolutionReason.Unknown

  private def toErrorCode(errorCode: OFErrorCode): ErrorCode =
    errorCode match
      case OFErrorCode.PROVIDER_NOT_READY    => ErrorCode.ProviderNotReady
      case OFErrorCode.FLAG_NOT_FOUND        => ErrorCode.FlagNotFound
      case OFErrorCode.PARSE_ERROR           => ErrorCode.ParseError
      case OFErrorCode.TYPE_MISMATCH         => ErrorCode.TypeMismatch
      case OFErrorCode.TARGETING_KEY_MISSING => ErrorCode.TargetingKeyMissing
      case OFErrorCode.INVALID_CONTEXT       => ErrorCode.InvalidContext
      case OFErrorCode.GENERAL               => ErrorCode.General
      case _                                 => ErrorCode.General

  private def toFlagMetadata(metadata: dev.openfeature.sdk.ImmutableMetadata): FlagMetadata =
    // ImmutableMetadata doesn't expose a direct map, so we return empty for now
    // In practice, flag metadata is rarely used and providers don't always populate it
    FlagMetadata.empty

  private def anyToObject(value: Any): Object = value match
    case b: Boolean    => java.lang.Boolean.valueOf(b)
    case s: String     => s
    case i: Int        => java.lang.Integer.valueOf(i)
    case l: Long       => java.lang.Long.valueOf(l)
    case d: Double     => java.lang.Double.valueOf(d)
    case f: Float      => java.lang.Float.valueOf(f)
    case list: List[?] => list.map(anyToObject).asJava
    case map: Map[?, ?] =>
      map.asInstanceOf[Map[String, Any]].map { case (k, v) => k -> anyToObject(v) }.asJava
    case null  => null
    case other => other.toString

  private def valueToMap(value: dev.openfeature.sdk.Value): Map[String, Any] =
    if value == null || !value.isStructure then Map.empty
    else
      value
        .asStructure()
        .asMap()
        .asScala
        .map { case (k, v) => k -> valueToAny(v) }
        .toMap

  private def valueToAny(value: dev.openfeature.sdk.Value): Any =
    if value == null then null
    else if value.isBoolean then value.asBoolean()
    else if value.isString then value.asString()
    else if value.isNumber then value.asDouble()
    else if value.isList then value.asList().asScala.map(valueToAny).toList
    else if value.isStructure then valueToMap(value)
    else if value.isInstant then value.asInstant()
    else null

  override def boolean(key: String, default: Boolean): IO[FeatureFlagError, Boolean] =
    booleanDetails(key, default).map(_.value)

  override def string(key: String, default: String): IO[FeatureFlagError, String] =
    stringDetails(key, default).map(_.value)

  override def int(key: String, default: Int): IO[FeatureFlagError, Int] =
    intDetails(key, default).map(_.value)

  override def long(key: String, default: Long): IO[FeatureFlagError, Long] =
    valueDetails(key, default).map(_.value)

  override def double(key: String, default: Double): IO[FeatureFlagError, Double] =
    doubleDetails(key, default).map(_.value)

  override def obj(key: String, default: Map[String, Any]): IO[FeatureFlagError, Map[String, Any]] =
    valueDetails(key, default).map(_.value)

  override def value[A: FlagType](key: String, default: A): IO[FeatureFlagError, A] =
    valueDetails(key, default).map(_.value)

  override def boolean(key: String, default: Boolean, ctx: EvaluationContext): IO[FeatureFlagError, Boolean] =
    runWithHooks(key, default, ctx, effectCtx => evaluateFlag(key, default, effectCtx)).map(_.value)

  override def string(key: String, default: String, ctx: EvaluationContext): IO[FeatureFlagError, String] =
    runWithHooks(key, default, ctx, effectCtx => evaluateFlag(key, default, effectCtx)).map(_.value)

  override def int(key: String, default: Int, ctx: EvaluationContext): IO[FeatureFlagError, Int] =
    runWithHooks(key, default, ctx, effectCtx => evaluateFlag(key, default, effectCtx)).map(_.value)

  override def double(key: String, default: Double, ctx: EvaluationContext): IO[FeatureFlagError, Double] =
    runWithHooks(key, default, ctx, effectCtx => evaluateFlag(key, default, effectCtx)).map(_.value)

  override def value[A: FlagType](key: String, default: A, ctx: EvaluationContext): IO[FeatureFlagError, A] =
    runWithHooks(key, default, ctx, effectCtx => evaluateFlag(key, default, effectCtx)).map(_.value)

  override def booleanDetails(key: String, default: Boolean): IO[FeatureFlagError, FlagResolution[Boolean]] =
    effectiveContext(EvaluationContext.empty).flatMap { ctx =>
      runWithHooks(key, default, ctx, effectCtx => evaluateFlag(key, default, effectCtx))
    }

  override def stringDetails(key: String, default: String): IO[FeatureFlagError, FlagResolution[String]] =
    effectiveContext(EvaluationContext.empty).flatMap { ctx =>
      runWithHooks(key, default, ctx, effectCtx => evaluateFlag(key, default, effectCtx))
    }

  override def intDetails(key: String, default: Int): IO[FeatureFlagError, FlagResolution[Int]] =
    effectiveContext(EvaluationContext.empty).flatMap { ctx =>
      runWithHooks(key, default, ctx, effectCtx => evaluateFlag(key, default, effectCtx))
    }

  override def doubleDetails(key: String, default: Double): IO[FeatureFlagError, FlagResolution[Double]] =
    effectiveContext(EvaluationContext.empty).flatMap { ctx =>
      runWithHooks(key, default, ctx, effectCtx => evaluateFlag(key, default, effectCtx))
    }

  override def valueDetails[A: FlagType](key: String, default: A): IO[FeatureFlagError, FlagResolution[A]] =
    effectiveContext(EvaluationContext.empty).flatMap { ctx =>
      runWithHooks(key, default, ctx, effectCtx => evaluateFlag(key, default, effectCtx))
    }

  // Evaluation with options (invocation-level hooks)

  private def runWithAllHooks[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    options: EvaluationOptions,
    evaluate: EvaluationContext => IO[FeatureFlagError, FlagResolution[A]]
  ): IO[FeatureFlagError, FlagResolution[A]] =
    for
      clientHooks <- hooksRef.get
      // Combine client hooks with invocation hooks (client first, then invocation)
      allHooks = clientHooks ++ options.hooks
      metadata = ProviderMetadata(providerName)
      hookCtx = HookContext(
        flagKey = key,
        flagType = FlagValueType.fromFlagType[A],
        defaultValue = default,
        evaluationContext = context,
        providerMetadata = metadata
      )
      result <-
        if allHooks.isEmpty then evaluate(context)
        else runHookPipelineWithHints(hookCtx, allHooks, context, options.hookHints, evaluate)
    yield result

  private def runHookPipelineWithHints[A](
    hookCtx: HookContext,
    hooks: List[FeatureHook],
    context: EvaluationContext,
    initialHints: HookHints,
    evaluate: EvaluationContext => IO[FeatureFlagError, FlagResolution[A]]
  ): IO[FeatureFlagError, FlagResolution[A]] =
    val composedHook = FeatureHook.compose(hooks)

    for
      beforeResult <- composedHook.before(hookCtx, initialHints)
      (effectiveCtx, hints) = beforeResult.getOrElse((context, initialHints))
      result <- evaluate(effectiveCtx)
        .tapBoth(
          err => composedHook.error(hookCtx, err, hints),
          res => composedHook.after(hookCtx, res, hints)
        )
        .ensuring(composedHook.finallyAfter(hookCtx, hints).ignore)
    yield result

  override def booleanDetails(
    key: String,
    default: Boolean,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Boolean]] =
    effectiveContext(ctx).flatMap { effectCtx =>
      runWithAllHooks(key, default, effectCtx, options, c => evaluateFlag(key, default, c))
    }

  override def stringDetails(
    key: String,
    default: String,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[String]] =
    effectiveContext(ctx).flatMap { effectCtx =>
      runWithAllHooks(key, default, effectCtx, options, c => evaluateFlag(key, default, c))
    }

  override def intDetails(
    key: String,
    default: Int,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Int]] =
    effectiveContext(ctx).flatMap { effectCtx =>
      runWithAllHooks(key, default, effectCtx, options, c => evaluateFlag(key, default, c))
    }

  override def doubleDetails(
    key: String,
    default: Double,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Double]] =
    effectiveContext(ctx).flatMap { effectCtx =>
      runWithAllHooks(key, default, effectCtx, options, c => evaluateFlag(key, default, c))
    }

  override def valueDetails[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[A]] =
    effectiveContext(ctx).flatMap { effectCtx =>
      runWithAllHooks(key, default, effectCtx, options, c => evaluateFlag(key, default, c))
    }

  override def setGlobalContext(ctx: EvaluationContext): UIO[Unit] =
    globalContextRef.set(ctx)

  override def globalContext: UIO[EvaluationContext] =
    globalContextRef.get

  override def withContext[R, E, A](ctx: EvaluationContext)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    fiberContextRef.get.flatMap { current =>
      fiberContextRef.locally(current.merge(ctx))(zio)
    }

  override def transaction[R, E, A](
    overrides: Map[String, Any],
    context: EvaluationContext,
    cacheEvaluations: Boolean
  )(zio: ZIO[R, E, A]): ZIO[R, E | FeatureFlagError, TransactionResult[A]] =
    for
      current <- transactionRef.get
      _ <- ZIO.when(current.isDefined)(
        ZIO.fail(FeatureFlagError.NestedTransactionNotAllowed)
      )
      state    <- TransactionState.make(overrides, context, cacheEvaluations)
      result   <- transactionRef.locally(Some(state))(zio)
      txResult <- state.toResult(result)
    yield txResult

  override def inTransaction: UIO[Boolean] =
    transactionRef.get.map(_.isDefined)

  override def currentEvaluatedFlags: UIO[Map[String, zio.openfeature.FlagEvaluation[?]]] =
    transactionRef.get.flatMap {
      case Some(state) => state.getEvaluations
      case None        => ZIO.succeed(Map.empty)
    }

  override def events: ZStream[Any, Nothing, ProviderEvent] =
    ZStream.fromHub(eventHub)

  @scala.annotation.nowarn("msg=deprecated")
  override def providerStatus: UIO[ProviderStatus] =
    ZIO.succeed {
      val state = provider.getState
      state match
        case ProviderState.NOT_READY => ProviderStatus.NotReady
        case ProviderState.READY     => ProviderStatus.Ready
        case ProviderState.ERROR     => ProviderStatus.Error
        case ProviderState.STALE     => ProviderStatus.Stale
        case _                       => ProviderStatus.NotReady
    }

  override def providerMetadata: UIO[ProviderMetadata] =
    ZIO.succeed(ProviderMetadata(providerName))

  // Event Handlers

  override def onProviderReady(handler: ProviderMetadata => UIO[Unit]): UIO[Unit] =
    events
      .collect { case ProviderEvent.Ready(metadata) => metadata }
      .foreach(handler)
      .forkDaemon
      .unit

  override def onProviderError(handler: (Throwable, ProviderMetadata) => UIO[Unit]): UIO[Unit] =
    events
      .collect { case ProviderEvent.Error(error, metadata) => (error, metadata) }
      .foreach { case (error, metadata) => handler(error, metadata) }
      .forkDaemon
      .unit

  override def onProviderStale(handler: (String, ProviderMetadata) => UIO[Unit]): UIO[Unit] =
    events
      .collect { case ProviderEvent.Stale(reason, metadata) => (reason, metadata) }
      .foreach { case (reason, metadata) => handler(reason, metadata) }
      .forkDaemon
      .unit

  override def onConfigurationChanged(handler: (Set[String], ProviderMetadata) => UIO[Unit]): UIO[Unit] =
    events
      .collect { case ProviderEvent.ConfigurationChanged(flags, metadata) => (flags, metadata) }
      .foreach { case (flags, metadata) => handler(flags, metadata) }
      .forkDaemon
      .unit

  override def addHook(hook: FeatureHook): UIO[Unit] =
    hooksRef.update(_ :+ hook)

  override def clearHooks: UIO[Unit] =
    hooksRef.set(List.empty)

  override def hooks: UIO[List[FeatureHook]] =
    hooksRef.get

  // Tracking API

  override def track(eventName: String): IO[FeatureFlagError, Unit] =
    ZIO
      .attemptBlocking(client.track(eventName))
      .mapError(e => FeatureFlagError.ProviderError(e))

  override def track(eventName: String, context: EvaluationContext): IO[FeatureFlagError, Unit] =
    ZIO
      .attemptBlocking {
        val ofContext = ContextConverter.toOpenFeature(context)
        client.track(eventName, ofContext)
      }
      .mapError(e => FeatureFlagError.ProviderError(e))

  override def track(eventName: String, details: TrackingEventDetails): IO[FeatureFlagError, Unit] =
    ZIO
      .attemptBlocking {
        val ofDetails = toOpenFeatureDetails(details)
        client.track(eventName, ofDetails)
      }
      .mapError(e => FeatureFlagError.ProviderError(e))

  override def track(
    eventName: String,
    context: EvaluationContext,
    details: TrackingEventDetails
  ): IO[FeatureFlagError, Unit] =
    ZIO
      .attemptBlocking {
        val ofContext = ContextConverter.toOpenFeature(context)
        val ofDetails = toOpenFeatureDetails(details)
        client.track(eventName, ofContext, ofDetails)
      }
      .mapError(e => FeatureFlagError.ProviderError(e))

  private def toOpenFeatureDetails(details: TrackingEventDetails): MutableTrackingEventDetails =
    val result = details.value match
      case Some(v) => new MutableTrackingEventDetails(v)
      case None    => new MutableTrackingEventDetails()
    details.attributes.foreach { case (k, v) =>
      addAttributeToDetails(result, k, v)
    }
    result

  private def addAttributeToDetails(details: MutableTrackingEventDetails, key: String, value: Any): Unit =
    value match
      case b: Boolean                 => details.add(key, b)
      case s: String                  => details.add(key, s)
      case i: Int                     => details.add(key, Integer.valueOf(i))
      case l: Long                    => details.add(key, java.lang.Double.valueOf(l.toDouble))
      case d: Double                  => details.add(key, d)
      case f: Float                   => details.add(key, java.lang.Double.valueOf(f.toDouble))
      case instant: java.time.Instant => details.add(key, instant)
      case list: List[?] =>
        val values = list.map(v => new dev.openfeature.sdk.Value(anyToObject(v))).asJava
        details.add(key, values)
      case map: Map[?, ?] =>
        val structure = dev.openfeature.sdk.Structure.mapToStructure(
          map.asInstanceOf[Map[String, Any]].map { case (k, v) => k -> anyToObject(v) }.asJava
        )
        details.add(key, structure)
      case null  => () // Skip null values
      case other => details.add(key, other.toString)
