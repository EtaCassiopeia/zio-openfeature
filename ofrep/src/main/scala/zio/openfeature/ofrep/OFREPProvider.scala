package zio.openfeature.ofrep

import dev.openfeature.contrib.providers.ofrep.{OfrepProvider, OfrepProviderOptions}
import zio._
import zio.openfeature.FeatureFlagError

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, ExecutorService, ThreadFactory}

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

  /** ZLayer convenience that wraps [[make(String)]]. Use as `OFREPProvider.layer("https://flags.example.com")` to wire
    * an OFREP provider into a ZIO application; failures arrive as typed `FeatureFlagError.InvalidConfiguration` at
    * layer build time.
    */
  def layer(baseUrl: String): ZLayer[Any, FeatureFlagError.InvalidConfiguration, OfrepProvider] =
    ZLayer.fromZIO(make(baseUrl))

  /** ZLayer convenience that wraps [[make(OfrepProviderOptions)]]. */
  def layer(options: OfrepProviderOptions): ZLayer[Any, FeatureFlagError.InvalidConfiguration, OfrepProvider] =
    ZLayer.fromZIO(make(options))

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
