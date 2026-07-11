package zio.openfeature.conformance.bdd.matrix

import zio._
import zio.bdd.core.Suite
import zio.bdd.core.Assertions.assertTrue
import zio.bdd.core.step.ZIOSteps
import zio.bdd.gherkin.ScenarioMetadata
import zio.openfeature._
import zio.openfeature.optimizely.matrix.{RecommendationResult, RecommendationService}

/** Flag-matrix BDD suite where every scenario fetches the *same* datafile from the *same* simulated CDN endpoint,
  * concurrently, and differs only by evaluation context.
  *
  * Contrast with [[FlagMatrixSpec]]: there, `@flags(datafile=X)` selects *which fixture* a scenario's own dedicated
  * Rift mock space serves. Here there is no `datafile` key at all — every `@flags(...)` tag is a set of
  * evaluation-context attributes (e.g. `plan`, `region`), and all scenarios' providers point at one shared Rift
  * mock space (see [[ProxyMatrixHarness]]). `recommendation_rate_limit` in the
  * `audience-segments` datafile is gated on *both* attributes together (`plan = "premium" AND region = "eu"`), so a
  * single tag carrying both keys is what actually changes the flag's resolved value — not a provider swap.
  */
@Suite(
  featureDirs = Array("conformance-zio-bdd/src/test/resources/features/optimizely/proxy"),
  reporters = Array("pretty"),
  parallelism = 1,
  scenarioParallelism = 8,
  logLevel = "warning"
)
object ProxyFlagMatrixSpec extends ZIOSteps[FeatureFlags, World] {

  /** Every key in a `@flags(...)` tag becomes a global evaluation-context attribute — there's no `datafile` key to
    * special-case here, unlike [[FlagMatrixSpec]].
    */
  override def flagLayer(meta: ScenarioMetadata, flags: Map[String, String]): ZLayer[Any, Throwable, FeatureFlags] =
    ProxyMatrixHarness.layerForSharedCdn(flags)

  // ── Assertions (mirrors FlagMatrixSpec's; separate suite object, separate step registry) ──

  Then("the recommendation kind is " / string) { (expected: String) =>
    for {
      world <- ScenarioContext.get
      result = world.lastResult.getOrElse(RecommendationResult("__none__", -1))
      _ <- assertTrue(result.kind == expected, s"kind '${result.kind}' != '$expected'")
    } yield ()
  }

  And("the rate limit is " / int) { (expected: Int) =>
    for {
      world <- ScenarioContext.get
      result = world.lastResult.getOrElse(RecommendationResult("__none__", -1))
      _ <- assertTrue(result.rateLimit == expected, s"rateLimit ${result.rateLimit} != $expected")
    } yield ()
  }

  // ── Request step — entirely tag-driven, no plan/region wording in step text ──

  When("a recommendation is requested") {
    ZIO.serviceWithZIO[FeatureFlags] { ff =>
      val svc = new RecommendationService(ff)
      for {
        result <- svc.recommendWithContext(EvaluationContext("proxy-matrix-user"))
        _      <- ScenarioContext.update(_.copy(lastResult = Some(result)))
      } yield ()
    }
  }
}
