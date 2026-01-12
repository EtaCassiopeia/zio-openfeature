package zio.openfeature

import zio.*
import zio.stream.*
import dev.openfeature.sdk.{FeatureProvider as OFFeatureProvider, OpenFeatureAPI}

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

  // Evaluation with options (invocation-level hooks)
  def booleanDetails(
    key: String,
    default: Boolean,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Boolean]]
  def stringDetails(
    key: String,
    default: String,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[String]]
  def intDetails(
    key: String,
    default: Int,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Int]]
  def doubleDetails(
    key: String,
    default: Double,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Double]]
  def valueDetails[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[A]]

  def setGlobalContext(ctx: EvaluationContext): UIO[Unit]
  def globalContext: UIO[EvaluationContext]
  def withContext[R, E, A](ctx: EvaluationContext)(zio: ZIO[R, E, A]): ZIO[R, E, A]

  def transaction[R, E, A](
    overrides: Map[String, Any] = Map.empty,
    context: EvaluationContext = EvaluationContext.empty,
    cacheEvaluations: Boolean = true
  )(zio: ZIO[R, E, A]): ZIO[R, E | FeatureFlagError, TransactionResult[A]]

  def inTransaction: UIO[Boolean]
  def currentEvaluatedFlags: UIO[Map[String, FlagEvaluation[?]]]

  def events: ZStream[Any, Nothing, ProviderEvent]
  def providerStatus: UIO[ProviderStatus]
  def providerMetadata: UIO[ProviderMetadata]

  def addHook(hook: FeatureHook): UIO[Unit]
  def clearHooks: UIO[Unit]
  def hooks: UIO[List[FeatureHook]]

  // Tracking API
  def track(eventName: String): IO[FeatureFlagError, Unit]
  def track(eventName: String, context: EvaluationContext): IO[FeatureFlagError, Unit]
  def track(eventName: String, details: TrackingEventDetails): IO[FeatureFlagError, Unit]
  def track(eventName: String, context: EvaluationContext, details: TrackingEventDetails): IO[FeatureFlagError, Unit]

