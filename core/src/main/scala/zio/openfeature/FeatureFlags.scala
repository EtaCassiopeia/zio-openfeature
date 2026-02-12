package zio.openfeature

import zio._
import zio.stream._
import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider, OpenFeatureAPI}
import dev.openfeature.sdk.multiprovider.{MultiProvider, Strategy, FirstMatchStrategy, FirstSuccessfulStrategy}

trait FeatureFlags {
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
  def long(key: String, default: Long, ctx: EvaluationContext): IO[FeatureFlagError, Long]
  def double(key: String, default: Double, ctx: EvaluationContext): IO[FeatureFlagError, Double]
  def obj(key: String, default: Map[String, Any], ctx: EvaluationContext): IO[FeatureFlagError, Map[String, Any]]
  def value[A: FlagType](key: String, default: A, ctx: EvaluationContext): IO[FeatureFlagError, A]

  def booleanDetails(key: String, default: Boolean): IO[FeatureFlagError, FlagResolution[Boolean]]
  def stringDetails(key: String, default: String): IO[FeatureFlagError, FlagResolution[String]]
  def intDetails(key: String, default: Int): IO[FeatureFlagError, FlagResolution[Int]]
  def longDetails(key: String, default: Long): IO[FeatureFlagError, FlagResolution[Long]]
  def doubleDetails(key: String, default: Double): IO[FeatureFlagError, FlagResolution[Double]]
  def objDetails(key: String, default: Map[String, Any]): IO[FeatureFlagError, FlagResolution[Map[String, Any]]]
  def valueDetails[A: FlagType](key: String, default: A): IO[FeatureFlagError, FlagResolution[A]]

  // Detailed evaluation with context
  def booleanDetails(
    key: String,
    default: Boolean,
    ctx: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Boolean]]
  def stringDetails(key: String, default: String, ctx: EvaluationContext): IO[FeatureFlagError, FlagResolution[String]]
  def intDetails(key: String, default: Int, ctx: EvaluationContext): IO[FeatureFlagError, FlagResolution[Int]]
  def longDetails(key: String, default: Long, ctx: EvaluationContext): IO[FeatureFlagError, FlagResolution[Long]]
  def doubleDetails(key: String, default: Double, ctx: EvaluationContext): IO[FeatureFlagError, FlagResolution[Double]]
  def objDetails(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[Map[String, Any]]]
  def valueDetails[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext
  ): IO[FeatureFlagError, FlagResolution[A]]

  // Detailed evaluation with context and options (invocation-level hooks)
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
  def longDetails(
    key: String,
    default: Long,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Long]]
  def doubleDetails(
    key: String,
    default: Double,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Double]]
  def objDetails(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[Map[String, Any]]]
  def valueDetails[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): IO[FeatureFlagError, FlagResolution[A]]

  def setGlobalContext(ctx: EvaluationContext): UIO[Unit]
  def globalContext: UIO[EvaluationContext]

  /** Set the client-level evaluation context.
    *
    * Per OpenFeature spec, context merges in order: API (global) -> Client -> Transaction -> Invocation. Client context
    * is persisted on this FeatureFlags instance.
    */
  def setClientContext(ctx: EvaluationContext): UIO[Unit]

  /** Get the client-level evaluation context. */
  def clientContext: UIO[EvaluationContext]

  def withContext[R, E, A](ctx: EvaluationContext)(zio: ZIO[R, E, A]): ZIO[R, E, A]

  def transaction[R, E, A](
    overrides: Map[String, Any] = Map.empty,
    context: EvaluationContext = EvaluationContext.empty,
    cacheEvaluations: Boolean = true
  )(zio: ZIO[R, E, A]): ZIO[R, Compat.OrError[E, FeatureFlagError], TransactionResult[A]]

  def inTransaction: UIO[Boolean]
  def currentEvaluatedFlags: UIO[Map[String, FlagEvaluation[_]]]

  def events: ZStream[Any, Nothing, ProviderEvent]
  def providerStatus: UIO[ProviderStatus]
  def providerMetadata: UIO[ProviderMetadata]
  def clientMetadata: UIO[ClientMetadata]

  // Event Handlers - return a cancellation effect
  /** Register a handler for provider ready events. Returns a cancellation effect.
    *
    * Per OpenFeature spec 5.2.1 and 5.2.7, handlers can be registered and removed.
    */
  def onProviderReady(handler: ProviderMetadata => UIO[Unit]): UIO[UIO[Unit]]

  /** Register a handler for provider error events. Returns a cancellation effect. */
  def onProviderError(handler: (Throwable, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]]

  /** Register a handler for provider stale events. Returns a cancellation effect. */
  def onProviderStale(handler: (String, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]]

  /** Register a handler for configuration changed events. Returns a cancellation effect. */
  def onConfigurationChanged(handler: (Set[String], ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]]

  /** Register a handler for any provider event type. Returns a cancellation effect.
    *
    * This is a generic alternative to the specific event handler methods (onProviderReady, etc.).
    */
  def on(eventType: ProviderEventType, handler: ProviderEvent => UIO[Unit]): UIO[UIO[Unit]]

  def addHook(hook: FeatureHook): UIO[Unit]
  def clearHooks: UIO[Unit]
  def hooks: UIO[List[FeatureHook]]

  // Shutdown API (spec 1.6.1)
  def shutdown: UIO[Unit]

  // Tracking API
  def track(eventName: String): IO[FeatureFlagError, Unit]
  def track(eventName: String, context: EvaluationContext): IO[FeatureFlagError, Unit]
  def track(eventName: String, details: TrackingEventDetails): IO[FeatureFlagError, Unit]
  def track(eventName: String, context: EvaluationContext, details: TrackingEventDetails): IO[FeatureFlagError, Unit]
}

