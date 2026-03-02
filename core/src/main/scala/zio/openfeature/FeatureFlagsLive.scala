package zio.openfeature

import zio._
import zio.stream._
import zio.openfeature.internal.ContextConverter
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
  provider: OFFeatureProvider,
  providerName: String,
  domain: Option[String],
  globalContextRef: Ref[EvaluationContext],
  clientContextRef: Ref[EvaluationContext],
  fiberContextRef: FiberRef[EvaluationContext],
  transactionRef: FiberRef[Option[TransactionState]],
  hooksRef: Ref[List[FeatureHook]],
  eventHub: Hub[ProviderEvent],
  statusRef: Ref[ProviderStatus]
) extends FeatureFlags {

  // Bridge Java SDK provider events to ZIO event system
  private[openfeature] def startEventBridge: ZIO[Scope, Nothing, Unit] = {
    val metadata = ProviderMetadata(providerName)

    val readyHandler: java.util.function.Consumer[EventDetails] = _ =>
      Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe
          .run(
            statusRef.set(ProviderStatus.Ready) *>
              eventHub.publish(ProviderEvent.Ready(metadata))
          )
          .getOrThrowFiberFailure()
      }

    val errorHandler: java.util.function.Consumer[EventDetails] = details =>
      Unsafe.unsafe { implicit u =>
        val error     = new RuntimeException(Option(details.getMessage).getOrElse("Provider error"))
        val errorCode = Option(details.getErrorCode).map(toErrorCode)
        Runtime.default.unsafe
          .run(
            statusRef.set(ProviderStatus.Error) *>
              eventHub.publish(
                ProviderEvent.Error(error, metadata, errorCode, Option(details.getMessage))
              )
          )
          .getOrThrowFiberFailure()
      }

    val staleHandler: java.util.function.Consumer[EventDetails] = details =>
      Unsafe.unsafe { implicit u =>
        val reason = Option(details.getMessage).getOrElse("Provider stale")
        Runtime.default.unsafe
          .run(
            statusRef.set(ProviderStatus.Stale) *>
              eventHub.publish(ProviderEvent.Stale(reason, metadata))
          )
          .getOrThrowFiberFailure()
      }

    val configHandler: java.util.function.Consumer[EventDetails] = details =>
      Unsafe.unsafe { implicit u =>
        val flags = Option(details.getFlagsChanged)
          .map(_.asScala.toSet)
          .getOrElse(Set.empty[String])
        Runtime.default.unsafe
          .run(
            eventHub.publish(ProviderEvent.ConfigurationChanged(flags, metadata))
          )
          .getOrThrowFiberFailure()
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

  // Context merges in order per OpenFeature spec: API (global) -> Client -> Transaction -> Invocation
  private def effectiveContext(invocation: EvaluationContext): UIO[EvaluationContext] =
    for {
      global      <- globalContextRef.get
      clientCtx   <- clientContextRef.get
      fiberLocal  <- fiberContextRef.get
      transaction <- transactionRef.get
      txContext = transaction.map(_.context).getOrElse(EvaluationContext.empty)
    } yield global
      .merge(clientCtx)
      .merge(fiberLocal)
      .merge(txContext)
      .merge(invocation)

  private def runWithHooks[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    evaluate: EvaluationContext => IO[FeatureFlagError, FlagResolution[A]]
  ): IO[FeatureFlagError, FlagResolution[A]] =
    for {
      currentHooks <- hooksRef.get
      provHooks  = getProviderHooks
      allHooks   = currentHooks ++ provHooks
      metadata   = ProviderMetadata(providerName)
      clientMeta = ClientMetadata(domain)
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
        else runHookPipeline(hookCtx, allHooks, context, evaluate)
    } yield result

  private def runHookPipeline[A](
    hookCtx: HookContext,
    hooks: List[FeatureHook],
    context: EvaluationContext,
    evaluate: EvaluationContext => IO[FeatureFlagError, FlagResolution[A]]
  ): IO[FeatureFlagError, FlagResolution[A]] = {
    val composedHook = FeatureHook.compose(hooks)

    for {
      beforeResult <- composedHook.before(hookCtx, HookHints.empty)
      (effectiveCtx, hints) = beforeResult.getOrElse((context, HookHints.empty))
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
      case _                       => ZIO.unit
    }

  private def evaluateFlag[A: FlagType](
    key: String,
    default: A,
    invocationContext: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[A]] =
    for {
      _         <- checkProviderStatus
      txState   <- transactionRef.get
      effectCtx <- effectiveContext(invocationContext)
      result <- txState match {
        case Some(state) => evaluateWithTransaction(key, default, effectCtx, state)
        case None        => evaluateFromClient(key, default, effectCtx)
      }
    } yield result

  private def evaluateWithTransaction[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    state: TransactionState
  ): IO[FeatureFlagError, FlagResolution[A]] =
    // First check for explicit overrides
    state.getOverride(key) match {
      case Some(overrideValue) =>
        val flagType = FlagType[A]
        flagType.decode(overrideValue) match {
          case Right(decoded) =>
            val resolution = FlagResolution.cached(key, decoded)
            FlagEvaluation.overridden(key, decoded).flatMap { eval =>
              state.record(eval).as(resolution)
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
        state.getCachedEvaluation(key).flatMap {
          case Some(cached) =>
            val flagType = FlagType[A]
            flagType.decode(cached.value) match {
              case Right(decoded) =>
                ZIO.succeed(FlagResolution.cached(key, decoded))
              case Left(_) =>
                // Type mismatch with cached value - re-evaluate from client
                evaluateAndCache(key, default, context, state)
            }
          case None =>
            evaluateAndCache(key, default, context, state)
        }
    }

  private def evaluateAndCache[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    state: TransactionState
  ): IO[FeatureFlagError, FlagResolution[A]] =
    for {
      resolution <- evaluateFromClient(key, default, context)
      eval       <- zio.openfeature.FlagEvaluation.evaluated(key, resolution)
      _          <- state.record(eval)
    } yield resolution

  private def evaluateFromClient[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[A]] = {
    val flagType  = FlagType[A]
    val ofContext = ContextConverter.toOpenFeature(context)

    val evaluation: IO[FeatureFlagError, FlagResolution[A]] = flagType.typeName match {
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
            flagType.decode(rawValue) match {
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
              case Left(_) =>
                ZIO.fail(FeatureFlagError.TypeMismatch(key, flagType.typeName, "Object"))
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
    if (reason == null) ResolutionReason.Unknown
    else
      reason.toUpperCase match {
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

  private def toErrorCode(errorCode: OFErrorCode): ErrorCode =
    errorCode match {
      case OFErrorCode.PROVIDER_NOT_READY    => ErrorCode.ProviderNotReady
      case OFErrorCode.PROVIDER_FATAL        => ErrorCode.ProviderFatal
      case OFErrorCode.FLAG_NOT_FOUND        => ErrorCode.FlagNotFound
      case OFErrorCode.PARSE_ERROR           => ErrorCode.ParseError
      case OFErrorCode.TYPE_MISMATCH         => ErrorCode.TypeMismatch
      case OFErrorCode.TARGETING_KEY_MISSING => ErrorCode.TargetingKeyMissing
      case OFErrorCode.INVALID_CONTEXT       => ErrorCode.InvalidContext
      case OFErrorCode.GENERAL               => ErrorCode.General
    }

  private def toFlagMetadata(metadata: dev.openfeature.sdk.ImmutableMetadata): FlagMetadata =
    if (metadata == null || metadata.isEmpty) FlagMetadata.empty
    else
      try {
        val javaMap = metadata.asUnmodifiableMap()
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
      } catch { case _: Exception => FlagMetadata.empty }

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
        .map { case (k, v) => k -> valueToAny(v) }
        .toMap

  private def valueToAny(value: dev.openfeature.sdk.Value): Any =
    if (value == null) null
    else if (value.isBoolean) value.asBoolean()
    else if (value.isString) value.asString()
    else if (value.isNumber) value.asDouble()
    else if (value.isList) value.asList().asScala.map(valueToAny).toList
    else if (value.isStructure) valueToMap(value)
    else if (value.isInstant) value.asInstant()
    else null

  // Core evaluation method - the single entry point for all flag evaluations
  override def valueDetails[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[A]] =
    effectiveContext(ctx).flatMap { effectCtx =>
      if (options == EvaluationOptions.empty)
        runWithHooks(key, default, effectCtx, c => evaluateFlag(key, default, c))
      else
        runWithAllHooks(key, default, effectCtx, options, c => evaluateFlag(key, default, c))
    }

  private def runWithAllHooks[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    options: EvaluationOptions,
    evaluate: EvaluationContext => IO[FeatureFlagError, FlagResolution[A]]
  ): IO[FeatureFlagError, FlagResolution[A]] =
    for {
      clientHooks <- hooksRef.get
      provHooks = getProviderHooks
      // Combine client + invocation + provider hooks (per spec order)
      allHooks   = clientHooks ++ options.hooks ++ provHooks
      metadata   = ProviderMetadata(providerName)
      clientMeta = ClientMetadata(domain)
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
        else runHookPipelineWithHints(hookCtx, allHooks, context, options.hookHints, evaluate)
    } yield result

  private def runHookPipelineWithHints[A](
    hookCtx: HookContext,
    hooks: List[FeatureHook],
    context: EvaluationContext,
    initialHints: HookHints,
    evaluate: EvaluationContext => IO[FeatureFlagError, FlagResolution[A]]
  ): IO[FeatureFlagError, FlagResolution[A]] = {
    val composedHook = FeatureHook.compose(hooks)

    for {
      beforeResult <- composedHook.before(hookCtx, initialHints)
      (effectiveCtx, hints) = beforeResult.getOrElse((context, initialHints))
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

  override def setGlobalContext(ctx: EvaluationContext): UIO[Unit] =
    globalContextRef.set(ctx)

  override def globalContext: UIO[EvaluationContext] =
    globalContextRef.get

  override def setClientContext(ctx: EvaluationContext): UIO[Unit] =
    clientContextRef.set(ctx)

  override def clientContext: UIO[EvaluationContext] =
    clientContextRef.get

  override def withContext[R, E, A](ctx: EvaluationContext)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    fiberContextRef.get.flatMap { current =>
      fiberContextRef.locally(current.merge(ctx))(zio)
    }

  override def transaction[R, E, A](
    overrides: Map[String, Any],
    context: EvaluationContext,
    cacheEvaluations: Boolean
  )(zio: ZIO[R, E, A]): ZIO[R, Compat.OrError[E, FeatureFlagError], TransactionResult[A]] =
    for {
      current <- transactionRef.get
      _ <- ZIO.when(current.isDefined)(
        ZIO.fail(FeatureFlagError.NestedTransactionNotAllowed)
      )
      state    <- TransactionState.make(overrides, context, cacheEvaluations)
      result   <- transactionRef.locally(Some(state))(zio)
      txResult <- state.toResult(result)
    } yield txResult

  override def inTransaction: UIO[Boolean] =
    transactionRef.get.map(_.isDefined)

  override def currentEvaluatedFlags: UIO[Map[String, zio.openfeature.FlagEvaluation[_]]] =
    transactionRef.get.flatMap {
      case Some(state) => state.getEvaluations
      case None        => ZIO.succeed(Map.empty)
    }

  override def events: ZStream[Any, Nothing, ProviderEvent] =
    ZStream.fromHub(eventHub)

  override def providerStatus: UIO[ProviderStatus] =
    statusRef.get

  override def providerMetadata: UIO[ProviderMetadata] =
    ZIO.succeed(ProviderMetadata(providerName))

  override def clientMetadata: UIO[ClientMetadata] =
    ZIO.succeed(ClientMetadata(domain))

  // Event Handlers - return cancellation effects per OpenFeature spec 5.2.7

  /** Per OpenFeature spec 5.3.3, handlers attached after the provider reaches an associated state MUST run immediately.
    */
  override def onProviderReady(handler: ProviderMetadata => UIO[Unit]): UIO[UIO[Unit]] =
    for {
      // Check if provider is already ready and call handler immediately if so (spec 5.3.3)
      status <- providerStatus
      metadata = ProviderMetadata(providerName)
      _ <- ZIO.when(status == ProviderStatus.Ready)(handler(metadata))
      // Subscribe to future events
      fiber <- events
        .collect { case ProviderEvent.Ready(m) => m }
        .foreach(handler)
        .forkDaemon
    } yield fiber.interrupt.unit

  override def onProviderError(handler: (Throwable, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] =
    for {
      // Check if provider is already in error state (spec 5.3.3)
      status <- providerStatus
      metadata = ProviderMetadata(providerName)
      _ <- ZIO.when(status == ProviderStatus.Error || status == ProviderStatus.Fatal) {
        // For immediate execution on error, we don't have the error details, so use a generic error
        handler(new RuntimeException("Provider in error state"), metadata)
      }
      fiber <- events
        .collect { case ProviderEvent.Error(error, m, _, _) => (error, m) }
        .foreach { case (error, m) => handler(error, m) }
        .forkDaemon
    } yield fiber.interrupt.unit

  override def onProviderStale(handler: (String, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] =
    for {
      // Check if provider is already stale (spec 5.3.3)
      status <- providerStatus
      metadata = ProviderMetadata(providerName)
      _ <- ZIO.when(status == ProviderStatus.Stale) {
        handler("Provider in stale state", metadata)
      }
      fiber <- events
        .collect { case ProviderEvent.Stale(reason, m) => (reason, m) }
        .foreach { case (reason, m) => handler(reason, m) }
        .forkDaemon
    } yield fiber.interrupt.unit

  override def onConfigurationChanged(handler: (Set[String], ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] =
    // Configuration changed doesn't have an "associated state" so no immediate execution needed
    events
      .collect { case ProviderEvent.ConfigurationChanged(flags, m) => (flags, m) }
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
    hooksRef.update(_ :+ hook)

  override def clearHooks: UIO[Unit] =
    hooksRef.set(List.empty)

  override def hooks: UIO[List[FeatureHook]] =
    hooksRef.get

  // Provider hooks (spec: provider hooks included in hook pipeline)

  private def getProviderHooks: List[FeatureHook] =
    try {
      val javaHooks = provider.getProviderHooks
      if (javaHooks == null || javaHooks.isEmpty) Nil
      else javaHooks.asScala.toList.map(wrapJavaHook)
    } catch { case _: Exception => Nil }

  @scala.annotation.nowarn("msg=deprecated")
  private def toJavaHookContext(ctx: HookContext): JavaHookContext[Any] =
    JavaHookContext.from[Any](
      ctx.flagKey,
      toJavaFlagValueType(ctx.flagType),
      new dev.openfeature.sdk.ClientMetadata {
        def getDomain: String = domain.orNull
      },
      new dev.openfeature.sdk.Metadata {
        def getName: String = providerName
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
    res.errorCode.foreach(ec => details.setErrorCode(toJavaErrorCode(ec)))
    res.errorMessage.foreach(details.setErrorMessage)
    details
  }

  private def toJavaErrorCode(ec: ErrorCode): OFErrorCode = ec match {
    case ErrorCode.ProviderNotReady    => OFErrorCode.PROVIDER_NOT_READY
    case ErrorCode.ProviderFatal       => OFErrorCode.PROVIDER_FATAL
    case ErrorCode.FlagNotFound        => OFErrorCode.FLAG_NOT_FOUND
    case ErrorCode.ParseError          => OFErrorCode.PARSE_ERROR
    case ErrorCode.TypeMismatch        => OFErrorCode.TYPE_MISMATCH
    case ErrorCode.TargetingKeyMissing => OFErrorCode.TARGETING_KEY_MISSING
    case ErrorCode.InvalidContext      => OFErrorCode.INVALID_CONTEXT
    case ErrorCode.General             => OFErrorCode.GENERAL
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
          val ex     = err.cause.getOrElse(new RuntimeException(err.message))
          hook.error(jCtx, ex.asInstanceOf[Exception], jHints)
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

  // Shutdown API (spec 1.6.1, 1.6.2)

  override def shutdown: UIO[Unit] =
    for {
      _ <- statusRef.set(ProviderStatus.NotReady)
      _ <- hooksRef.set(List.empty)
      _ <- globalContextRef.set(EvaluationContext.empty)
      _ <- clientContextRef.set(EvaluationContext.empty)
      _ <- eventHub.shutdown
      _ <- ZIO.attemptBlocking(OpenFeatureAPI.getInstance().shutdown()).ignore
    } yield ()

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
