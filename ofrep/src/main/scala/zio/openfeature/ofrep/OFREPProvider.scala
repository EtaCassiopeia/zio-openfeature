package zio.openfeature.ofrep

import com.google.common.collect.{ImmutableList, ImmutableMap}
import dev.openfeature.contrib.providers.ofrep.{OfrepProvider, OfrepProviderOptions}
import zio._
import zio.openfeature.FeatureFlagError

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, ExecutorService, ThreadFactory}
import scala.jdk.CollectionConverters._

/** Scala-friendly configuration for an OFREP provider, mapped internally to the contrib provider's
  * `OfrepProviderOptions`. Prefer this over hand-building `OfrepProviderOptions`: it exposes only the safe knobs, takes
  * plain Scala types (no Guava `ImmutableMap`/`ImmutableList`), and — critically — the factories that consume it always
  * wire in a daemon executor via [[OFREPProvider.daemonOptionsBuilder]] and validate before building, so the contrib
  * default non-daemon 5-thread pool (a JVM-exit-blocking footgun, see issue #229) is never spawned by this path.
  *
  * @param baseUrl
  *   OFREP endpoint base URL (validated — see
  *   [[OFREPProvider.make(config:zio\.openfeature\.ofrep\.OFREPProviderConfig)*]])
  * @param requestTimeout
  *   Per-request timeout; `None` uses the contrib default
  * @param connectTimeout
  *   Connection-establishment timeout; `None` uses the contrib default
  * @param headers
  *   Static headers sent on every OFREP request (e.g. `"Authorization" -> List("Bearer …")`); empty means none
  * @param proxy
  *   Optional `java.net.ProxySelector` routing OFREP HTTP traffic through a proxy; `None` uses the JVM default
  */
final case class OFREPProviderConfig(
  baseUrl: String,
  requestTimeout: Option[java.time.Duration] = None,
  connectTimeout: Option[java.time.Duration] = None,
  headers: Map[String, List[String]] = Map.empty,
  proxy: Option[java.net.ProxySelector] = None
)

/** Scala-friendly factories for the OpenFeature Java SDK's OFREP contrib provider
  * ([[dev.openfeature.contrib.providers.ofrep.OfrepProvider]]).
  *
  * OFREP (OpenFeature Remote Evaluation Protocol) is the standard HTTP protocol for vendor-neutral remote flag
  * evaluation. The factories here sugar over the contrib provider's static constructors; the returned value is a plain
  * `OfrepProvider` (a `FeatureProvider`) that you pass to `FeatureFlags.fromProvider` or
  * `FeatureFlags.fromProviderAsync` like any other provider.
  *
  * '''Recommended''': use [[make]] / [[layer]] for validated construction. The legacy throwing factories (`apply`,
  * `fromOptions`) are kept for backwards compatibility but flagged deprecated — they accept any `baseUrl` string and
  * surface configuration mistakes only at the first evaluation, as opaque `ProviderError(MalformedURLException)`.
  *
  * '''Experimental:''' the underlying contrib provider artifact is at version 0.0.1. The OFREP protocol itself is
  * pre-1.0; both the wire format and this Scala facade may evolve in breaking ways. Pin the dependency deliberately.
  *
  * @see
  *   https://github.com/open-feature/protocol for the OFREP spec
  * @see
  *   https://github.com/open-feature/java-sdk-contrib/tree/main/providers/ofrep for the underlying implementation
  */
object OFREPProvider {

  // The ofrep contrib 0.0.1 Executor default (`OfrepProviderOptions.$default$executor()` = `Executors
  // .newFixedThreadPool(5)`) uses the JDK default ThreadFactory, which produces NON-daemon threads (named
  // `pool-N-thread-K`). Non-daemon threads block JVM exit, and `OfrepProviderOptions.builder().build()` spawns that
  // pool eagerly — so any path that builds options but never constructs/shuts down a provider (e.g. a validation
  // rejection) orphans the pool and can hang `sbt +test`. We always supply our own daemon executor so the contrib
  // default is never instantiated by code that goes through this wrapper. See issue #229.
  private val counter = new AtomicInteger(0)
  private val daemonThreadFactory: ThreadFactory = (r: Runnable) => {
    val t = new Thread(r, s"zio-openfeature-ofrep-${counter.incrementAndGet()}")
    t.setDaemon(true)
    t
  }
  private def newDaemonExecutor(): ExecutorService =
    Executors.newCachedThreadPool(daemonThreadFactory)

