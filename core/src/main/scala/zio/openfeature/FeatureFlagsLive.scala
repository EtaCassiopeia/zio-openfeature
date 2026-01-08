package zio.openfeature

import zio.*
import zio.stream.*

final private[openfeature] class FeatureFlagsLive(
  provider: FeatureProvider,
  globalContextRef: Ref[EvaluationContext],
  fiberContextRef: FiberRef[EvaluationContext]
) extends FeatureFlags:

  private def effectiveContext(invocation: EvaluationContext): UIO[EvaluationContext] =
    for
      global     <- globalContextRef.get
      fiberLocal <- fiberContextRef.get
    yield global.merge(fiberLocal).merge(invocation)

  private def evaluateFromProvider[A: FlagType](
    key: String,
    default: A,
    context: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[A]] =
    provider.status.flatMap {
      case ProviderStatus.NotReady     => ZIO.fail(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady))
      case ProviderStatus.ShuttingDown => ZIO.fail(FeatureFlagError.ProviderNotReady(ProviderStatus.ShuttingDown))
      case _                           => provider.resolveValue(key, default, context)
    }

  private def evaluateFlag[A: FlagType](
    key: String,
    default: A,
    invocationContext: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[A]] =
    effectiveContext(invocationContext).flatMap { effectCtx =>
      evaluateFromProvider(key, default, effectCtx)
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
    evaluateFlag(key, default, ctx).map(_.value)

  override def string(key: String, default: String, ctx: EvaluationContext): IO[FeatureFlagError, String] =
    evaluateFlag(key, default, ctx).map(_.value)

  override def int(key: String, default: Int, ctx: EvaluationContext): IO[FeatureFlagError, Int] =
    evaluateFlag(key, default, ctx).map(_.value)

  override def double(key: String, default: Double, ctx: EvaluationContext): IO[FeatureFlagError, Double] =
    evaluateFlag(key, default, ctx).map(_.value)

  override def value[A: FlagType](key: String, default: A, ctx: EvaluationContext): IO[FeatureFlagError, A] =
    evaluateFlag(key, default, ctx).map(_.value)

  override def booleanDetails(key: String, default: Boolean): IO[FeatureFlagError, FlagResolution[Boolean]] =
    effectiveContext(EvaluationContext.empty).flatMap { ctx =>
      evaluateFlag(key, default, ctx)
    }

  override def stringDetails(key: String, default: String): IO[FeatureFlagError, FlagResolution[String]] =
    effectiveContext(EvaluationContext.empty).flatMap { ctx =>
      evaluateFlag(key, default, ctx)
    }

  override def intDetails(key: String, default: Int): IO[FeatureFlagError, FlagResolution[Int]] =
    effectiveContext(EvaluationContext.empty).flatMap { ctx =>
      evaluateFlag(key, default, ctx)
    }

  override def doubleDetails(key: String, default: Double): IO[FeatureFlagError, FlagResolution[Double]] =
    effectiveContext(EvaluationContext.empty).flatMap { ctx =>
      evaluateFlag(key, default, ctx)
    }

  override def valueDetails[A: FlagType](key: String, default: A): IO[FeatureFlagError, FlagResolution[A]] =
    effectiveContext(EvaluationContext.empty).flatMap { ctx =>
      evaluateFlag(key, default, ctx)
    }

  override def setGlobalContext(ctx: EvaluationContext): UIO[Unit] =
    globalContextRef.set(ctx)

  override def globalContext: UIO[EvaluationContext] =
    globalContextRef.get

  override def withContext[R, E, A](ctx: EvaluationContext)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    fiberContextRef.get.flatMap { current =>
      fiberContextRef.locally(current.merge(ctx))(zio)
    }

  override def events: ZStream[Any, Nothing, ProviderEvent] =
    provider.events

  override def providerStatus: UIO[ProviderStatus] =
    provider.status

  override def providerMetadata: UIO[ProviderMetadata] =
    ZIO.succeed(provider.metadata)
