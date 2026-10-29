package zio.openfeature.optimizely

import com.optimizely.ab.Optimizely
import com.optimizely.ab.config.HttpProjectConfigManager
import com.optimizely.ab.config.parser.{ConfigParser, DefaultConfigParser}
import com.optimizely.ab.event.{AsyncEventHandler, BatchEventProcessor, EventHandler}
import zio._
import zio.openfeature.FeatureFlagError
import java.util.concurrent.{ExecutorService, Executors, ThreadFactory, TimeUnit}

/** Scala-friendly factories for an Optimizely-backed OpenFeature provider.
  *
  * The library integrates with Optimizely Feature Experimentation directly via the Optimizely Java SDK
  * (`com.optimizely.ab:core-api` + `core-httpclient-impl`) rather than the still-unpublished
  * `dev.openfeature.contrib.providers:optimizely` artifact. Callers get the same OpenFeature surface area
  * (`FeatureProvider`) as any other provider — pass the result of [[make]] to `FeatureFlags.fromProviderAsync`
  * (recommended for Optimizely — datafile fetch is a real HTTP call against the CDN) or, if you need a hard guarantee
  * that the provider is ready before the app starts serving traffic, `FeatureFlags.fromProvider`.
  *
  * '''Recommended production pattern:''' compose with `CircuitBreakerProvider` from the `extras` module so a degraded
  * Optimizely CDN doesn't take your app down. The circuit breaker opens after repeated failures and serves cached
  * decisions (or defaults) while it's open. Sketch:
  * {{{
  * import zio._
  * import zio.openfeature._
  * import zio.openfeature.extras.{CircuitBreakerProvider, CircuitBreakerProviderConfig}
  * import zio.openfeature.optimizely.OptimizelyProvider
  *
  * val program = ZIO.scoped {
  *   for {
  *     inner   <- OptimizelyProvider.make(sys.env("OPTIMIZELY_SDK_KEY"))
  *     wrapped <- CircuitBreakerProvider.make(
  *                  inner,
  *                  CircuitBreakerProviderConfig(failureThreshold = 5, resetTimeout = 30.seconds)
  *                )
  *     env     <- FeatureFlags.fromProvider(wrapped, FeatureFlagsConfig(initMode = InitMode.Async).withEvaluationTimeout(500.millis)).build
  *     ff       = env.get[FeatureFlags]
  *     enabled <- ff.boolean("flag", default = false)
  *   } yield enabled
  * }
  * }}}
  *
  * '''Failure semantics on bad credentials / unreachable CDN:'''
  *   - If the Optimizely datafile fetch fails (auth, network, 5xx), the underlying client stays `!isValid` and
  *     [[OptimizelyFeatureProvider.initialize]] throws after its `initWait` elapses. The outer
  *     `FeatureFlagsConfig().withInitTimeout(…)` translates that into either a layer build failure (sync mode) or a
  *     `ProviderStatus.Fatal` transition (async mode), so an evaluation never silently returns the default value under
  *     a misconfigured provider.
  *   - Construction (this object's `make`) does not block on the network, but the first datafile fetch starts then: the
  *     Optimizely SDK offers no way to build its polling config manager paused. `initialize()` waits for it.
  *
  * Construction validates the SDK key (and URL, where applicable) before touching the Optimizely SDK, and checks that
  * the SDK can find a JSON parser to read datafiles with; failures surface as `FeatureFlagError.InvalidConfiguration`
  * at layer build time, not at first evaluation.
  */
