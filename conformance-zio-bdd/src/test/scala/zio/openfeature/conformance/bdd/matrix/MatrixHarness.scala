package zio.openfeature.conformance.bdd.matrix

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import zio._
import zio.openfeature._
import zio.openfeature.optimizely.{OptimizelyProvider, OptimizelyProviderConfig}

/** Builds a per-scenario ZLayer[Any, Throwable, FeatureFlags] from a named datafile fixture.
  *
  * Each layer acquisition:
  *   1. Starts a fresh WireMock server on an ephemeral port.
  *   2. Stubs the Optimizely CDN endpoint to serve the named datafile fixture.
  *   3. Initialises an OptimizelyProvider synchronously (blocks until the datafile is fetched).
  *   4. Builds a FeatureFlags service wired to that provider.
  *
  * The layer is scoped: WireMock and the Optimizely client are shut down when the scenario ends,
  * so each scenario is fully isolated — no stub-swap, no polling wait, no shared state.
  *
  * This is the intended companion to @flags(datafile=X) scenario expansion: zio-bdd calls
  * `flagLayer(meta, Map("datafile" -> "X"))` once per scenario and provides the resulting layer
  * as the scenario's R environment.
  */
object MatrixHarness {

  private val SdkKey       = "test-matrix-key"
  private val DatafilePath = s"/datafiles/$SdkKey.json"

  private def loadDatafile(name: String): String = {
    val path = s"/datafiles/$name.json"
    val is   = getClass.getResourceAsStream(path)
    require(is != null, s"Datafile fixture not found on classpath: $path")
    scala.io.Source.fromInputStream(is).mkString
  }

  /** A scoped ZLayer that owns one WireMock server + one Optimizely provider for the named datafile.
    *
    * `plan`, when present, is seeded as a global-context attribute (via `setGlobalContext`) before the
    * layer is handed to the scenario. The OpenFeature spec merges global context into every invocation
    * automatically (API -> Transaction -> Client -> Invocation), so a single `@flags(datafile=X, plan=Y)`
    * tag can combine a provider swap with a context override in one `flagLayer` call — no separate
    * "with plan" step text required.
    */
  def layerForDatafile(name: String, plan: Option[String] = None): ZLayer[Any, Throwable, FeatureFlags] =
    ZLayer.scoped {
      for {
        server <- ZIO.acquireRelease(
                    ZIO.succeed {
                      val s = new WireMockServer(WireMockConfiguration.options().dynamicPort())
                      s.start()
                      s.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(loadDatafile(name))))
                      s
                    }
                  )(s => ZIO.succeed(s.stop()))
        dataUrl  = s"http://localhost:${server.port()}$DatafilePath"
        config   = OptimizelyProviderConfig(
                     sdkKey          = SdkKey,
                     datafileUrl     = Some(dataUrl),
                     initWait        = java.time.Duration.ofSeconds(5),
                     pollingInterval = Some(java.time.Duration.ofSeconds(3600)), // no polling in tests
                     blockingTimeout = Some(java.time.Duration.ofSeconds(2))
                   )
        provider <- OptimizelyProvider.scoped(config).mapError(e => RuntimeException(e.message))
        env      <- FeatureFlags.fromProvider(provider).build
        ff        = env.get[FeatureFlags]
        _        <- ZIO.foreachDiscard(plan)(p => ff.setGlobalContext(EvaluationContext.empty.withAttribute("plan", AttributeValue.StringValue(p))))
      } yield ff
    }
}
