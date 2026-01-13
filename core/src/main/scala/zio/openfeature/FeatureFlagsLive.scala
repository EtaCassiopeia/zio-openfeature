package zio.openfeature

import zio.*
import zio.stream.*
import zio.openfeature.internal.{ContextConverter, ValueConverter}
import dev.openfeature.sdk.{
  Client as OFClient,
  EvaluationContext as OFEvaluationContext,
  FeatureProvider as OFFeatureProvider,
  ProviderState,
  MutableTrackingEventDetails
}
import scala.jdk.CollectionConverters.*

/** Live implementation of FeatureFlags that wraps the OpenFeature Java SDK.
  */
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
  eventHub: Hub[ProviderEvent]
) extends FeatureFlags:

  // ==================== Context Management ====================

  /** Compute effective context by merging all context levels.
    *
    * Merge order per OpenFeature spec: API (global) -> Client -> Scoped -> Transaction -> Invocation
    */
  private def effectiveContext(invocation: EvaluationContext): UIO[EvaluationContext] =
    for
      global      <- globalContextRef.get
      clientCtx   <- clientContextRef.get
      fiberLocal  <- fiberContextRef.get
      transaction <- transactionRef.get
      txContext = transaction.map(_.context).getOrElse(EvaluationContext.empty)
    yield global
      .merge(clientCtx)
      .merge(fiberLocal)
      .merge(txContext)
      .merge(invocation)

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

  // ==================== Hook Execution ====================

  /** Run the hook pipeline with the given hooks and hints. */
  private def runHookPipeline[A](
    hookCtx: HookContext,
    hooks: List[FeatureHook],
    context: EvaluationContext,
    hints: HookHints,
    evaluate: EvaluationContext => IO[FeatureFlagError, FlagResolution[A]]
  ): IO[FeatureFlagError, FlagResolution[A]] =
    if hooks.isEmpty then evaluate(context)
    else
      val composedHook = FeatureHook.compose(hooks)
      for
        beforeResult <- composedHook.before(hookCtx, hints)
        (effectiveCtx, finalHints) = beforeResult.getOrElse((context, hints))
        result <- evaluate(effectiveCtx)
          .tapBoth(
            err => composedHook.error(hookCtx, err, finalHints),
            res => composedHook.after(hookCtx, res, finalHints)
          )
          .ensuring(composedHook.finallyAfter(hookCtx, finalHints).ignore)
      yield result

  /** Create hook context for an evaluation. */
  private def createHookContext[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext
  ): HookContext =
    HookContext(
      flagKey = key,
      flagType = FlagValueType.fromFlagType[A],
      defaultValue = default,
      evaluationContext = context,
      providerMetadata = ProviderMetadata(providerName)
    )

  override def addHook(hook: FeatureHook): UIO[Unit] =
    hooksRef.update(_ :+ hook)

  override def clearHooks: UIO[Unit] =
    hooksRef.set(List.empty)

  override def hooks: UIO[List[FeatureHook]] =
    hooksRef.get

  // ==================== Flag Evaluation ====================

  /** Core evaluation method that handles transactions, hooks, and provider calls. */
  private def evaluate[A: FlagType](
    key: String,
    default: A,
    invocationContext: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[A]] =
    for
      clientHooks <- hooksRef.get
      allHooks = clientHooks ++ options.hooks
      effectCtx <- effectiveContext(invocationContext)
      hookCtx = createHookContext(key, default, effectCtx)
      result <- runHookPipeline(hookCtx, allHooks, effectCtx, options.hookHints, evaluateWithContext(key, default))
    yield result

  /** Evaluate a flag with the given context, handling transactions. */
  private def evaluateWithContext[A: FlagType](
    key: String,
    default: A
  )(context: EvaluationContext): IO[FeatureFlagError, FlagResolution[A]] =
    transactionRef.get.flatMap {
      case Some(state) => evaluateWithTransaction(key, default, context, state)
      case None        => evaluateFromClient(key, default, context)
    }

  /** Evaluate within a transaction, checking overrides and cache. */
  private def evaluateWithTransaction[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    state: TransactionState
  ): IO[FeatureFlagError, FlagResolution[A]] =
    state.getOverride(key) match
      case Some(overrideValue) =>
        handleOverride(key, overrideValue, state)
      case None =>
        state.getCachedEvaluation(key).flatMap {
          case Some(cached) => handleCachedEvaluation(key, default, context, state, cached)
          case None         => evaluateAndRecord(key, default, context, state)
        }

  /** Handle an override value from a transaction. */
  private def handleOverride[A: FlagType](
    key: String,
    overrideValue: Any,
    state: TransactionState
  ): IO[FeatureFlagError, FlagResolution[A]] =
    val flagType = FlagType[A]
    flagType.decode(overrideValue) match
      case Right(decoded) =>
        val resolution = FlagResolution.cached(key, decoded)
        FlagEvaluation.overridden(key, decoded).flatMap(state.record(_)).as(resolution)
      case Left(_) =>
        ZIO.fail(FeatureFlagError.OverrideTypeMismatch(key, flagType.typeName, overrideValue.getClass.getSimpleName))

  /** Handle a cached evaluation from a transaction. */
  private def handleCachedEvaluation[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    state: TransactionState,
    cached: FlagEvaluation[?]
  ): IO[FeatureFlagError, FlagResolution[A]] =
    val flagType = FlagType[A]
    flagType.decode(cached.value) match
      case Right(decoded) => ZIO.succeed(FlagResolution.cached(key, decoded))
      case Left(_)        => evaluateAndRecord(key, default, context, state)

  /** Evaluate from client and record in transaction state. */
  private def evaluateAndRecord[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    state: TransactionState
  ): IO[FeatureFlagError, FlagResolution[A]] =
    for
      resolution <- evaluateFromClient(key, default, context)
      eval       <- FlagEvaluation.evaluated(key, resolution)
      _          <- state.record(eval)
    yield resolution

  /** Call the OpenFeature SDK client to evaluate a flag. */
  private def evaluateFromClient[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[A]] =
    val flagType  = FlagType[A]
    val ofContext = ContextConverter.toOpenFeature(context)

    flagType.typeName match
      case "Boolean" =>
        evaluateBoolean(key, default.asInstanceOf[Boolean], ofContext)
          .asInstanceOf[IO[FeatureFlagError, FlagResolution[A]]]
      case "String" =>
        evaluateString(key, default.asInstanceOf[String], ofContext)
          .asInstanceOf[IO[FeatureFlagError, FlagResolution[A]]]
      case "Int" =>
        evaluateInt(key, default.asInstanceOf[Int], ofContext).asInstanceOf[IO[FeatureFlagError, FlagResolution[A]]]
      case "Long" =>
        evaluateLong(key, default.asInstanceOf[Long], ofContext).asInstanceOf[IO[FeatureFlagError, FlagResolution[A]]]
      case "Float" =>
        evaluateFloat(key, default.asInstanceOf[Float], ofContext).asInstanceOf[IO[FeatureFlagError, FlagResolution[A]]]
      case "Double" =>
        evaluateDouble(key, default.asInstanceOf[Double], ofContext)
          .asInstanceOf[IO[FeatureFlagError, FlagResolution[A]]]
      case "Object" =>
        evaluateObject(key, default.asInstanceOf[Map[String, Any]], ofContext)
          .asInstanceOf[IO[FeatureFlagError, FlagResolution[A]]]
      case _ => evaluateCustom(key, default, ofContext)

  /** Helper to create FlagResolution from SDK details. */
  private def toResolution[A](
    key: String,
    value: A,
    details: dev.openfeature.sdk.FlagEvaluationDetails[?]
  ): FlagResolution[A] =
    FlagResolution(
      value = value,
      variant = Option(details.getVariant),
      reason = ValueConverter.toResolutionReason(details.getReason),
      metadata = ValueConverter.toFlagMetadata(details.getFlagMetadata),
      flagKey = key,
      errorCode = Option(details.getErrorCode).map(ValueConverter.toErrorCode),
      errorMessage = Option(details.getErrorMessage)
    )

  private def evaluateBoolean(
    key: String,
    default: Boolean,
    ctx: OFEvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Boolean]] =
    ZIO
      .attemptBlocking(client.getBooleanDetails(key, default, ctx))
      .mapError(FeatureFlagError.ProviderError(_))
      .map(d => toResolution(key, d.getValue.booleanValue(), d))

  private def evaluateString(
    key: String,
    default: String,
    ctx: OFEvaluationContext
  ): IO[FeatureFlagError, FlagResolution[String]] =
    ZIO
      .attemptBlocking(client.getStringDetails(key, default, ctx))
      .mapError(FeatureFlagError.ProviderError(_))
      .map(d => toResolution(key, d.getValue, d))

  private def evaluateInt(
    key: String,
    default: Int,
    ctx: OFEvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Int]] =
    ZIO
      .attemptBlocking(client.getIntegerDetails(key, Integer.valueOf(default), ctx))
      .mapError(FeatureFlagError.ProviderError(_))
      .map(d => toResolution(key, d.getValue.intValue(), d))

  private def evaluateLong(
    key: String,
    default: Long,
    ctx: OFEvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Long]] =
    ZIO
      .attemptBlocking(client.getIntegerDetails(key, Integer.valueOf(default.toInt), ctx))
      .mapError(FeatureFlagError.ProviderError(_))
      .map(d => toResolution(key, d.getValue.longValue(), d))

  private def evaluateFloat(
    key: String,
    default: Float,
    ctx: OFEvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Float]] =
    ZIO
      .attemptBlocking(client.getDoubleDetails(key, java.lang.Double.valueOf(default.toDouble), ctx))
      .mapError(FeatureFlagError.ProviderError(_))
      .map(d => toResolution(key, d.getValue.floatValue(), d))

  private def evaluateDouble(
    key: String,
    default: Double,
    ctx: OFEvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Double]] =
    ZIO
      .attemptBlocking(client.getDoubleDetails(key, java.lang.Double.valueOf(default), ctx))
      .mapError(FeatureFlagError.ProviderError(_))
      .map(d => toResolution(key, d.getValue.doubleValue(), d))

  private def evaluateObject(
    key: String,
    default: Map[String, Any],
    ctx: OFEvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Map[String, Any]]] =
    ZIO
      .attemptBlocking {
        val defaultValue = ValueConverter.mapToValue(default)
        client.getObjectDetails(key, defaultValue, ctx)
      }
      .mapError(FeatureFlagError.ProviderError(_))
      .map(d => toResolution(key, ValueConverter.valueToMap(d.getValue), d))

  private def evaluateCustom[A: FlagType](
    key: String,
    default: A,
    ctx: OFEvaluationContext
  ): IO[FeatureFlagError, FlagResolution[A]] =
    val flagType = FlagType[A]
    ZIO
      .attemptBlocking(client.getObjectDetails(key, new dev.openfeature.sdk.Value(), ctx))
      .mapError(FeatureFlagError.ProviderError(_))
      .flatMap { d =>
        flagType.decode(ValueConverter.valueToScala(d.getValue)) match
          case Right(decoded) => ZIO.succeed(toResolution(key, decoded, d))
          case Left(_)        => ZIO.fail(FeatureFlagError.TypeMismatch(key, flagType.typeName, "Object"))
      }

  // ==================== Public Evaluation API ====================

  override def boolean(key: String, default: Boolean): IO[FeatureFlagError, Boolean] =
    evaluate(key, default, EvaluationContext.empty, EvaluationOptions.empty).map(_.value)

  override def string(key: String, default: String): IO[FeatureFlagError, String] =
    evaluate(key, default, EvaluationContext.empty, EvaluationOptions.empty).map(_.value)

  override def int(key: String, default: Int): IO[FeatureFlagError, Int] =
    evaluate(key, default, EvaluationContext.empty, EvaluationOptions.empty).map(_.value)

  override def long(key: String, default: Long): IO[FeatureFlagError, Long] =
    evaluate(key, default, EvaluationContext.empty, EvaluationOptions.empty).map(_.value)

  override def double(key: String, default: Double): IO[FeatureFlagError, Double] =
    evaluate(key, default, EvaluationContext.empty, EvaluationOptions.empty).map(_.value)

  override def obj(key: String, default: Map[String, Any]): IO[FeatureFlagError, Map[String, Any]] =
    evaluate(key, default, EvaluationContext.empty, EvaluationOptions.empty).map(_.value)

  override def value[A: FlagType](key: String, default: A): IO[FeatureFlagError, A] =
    evaluate(key, default, EvaluationContext.empty, EvaluationOptions.empty).map(_.value)

  override def boolean(key: String, default: Boolean, ctx: EvaluationContext): IO[FeatureFlagError, Boolean] =
    evaluate(key, default, ctx, EvaluationOptions.empty).map(_.value)

  override def string(key: String, default: String, ctx: EvaluationContext): IO[FeatureFlagError, String] =
    evaluate(key, default, ctx, EvaluationOptions.empty).map(_.value)

  override def int(key: String, default: Int, ctx: EvaluationContext): IO[FeatureFlagError, Int] =
    evaluate(key, default, ctx, EvaluationOptions.empty).map(_.value)

  override def double(key: String, default: Double, ctx: EvaluationContext): IO[FeatureFlagError, Double] =
    evaluate(key, default, ctx, EvaluationOptions.empty).map(_.value)

  override def value[A: FlagType](key: String, default: A, ctx: EvaluationContext): IO[FeatureFlagError, A] =
    evaluate(key, default, ctx, EvaluationOptions.empty).map(_.value)

  override def booleanDetails(key: String, default: Boolean): IO[FeatureFlagError, FlagResolution[Boolean]] =
    evaluate(key, default, EvaluationContext.empty, EvaluationOptions.empty)

  override def stringDetails(key: String, default: String): IO[FeatureFlagError, FlagResolution[String]] =
    evaluate(key, default, EvaluationContext.empty, EvaluationOptions.empty)

  override def intDetails(key: String, default: Int): IO[FeatureFlagError, FlagResolution[Int]] =
    evaluate(key, default, EvaluationContext.empty, EvaluationOptions.empty)

  override def doubleDetails(key: String, default: Double): IO[FeatureFlagError, FlagResolution[Double]] =
    evaluate(key, default, EvaluationContext.empty, EvaluationOptions.empty)

  override def valueDetails[A: FlagType](key: String, default: A): IO[FeatureFlagError, FlagResolution[A]] =
    evaluate(key, default, EvaluationContext.empty, EvaluationOptions.empty)

  override def booleanDetails(
    key: String,
    default: Boolean,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Boolean]] =
    evaluate(key, default, ctx, options)

  override def stringDetails(
    key: String,
    default: String,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[String]] =
    evaluate(key, default, ctx, options)

  override def intDetails(
    key: String,
    default: Int,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Int]] =
    evaluate(key, default, ctx, options)

  override def doubleDetails(
    key: String,
    default: Double,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Double]] =
    evaluate(key, default, ctx, options)

  override def valueDetails[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[A]] =
    evaluate(key, default, ctx, options)

  // ==================== Transactions ====================

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

  override def currentEvaluatedFlags: UIO[Map[String, FlagEvaluation[?]]] =
    transactionRef.get.flatMap {
      case Some(state) => state.getEvaluations
      case None        => ZIO.succeed(Map.empty)
    }

  // ==================== Events ====================

  override def events: ZStream[Any, Nothing, ProviderEvent] =
    ZStream.fromHub(eventHub)

  @scala.annotation.nowarn("msg=deprecated")
  override def providerStatus: UIO[ProviderStatus] =
    ZIO.succeed {
      provider.getState match
        case ProviderState.NOT_READY => ProviderStatus.NotReady
        case ProviderState.READY     => ProviderStatus.Ready
        case ProviderState.ERROR     => ProviderStatus.Error
        case ProviderState.STALE     => ProviderStatus.Stale
        case _                       => ProviderStatus.NotReady
    }

  override def providerMetadata: UIO[ProviderMetadata] =
    ZIO.succeed(ProviderMetadata(providerName))

  override def clientMetadata: UIO[ClientMetadata] =
    ZIO.succeed(ClientMetadata(domain))

  // ==================== Event Handlers ====================

  /** Helper to create an event handler with optional immediate execution.
    *
    * Per OpenFeature spec 5.3.3, handlers attached after the provider reaches an associated state MUST run immediately.
    */
  private def createEventHandler[A](
    shouldRunImmediately: ProviderStatus => Boolean,
    createImmediateEvent: ProviderMetadata => ProviderEvent,
    filterEvents: ProviderEvent => Option[A],
    handler: A => UIO[Unit]
  ): UIO[UIO[Unit]] =
    for
      status <- providerStatus
      metadata = ProviderMetadata(providerName)
      _ <- ZIO.when(shouldRunImmediately(status)) {
        filterEvents(createImmediateEvent(metadata)).fold(ZIO.unit)(handler)
      }
      fiber <- events.collect { case e if filterEvents(e).isDefined => filterEvents(e).get }.foreach(handler).forkDaemon
    yield fiber.interrupt.unit

  override def onProviderReady(handler: ProviderMetadata => UIO[Unit]): UIO[UIO[Unit]] =
    createEventHandler[ProviderMetadata](
      _ == ProviderStatus.Ready,
      ProviderEvent.Ready(_),
      { case ProviderEvent.Ready(m) => Some(m); case _ => None },
      handler
    )

  override def onProviderError(handler: (Throwable, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] =
    createEventHandler[(Throwable, ProviderMetadata)](
      s => s == ProviderStatus.Error || s == ProviderStatus.Fatal,
      m => ProviderEvent.Error(new RuntimeException("Provider in error state"), m),
      { case ProviderEvent.Error(e, m) => Some((e, m)); case _ => None },
      handler.tupled
    )

  override def onProviderStale(handler: (String, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] =
    createEventHandler[(String, ProviderMetadata)](
      _ == ProviderStatus.Stale,
      m => ProviderEvent.Stale("Provider in stale state", m),
      { case ProviderEvent.Stale(r, m) => Some((r, m)); case _ => None },
      handler.tupled
    )

  override def onConfigurationChanged(handler: (Set[String], ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] =
    // Configuration changed doesn't have an "associated state" so no immediate execution
    events
      .collect { case ProviderEvent.ConfigurationChanged(flags, m) => (flags, m) }
      .foreach(handler.tupled)
      .forkDaemon
      .map(_.interrupt.unit)

  override def on(eventType: ProviderEventType, handler: ProviderEvent => UIO[Unit]): UIO[UIO[Unit]] =
    for
      status <- providerStatus
      metadata = ProviderMetadata(providerName)
      _ <- eventType match
        case ProviderEventType.Ready if status == ProviderStatus.Ready =>
          handler(ProviderEvent.Ready(metadata))
        case ProviderEventType.Error if status == ProviderStatus.Error || status == ProviderStatus.Fatal =>
          handler(ProviderEvent.Error(new RuntimeException("Provider in error state"), metadata))
        case ProviderEventType.Stale if status == ProviderStatus.Stale =>
          handler(ProviderEvent.Stale("Provider in stale state", metadata))
        case _ => ZIO.unit
      fiber <- events.filter(_.eventType == eventType).foreach(handler).forkDaemon
    yield fiber.interrupt.unit

  // ==================== Tracking ====================

  override def track(eventName: String): IO[FeatureFlagError, Unit] =
    ZIO
      .attemptBlocking(client.track(eventName))
      .mapError(FeatureFlagError.ProviderError(_))

  override def track(eventName: String, context: EvaluationContext): IO[FeatureFlagError, Unit] =
    ZIO
      .attemptBlocking(client.track(eventName, ContextConverter.toOpenFeature(context)))
      .mapError(FeatureFlagError.ProviderError(_))

  override def track(eventName: String, details: TrackingEventDetails): IO[FeatureFlagError, Unit] =
    ZIO
      .attemptBlocking(client.track(eventName, toTrackingDetails(details)))
      .mapError(FeatureFlagError.ProviderError(_))

  override def track(
    eventName: String,
    context: EvaluationContext,
    details: TrackingEventDetails
  ): IO[FeatureFlagError, Unit] =
    ZIO
      .attemptBlocking(client.track(eventName, ContextConverter.toOpenFeature(context), toTrackingDetails(details)))
      .mapError(FeatureFlagError.ProviderError(_))

  private def toTrackingDetails(details: TrackingEventDetails): MutableTrackingEventDetails =
    val result = details.value.fold(new MutableTrackingEventDetails())(new MutableTrackingEventDetails(_))
    details.attributes.foreach { (key, value) =>
      addTrackingAttribute(result, key, value)
    }
    result

  private def addTrackingAttribute(details: MutableTrackingEventDetails, key: String, value: Any): Unit =
    value match
      case b: Boolean                 => details.add(key, b)
      case s: String                  => details.add(key, s)
      case i: Int                     => details.add(key, Integer.valueOf(i))
      case l: Long                    => details.add(key, java.lang.Double.valueOf(l.toDouble))
      case d: Double                  => details.add(key, d)
      case f: Float                   => details.add(key, java.lang.Double.valueOf(f.toDouble))
      case instant: java.time.Instant => details.add(key, instant)
      case list: List[?] =>
        val values = list.map(v => new dev.openfeature.sdk.Value(ValueConverter.scalaToJava(v))).asJava
        details.add(key, values)
      case map: Map[?, ?] =>
        val structure = dev.openfeature.sdk.Structure.mapToStructure(
          map.asInstanceOf[Map[String, Any]].map((k, v) => k -> ValueConverter.scalaToJava(v)).asJava
        )
        details.add(key, structure)
      case null  => () // Skip null values
      case other => details.add(key, other.toString)