/** Configuration for a factory-built Optimizely provider.
  *
  * @param sdkKey
  *   Optimizely SDK key (validated — see [[OptimizelyProvider.make]])
  * @param datafileUrl
  *   Optional self-hosted datafile URL (Optimizely Agent); `None` uses the public CDN
  * @param initWait
  *   How long `initialize()` waits for the first datafile load before failing
  * @param pollingInterval
  *   Datafile poll interval; `None` uses the SDK default (5 minutes). Tests that need fast revision pickup (or
  *   effectively none) can tune this instead of hand-rolling client construction.
  * @param blockingTimeout
  *   Upper bound on the SDK's internal blocking `getConfig()` waits; `None` uses the SDK default (10 seconds)
  * @param staleAfter
  *   Staleness watchdog threshold. After the provider is READY, if datafile poll fetches stop succeeding for this long
  *   the provider transitions to `PROVIDER_STALE` (and back to `PROVIDER_READY` once fetches resume). The watchdog is
  *   only active when datafile polling is enabled (`pollingInterval` is `Some`) — with polling off there is nothing to
  *   observe and no watchdog runs. When polling is on and this is `None`, the threshold defaults to `3 ×
  *   pollingInterval`. Must be positive.
  * @param events
  *   What happens to the impression and conversion events the SDK produces. The default, [[OptimizelyEvents.Batched]],
  *   sends them to Optimizely in batches, as the SDK's own default factory does: without them, experiments show no
  *   visitors and `track` conversions are lost. [[OptimizelyEvents.Disabled]] drops them.
  */
final case class OptimizelyProviderConfig(
  sdkKey: String,
  datafileUrl: Option[String] = None,
  initWait: java.time.Duration = OptimizelyProvider.DefaultInitWait,
  pollingInterval: Option[java.time.Duration] = None,
  blockingTimeout: Option[java.time.Duration] = None,
  staleAfter: Option[java.time.Duration] = None,
  events: OptimizelyEvents = OptimizelyEvents.Batched()
)

/** What a factory-built provider does with the events the Optimizely SDK produces: an impression for each decision
  * (when the datafile's `sendFlagDecisions` is on, rollout decisions included) and a conversion for each `track`.
  *
  * Events carry the user id (the context's targeting key), the audience attributes derived from the evaluation context,
  * and the decision or conversion, and are sent to `logx.optimizely.com`.
  */
sealed trait OptimizelyEvents extends Product with Serializable

object OptimizelyEvents {

  /** Drop every event: nothing is sent to Optimizely. Experiment results then show no visitors and no conversions. */
  case object Disabled extends OptimizelyEvents

  /** Queue events and send them to Optimizely in batches, from a background thread.
    *
    * @param batchSize
    *   events per request (the SDK default, 10); at least 1
    * @param flushInterval
    *   longest an event waits in the queue before its batch is sent (the SDK default, 30 seconds); positive
    * @param closeTimeout
    *   on shutdown, how long to wait for the queued events to be flushed, and then again for the requests in flight to
    *   finish; positive and at most 4 seconds. The two waits and the datafile poller's close must fit the provider's
    *   10-second shutdown bound, so `2 × 4 s` is the most it allows. Events still queued when it runs out are dropped.
    */
  final case class Batched(
    batchSize: Int = 10,
    flushInterval: java.time.Duration = java.time.Duration.ofSeconds(30),
    closeTimeout: java.time.Duration = java.time.Duration.ofSeconds(3)
  ) extends OptimizelyEvents

  /** Upper bound on [[Batched.closeTimeout]]; see there. */
  private[optimizely] val MaxCloseTimeout: java.time.Duration = java.time.Duration.ofSeconds(4)
}

object OptimizelyProvider {

  /** Default time the underlying `OptimizelyFeatureProvider.initialize()` will wait for the first datafile load before
    * declaring the provider failed. It is deliberately shorter than the `FeatureFlags` default `initTimeout` (30
    * seconds), so a datafile that never loads is reported with the provider's own error ("datafile did not load …")
    * rather than the generic outer timeout.
    */
  val DefaultInitWait: java.time.Duration = java.time.Duration.ofSeconds(25)

  // Staleness watchdog tuning: without `staleAfter`, the provider is STALE after this many polling intervals with no
  // successful fetch; the watchdog checks every polling interval or every threshold / divisor, whichever is shorter,
  // and never more often than every `MinCheckIntervalMs`.
  private[optimizely] val DefaultStaleMultiplier: Long = 3L
  private[optimizely] val StalenessCheckDivisor: Long  = 2L
  private[optimizely] val MinCheckIntervalMs: Long     = 1L

