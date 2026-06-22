package zio.openfeature.conformance.bdd.matrix

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import zio._
import zio.openfeature._
import zio.openfeature.optimizely.{OptimizelyProvider, OptimizelyProviderConfig}

/** Builds per-scenario `FeatureFlags` layers that all fetch the *same* datafile from the *same* simulated CDN endpoint,
  * concurrently, via a shared mitmproxy + WireMock pair started once for the whole suite.
  *
  * This is a different isolation shape than [[MatrixHarness]]: there, every scenario gets its own WireMock server
  * (different port, often a different datafile) — providers never actually contend for the same backing service. Here,
  * every scenario's `OptimizelyProvider` is configured with the identical CDN-shaped `datafileUrl`
  * (`http://$MatchHost$DatafilePath`); what makes concurrent scenarios resolve different flag values isn't a different
  * datafile, it's a different evaluation context (set as that scenario's global context from its `@flags(...)` tag).
  *
  * The CDN hop itself is simulated rather than stubbed directly: `OptimizelyProvider`'s HTTP client is configured (via
  * standard `http.proxyHost`/`http.proxyPort` JVM properties, which the Optimizely SDK's default Apache HttpClient
  * honors automatically) to route through a forward proxy — mitmproxy, run via Testcontainers — which detects the
  * request's Host header and redirects it to the shared WireMock instance. So every scenario's provider really does
  * "call the CDN"; mitmproxy is what's standing in for Optimizely's real edge network.
  *
  * Per-scenario isolation is still provider-level, not infra-level: each call to [[layerForSharedCdn]] builds a fresh
  * `OptimizelyProvider` registered under its own freshly-generated domain (see [[MatrixHarness]]'s doc for why a named
  * domain is required for `scenarioParallelism > 1` to be safe) — only the WireMock server and the mitmproxy container
  * are shared.
  */
object ProxyMatrixHarness {

  private val SdkKey       = "test-proxy-matrix-key"
  private val MatchHost    = "cdn.optimizely.invalid"
  private val DatafilePath = s"/datafiles/$SdkKey.json"
  private val Datafile     = loadDatafile("audience-segments")

  private def loadDatafile(name: String): String = {
    val path = s"/datafiles/$name.json"
    val is   = getClass.getResourceAsStream(path)
    require(is != null, s"Datafile fixture not found on classpath: $path")
    scala.io.Source.fromInputStream(is).mkString
  }

  // testcontainers' GenericContainer is F-bounded (`GenericContainer[SELF <: GenericContainer[SELF]]`);
  // subclassing with the self type keeps its fluent `withX` builder methods chainable from Scala.
  final private class MitmProxyContainer(image: String) extends GenericContainer[MitmProxyContainer](image)

  /** Starts the shared WireMock server + mitmproxy container on first access and sets the JVM-wide
    * `http.proxyHost`/`http.proxyPort` properties so every `OptimizelyProvider` built afterward in this JVM routes
    * through it. `lazy val` makes this both once-only and safe under concurrent scenarios (the JVM spec guarantees at
    * most one thread runs the initializer). Deliberately never torn down explicitly — Testcontainers' own Ryuk reaper
    * removes the container, and the WireMock server is a plain daemon-backed Jetty instance, when the test JVM exits.
    */
  private lazy val sharedInfra: Unit = {
    // zio-bdd's test framework runs scenario fibers on its own thread pool, whose threads' context
    // classloader doesn't have visibility into this jar's META-INF/services entries the way sbt's
    // own test-running thread does — testcontainers' `DockerClientProviderStrategy` lookup is a plain
    // `ServiceLoader.load(...)` against the *current thread's* context classloader, so without this it
    // silently finds zero strategies and fails with "Could not find a valid Docker environment."
    Thread.currentThread().setContextClassLoader(getClass.getClassLoader)

    val wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort())
    wireMock.start()
    wireMock.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(Datafile)))

    Testcontainers.exposeHostPorts(wireMock.port())

    val proxy = new MitmProxyContainer("mitmproxy/mitmproxy:11.1.3")
      .withExposedPorts(8080)
      .withCopyFileToContainer(
        MountableFile.forClasspathResource("mitmproxy/redirect_to_wiremock.py"),
        "/addons/redirect_to_wiremock.py"
      )
      .withEnv("MATCH_HOST", MatchHost)
      .withEnv("TARGET_HOST", "host.testcontainers.internal")
      .withEnv("TARGET_PORT", wireMock.port().toString)
      .withCommand("mitmdump", "--listen-port", "8080", "-s", "/addons/redirect_to_wiremock.py")
      .waitingFor(Wait.forListeningPort())
    proxy.start()

    java.lang.System.setProperty("http.proxyHost", proxy.getHost)
    java.lang.System.setProperty("http.proxyPort", proxy.getMappedPort(8080).toString)
    java.lang.System.setProperty("http.nonProxyHosts", "")
  }

  /** A scoped `FeatureFlags` layer backed by a fresh provider pointed at the shared simulated CDN.
    *
    * `contextAttributes` becomes that scenario's *global* evaluation context — `setGlobalContext` replaces rather than
    * merges, so every attribute from the tag is folded into one context and set in a single call, rather than one call
    * per key (which would have each call silently discard the previous attribute).
    */
  def layerForSharedCdn(contextAttributes: Map[String, String]): ZLayer[Any, Throwable, FeatureFlags] =
    ZLayer.scoped {
      for {
        // `sharedInfra` is a JVM `lazy val`, which compiles to a `synchronized` block: the first fiber to
        // force it blocks its underlying OS thread for as long as the container takes to start. Under
        // `scenarioParallelism > 1`, every scenario's fiber forces it near-simultaneously — `attemptBlocking`
        // routes that onto ZIO's dedicated blocking thread pool so it can't starve the (much smaller) compute
        // pool the rest of the runtime depends on.
        _ <- ZIO.attemptBlocking(sharedInfra)
        config = OptimizelyProviderConfig(
          sdkKey = SdkKey,
          datafileUrl = Some(s"http://$MatchHost$DatafilePath"),
          initWait = java.time.Duration.ofSeconds(10),
          pollingInterval = Some(java.time.Duration.ofSeconds(3600)), // no polling in tests
          blockingTimeout = Some(java.time.Duration.ofSeconds(5))
        )
        provider <- OptimizelyProvider.scoped(config).mapError(e => RuntimeException(e.message))
        domain = s"proxy-flag-matrix-${java.util.UUID.randomUUID()}"
        env <- FeatureFlags.fromProviderWithDomain(provider, domain).build
        ff = env.get[FeatureFlags]
        ctx = contextAttributes.foldLeft(EvaluationContext.empty) { case (acc, (k, v)) =>
          acc.withAttribute(k, AttributeValue.StringValue(v))
        }
        _ <- ff.setGlobalContext(ctx)
      } yield ff
    }
}
