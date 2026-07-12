package zio.openfeature.conformance.bdd.matrix

import zio._
import zio.bdd.core.Suite
import zio.bdd.core.Assertions.assertTrue
import zio.bdd.core.step.ZIOSteps
import zio.bdd.gherkin.ScenarioMetadata
import zio.openfeature._
import zio.openfeature.optimizely.matrix.{InterceptHarness, RecommendationService}

/** Rift intercept (TLS-MITM) suite — exercises the SDK's REAL default-CDN transport path (#280).
  *
  * Unlike [[FlagMatrixSpec]] / [[MatrixHarness]], which override `datafileUrl` to a mock URL and fetch over
  * plain HTTP, this suite leaves `datafileUrl = None` so the Optimizely SDK builds its production URL
  * (`https://cdn.optimizely.com/datafiles/<key>.json`) and performs a real HTTPS fetch — which Rift's intercept
  * engine transparently MITMs, serving the datafile fixture. It proves the same decision logic works over the
  * default URL template + HTTPS + TLS-trust path a production deployment uses.
  *
  * `scenarioParallelism = 1`: the intercept listener is per-engine, and each scenario stands up its own intercept
  * engine + per-provider `OptimizelyHttpClient` (trusting Rift's CA, routing through the intercept listener) — no
  * JVM-global proxy/truststore, so nothing leaks across suites (#278).
  */
@Suite(
  featureDirs         = Array("conformance-zio-bdd/src/test/resources/features/optimizely-intercept"),
  reporters           = Array("pretty"),
  parallelism         = 1,
  scenarioParallelism = 1,
  logLevel            = "warning"
)
object InterceptFlagMatrixSpec extends ZIOSteps[FeatureFlags, World] {

  override def flagLayer(meta: ScenarioMetadata, flags: Map[String, String]): ZLayer[Any, Throwable, FeatureFlags] =
    InterceptHarness.layerForDatafile(flags.getOrElse("datafile", "kill-switch-off"))

  Then("the recommendation service returns kind " / string) { (expected: String) =>
    ZIO.serviceWithZIO[FeatureFlags] { ff =>
      val svc = new RecommendationService(ff)
      for {
        kind <- svc.recommend
        _    <- assertTrue(kind == expected, s"recommendation kind '$kind' != '$expected'")
      } yield ()
    }
  }
}