object FeatureFlags {

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

  def long(key: String, default: Long, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, Long] =
    ZIO.serviceWithZIO(_.long(key, default, ctx))

  def double(key: String, default: Double, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, Double] =
    ZIO.serviceWithZIO(_.double(key, default, ctx))

  def obj(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext
  ): ZIO[FeatureFlags, FeatureFlagError, Map[String, Any]] =
    ZIO.serviceWithZIO(_.obj(key, default, ctx))

  def value[A: FlagType](key: String, default: A, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, A] =
    ZIO.serviceWithZIO(_.value(key, default, ctx))

  def booleanDetails(key: String, default: Boolean): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Boolean]] =
    ZIO.serviceWithZIO(_.booleanDetails(key, default))

  def stringDetails(key: String, default: String): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[String]] =
    ZIO.serviceWithZIO(_.stringDetails(key, default))

  def intDetails(key: String, default: Int): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Int]] =
    ZIO.serviceWithZIO(_.intDetails(key, default))

  def longDetails(key: String, default: Long): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Long]] =
    ZIO.serviceWithZIO(_.longDetails(key, default))

  def doubleDetails(key: String, default: Double): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Double]] =
    ZIO.serviceWithZIO(_.doubleDetails(key, default))

  def objDetails(
    key: String,
    default: Map[String, Any]
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Map[String, Any]]] =
    ZIO.serviceWithZIO(_.objDetails(key, default))

  def valueDetails[A: FlagType](key: String, default: A): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[A]] =
    ZIO.serviceWithZIO(_.valueDetails(key, default))

  // Detailed evaluation with context

  def booleanDetails(
    key: String,
    default: Boolean,
    ctx: EvaluationContext
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Boolean]] =
    ZIO.serviceWithZIO(_.booleanDetails(key, default, ctx))

  def stringDetails(
    key: String,
    default: String,
    ctx: EvaluationContext
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[String]] =
    ZIO.serviceWithZIO(_.stringDetails(key, default, ctx))

  def intDetails(
    key: String,
    default: Int,
    ctx: EvaluationContext
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Int]] =
    ZIO.serviceWithZIO(_.intDetails(key, default, ctx))

  def longDetails(
    key: String,
    default: Long,
    ctx: EvaluationContext
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Long]] =
    ZIO.serviceWithZIO(_.longDetails(key, default, ctx))

  def doubleDetails(
    key: String,
    default: Double,
    ctx: EvaluationContext
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Double]] =
    ZIO.serviceWithZIO(_.doubleDetails(key, default, ctx))

  def objDetails(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Map[String, Any]]] =
    ZIO.serviceWithZIO(_.objDetails(key, default, ctx))

  def valueDetails[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[A]] =
    ZIO.serviceWithZIO(_.valueDetails(key, default, ctx))

  // Detailed evaluation with context and options (invocation-level hooks)

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

  def longDetails(
    key: String,
    default: Long,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Long]] =
    ZIO.serviceWithZIO(_.longDetails(key, default, ctx, options))

  def doubleDetails(
    key: String,
    default: Double,
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Double]] =
    ZIO.serviceWithZIO(_.doubleDetails(key, default, ctx, options))

  def objDetails(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext,
    options: EvaluationOptions
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Map[String, Any]]] =
    ZIO.serviceWithZIO(_.objDetails(key, default, ctx, options))

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

  def setClientContext(ctx: EvaluationContext): ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.setClientContext(ctx))

  def clientContext: ZIO[FeatureFlags, Nothing, EvaluationContext] =
    ZIO.serviceWithZIO(_.clientContext)

  def withContext[R, E, A](ctx: EvaluationContext)(zio: ZIO[R, E, A]): ZIO[R with FeatureFlags, E, A] =
    ZIO.serviceWithZIO[FeatureFlags](_.withContext(ctx)(zio))

  def transaction[R, E, A](
    overrides: Map[String, Any] = Map.empty,
    context: EvaluationContext = EvaluationContext.empty,
    cacheEvaluations: Boolean = true
  )(zio: ZIO[R, E, A]): ZIO[R with FeatureFlags, Compat.OrError[E, FeatureFlagError], TransactionResult[A]] =
    ZIO.serviceWithZIO[FeatureFlags](_.transaction(overrides, context, cacheEvaluations)(zio))

  def inTransaction: ZIO[FeatureFlags, Nothing, Boolean] =
    ZIO.serviceWithZIO(_.inTransaction)

  def currentEvaluatedFlags: ZIO[FeatureFlags, Nothing, Map[String, FlagEvaluation[_]]] =
    ZIO.serviceWithZIO(_.currentEvaluatedFlags)

  def events: ZStream[FeatureFlags, Nothing, ProviderEvent] =
    ZStream.serviceWithStream(_.events)

  def providerStatus: ZIO[FeatureFlags, Nothing, ProviderStatus] =
    ZIO.serviceWithZIO(_.providerStatus)

  def providerMetadata: ZIO[FeatureFlags, Nothing, ProviderMetadata] =
    ZIO.serviceWithZIO(_.providerMetadata)

  def clientMetadata: ZIO[FeatureFlags, Nothing, ClientMetadata] =
    ZIO.serviceWithZIO(_.clientMetadata)

  // Event Handlers - return cancellation effects

  /** Register a handler for provider ready events. Returns a cancellation effect. */
  def onProviderReady(handler: ProviderMetadata => UIO[Unit]): ZIO[FeatureFlags, Nothing, UIO[Unit]] =
    ZIO.serviceWithZIO(_.onProviderReady(handler))

  /** Register a handler for provider error events. Returns a cancellation effect. */
  def onProviderError(handler: (Throwable, ProviderMetadata) => UIO[Unit]): ZIO[FeatureFlags, Nothing, UIO[Unit]] =
    ZIO.serviceWithZIO(_.onProviderError(handler))

  /** Register a handler for provider stale events. Returns a cancellation effect. */
  def onProviderStale(handler: (String, ProviderMetadata) => UIO[Unit]): ZIO[FeatureFlags, Nothing, UIO[Unit]] =
    ZIO.serviceWithZIO(_.onProviderStale(handler))

  /** Register a handler for configuration changed events. Returns a cancellation effect. */
  def onConfigurationChanged(
    handler: (Set[String], ProviderMetadata) => UIO[Unit]
  ): ZIO[FeatureFlags, Nothing, UIO[Unit]] =
    ZIO.serviceWithZIO(_.onConfigurationChanged(handler))

  /** Register a handler for any provider event type. Returns a cancellation effect. */
  def on(eventType: ProviderEventType, handler: ProviderEvent => UIO[Unit]): ZIO[FeatureFlags, Nothing, UIO[Unit]] =
    ZIO.serviceWithZIO(_.on(eventType, handler))

  def shutdown: ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.shutdown)

  def addHook(hook: FeatureHook): ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.addHook(hook))

  def clearHooks: ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.clearHooks)

  def hooks: ZIO[FeatureFlags, Nothing, List[FeatureHook]] =
    ZIO.serviceWithZIO(_.hooks)

  // API-level Hooks (per OpenFeature spec 4.4.1)

  /** Add an API-level hook that applies to all clients. */
  def addApiHook(hook: dev.openfeature.sdk.Hook[_]): UIO[Unit] =
    ZIO.succeed(OpenFeatureAPI.getInstance().addHooks(hook))

  /** Clear all API-level hooks. */
  def clearApiHooks: UIO[Unit] =
    ZIO.succeed(OpenFeatureAPI.getInstance().clearHooks())

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

  /** Create a FeatureFlags layer from any OpenFeature provider. */
  def fromProvider(provider: OFFeatureProvider): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped {
      for {
        api    <- ZIO.succeed(OpenFeatureAPI.getInstance())
        _      <- ZIO.attemptBlocking(api.setProviderAndWait(provider))
        client <- ZIO.attempt(api.getClient())
        providerName = Option(provider.getMetadata).map(_.getName).getOrElse("unknown")
        globalCtxRef   <- Ref.make(EvaluationContext.empty)
        clientCtxRef   <- Ref.make(EvaluationContext.empty)
        fiberCtxRef    <- FiberRef.make(EvaluationContext.empty)
        transactionRef <- FiberRef.make[Option[TransactionState]](None)
        hooksRef       <- Ref.make(List.empty[FeatureHook])
        eventHub       <- Hub.unbounded[ProviderEvent]
        statusRef      <- Ref.make[ProviderStatus](ProviderStatus.Ready)
        _              <- ZIO.addFinalizer(ZIO.attemptBlocking(api.shutdown()).ignore)
      } yield new FeatureFlagsLive(
        client,
        provider,
        providerName,
        None, // default domain
        globalCtxRef,
        clientCtxRef,
        fiberCtxRef,
        transactionRef,
        hooksRef,
        eventHub,
        statusRef
      )
    }

  /** Create a FeatureFlags layer with a named domain/client. */
  def fromProviderWithDomain(provider: OFFeatureProvider, domain: String): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped {
      for {
        statusRef <- Ref.make[ProviderStatus](ProviderStatus.Ready)
        ff        <- buildWithDomain(provider, domain, statusRef)
      } yield ff
    }

  /** Create a FeatureFlags layer with a named domain/client and a shared status ref. */
  private[openfeature] def fromProviderWithDomain(
    provider: OFFeatureProvider,
    domain: String,
    statusRef: Ref[ProviderStatus]
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped(buildWithDomain(provider, domain, statusRef))

  private def buildWithDomain(
    provider: OFFeatureProvider,
    domain: String,
    statusRef: Ref[ProviderStatus]
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    for {
      api    <- ZIO.succeed(OpenFeatureAPI.getInstance())
      _      <- ZIO.attemptBlocking(api.setProviderAndWait(domain, provider))
      client <- ZIO.attempt(api.getClient(domain))
      providerName = Option(provider.getMetadata).map(_.getName).getOrElse("unknown")
      globalCtxRef   <- Ref.make(EvaluationContext.empty)
      clientCtxRef   <- Ref.make(EvaluationContext.empty)
      fiberCtxRef    <- FiberRef.make(EvaluationContext.empty)
      transactionRef <- FiberRef.make[Option[TransactionState]](None)
      hooksRef       <- Ref.make(List.empty[FeatureHook])
      eventHub       <- Hub.unbounded[ProviderEvent]
    } yield new FeatureFlagsLive(
      client,
      provider,
      providerName,
      Some(domain),
      globalCtxRef,
      clientCtxRef,
      fiberCtxRef,
      transactionRef,
      hooksRef,
      eventHub,
      statusRef
    )

  /** Create a FeatureFlags layer from multiple providers using the first-match strategy. */
  def fromMultiProvider(providers: List[OFFeatureProvider]): ZLayer[Scope, Throwable, FeatureFlags] = {
    import scala.jdk.CollectionConverters._
    fromProvider(new MultiProvider(providers.asJava))
  }

  /** Create a FeatureFlags layer from multiple providers with a custom strategy. */
  def fromMultiProvider(
    providers: List[OFFeatureProvider],
    strategy: Strategy
  ): ZLayer[Scope, Throwable, FeatureFlags] = {
    import scala.jdk.CollectionConverters._
    fromProvider(new MultiProvider(providers.asJava, strategy))
  }

  /** Create a FeatureFlags layer with initial hooks. */
  def fromProviderWithHooks(
    provider: OFFeatureProvider,
    initialHooks: List[FeatureHook]
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped {
      for {
        api    <- ZIO.succeed(OpenFeatureAPI.getInstance())
        _      <- ZIO.attemptBlocking(api.setProviderAndWait(provider))
        client <- ZIO.attempt(api.getClient())
        providerName = Option(provider.getMetadata).map(_.getName).getOrElse("unknown")
        globalCtxRef   <- Ref.make(EvaluationContext.empty)
        clientCtxRef   <- Ref.make(EvaluationContext.empty)
        fiberCtxRef    <- FiberRef.make(EvaluationContext.empty)
        transactionRef <- FiberRef.make[Option[TransactionState]](None)
        hooksRef       <- Ref.make(initialHooks)
        eventHub       <- Hub.unbounded[ProviderEvent]
        statusRef      <- Ref.make[ProviderStatus](ProviderStatus.Ready)
        _              <- ZIO.addFinalizer(ZIO.attemptBlocking(api.shutdown()).ignore)
      } yield new FeatureFlagsLive(
        client,
        provider,
        providerName,
        None, // default domain
        globalCtxRef,
        clientCtxRef,
        fiberCtxRef,
        transactionRef,
        hooksRef,
        eventHub,
        statusRef
      )
    }
}
