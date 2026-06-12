package zio.openfeature

import zio._
import zio.stream._
import zio.openfeature.internal.FeatureFlagsState
import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider, OpenFeatureAPI, OpenFeatureAPIFactory, ProviderState}
import dev.openfeature.sdk.multiprovider.{MultiProvider, Strategy, FirstMatchStrategy, FirstSuccessfulStrategy}
import java.util.concurrent.TimeoutException

trait FeatureFlags {

  // Abstract detailed evaluation methods (one per type, with defaults for ctx and options)

  def booleanDetails(
    key: String,
    default: Boolean,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[FeatureFlagError, FlagResolution[Boolean]]

  def stringDetails(
    key: String,
    default: String,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[FeatureFlagError, FlagResolution[String]]

  def intDetails(
    key: String,
    default: Int,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[FeatureFlagError, FlagResolution[Int]]

  def longDetails(
    key: String,
    default: Long,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[FeatureFlagError, FlagResolution[Long]]

  def doubleDetails(
    key: String,
    default: Double,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[FeatureFlagError, FlagResolution[Double]]

  def objDetails(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[FeatureFlagError, FlagResolution[Map[String, Any]]]

  def valueDetails[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): IO[FeatureFlagError, FlagResolution[A]]

  // Concrete simple evaluation methods (delegate to details)

  def boolean(key: String, default: Boolean): IO[FeatureFlagError, Boolean] =
    booleanDetails(key, default).map(_.value)

  def boolean(key: String, default: Boolean, ctx: EvaluationContext): IO[FeatureFlagError, Boolean] =
    booleanDetails(key, default, ctx).map(_.value)

  def string(key: String, default: String): IO[FeatureFlagError, String] =
    stringDetails(key, default).map(_.value)

  def string(key: String, default: String, ctx: EvaluationContext): IO[FeatureFlagError, String] =
    stringDetails(key, default, ctx).map(_.value)

  def int(key: String, default: Int): IO[FeatureFlagError, Int] =
    intDetails(key, default).map(_.value)

  def int(key: String, default: Int, ctx: EvaluationContext): IO[FeatureFlagError, Int] =
    intDetails(key, default, ctx).map(_.value)

  def long(key: String, default: Long): IO[FeatureFlagError, Long] =
    longDetails(key, default).map(_.value)

  def long(key: String, default: Long, ctx: EvaluationContext): IO[FeatureFlagError, Long] =
    longDetails(key, default, ctx).map(_.value)

  def double(key: String, default: Double): IO[FeatureFlagError, Double] =
    doubleDetails(key, default).map(_.value)

  def double(key: String, default: Double, ctx: EvaluationContext): IO[FeatureFlagError, Double] =
    doubleDetails(key, default, ctx).map(_.value)

  def obj(key: String, default: Map[String, Any]): IO[FeatureFlagError, Map[String, Any]] =
    objDetails(key, default).map(_.value)

  def obj(key: String, default: Map[String, Any], ctx: EvaluationContext): IO[FeatureFlagError, Map[String, Any]] =
    objDetails(key, default, ctx).map(_.value)

  def value[A: FlagType](key: String, default: A): IO[FeatureFlagError, A] =
    valueDetails(key, default).map(_.value)

  def value[A: FlagType](key: String, default: A, ctx: EvaluationContext): IO[FeatureFlagError, A] =
    valueDetails(key, default, ctx).map(_.value)

  def setGlobalContext(ctx: EvaluationContext): UIO[Unit]
  def globalContext: UIO[EvaluationContext]

  /** Set the client-level evaluation context.
    *
    * Per OpenFeature spec, context merges in order: API (global) -> Transaction -> Client -> Invocation. Client context
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
  def addHooks(hooks: List[FeatureHook]): UIO[Unit]
  def clearHooks: UIO[Unit]
  def hooks: UIO[List[FeatureHook]]

  /** Register a ZIO API-level hook (spec §4.4.1 level 1). Runs before client-level hooks on every evaluation. */
  def addZioApiHook(hook: FeatureHook): UIO[Unit]
  def addZioApiHooks(hooks: List[FeatureHook]): UIO[Unit]
  def clearZioApiHooks: UIO[Unit]
  def zioApiHooks: UIO[List[FeatureHook]]

  /** Add an API-level hook that applies to all clients sharing this instance's OpenFeatureAPI. */
  def addApiHook(hook: dev.openfeature.sdk.Hook[_]): UIO[Unit]

  /** Clear all API-level hooks on this instance's OpenFeatureAPI. */
  def clearApiHooks: UIO[Unit]

  /** Replace the underlying provider at runtime.
    *
    * The old provider is shut down, the new provider is initialized, and the status transitions through `NotReady`
    * during the swap. Hooks, context, and event handlers are preserved. Evaluations that start during the swap fail
    * with `ProviderNotReady`.
    */
  def setProvider(provider: OFFeatureProvider): IO[FeatureFlagError, Unit]

  // Shutdown API (spec 1.6.1)
  def shutdown: UIO[Unit]

  // Tracking API
  def track(eventName: String): IO[FeatureFlagError, Unit]
  def track(eventName: String, context: EvaluationContext): IO[FeatureFlagError, Unit]
  def track(eventName: String, details: TrackingEventDetails): IO[FeatureFlagError, Unit]
  def track(eventName: String, context: EvaluationContext, details: TrackingEventDetails): IO[FeatureFlagError, Unit]
  def trackedEvents: UIO[List[(String, EvaluationContext, Option[TrackingEventDetails])]]
}

object FeatureFlags {

  // Service Accessors - simple evaluation

  def boolean(key: String, default: Boolean): ZIO[FeatureFlags, FeatureFlagError, Boolean] =
    ZIO.serviceWithZIO(_.boolean(key, default))

  def boolean(key: String, default: Boolean, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, Boolean] =
    ZIO.serviceWithZIO(_.boolean(key, default, ctx))

  def string(key: String, default: String): ZIO[FeatureFlags, FeatureFlagError, String] =
    ZIO.serviceWithZIO(_.string(key, default))

  def string(key: String, default: String, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, String] =
    ZIO.serviceWithZIO(_.string(key, default, ctx))

  def int(key: String, default: Int): ZIO[FeatureFlags, FeatureFlagError, Int] =
    ZIO.serviceWithZIO(_.int(key, default))

  def int(key: String, default: Int, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, Int] =
    ZIO.serviceWithZIO(_.int(key, default, ctx))

  def long(key: String, default: Long): ZIO[FeatureFlags, FeatureFlagError, Long] =
    ZIO.serviceWithZIO(_.long(key, default))

  def long(key: String, default: Long, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, Long] =
    ZIO.serviceWithZIO(_.long(key, default, ctx))

  def double(key: String, default: Double): ZIO[FeatureFlags, FeatureFlagError, Double] =
    ZIO.serviceWithZIO(_.double(key, default))

  def double(key: String, default: Double, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, Double] =
    ZIO.serviceWithZIO(_.double(key, default, ctx))

  def obj(key: String, default: Map[String, Any]): ZIO[FeatureFlags, FeatureFlagError, Map[String, Any]] =
    ZIO.serviceWithZIO(_.obj(key, default))

  def obj(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext
  ): ZIO[FeatureFlags, FeatureFlagError, Map[String, Any]] =
    ZIO.serviceWithZIO(_.obj(key, default, ctx))

  def value[A: FlagType](key: String, default: A): ZIO[FeatureFlags, FeatureFlagError, A] =
    ZIO.serviceWithZIO(_.value(key, default))

  def value[A: FlagType](key: String, default: A, ctx: EvaluationContext): ZIO[FeatureFlags, FeatureFlagError, A] =
    ZIO.serviceWithZIO(_.value(key, default, ctx))

  // Service Accessors - detailed evaluation (with default parameters)

  def booleanDetails(
    key: String,
    default: Boolean,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Boolean]] =
    ZIO.serviceWithZIO(_.booleanDetails(key, default, ctx, options))

  def stringDetails(
    key: String,
    default: String,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[String]] =
    ZIO.serviceWithZIO(_.stringDetails(key, default, ctx, options))

  def intDetails(
    key: String,
    default: Int,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Int]] =
    ZIO.serviceWithZIO(_.intDetails(key, default, ctx, options))

  def longDetails(
    key: String,
    default: Long,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Long]] =
    ZIO.serviceWithZIO(_.longDetails(key, default, ctx, options))

  def doubleDetails(
    key: String,
    default: Double,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Double]] =
    ZIO.serviceWithZIO(_.doubleDetails(key, default, ctx, options))

  def objDetails(
    key: String,
    default: Map[String, Any],
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
  ): ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Map[String, Any]]] =
    ZIO.serviceWithZIO(_.objDetails(key, default, ctx, options))