  /** Validate an SDK key and construct a provider that fetches its datafile from the Optimizely CDN.
    *
    * Validation rules:
    *   - non-null and non-empty after trim
    *   - no whitespace inside the key
    *   - matches `[A-Za-z0-9_-]+`
    *   - not an obvious placeholder (e.g. `YOUR_SDK_KEY`)
    */
  def make(sdkKey: String): IO[FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    make(sdkKey, datafileUrl = None, initWait = DefaultInitWait)

  /** Validate an SDK key + custom datafile URL and construct a provider. Use this when running an Optimizely Agent
    * inside your network rather than fetching from the public CDN. Both the SDK key and URL are validated.
    */
  def make(sdkKey: String, datafileUrl: String): IO[FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    make(sdkKey, datafileUrl = Some(datafileUrl), initWait = DefaultInitWait)

  /** Full-control overload exposing the internal `initWait`. Most callers should prefer one of the simpler overloads
    * plus the outer `FeatureFlagsConfig.initTimeout`.
    */
  def make(
    sdkKey: String,
    datafileUrl: Option[String],
    initWait: java.time.Duration
  ): IO[FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    make(OptimizelyProviderConfig(sdkKey, datafileUrl, initWait))

  /** Validate the configuration and construct a provider.
    *
    * Construction does not block on the network, but the datafile poller starts then — the Optimizely SDK offers no way
    * to build it paused — and `initialize()` waits for its first fetch. The returned provider owns a polling executor
    * and an HTTP client — the caller is responsible for `shutdown()` (registering it with a `FeatureFlags` layer
    * counts: the layer's finalizer shuts the provider down). Prefer [[scoped]] or [[layer]] to make that ownership
    * explicit.
    */
  def make(config: OptimizelyProviderConfig): IO[FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    make(config, httpClient = None)

  /** Test seam: [[make]] with an injected HTTP client (see `TestHttpClient` in the test sources). */
  private[optimizely] def make(
    config: OptimizelyProviderConfig,
    httpClient: Option[com.optimizely.ab.OptimizelyHttpClient]
  ): IO[FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    make(config, httpClient, eventHandler = None)

  /** Test seam: [[make]] with an injected HTTP client and, for [[OptimizelyEvents.Batched]], an event handler that
    * replaces the one sending batches to Optimizely.
    */
  private[optimizely] def make(
    config: OptimizelyProviderConfig,
    httpClient: Option[com.optimizely.ab.OptimizelyHttpClient],
    eventHandler: Option[EventHandler]
  ): IO[FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    makeWithParserProbe(config, httpClient, DefaultConfigParser.getInstance(), eventHandler)

  /** Test seam: [[make]] with the JSON-parser lookup injected, so a missing parser can be simulated. */
  private[optimizely] def makeWithParserProbe(
    config: OptimizelyProviderConfig,
    httpClient: Option[com.optimizely.ab.OptimizelyHttpClient],
    parserProbe: => ConfigParser,
    eventHandler: Option[EventHandler] = None
  ): IO[FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    for {
      validKey <- validateSdkKey(config.sdkKey)
      validUrl <- config.datafileUrl match {
        case Some(u) => validateDatafileUrl(u).map(Some(_))
        case None    => ZIO.succeed(None: Option[String])
      }
      _ <- validatePositive("pollingInterval", config.pollingInterval)
      _ <- validatePositive("blockingTimeout", config.blockingTimeout)
      _ <- validatePositive("staleAfter", config.staleAfter)
      _ <- validateEvents(config.events)
      _ <- requireJsonParser(parserProbe)
      // The watchdog observes datafile fetches, so it only makes sense when datafile polling is enabled. When it is,
      // the fetch-success signal is shared between the observing HTTP client (below) and the provider's watchdog.
      staleness = stalenessConfig(config)
      observer  = staleness.map(_.observer)
      built <- ZIO
        .attempt(
          buildClient(
            validKey,
            validUrl,
            config.pollingInterval,
            config.blockingTimeout,
            httpClient,
            observer,
            config.events,
            eventHandler
          )
        )
        .mapError(t => FeatureFlagError.InvalidConfiguration(s"Optimizely client build failed: ${t.getMessage}"))
    } yield new OptimizelyFeatureProvider(
      built.optimizely,
      config.initWait,
      closeOnShutdown = true,
      configManager = Some(built.configManager),
      staleness = staleness,
      // Closed in order after `optimizely.close()`, which has flushed the event processor by then. The event
      // processor's executor goes first: `BatchEventProcessor.close()` stops its consumer loop but never shuts the
      // executor down. The HTTP client goes last, as the event handler may use it until the processor is closed.
      ownedResources = built.eventExecutor.toList.map(shutdownOnClose) :+ built.cmabHttpClient
    )

  private def shutdownOnClose(executor: ExecutorService): java.io.Closeable =
    () => executor.shutdownNow(): Unit

  /** Derive the staleness watchdog configuration from a provider config, or `None` when the watchdog is disabled.
    *
    * The watchdog is enabled iff datafile polling is on (`pollingInterval` is `Some`) — with polling off there are no
    * fetches to observe. When on, the staleness threshold is `staleAfter` if given, else `3 × pollingInterval`. The
    * check interval is the smaller of one polling interval and half the threshold (so staleness is detected within
    * roughly one poll of the window elapsing), floored at 1ms.
    */
  private[optimizely] def stalenessConfig(
    config: OptimizelyProviderConfig
  ): Option[OptimizelyFeatureProvider.StalenessConfig] =
    config.pollingInterval.map { poll =>
      val staleAfter = config.staleAfter.getOrElse(poll.multipliedBy(DefaultStaleMultiplier))
      val checkMs = math.max(MinCheckIntervalMs, math.min(poll.toMillis, staleAfter.toMillis / StalenessCheckDivisor))
      OptimizelyFeatureProvider.StalenessConfig(
        new OptimizelyFeatureProvider.FetchObserver,
        staleAfter,
        java.time.Duration.ofMillis(checkMs)
      )
    }

  /** Fail construction when the Optimizely SDK has no JSON parser to read a datafile with.
    *
    * The SDK resolves its parser once, in a lazy holder's static initialiser, so a missing parser surfaces as a
    * `LinkageError` — `ExceptionInInitializerError` on the first lookup, `NoClassDefFoundError` after — rather than an
    * `Exception`. Left to the SDK's poller thread, that error escapes its `catch (Exception)`, kills the scheduled poll
    * unlogged, and `initialize()` later times out blaming the SDK key or network. Probing here, before any poller
    * thread exists, reports the real cause. `ZIO.attempt` catches `LinkageError`; `NonFatal` would not.
    */
  private[optimizely] def requireJsonParser(probe: => ConfigParser): IO[FeatureFlagError.InvalidConfiguration, Unit] =
    ZIO
      .attempt(probe)
      .unit
      .mapError(t =>
        FeatureFlagError.InvalidConfiguration(
          "Optimizely SDK could not initialise a JSON parser (it needs gson, jackson-databind, org.json or " +
            "json-simple on the classpath). zio-openfeature-optimizely depends on jackson-databind, so check for a " +
            "dependency exclusion or a Jackson version the SDK cannot link against. " +
            // The holder's ExceptionInInitializerError carries no message of its own; the cause says what failed.
            s"Cause: ${Option(t.getCause).getOrElse(t)}"
        )
      )

  /** [[make]] with scope-managed shutdown: when the surrounding `Scope` closes, the provider is shut down (bounded),
    * stopping datafile polling and closing the SDK's HTTP client — even if the provider was never registered with a
    * `FeatureFlags` layer or its initialization failed. This is the recommended construction for tests and for any
    * composition where the provider might not reach a layer that owns its lifecycle.
    */
  def scoped(
    config: OptimizelyProviderConfig
  ): ZIO[Scope, FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    scoped(config, httpClient = None)

  /** Test seam: [[scoped]] with an injected HTTP client (see `TestHttpClient` in the test sources). */
  private[optimizely] def scoped(
    config: OptimizelyProviderConfig,
    httpClient: Option[com.optimizely.ab.OptimizelyHttpClient]
  ): ZIO[Scope, FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    scoped(config, httpClient, eventHandler = None)

  /** Test seam: [[scoped]] with an injected HTTP client and event handler (see the matching [[make]] overload). */
  private[optimizely] def scoped(
    config: OptimizelyProviderConfig,
    httpClient: Option[com.optimizely.ab.OptimizelyHttpClient],
    eventHandler: Option[EventHandler]
  ): ZIO[Scope, FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    ZIO.acquireRelease(make(config, httpClient, eventHandler))(releaseProvider)

  /** [[scoped]] for the common CDN-only case. */
  def scoped(sdkKey: String): ZIO[Scope, FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    scoped(OptimizelyProviderConfig(sdkKey))

  /** Upper bound on provider teardown. `shutdown()` is `shutdownNow`-based and normally returns in milliseconds; the
    * bound exists so a pathological close (e.g. an HTTP client wedged mid-request) cannot hang scope teardown — the
    * SDK's polling and evictor threads are daemon, so the JVM can still exit either way. It is longer than the OFREP
    * provider's bound: closing the Optimizely client stops its poller and HTTP clients and, with an event processor,
    * flushes buffered events, where OFREP only stops an executor.
    */
  private val ShutdownTimeout: zio.Duration = 10.seconds

  private def releaseProvider(provider: OptimizelyFeatureProvider): UIO[Unit] =
    // `.disconnect` because finalizers run uninterruptibly and the timeout must still fire.
    ZIO.attemptBlocking(provider.shutdown()).disconnect.timeout(ShutdownTimeout).ignore

  /** Escape hatch for callers who already manage an `Optimizely` client lifecycle (e.g. they want a user profile
    * service, an ODP manager, or an event processor tuned beyond what [[OptimizelyEvents]] offers). The returned
    * provider does NOT close the client on shutdown; the caller stays in charge, including of how the client's events
    * are dispatched.
    */
  def fromOptimizelyClient(client: Optimizely): UIO[OptimizelyFeatureProvider] =
    ZIO.succeed(new OptimizelyFeatureProvider(client, DefaultInitWait, closeOnShutdown = false))

  /** ZLayer for the common CDN-only case. The layer owns the provider lifecycle: its finalizer shuts the provider down
    * (stopping datafile polling and the HTTP client) when the layer is released.
    */
  def layer(sdkKey: String): ZLayer[Any, FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    layer(OptimizelyProviderConfig(sdkKey))

  /** ZLayer for a self-hosted datafile URL. Owns the provider lifecycle — see [[layer(String)]]. */
  def layer(
    sdkKey: String,
    datafileUrl: String
  ): ZLayer[Any, FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    layer(OptimizelyProviderConfig(sdkKey, datafileUrl = Some(datafileUrl)))

  /** ZLayer from a full [[OptimizelyProviderConfig]]. Owns the provider lifecycle — see [[layer(String)]]. */
  def layer(
    config: OptimizelyProviderConfig
  ): ZLayer[Any, FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    layer(config, httpClient = None)

  /** Test seam: [[layer]] with an injected HTTP client (see `TestHttpClient` in the test sources). */
  private[optimizely] def layer(
    config: OptimizelyProviderConfig,
    httpClient: Option[com.optimizely.ab.OptimizelyHttpClient]
  ): ZLayer[Any, FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    layer(config, httpClient, eventHandler = None)

  /** Test seam: [[layer]] with an injected HTTP client and event handler (see the matching [[make]] overload). */
  private[optimizely] def layer(
    config: OptimizelyProviderConfig,
    httpClient: Option[com.optimizely.ab.OptimizelyHttpClient],
    eventHandler: Option[EventHandler]
  ): ZLayer[Any, FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    ZLayer.scoped(scoped(config, httpClient, eventHandler))

  // Construction

  private def buildClient(
    sdkKey: String,
    datafileUrl: Option[String],
    pollingInterval: Option[java.time.Duration],
    blockingTimeout: Option[java.time.Duration],
    // Test seam (see the `private[optimizely]` overloads below): inject a custom HTTP client. Production callers
    // never set this, so the SDK builds its default client.
    httpClient: Option[com.optimizely.ab.OptimizelyHttpClient],
    // Present when the staleness watchdog is enabled: the HTTP client is wrapped so every successful datafile fetch
    // advances this observer's timestamp. Wrapping preserves the wrapped client's exact behaviour — for the
    // production path that is the SDK default client, so nothing about how fetches are performed changes.
    fetchObserver: Option[OptimizelyFeatureProvider.FetchObserver],
    events: OptimizelyEvents,
    // Test seam: replaces the handler that sends event batches to Optimizely. Ignored when events are disabled.
    eventHandler: Option[EventHandler]
  ): BuiltClient = {
    // Share a single NotificationCenter between the polling config manager and the Optimizely client. Without this,
    // the manager fires UpdateConfigNotification on its own private NotificationCenter and handlers registered via
    // `Optimizely.addUpdateConfigNotificationHandler` (the public API our provider uses) never see subsequent datafile
    // updates — observed empirically. The initial load still wakes the init latch because we also poll
    // `optimizely.isValid` directly, but the OpenFeature `PROVIDER_CONFIGURATION_CHANGED` event would never fire on
    // any datafile revision after the first one.
    val notificationCenter = new com.optimizely.ab.notification.NotificationCenter()
    val configBuilder = HttpProjectConfigManager.builder().withSdkKey(sdkKey).withNotificationCenter(notificationCenter)
    datafileUrl.foreach(configBuilder.withUrl)
    pollingInterval.foreach(d =>
      configBuilder.withPollingInterval(java.lang.Long.valueOf(d.toMillis), TimeUnit.MILLISECONDS)
    )
    blockingTimeout.foreach(d =>
      configBuilder.withBlockingTimeout(java.lang.Long.valueOf(d.toMillis), TimeUnit.MILLISECONDS)
    )
    // Choose the HTTP client the config manager polls with, wrapping it to observe fetch outcomes when the watchdog
    // is enabled:
    //   - watchdog on, no injected client  -> observe the SDK's default client (production staleness path)
    //   - watchdog on, injected client      -> observe the injected client (test seam still observed)
    //   - watchdog off, injected client     -> use the injected client as-is
    //   - watchdog off, no injected client  -> leave it unset so the SDK builds its own default (unchanged behaviour)
    val effectiveHttpClient: Option[com.optimizely.ab.OptimizelyHttpClient] = fetchObserver match {
      case Some(observer) =>
        val onSuccess: Runnable = () => observer.recordSuccess()
        Some(httpClient match {
          case Some(injected) => com.optimizely.ab.ObservingOptimizelyHttpClient.wrapping(injected, onSuccess)
          case None           => com.optimizely.ab.ObservingOptimizelyHttpClient.wrappingDefault(onSuccess)
        })
      case None => httpClient
    }
    effectiveHttpClient.foreach(configBuilder.withOptimizelyHttpClient)
    // `build()` (no-arg) would block construction up to the SDK's blocking timeout (default 10s) waiting for the
    // first datafile — with an unreachable CDN, construction stalls. `build(true)` skips that blocking wait, but
    // `HttpProjectConfigManager.Builder.build(boolean)` calls `start()` unconditionally either way (there's no
    // public SDK API to construct without auto-starting), so the manager's first poll is already scheduled by the
    // time this returns.
    //
    // We deliberately do NOT call `configManager.stop()` here to "undo" that: cancelling the scheduled fetch uses
    // `cancel(mayInterrupt = true)`, and interrupting a thread blocked mid-socket-I/O on JDK 13+'s NioSocketImpl
    // force-closes the channel — which can corrupt the shared `OptimizelyHttpClient`'s connection pool for the
    // manager's entire remaining lifetime if the cancel lands while that first fetch is actually in flight (more
    // likely the farther the datafile host is, e.g. behind a proxy). Letting the construction-time fetch run to
    // completion is safe: `initialize()` registers the update-notification handler before calling
    // `configManager.start()` itself, and `start()` is idempotent (a no-op if already started), so the first fetch
    // either completes before `initialize()` runs — caught by `initialize()`'s `optimizely.isValid` fallback check
    // — or is still in flight and finishes normally afterward.
    val configManager = configBuilder.build(true)
    // CMAB experiments decide through a CmabService (SDK 4.6). Without one the SDK serves a placeholder "$" variation
    // and logs a WARN on every build. Wired like `OptimizelyFactory.newDefaultInstance`, except that this provider owns
    // the CMAB HTTP client so it can close it: `Optimizely.close()` does not.
    val cmabHttpClient = com.optimizely.ab.OptimizelyHttpClient.builder().build()
    val cmabService    = com.optimizely.ab.cmab.service.DefaultCmabService
      .builder()
      .withClient(new com.optimizely.ab.cmab.DefaultCmabClient(cmabHttpClient))
      .build()
    val builder =
      Optimizely
        .builder()
        .withConfigManager(configManager)
        .withNotificationCenter(notificationCenter)
        .withCmabService(cmabService)
    // Without an event processor the SDK falls back to a no-op handler and drops every impression and conversion.
    // `Batched` wires what `OptimizelyFactory.newDefaultInstance` does, a batch processor in front of an async
    // handler, without the factory itself: it also starts an ODP manager and reads global `PropertyUtils` state.
    val eventExecutor = events match {
      case OptimizelyEvents.Disabled         => None
      case batched: OptimizelyEvents.Batched =>
        val closeTimeoutMs = math.max(1L, batched.closeTimeout.toMillis)
        // The handler shares the CMAB HTTP client, so the provider owns a single HTTP client.
        // `AsyncEventHandler.close()` closes it too; the provider's own close afterwards is then a no-op, and still
        // covers an injected handler. The handler's close timeout defaults to forever, so it must be set to keep
        // shutdown bounded.
        val handler = eventHandler.getOrElse(
          AsyncEventHandler
            .builder()
            .withCloseTimeout(closeTimeoutMs, TimeUnit.MILLISECONDS)
            .withOptimizelyHttpClient(cmabHttpClient)
            .build()
        )
        // An executor the provider owns, so it can shut it down: `BatchEventProcessor.close()` does not (see make).
        val executor  = Executors.newSingleThreadExecutor(EventProcessorThreadFactory)
        val processor = BatchEventProcessor
          .builder()
          .withEventHandler(handler)
          .withNotificationCenter(notificationCenter)
          .withBatchSize(Integer.valueOf(batched.batchSize))
          .withFlushInterval(java.lang.Long.valueOf(math.max(1L, batched.flushInterval.toMillis)))
          .withTimeout(closeTimeoutMs, TimeUnit.MILLISECONDS)
          .withExecutor(executor)
          .build()
        builder.withEventProcessor(processor): Unit
        Some(executor)
    }
    BuiltClient(builder.build(), configManager, cmabHttpClient, eventExecutor)
  }

  final private case class BuiltClient(
    optimizely: Optimizely,
    configManager: HttpProjectConfigManager,
    cmabHttpClient: com.optimizely.ab.OptimizelyHttpClient,
    // The event processor's executor, present when events are batched; shut down by the provider (see make).
    eventExecutor: Option[ExecutorService]
  )

  private val EventProcessorThreadFactory: ThreadFactory = (r: Runnable) => {
    val t = new Thread(r, "optimizely-event-processor")
    t.setDaemon(true)
    t
  }

  // Validation

  private val SdkKeyPattern: java.util.regex.Pattern = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]+$")

  // Optimizely SDK keys are short (~10–20 chars in practice); we bound the range generously so test/staging keys with
  // custom formats are still accepted.
  private val MinSdkKeyLen = 6
  private val MaxSdkKeyLen = 128

  private val Placeholders: Set[String] = Set(
    "your_sdk_key",
    "your-sdk-key",
    "yoursdkkey",
    "<sdk-key>",
    "<sdkkey>",
    "sdk_key",
    "sdkkey",
    "changeme",
    "placeholder"
  )

  private def validateSdkKey(raw: String): IO[FeatureFlagError.InvalidConfiguration, String] = {
    def fail(reason: String) = ZIO.fail(FeatureFlagError.InvalidConfiguration(reason))
    if (raw == null) fail("Optimizely sdkKey is null")
    else {
      val trimmed = raw.trim
      val lower   = trimmed.toLowerCase
      if (trimmed.isEmpty) fail("Optimizely sdkKey is empty")
      else if (trimmed != raw) fail(s"Optimizely sdkKey has surrounding whitespace: '$raw'")
      else if (raw.exists(_.isWhitespace)) fail(s"Optimizely sdkKey contains whitespace: '$raw'")
      else if (trimmed.length < MinSdkKeyLen)
        fail(s"Optimizely sdkKey too short (${trimmed.length} < $MinSdkKeyLen)")
      else if (trimmed.length > MaxSdkKeyLen)
        fail(s"Optimizely sdkKey too long (${trimmed.length} > $MaxSdkKeyLen)")
      else if (!SdkKeyPattern.matcher(trimmed).matches())
        fail(s"Optimizely sdkKey contains disallowed characters: '$raw' (expected [A-Za-z0-9_-])")
      else if (Placeholders.contains(lower))
        fail(s"Optimizely sdkKey looks like a placeholder: '$raw'")
      else ZIO.succeed(trimmed)
    }
  }

  private def validatePositive(
    name: String,
    value: Option[java.time.Duration]
  ): IO[FeatureFlagError.InvalidConfiguration, Unit] =
    ZIO.foreachDiscard(value.toList) { d =>
      ZIO
        .fail(FeatureFlagError.InvalidConfiguration(s"Optimizely $name must be positive: $d"))
        .when(d.isNegative || d.isZero)
    }

  private[optimizely] def validateEvents(events: OptimizelyEvents): IO[FeatureFlagError.InvalidConfiguration, Unit] = {
    def fail(reason: String) = ZIO.fail(FeatureFlagError.InvalidConfiguration(reason))
    events match {
      case null                                                             => fail("Optimizely events is null")
      case OptimizelyEvents.Disabled                                        => ZIO.unit
      case OptimizelyEvents.Batched(batchSize, flushInterval, closeTimeout) =>
        if (batchSize < 1) fail(s"Optimizely events.batchSize must be at least 1: $batchSize")
        else if (flushInterval == null) fail("Optimizely events.flushInterval is null")
        else if (closeTimeout == null) fail("Optimizely events.closeTimeout is null")
        else if (closeTimeout.compareTo(OptimizelyEvents.MaxCloseTimeout) > 0)
          fail(
            s"Optimizely events.closeTimeout must be at most ${OptimizelyEvents.MaxCloseTimeout}: $closeTimeout " +
              "(it is waited out twice on shutdown, within the provider's 10-second bound)"
          )
        else
          validatePositive("events.flushInterval", Some(flushInterval)) *>
            validatePositive("events.closeTimeout", Some(closeTimeout))
    }
  }

  private val AllowedDatafileSchemes = Set("http", "https")

  private def validateDatafileUrl(raw: String): IO[FeatureFlagError.InvalidConfiguration, String] = {
    def fail(reason: String) = ZIO.fail(FeatureFlagError.InvalidConfiguration(reason))
    if (raw == null) fail("Optimizely datafileUrl is null")
    else {
      val trimmed = raw.trim
      if (trimmed.isEmpty) fail("Optimizely datafileUrl is empty")
      else
        ZIO
          .attempt(java.net.URI.create(trimmed))
          .mapError(t => FeatureFlagError.InvalidConfiguration(s"malformed datafileUrl '$trimmed': ${t.getMessage}"))
          .flatMap { uri =>
            val scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("")
            val host   = Option(uri.getHost).getOrElse("")
            if (!AllowedDatafileSchemes.contains(scheme))
              fail(s"unsupported scheme '$scheme' in datafileUrl '$trimmed' (expected http or https)")
            else if (host.isEmpty) fail(s"datafileUrl '$trimmed' has no host")
            else ZIO.succeed(trimmed)
          }
    }
  }
}
