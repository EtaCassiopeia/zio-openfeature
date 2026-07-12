package zio.openfeature.conformance.bdd.matrix

import zio._
import zio.bdd.mock._
import zio.bdd.mock.dsl._
import zio.openfeature._
import zio.openfeature.optimizely.{OptimizelyProvider, OptimizelyProviderConfig}

/** Builds per-scenario `FeatureFlags` layers that all fetch the *same* datafile from the *same*
  * simulated CDN endpoint, concurrently, via a single Rift mock space shared for the whole suite.
  *
  * This is a different isolation shape than [[MatrixHarness]]: there, every scenario gets its own
  * mock space (different port, often a different datafile) — providers never actually contend for the
  * same backing service. Here, every scenario's `OptimizelyProvider` is configured with the identical
  * `datafileUrl` (the shared space's `baseUri`); what makes concurrent scenarios resolve different
  * flag values isn't a different datafile, it's a different evaluation context (set as that scenario's
  * global context from its `@flags(...)` tag).
  *
  * The CDN is simulated by Rift's in-process native HTTP engine — no WireMock server, no mitmproxy
  * container, and no JVM-global `http.proxy*` properties (whose leakage across suites broke sibling
  * suites under a shared/forked JVM, #278). Each scenario's provider fetches the datafile straight
  * from the shared space.
  *
  * Per-scenario isolation is still provider-level: each call to [[layerForSharedCdn]] builds a fresh
  * `OptimizelyProvider` under its own freshly-generated domain (see [[MatrixHarness]]'s doc for why a
  * named domain is required for `scenarioParallelism > 1` to be safe) — only the mock space is shared.
  */
object ProxyMatrixHarness {

  private val SdkKey       = "test-proxy-matrix-key"
  private val DatafilePath = s"/datafiles/$SdkKey.json"
  private val Datafile     = loadDatafile("audience-segments")

  private def loadDatafile(name: String): String = {
    val path = s"/datafiles/$name.json"
    val is   = getClass.getResourceAsStream(path)
    require(is != null, s"Datafile fixture not found on classpath: $path")
    scala.io.Source.fromInputStream(is).mkString
  }

  private def datafileRule: MockRule =
    get(DatafilePath).respondWith(ok.withHeader("Content-Type", "application/json").withBody(Body.Json(Datafile)))

  /** ONE mock space serving the audience-segments datafile for the whole suite, provisioned once on
    * first access. Every scenario's provider points at its `baseUri` — the single shared CDN. Built
    * via a `lazy val` (once-only and safe under concurrent scenarios: the JVM guarantees at most one
    * thread runs the initializer). Never destroyed explicitly — the shared Rift engine is a JVM-lifetime
    * test singleton reclaimed at process exit, and the module forks its test JVM so sbt tears it down.
    */
  private lazy val sharedDataUrl: String =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          RiftEngine.mockControl
            .provision(MockSpec(List(datafileRule)).source)
            .flatMap(spaces => ZIO.fromOption(spaces.headOption).orElseFail(new RuntimeException("no shared mock space provisioned")))
            .map(space => s"${space.baseUri}$DatafilePath")
        )
        .getOrThrowFiberFailure()
    }

  /** A scoped `FeatureFlags` layer backed by a fresh provider pointed at the shared simulated CDN.
    *
    * `contextAttributes` becomes that scenario's *global* evaluation context — `setGlobalContext`
    * replaces rather than merges, so every attribute from the tag is folded into one context and set in
    * a single call, rather than one call per key (which would have each call silently discard the
    * previous attribute).
    */
  def layerForSharedCdn(contextAttributes: Map[String, String]): ZLayer[Any, Throwable, FeatureFlags] =
    ZLayer.scoped {
      for {
        dataUrl <- ZIO.attemptBlocking(sharedDataUrl)
        config = OptimizelyProviderConfig(
                   sdkKey = SdkKey,
                   datafileUrl = Some(dataUrl),
                   initWait = java.time.Duration.ofSeconds(10),
                   pollingInterval = Some(java.time.Duration.ofSeconds(3600)), // no polling in tests
                   blockingTimeout = Some(java.time.Duration.ofSeconds(5))
                 )
        provider <- OptimizelyProvider.scoped(config).mapError(e => new RuntimeException(e.message))
        domain = s"proxy-flag-matrix-${java.util.UUID.randomUUID()}"
        env <- FeatureFlags.fromProvider(provider, FeatureFlagsConfig().withDomain(domain)).build
        ff = env.get[FeatureFlags]
        ctx = contextAttributes.foldLeft(EvaluationContext.empty) { case (acc, (k, v)) =>
                acc.withAttribute(k, AttributeValue.StringValue(v))
              }
        _ <- ff.setGlobalContext(ctx)
      } yield ff
    }
}
