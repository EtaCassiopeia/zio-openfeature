package zio.openfeature.optimizely

import com.optimizely.ab.Optimizely
import com.optimizely.ab.config.HttpProjectConfigManager
import zio._
import zio.openfeature.FeatureFlagError
import java.util.concurrent.TimeUnit

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
  *     env     <- FeatureFlags.fromProviderAsync(wrapped, 500.millis).build
  *     ff       = env.get[FeatureFlags]
  *     enabled <- ff.boolean("flag", default = false)
  *   } yield enabled
  * }
  * }}}
  *
  * '''Failure semantics on bad credentials / unreachable CDN:'''
  *   - If the Optimizely datafile fetch fails (auth, network, 5xx), the underlying client stays `!isValid` and
  *     [[OptimizelyFeatureProvider.initialize]] throws after its `initWait` elapses. The outer
  *     `FeatureFlags.fromProvider*(initTimeout = …)` translates that into either a layer build failure (sync mode) or a
  *     `ProviderStatus.Fatal` transition (async mode), so an evaluation never silently returns the default value under
  *     a misconfigured provider.
  *   - Construction itself (this object's `make`) does NOT make network calls — it only validates inputs and builds the
  *     Optimizely client object. The actual HTTP fetch happens inside `initialize()`.
  *
  * Construction validates the SDK key (and URL, where applicable) before touching the Optimizely SDK; failures surface
  * as `FeatureFlagError.InvalidConfiguration` at layer build time, not at first evaluation.
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
  */
final case class OptimizelyProviderConfig(
  sdkKey: String,
  datafileUrl: Option[String] = None,
  initWait: java.time.Duration = OptimizelyProvider.DefaultInitWait,
  pollingInterval: Option[java.time.Duration] = None,
  blockingTimeout: Option[java.time.Duration] = None
)

object OptimizelyProvider {

  /** Default time the underlying `OptimizelyFeatureProvider.initialize()` will wait for the first datafile load before
    * declaring the provider failed. The outer `FeatureFlags` factories layer their own `initTimeout` on top; this inner
    * bound is a defence-in-depth so a misconfigured client doesn't hang the build pool indefinitely.
    */
  val DefaultInitWait: java.time.Duration = java.time.Duration.ofSeconds(30)

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
    * plus the outer `FeatureFlags.fromProvider*` `initTimeout`.
    */
  def make(
    sdkKey: String,
    datafileUrl: Option[String],
    initWait: java.time.Duration
  ): IO[FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    make(OptimizelyProviderConfig(sdkKey, datafileUrl, initWait))

  /** Validate the configuration and construct a provider.
    *
    * Construction performs no network activity and starts no background polling: the datafile poller is created paused
    * and only starts inside `initialize()`. The returned provider owns a polling executor and an HTTP client — the
    * caller is responsible for `shutdown()` (registering it with a `FeatureFlags` layer counts: the layer's finalizer
    * shuts the provider down). Prefer [[scoped]] or [[layer]] to make that ownership explicit.
    */
  def make(config: OptimizelyProviderConfig): IO[FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    make(config, httpClient = None)

  /** Test seam: [[make]] with an injected HTTP client (see `TestHttpClient` in the test sources). */
  private[optimizely] def make(
    config: OptimizelyProviderConfig,
    httpClient: Option[com.optimizely.ab.OptimizelyHttpClient]
  ): IO[FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    for {
      validKey <- validateSdkKey(config.sdkKey)
      validUrl <- config.datafileUrl match {
        case Some(u) => validateDatafileUrl(u).map(Some(_))
        case None    => ZIO.succeed(None: Option[String])
      }
      _ <- validatePositive("pollingInterval", config.pollingInterval)
      _ <- validatePositive("blockingTimeout", config.blockingTimeout)
      pair <- ZIO
        .attempt(buildClient(validKey, validUrl, config.pollingInterval, config.blockingTimeout, httpClient))
        .mapError(t => FeatureFlagError.InvalidConfiguration(s"Optimizely client build failed: ${t.getMessage}"))
    } yield new OptimizelyFeatureProvider(
      pair._1,
      config.initWait,
      closeOnShutdown = true,
      configManager = Some(pair._2)
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
    ZIO.acquireRelease(make(config, httpClient))(releaseProvider)

  /** [[scoped]] for the common CDN-only case. */
  def scoped(sdkKey: String): ZIO[Scope, FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    scoped(OptimizelyProviderConfig(sdkKey))

  /** Upper bound on provider teardown. `shutdown()` is `shutdownNow`-based and normally returns in milliseconds; the
    * bound exists so a pathological close (e.g. an HTTP client wedged mid-request) cannot hang scope teardown — the
    * SDK's polling and evictor threads are daemon, so the JVM can still exit either way.
    */
  private val ShutdownTimeout: zio.Duration = 10.seconds

  private def releaseProvider(provider: OptimizelyFeatureProvider): UIO[Unit] =
    // `.disconnect` because finalizers run uninterruptibly and the timeout must still fire.
    ZIO.attemptBlocking(provider.shutdown()).disconnect.timeout(ShutdownTimeout).ignore

  /** Escape hatch for callers who already manage an `Optimizely` client lifecycle (e.g. they want their own polling
    * interval, event handler, or user profile service). The returned provider does NOT close the client on shutdown;
    * the caller stays in charge.
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
    ZLayer.scoped(scoped(config, httpClient))

  // Construction

  private def buildClient(
    sdkKey: String,
    datafileUrl: Option[String],
    pollingInterval: Option[java.time.Duration],
    blockingTimeout: Option[java.time.Duration],
    // Test seam (see the `private[optimizely]` overloads below): inject a custom HTTP client. Production callers
    // never set this, so the SDK builds its default client.
    httpClient: Option[com.optimizely.ab.OptimizelyHttpClient]
  ): (Optimizely, HttpProjectConfigManager) = {
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
    httpClient.foreach(configBuilder.withOptimizelyHttpClient)
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
    // — or is still in flight and finishes normally afterward. See #237.
    val configManager = configBuilder.build(true)
    val optimizely =
      Optimizely.builder().withConfigManager(configManager).withNotificationCenter(notificationCenter).build()
    (optimizely, configManager)
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