  /** A fresh `OfrepProviderOptions.Builder` pre-configured with a daemon `ExecutorService`.
    *
    * Use this instead of `OfrepProviderOptions.builder()` when configuring options (custom timeouts, proxy, auth
    * headers, etc.) outside the standard [[make]] / [[layer]] factories. The contrib provider 0.0.1 default executor is
    * `Executors.newFixedThreadPool(5)` with the JDK default `ThreadFactory` (non-daemon threads), which blocks JVM exit
    * if the pool is never shut down. This helper wires in a daemon-thread pool up front so that can't happen. Pass the
    * resulting `OfrepProviderOptions` to
    * [[make(options:dev\.openfeature\.contrib\.providers\.ofrep\.OfrepProviderOptions)*]].
    */
  def daemonOptionsBuilder(): OfrepProviderOptions.Builder =
    OfrepProviderOptions.builder().executor(newDaemonExecutor())

  /** Validate a base URL and construct an OFREP provider. The validation rules are:
    *   - non-empty after trim
    *   - parses as a `java.net.URI`
    *   - scheme is `http` or `https` (case-insensitive)
    *   - non-empty host
    *
    * Any failure surfaces as `FeatureFlagError.InvalidConfiguration` so layer composition can fail at startup rather
    * than at the first evaluation.
    */
  def make(baseUrl: String): IO[FeatureFlagError.InvalidConfiguration, OfrepProvider] =
    validateBaseUrl(baseUrl).flatMap { validated =>
      ZIO
        .attempt(
          OfrepProvider.constructProvider(daemonOptionsBuilder().baseUrl(validated).build())
        )
        .mapError(t => FeatureFlagError.InvalidConfiguration(s"OFREP provider construction failed: ${t.getMessage}"))
    }

  /** Validate a fully-configured [[OfrepProviderOptions]] (auth headers, timeouts, executor, etc.) and construct the
    * provider. Validates the options' `baseUrl` and rejects obvious misconfiguration up front.
    *
    * If you build the options yourself, prefer [[daemonOptionsBuilder]] over `OfrepProviderOptions.builder()` so the
    * contrib provider's default non-daemon 5-thread pool is never instantiated — a pool whose owning provider is never
    * shut down (including this method's own validation-rejection path) blocks JVM exit. This method must not mutate
    * caller-supplied options, so the executor choice is the caller's responsibility.
    */
  def make(options: OfrepProviderOptions): IO[FeatureFlagError.InvalidConfiguration, OfrepProvider] =
    for {
      _ <- ZIO
        .attempt(options.getBaseUrl)
        .mapError(t =>
          FeatureFlagError.InvalidConfiguration(s"could not read baseUrl from OfrepProviderOptions: ${t.getMessage}")
        )
        .flatMap {
          case null  => ZIO.fail(FeatureFlagError.InvalidConfiguration("OfrepProviderOptions.baseUrl is null"))
          case other => validateBaseUrl(other).unit
        }
      provider <- ZIO
        .attempt(OfrepProvider.constructProvider(options))
        .mapError(t => FeatureFlagError.InvalidConfiguration(s"OFREP provider construction failed: ${t.getMessage}"))
    } yield provider

