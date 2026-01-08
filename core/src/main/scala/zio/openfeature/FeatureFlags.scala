package zio.openfeature

import zio.*
import zio.stream.*

trait FeatureFlags:
  def boolean(key: String, default: Boolean): IO[FeatureFlagError, Boolean]
  def string(key: String, default: String): IO[FeatureFlagError, String]
  def int(key: String, default: Int): IO[FeatureFlagError, Int]
  def long(key: String, default: Long): IO[FeatureFlagError, Long]
  def double(key: String, default: Double): IO[FeatureFlagError, Double]
  def obj(key: String, default: Map[String, Any]): IO[FeatureFlagError, Map[String, Any]]
  def value[A: FlagType](key: String, default: A): IO[FeatureFlagError, A]

  def boolean(key: String, default: Boolean, ctx: EvaluationContext): IO[FeatureFlagError, Boolean]
  def string(key: String, default: String, ctx: EvaluationContext): IO[FeatureFlagError, String]
  def int(key: String, default: Int, ctx: EvaluationContext): IO[FeatureFlagError, Int]
  def double(key: String, default: Double, ctx: EvaluationContext): IO[FeatureFlagError, Double]
  def value[A: FlagType](key: String, default: A, ctx: EvaluationContext): IO[FeatureFlagError, A]

  def booleanDetails(key: String, default: Boolean): IO[FeatureFlagError, FlagResolution[Boolean]]
  def stringDetails(key: String, default: String): IO[FeatureFlagError, FlagResolution[String]]
  def intDetails(key: String, default: Int): IO[FeatureFlagError, FlagResolution[Int]]
  def doubleDetails(key: String, default: Double): IO[FeatureFlagError, FlagResolution[Double]]
  def valueDetails[A: FlagType](key: String, default: A): IO[FeatureFlagError, FlagResolution[A]]

  def setGlobalContext(ctx: EvaluationContext): UIO[Unit]
  def globalContext: UIO[EvaluationContext]
  def withContext[R, E, A](ctx: EvaluationContext)(zio: ZIO[R, E, A]): ZIO[R, E, A]

  def events: ZStream[Any, Nothing, ProviderEvent]
  def providerStatus: UIO[ProviderStatus]
  def providerMetadata: UIO[ProviderMetadata]

object FeatureFlags:
  def boolean(key: String, default: Boolean): ZIO[FeatureFlags, FeatureFlagError, Boolean] =
    ZIO.serviceWithZIO(_.boolean(key, default))

  def string(key: String, default: String): ZIO[FeatureFlags, FeatureFlagError, String] =
    ZIO.serviceWithZIO(_.string(key, default))

  def int(key: String, default: Int): ZIO[FeatureFlags, FeatureFlagError, Int] =
    ZIO.serviceWithZIO(_.int(key, default))

  def long(key: String, default: Long): ZIO[FeatureFlags, FeatureFlagError, Long] =
    ZIO.serviceWithZIO(_.long(key, default))

  def double(key: String, default: Double): ZIO[FeatureFlags, FeatureFlagError, Double] =
    ZIO.serviceWithZIO(_.double(key, default))

  def obj(key: String, default: Map[String, Any]): ZIO[FeatureFlags, FeatureFlagError, Map[String, Any]] =
    ZIO.serviceWithZIO(_.obj(key, default))

  def value[A: FlagType](key: String, default: A): ZIO[FeatureFlags, FeatureFlagError, A] =
    ZIO.serviceWithZIO(_.value(key, default))

  def boolean(key: String, default: Boolean, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, Boolean] =
    ZIO.serviceWithZIO(_.boolean(key, default, ctx))

  def string(key: String, default: String, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, String] =
    ZIO.serviceWithZIO(_.string(key, default, ctx))

  def int(key: String, default: Int, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, Int] =
    ZIO.serviceWithZIO(_.int(key, default, ctx))

  def double(key: String, default: Double, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, Double] =
    ZIO.serviceWithZIO(_.double(key, default, ctx))

  def value[A: FlagType](key: String, default: A, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, A] =
    ZIO.serviceWithZIO(_.value(key, default, ctx))

  def booleanDetails(key: String, default: Boolean): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Boolean]] =
    ZIO.serviceWithZIO(_.booleanDetails(key, default))

  def stringDetails(key: String, default: String): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[String]] =
    ZIO.serviceWithZIO(_.stringDetails(key, default))

  def intDetails(key: String, default: Int): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Int]] =
    ZIO.serviceWithZIO(_.intDetails(key, default))

  def doubleDetails(key: String, default: Double): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Double]] =
    ZIO.serviceWithZIO(_.doubleDetails(key, default))

  def valueDetails[A: FlagType](key: String, default: A): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[A]] =
    ZIO.serviceWithZIO(_.valueDetails(key, default))

  def setGlobalContext(ctx: EvaluationContext): ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.setGlobalContext(ctx))

  def globalContext: ZIO[FeatureFlags, Nothing, EvaluationContext] =
    ZIO.serviceWithZIO(_.globalContext)

  def withContext[R, E, A](ctx: EvaluationContext)(zio: ZIO[R, E, A]): ZIO[R & FeatureFlags, E, A] =
    ZIO.serviceWithZIO[FeatureFlags](_.withContext(ctx)(zio))

  def events: ZStream[FeatureFlags, Nothing, ProviderEvent] =
    ZStream.serviceWithStream(_.events)

  def providerStatus: ZIO[FeatureFlags, Nothing, ProviderStatus] =
    ZIO.serviceWithZIO(_.providerStatus)

  def providerMetadata: ZIO[FeatureFlags, Nothing, ProviderMetadata] =
    ZIO.serviceWithZIO(_.providerMetadata)

  val live: ZLayer[FeatureProvider, Nothing, FeatureFlags] =
    ZLayer.scoped {
      for
        provider         <- ZIO.service[FeatureProvider]
        globalContextRef <- Ref.make(EvaluationContext.empty)
        fiberContextRef  <- FiberRef.make(EvaluationContext.empty)
      yield FeatureFlagsLive(provider, globalContextRef, fiberContextRef)
    }