object FeatureFlags:

  // Service Accessors

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

  // Evaluation with options (invocation-level hooks)

  def booleanDetails(
    key: String,
    default: Boolean,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Boolean]] =
    ZIO.serviceWithZIO(_.booleanDetails(key, default, ctx, options))

  def stringDetails(
    key: String,
    default: String,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[String]] =
    ZIO.serviceWithZIO(_.stringDetails(key, default, ctx, options))

  def intDetails(
    key: String,
    default: Int,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Int]] =
    ZIO.serviceWithZIO(_.intDetails(key, default, ctx, options))

  def doubleDetails(
    key: String,
    default: Double,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Double]] =
    ZIO.serviceWithZIO(_.doubleDetails(key, default, ctx, options))

  def valueDetails[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[A]] =
    ZIO.serviceWithZIO(_.valueDetails(key, default, ctx, options))

  def setGlobalContext(ctx: EvaluationContext): ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.setGlobalContext(ctx))

  def globalContext: ZIO[FeatureFlags, Nothing, EvaluationContext] =
    ZIO.serviceWithZIO(_.globalContext)

  def withContext[R, E, A](ctx: EvaluationContext)(zio: ZIO[R, E, A]): ZIO[R & FeatureFlags, E, A] =
    ZIO.serviceWithZIO[FeatureFlags](_.withContext(ctx)(zio))

  def transaction[R, E, A](
    overrides: Map[String, Any] = Map.empty,
    context: EvaluationContext = EvaluationContext.empty,
    cacheEvaluations: Boolean = true
  )(zio: ZIO[R, E, A]): ZIO[R & FeatureFlags, E | FeatureFlagError, TransactionResult[A]] =
    ZIO.serviceWithZIO[FeatureFlags](_.transaction(overrides, context, cacheEvaluations)(zio))

  def inTransaction: ZIO[FeatureFlags, Nothing, Boolean] =
    ZIO.serviceWithZIO(_.inTransaction)

  def currentEvaluatedFlags: ZIO[FeatureFlags, Nothing, Map[String, FlagEvaluation[?]]] =
    ZIO.serviceWithZIO(_.currentEvaluatedFlags)

  def events: ZStream[FeatureFlags, Nothing, ProviderEvent] =
    ZStream.serviceWithStream(_.events)

  def providerStatus: ZIO[FeatureFlags, Nothing, ProviderStatus] =
    ZIO.serviceWithZIO(_.providerStatus)

  def providerMetadata: ZIO[FeatureFlags, Nothing, ProviderMetadata] =
    ZIO.serviceWithZIO(_.providerMetadata)

  def addHook(hook: FeatureHook): ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.addHook(hook))

  def clearHooks: ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.clearHooks)

  def hooks: ZIO[FeatureFlags, Nothing, List[FeatureHook]] =
    ZIO.serviceWithZIO(_.hooks)

  // Tracking API

  def track(eventName: String): ZIO[FeatureFlags, FeatureFlagError, Unit] =
    ZIO.serviceWithZIO(_.track(eventName))

  def track(eventName: String, context: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, Unit] =
    ZIO.serviceWithZIO(_.track(eventName, context))

  def track(eventName: String, details: TrackingEventDetails): ZIO[FeatureFlags, FeatureFlagError, Unit] =
    ZIO.serviceWithZIO(_.track(eventName, details))

  def track(
    eventName: String,
    context: EvaluationContext,
    details: TrackingEventDetails
  ): ZIO[FeatureFlags, FeatureFlagError, Unit] =
    ZIO.serviceWithZIO(_.track(eventName, context, details))

  // Factory Methods

  /** Create a FeatureFlags layer from any OpenFeature provider.
    *
    * Example:
    * {{{
    * import dev.openfeature.contrib.providers.flagd.FlagdProvider
    *
    * val layer = FeatureFlags.fromProvider(new FlagdProvider())
    * }}}
    */
  def fromProvider(provider: OFFeatureProvider): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped {
      for
        api    <- ZIO.succeed(OpenFeatureAPI.getInstance())
        _      <- ZIO.attemptBlocking(api.setProviderAndWait(provider))
        client <- ZIO.attempt(api.getClient())
        providerName = Option(provider.getMetadata).map(_.getName).getOrElse("unknown")
        globalCtxRef   <- Ref.make(EvaluationContext.empty)
        fiberCtxRef    <- FiberRef.make(EvaluationContext.empty)
        transactionRef <- FiberRef.make[Option[TransactionState]](None)
        hooksRef       <- Ref.make(List.empty[FeatureHook])
        eventHub       <- Hub.unbounded[ProviderEvent]
        _              <- ZIO.addFinalizer(ZIO.attemptBlocking(api.shutdown()).ignore)
      yield FeatureFlagsLive(
        client,
        provider,
        providerName,
        globalCtxRef,
        fiberCtxRef,
        transactionRef,
        hooksRef,
        eventHub
      )
    }

  /** Create a FeatureFlags layer with a named domain/client.
    *
    * Useful for multi-provider setups where you need isolated clients. Note: Does not call shutdown on finalization to
    * avoid affecting other domains.
    */
  def fromProviderWithDomain(provider: OFFeatureProvider, domain: String): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped {
      for
        api    <- ZIO.succeed(OpenFeatureAPI.getInstance())
        _      <- ZIO.attemptBlocking(api.setProviderAndWait(domain, provider))
        client <- ZIO.attempt(api.getClient(domain))
        providerName = Option(provider.getMetadata).map(_.getName).getOrElse("unknown")
        globalCtxRef   <- Ref.make(EvaluationContext.empty)
        fiberCtxRef    <- FiberRef.make(EvaluationContext.empty)
        transactionRef <- FiberRef.make[Option[TransactionState]](None)
        hooksRef       <- Ref.make(List.empty[FeatureHook])
        eventHub       <- Hub.unbounded[ProviderEvent]
      // Note: We don't shutdown here as it would affect all domains globally
      yield FeatureFlagsLive(
        client,
        provider,
        providerName,
        globalCtxRef,
        fiberCtxRef,
        transactionRef,
        hooksRef,
        eventHub
      )
    }

  /** Create a FeatureFlags layer with initial hooks.
    */
  def fromProviderWithHooks(
    provider: OFFeatureProvider,
    initialHooks: List[FeatureHook]
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped {
      for
        api    <- ZIO.succeed(OpenFeatureAPI.getInstance())
        _      <- ZIO.attemptBlocking(api.setProviderAndWait(provider))
        client <- ZIO.attempt(api.getClient())
        providerName = Option(provider.getMetadata).map(_.getName).getOrElse("unknown")
        globalCtxRef   <- Ref.make(EvaluationContext.empty)
        fiberCtxRef    <- FiberRef.make(EvaluationContext.empty)
        transactionRef <- FiberRef.make[Option[TransactionState]](None)
        hooksRef       <- Ref.make(initialHooks)
        eventHub       <- Hub.unbounded[ProviderEvent]
        _              <- ZIO.addFinalizer(ZIO.attemptBlocking(api.shutdown()).ignore)
      yield FeatureFlagsLive(
        client,
        provider,
        providerName,
        globalCtxRef,
        fiberCtxRef,
        transactionRef,
        hooksRef,
        eventHub
      )
    }