  def valueDetails[A: FlagType](
    key: String,
    default: A,
    ctx: EvaluationContext = EvaluationContext.empty,
    options: EvaluationOptions = EvaluationOptions.empty
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

  def addHooks(hooks: List[FeatureHook]): ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.addHooks(hooks))

  def clearHooks: ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.clearHooks)

  def hooks: ZIO[FeatureFlags, Nothing, List[FeatureHook]] =
    ZIO.serviceWithZIO(_.hooks)

  def addZioApiHook(hook: FeatureHook): ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.addZioApiHook(hook))

  def addZioApiHooks(hooks: List[FeatureHook]): ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.addZioApiHooks(hooks))

  def clearZioApiHooks: ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.clearZioApiHooks)

  def zioApiHooks: ZIO[FeatureFlags, Nothing, List[FeatureHook]] =
    ZIO.serviceWithZIO(_.zioApiHooks)

  // API-level Hooks (per OpenFeature spec 4.4.1)

  def addApiHook(hook: dev.openfeature.sdk.Hook[_]): ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.addApiHook(hook))

  def clearApiHooks: ZIO[FeatureFlags, Nothing, Unit] =
    ZIO.serviceWithZIO(_.clearApiHooks)

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

  def trackedEvents: ZIO[FeatureFlags, Nothing, List[(String, EvaluationContext, Option[TrackingEventDetails])]] =
    ZIO.serviceWithZIO(_.trackedEvents)

  def setProvider(provider: OFFeatureProvider): ZIO[FeatureFlags, FeatureFlagError, Unit] =
    ZIO.serviceWithZIO(_.setProvider(provider))

  // Factory Methods

  /** Default cap on how long initialization may take before the layer build fails or — for async layers — transitions
    * to `Fatal`. Mirrors the documented default referenced in every factory's ScalaDoc.
    */
  private[openfeature] val DefaultInitTimeout: Duration = 30.seconds

  /** Verify the provider reached a usable state after sync initialization. Anything outside `READY` / `STALE` causes
    * the layer build to fail so misconfiguration surfaces at startup, not at first evaluation. Returns the ZIO-level
    * `ProviderStatus` that should be reflected for a usable state.
    */
  @scala.annotation.nowarn("msg=deprecated")
  private def verifyInitState(provider: OFFeatureProvider): ZIO[Any, Throwable, ProviderStatus] =
    ZIO.attempt(provider.getState).flatMap {
      case ProviderState.READY => ZIO.succeed(ProviderStatus.Ready)
      case ProviderState.STALE => ZIO.succeed(ProviderStatus.Stale)
      case bad @ (ProviderState.ERROR | ProviderState.FATAL | ProviderState.NOT_READY) =>
        ZIO.fail(new IllegalStateException(s"Provider in $bad state after initialization"))
    }

  /** Shared initialization logic for all factory methods. */
  private[openfeature] def build(
    provider: OFFeatureProvider,
    domain: Option[String],
    version: Option[String],
    initialHooks: List[FeatureHook],
    statusRef: Option[Ref[ProviderStatus]],
    addShutdownFinalizer: Boolean,
    apiOverride: Option[OpenFeatureAPI] = None,
    evaluationTimeout: Option[Duration] = None,
    initTimeout: Duration = DefaultInitTimeout
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    for {
      api <- ZIO.succeed(apiOverride.getOrElse(OpenFeatureAPI.getInstance()))
      // Register the API shutdown finalizer BEFORE initiating provider registration. If init times out,
      // the provider reports a bad state, or getClient fails, the scope close still tears down whatever
      // the (possibly still-running, disconnected) setProviderAndWait managed to register.
      _ <- ZIO.when(addShutdownFinalizer)(ZIO.addFinalizer(ZIO.attemptBlocking(api.shutdown()).ignore))
      setAndWait = domain match {
        case Some(d) => ZIO.attemptBlocking(api.setProviderAndWait(d, provider))
        case None    => ZIO.attemptBlocking(api.setProviderAndWait(provider))
      }
      // Bound the blocking init. `.disconnect` ensures the timeout returns promptly even though
      // `attemptBlocking` runs on the blocking pool; the underlying call may still run to completion
      // in the background. On any init failure the provider itself is shut down (best-effort) so it
      // doesn't keep threads/connections alive — this also covers the domain/registry paths where no
      // API finalizer is registered.
      verified <- (setAndWait.disconnect
        .timeoutFail(new TimeoutException(s"Provider initialization exceeded $initTimeout"))(initTimeout) *>
        verifyInitState(provider))
        .tapError(_ => ZIO.attemptBlocking(provider.shutdown()).ignore)
      client <- (domain, version) match {
        case (Some(d), Some(v)) => ZIO.attempt(api.getClient(d, v))
        case (Some(d), None)    => ZIO.attempt(api.getClient(d))
        case _                  => ZIO.attempt(api.getClient())
      }
      providerName = Option(provider.getMetadata).map(_.getName).getOrElse("unknown")
      providerRef     <- Ref.make(provider)
      providerNameRef <- Ref.make(providerName)
      swapLock        <- Semaphore.make(1)
      baseState       <- FeatureFlagsState.make
      state = statusRef.fold(baseState)(ref => baseState.copy(statusRef = ref))
      _ <- state.hooksRef.set(initialHooks)
      // Only seed status when the caller didn't hand us a shared ref (testkit shares one).
      _ <- statusRef.fold(state.statusRef.set(verified))(_ => ZIO.unit)
      ff = new FeatureFlagsLive(
        client,
        providerRef,
        providerNameRef,
        domain,
        version,
        state,
        api,
        swapLock,
        evaluationTimeout = evaluationTimeout
      )
      _ <- ff.startEventBridge
    } yield ff

  /** Create a FeatureFlags layer from any OpenFeature provider.
    *
    * Initialization is bounded by the default 30s init timeout: if `setProviderAndWait` takes longer, or the provider
    * reports `ERROR`/`FATAL`/`NOT_READY` afterwards, the layer build fails with a `TimeoutException` or
    * `IllegalStateException` wrapped at the layer boundary. Use the overload that accepts an explicit `initTimeout` to
    * raise/lower this bound; pass a very large duration (e.g. `365.days`) to effectively disable it.
    */
  def fromProvider(provider: OFFeatureProvider): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped(
      build(provider, domain = None, version = None, initialHooks = Nil, statusRef = None, addShutdownFinalizer = true)
    )

  /** Create a FeatureFlags layer with a global evaluation timeout.
    *
    * If a provider evaluation takes longer than `evaluationTimeout`, it fails with `ProviderError` containing a
    * `TimeoutException`. This prevents hung providers from blocking fibers indefinitely. Per-call timeouts set via
    * `EvaluationOptions.timeout` override this global default. Initialization uses the default 30s init timeout — see
    * [[fromProvider(OFFeatureProvider, Duration, Duration)]] to override.
    */
  def fromProvider(provider: OFFeatureProvider, evaluationTimeout: Duration): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped(
      build(
        provider,
        domain = None,
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = true,
        evaluationTimeout = Some(evaluationTimeout)
      )
    )

  /** Create a FeatureFlags layer with explicit evaluation and initialization timeouts.
    *
    * `initTimeout` bounds the sync init: if `setProviderAndWait` takes longer, the layer build fails with a
    * `TimeoutException`. After init, if the provider reports `ERROR`/`FATAL`/`NOT_READY`, the build also fails so
    * misconfiguration surfaces at startup rather than at first evaluation. Pass a very large duration to effectively
    * disable the init timeout.
    */
  def fromProvider(
    provider: OFFeatureProvider,
    evaluationTimeout: Duration,
    initTimeout: Duration
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped(
      build(
        provider,
        domain = None,
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = true,
        evaluationTimeout = Some(evaluationTimeout),
        initTimeout = initTimeout
      )
    )

  /** Create a FeatureFlags layer with a named domain/client. */
  def fromProviderWithDomain(provider: OFFeatureProvider, domain: String): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped(
      build(
        provider,
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = false
      )
    )

  /** Create a FeatureFlags layer with a named domain/client and version. */
  def fromProviderWithDomain(
    provider: OFFeatureProvider,
    domain: String,
    version: String
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped(
      build(
        provider,
        domain = Some(domain),
        version = Some(version),
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = false
      )
    )

  /** Create a FeatureFlags layer with a named domain/client and a shared status ref. */
  private[openfeature] def fromProviderWithDomain(
    provider: OFFeatureProvider,
    domain: String,
    statusRef: Ref[ProviderStatus],
    api: Option[OpenFeatureAPI] = None
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped(
      build(
        provider,
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = Some(statusRef),
        addShutdownFinalizer = false,
        apiOverride = api
      )
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
    ZLayer.scoped(
      build(
        provider,
        domain = None,
        version = None,
        initialHooks = initialHooks,
        statusRef = None,
        addShutdownFinalizer = true
      )
    )

  // Async Factory Methods (non-blocking provider initialization)

  /** Shared initialization logic for async factory methods.
    *
    * Uses `setProvider` (non-blocking) instead of `setProviderAndWait`. The provider initializes in the background;
    * evaluations fail with `ProviderNotReady` until the event bridge receives a `PROVIDER_READY` event. To avoid a race
    * where the provider becomes ready before the event bridge is registered, we start the event bridge first and then
    * check the provider's actual state.
    */
  private[openfeature] def buildAsync(
    provider: OFFeatureProvider,
    domain: Option[String],
    version: Option[String],
    initialHooks: List[FeatureHook],
    statusRef: Option[Ref[ProviderStatus]],
    addShutdownFinalizer: Boolean,
    apiOverride: Option[OpenFeatureAPI] = None,
    onReady: Option[java.util.concurrent.CountDownLatch] = None,
    evaluationTimeout: Option[Duration] = None,
    initTimeout: Duration = DefaultInitTimeout
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    for {
      api <- ZIO.succeed(apiOverride.getOrElse(OpenFeatureAPI.getInstance()))
      // Register the API shutdown finalizer BEFORE the provider, so a failure later in the build
      // (e.g. getClient) still tears down the registered provider when the scope closes.
      _ <- ZIO.when(addShutdownFinalizer)(ZIO.addFinalizer(ZIO.attemptBlocking(api.shutdown()).ignore))
      // Register provider FIRST so the client binds to it (not the NoOp default)
      _ <- domain match {
        case Some(d) => ZIO.succeed(api.setProvider(d, provider))
        case None    => ZIO.succeed(api.setProvider(provider))
      }
      client <- (domain, version) match {
        case (Some(d), Some(v)) => ZIO.attempt(api.getClient(d, v))
        case (Some(d), None)    => ZIO.attempt(api.getClient(d))
        case _                  => ZIO.attempt(api.getClient())
      }
      providerName = Option(provider.getMetadata).map(_.getName).getOrElse("unknown")
      providerRef     <- Ref.make(provider)
      providerNameRef <- Ref.make(providerName)
      swapLock        <- Semaphore.make(1)
      baseState       <- FeatureFlagsState.make
      state = statusRef.fold(baseState)(ref => baseState.copy(statusRef = ref))
      _ <- state.hooksRef.set(initialHooks)
      ff = new FeatureFlagsLive(
        client,
        providerRef,
        providerNameRef,
        domain,
        version,
        state,
        api,
        swapLock,
        onReady,
        evaluationTimeout
      )
      // Start event bridge — if provider is already ready, replay fires immediately
      _ <- ff.startEventBridge
      // Init watchdog: after initTimeout, if the provider hasn't moved past NotReady/Error,
      // transition to Fatal so callers polling providerStatus stop waiting. The fiber is forked
      // into the layer's Scope so it's interrupted when the layer is released.
      _ <- (ZIO.sleep(initTimeout) *> state.statusRef.update {
        case ProviderStatus.NotReady | ProviderStatus.Error => ProviderStatus.Fatal
        case other                                          => other
      }).forkScoped
    } yield ff

  /** Create a FeatureFlags layer from any OpenFeature provider (non-blocking).
    *
    * The provider initializes in the background. Evaluations fail with `ProviderNotReady` until the provider is ready.
    * Use `onProviderReady` or `providerStatus` to detect when the provider becomes available. If the provider has not
    * become ready within the default 30s init timeout, status atomically transitions to `Fatal` so callers polling
    * `providerStatus` stop waiting. See [[fromProviderAsync(OFFeatureProvider, Duration, Duration)]] to override.
    */
  def fromProviderAsync(provider: OFFeatureProvider): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped(
      buildAsync(
        provider,
        domain = None,
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = true
      )
    )

  /** Create a FeatureFlags layer with a global evaluation timeout (non-blocking).
    *
    * Combines async initialization with evaluation timeout protection. The provider initializes in the background;
    * evaluations fail with `ProviderNotReady` until ready. Once ready, evaluations that exceed `evaluationTimeout` fail
    * with `ProviderError` containing a `TimeoutException`. The init-side default 30s watchdog still applies — see
    * [[fromProviderAsync(OFFeatureProvider, Duration, Duration)]] to override.
    */
  def fromProviderAsync(
    provider: OFFeatureProvider,
    evaluationTimeout: Duration
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped(
      buildAsync(
        provider,
        domain = None,
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = true,
        evaluationTimeout = Some(evaluationTimeout)
      )
    )

  /** Create a FeatureFlags layer with explicit evaluation and initialization timeouts (non-blocking).
    *
    * `initTimeout` bounds the async ready window: after that duration, if status is still `NotReady` or `Error`, it
    * atomically transitions to `Fatal`. Pass a very large duration to effectively disable the watchdog.
    */
  def fromProviderAsync(
    provider: OFFeatureProvider,
    evaluationTimeout: Duration,
    initTimeout: Duration
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped(
      buildAsync(
        provider,
        domain = None,
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = true,
        evaluationTimeout = Some(evaluationTimeout),
        initTimeout = initTimeout
      )
    )

  /** Create a FeatureFlags layer with a named domain (non-blocking). */
  def fromProviderWithDomainAsync(
    provider: OFFeatureProvider,
    domain: String
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped(
      buildAsync(
        provider,
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = false
      )
    )

  /** Create a FeatureFlags layer with a named domain and version (non-blocking). */
  def fromProviderWithDomainAsync(
    provider: OFFeatureProvider,
    domain: String,
    version: String
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped(
      buildAsync(
        provider,
        domain = Some(domain),
        version = Some(version),
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = false
      )
    )

  /** Create a FeatureFlags layer with a named domain and shared status ref (non-blocking). */
  private[openfeature] def fromProviderWithDomainAsync(
    provider: OFFeatureProvider,
    domain: String,
    statusRef: Ref[ProviderStatus],
    api: Option[OpenFeatureAPI] = None,
    onReady: Option[java.util.concurrent.CountDownLatch] = None
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped(
      buildAsync(
        provider,
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = Some(statusRef),
        addShutdownFinalizer = false,
        apiOverride = api,
        onReady = onReady
      )
    )

  /** Create a FeatureFlags layer with initial hooks (non-blocking). */
  def fromProviderWithHooksAsync(
    provider: OFFeatureProvider,
    initialHooks: List[FeatureHook]
  ): ZLayer[Scope, Throwable, FeatureFlags] =
    ZLayer.scoped(
      buildAsync(
        provider,
        domain = None,
        version = None,
        initialHooks = initialHooks,
        statusRef = None,
        addShutdownFinalizer = true
      )
    )

  /** Create a FeatureFlags layer from multiple providers (non-blocking, first-match strategy). */
  def fromMultiProviderAsync(providers: List[OFFeatureProvider]): ZLayer[Scope, Throwable, FeatureFlags] = {
    import scala.jdk.CollectionConverters._
    fromProviderAsync(new MultiProvider(providers.asJava))
  }

  /** Create a FeatureFlags layer from multiple providers with a custom strategy (non-blocking). */
  def fromMultiProviderAsync(
    providers: List[OFFeatureProvider],
    strategy: Strategy
  ): ZLayer[Scope, Throwable, FeatureFlags] = {
    import scala.jdk.CollectionConverters._
    fromProviderAsync(new MultiProvider(providers.asJava, strategy))
  }
}
