package zio.openfeature

import zio.*
import zio.stream.*

final private[openfeature] class FeatureFlagsLive(
  provider: FeatureProvider,
  globalContextRef: Ref[EvaluationContext],
  fiberContextRef: FiberRef[EvaluationContext],
  transactionRef: FiberRef[Option[TransactionState]],
  hooksRef: Ref[List[FeatureHook]]
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
      metadata     <- provider.status.map(_ => provider.metadata)
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
        case None        => evaluateFromProvider(key, default, effectCtx)
    yield result

  private def evaluateWithTransaction[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext,
    state: TransactionState
  ): IO[FeatureFlagError, FlagResolution[A]] =
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
        for
          resolution <- evaluateFromProvider(key, default, context)
          eval       <- FlagEvaluation.evaluated(key, resolution)
          _          <- state.record(eval)
        yield resolution

  private def evaluateFromProvider[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[A]] =
    provider.status.flatMap {
      case ProviderStatus.NotReady =>
        ZIO.fail(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady))
      case ProviderStatus.ShuttingDown =>
        ZIO.fail(FeatureFlagError.ProviderNotReady(ProviderStatus.ShuttingDown))
      case _ =>
        provider.resolveValue(key, default, context)
    }

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
    context: EvaluationContext
  )(zio: ZIO[R, E, A]): ZIO[R, E | FeatureFlagError, TransactionResult[A]] =
    for
      current <- transactionRef.get
      _ <- ZIO.when(current.isDefined)(
        ZIO.fail(FeatureFlagError.NestedTransactionNotAllowed)
      )
      state    <- TransactionState.make(overrides, context)
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

  override def events: ZStream[Any, Nothing, ProviderEvent] =
    provider.events

  override def providerStatus: UIO[ProviderStatus] =
    provider.status

  override def providerMetadata: UIO[ProviderMetadata] =
    ZIO.succeed(provider.metadata)

  override def addHook(hook: FeatureHook): UIO[Unit] =
    hooksRef.update(_ :+ hook)

  override def clearHooks: UIO[Unit] =
    hooksRef.set(List.empty)

  override def hooks: UIO[List[FeatureHook]] =
    hooksRef.get