  /** Validate an [[OFREPProviderConfig]] and construct a provider. The `baseUrl` is validated FIRST — before any
    * executor or options are built — so an invalid configuration fails without ever spawning a thread pool. Only on
    * success are the options assembled internally, starting from [[daemonOptionsBuilder]] (daemon executor) and
    * applying the configured request/connect timeouts, headers, and proxy.
    *
    * This is the ergonomic path for configuring timeouts/headers/proxy: it takes plain Scala types and keeps the
    * daemon-executor and validate-before-pool guarantees, so callers never need to touch the raw Guava builder.
    */
  def make(config: OFREPProviderConfig): IO[FeatureFlagError.InvalidConfiguration, OfrepProvider] =
    validateBaseUrl(config.baseUrl).flatMap { validated =>
      ZIO
        .attempt(OfrepProvider.constructProvider(buildOptions(config, validated)))
        .mapError(t => FeatureFlagError.InvalidConfiguration(s"OFREP provider construction failed: ${t.getMessage}"))
    }

  /** Upper bound on provider teardown. `OfrepProvider.shutdown()` terminates the options executor and closes the
    * HttpClient; the bound exists so a pathological close cannot hang scope teardown. When the executor is the daemon
    * pool this wrapper installs, the JVM can exit regardless — but a caller-supplied non-daemon executor (via
    * [[make(options:dev\.openfeature\.contrib\.providers\.ofrep\.OfrepProviderOptions)*]]) is only released by this
    * shutdown, so the finalizer matters.
    */
  private val ShutdownTimeout: zio.Duration = 5.seconds

  private def releaseOfrep(provider: OfrepProvider): UIO[Unit] =
    // `.disconnect` because finalizers run uninterruptibly and the timeout must still fire.
    ZIO.attemptBlocking(provider.shutdown()).disconnect.timeout(ShutdownTimeout).ignore

  /** [[make(String)]] with scope-managed shutdown: when the surrounding `Scope` closes, the provider is shut down
    * (bounded), terminating its executor and closing the HttpClient — even if it was never registered with a
    * `FeatureFlags` layer.
    */
  def scoped(baseUrl: String): ZIO[Scope, FeatureFlagError.InvalidConfiguration, OfrepProvider] =
    ZIO.acquireRelease(make(baseUrl))(releaseOfrep)

  /** [[make(OfrepProviderOptions)]] with scope-managed shutdown — see [[scoped(baseUrl:String)*]]. Especially important
    * here: caller-supplied options may hold a non-daemon executor, and this finalizer is what releases it.
    */
  def scoped(options: OfrepProviderOptions): ZIO[Scope, FeatureFlagError.InvalidConfiguration, OfrepProvider] =
    ZIO.acquireRelease(make(options))(releaseOfrep)

  /** [[make(config:zio\.openfeature\.ofrep\.OFREPProviderConfig)*]] with scope-managed shutdown — see
    * [[scoped(baseUrl:String)*]].
    */
  def scoped(config: OFREPProviderConfig): ZIO[Scope, FeatureFlagError.InvalidConfiguration, OfrepProvider] =
    ZIO.acquireRelease(make(config))(releaseOfrep)

  /** ZLayer convenience that wraps [[make(String)]]. Use as `OFREPProvider.layer("https://flags.example.com")` to wire
    * an OFREP provider into a ZIO application; failures arrive as typed `FeatureFlagError.InvalidConfiguration` at
    * layer build time. The layer owns the provider lifecycle: its finalizer shuts the provider down when the layer's
    * scope closes.
    */
  def layer(baseUrl: String): ZLayer[Any, FeatureFlagError.InvalidConfiguration, OfrepProvider] =
    ZLayer.scoped(scoped(baseUrl))

  /** ZLayer convenience that wraps [[make(OfrepProviderOptions)]]. Owns the provider lifecycle — see
    * [[layer(baseUrl:String)*]].
    */
  def layer(options: OfrepProviderOptions): ZLayer[Any, FeatureFlagError.InvalidConfiguration, OfrepProvider] =
    ZLayer.scoped(scoped(options))

  /** ZLayer from an [[OFREPProviderConfig]]. Owns the provider lifecycle — see [[layer(baseUrl:String)*]]. */
  def layer(config: OFREPProviderConfig): ZLayer[Any, FeatureFlagError.InvalidConfiguration, OfrepProvider] =
    ZLayer.scoped(scoped(config))

  /** Create an OFREP provider with the contrib provider's built-in default options (baseUrl defaults to whatever the
    * contrib library declares — currently `http://localhost:8016`).
    *
    * @deprecated
    *   Use `OFREPProvider.make(...)` or `OFREPProvider.layer(...)` for validated construction.
    */
  @deprecated("Use OFREPProvider.make(...) or OFREPProvider.layer(...) for validated construction", "0.2.0")
  def apply(): OfrepProvider =
    OfrepProvider.constructProvider(daemonOptionsBuilder().build())

  /** Create an OFREP provider pointed at a specific endpoint, otherwise using contrib defaults.
    *
    * @deprecated
    *   Use `OFREPProvider.make(baseUrl)` or `OFREPProvider.layer(baseUrl)` for validated construction.
    */
  @deprecated("Use OFREPProvider.make(baseUrl) or OFREPProvider.layer(baseUrl) for validated construction", "0.2.0")
  def apply(baseUrl: String): OfrepProvider =
    OfrepProvider.constructProvider(daemonOptionsBuilder().baseUrl(baseUrl).build())

  /** Create an OFREP provider with a fully configured [[OfrepProviderOptions]].
    *
    * @deprecated
    *   Use `OFREPProvider.make(options)` or `OFREPProvider.layer(options)` for validated construction.
    */
  @deprecated("Use OFREPProvider.make(options) or OFREPProvider.layer(options) for validated construction", "0.2.0")
  def fromOptions(options: OfrepProviderOptions): OfrepProvider =
    OfrepProvider.constructProvider(options)

  // Options assembly

  /** Assemble `OfrepProviderOptions` from a validated config. Always starts from [[daemonOptionsBuilder]] so the daemon
    * executor is installed; the caller must have validated `validatedBaseUrl` already.
    */
  private def buildOptions(config: OFREPProviderConfig, validatedBaseUrl: String): OfrepProviderOptions = {
    // The Guava-style builder returns itself from each setter, so this fold threads a single builder instance.
    val base       = daemonOptionsBuilder().baseUrl(validatedBaseUrl)
    val withReq    = config.requestTimeout.fold(base)(base.requestTimeout)
    val withConn   = config.connectTimeout.fold(withReq)(withReq.connectTimeout)
    val withProxy  = config.proxy.fold(withConn)(withConn.proxySelector)
    val configured = if (config.headers.isEmpty) withProxy else withProxy.headers(toGuavaHeaders(config.headers))
    configured.build()
  }

  private def toGuavaHeaders(headers: Map[String, List[String]]): ImmutableMap[String, ImmutableList[String]] = {
    val builder = ImmutableMap.builder[String, ImmutableList[String]]()
    headers.foreach { case (name, values) => builder.put(name, ImmutableList.copyOf(values.asJava)) }
    builder.build()
  }

  // Validation

  private val AllowedSchemes = Set("http", "https")

  private def validateBaseUrl(raw: String): IO[FeatureFlagError.InvalidConfiguration, String] = {
    val invalid: String => FeatureFlagError.InvalidConfiguration =
      reason => FeatureFlagError.InvalidConfiguration(reason)
    if (raw == null) ZIO.fail(invalid("baseUrl is null"))
    else {
      val trimmed = raw.trim
      if (trimmed.isEmpty) ZIO.fail(invalid("baseUrl is empty"))
      else
        ZIO
          .attempt(java.net.URI.create(trimmed))
          .mapError(t => invalid(s"malformed baseUrl '$trimmed': ${t.getMessage}"))
          .flatMap { uri =>
            val scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("")
            val host   = Option(uri.getHost).getOrElse("")
            if (!AllowedSchemes.contains(scheme))
              ZIO.fail(invalid(s"unsupported scheme '$scheme' in baseUrl '$trimmed' (expected http or https)"))
            else if (host.isEmpty)
              ZIO.fail(invalid(s"baseUrl '$trimmed' has no host"))
            else ZIO.succeed(trimmed)
          }
    }
  }
}
