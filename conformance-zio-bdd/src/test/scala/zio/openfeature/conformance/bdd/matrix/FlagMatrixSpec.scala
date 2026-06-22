package zio.openfeature.conformance.bdd.matrix

import zio._
import zio.bdd.core.Suite
import zio.bdd.core.Assertions.assertTrue
import zio.bdd.core.step.ZIOSteps
import zio.bdd.gherkin.ScenarioMetadata
import zio.openfeature._
import zio.openfeature.optimizely.matrix.{RecommendationResult, RecommendationService}

/** Flag-matrix BDD suite driven by @flags(datafile=X) scenario expansion.
  *
  * == How it works ==
  *
  * Each scenario is annotated with one or more @flags(datafile=<name>) tags. zio-bdd expands
  * each tag into a separate scenario run and calls flagLayer(meta, Map("datafile" -> "<name>")).
  * That layer builds a fresh WireMock server + Optimizely provider scoped to that scenario —
  * no global harness, no stub-swap between scenarios, no polling wait in step bodies.
  *
  * Isolation guarantees:
  *   - Each run gets its own WireMock on an ephemeral port and its own Optimizely client.
  *   - WireMock and the client are shut down when the scenario scope closes.
  *   - scenarioParallelism > 1 is safe: providers don't share state.
  *
  * Lives in `conformance-zio-bdd` (rather than its own module) so this stays a single zio-bdd
  * home instead of a third Scala-3-only test module; `RecommendationService` itself is reused
  * from `optimizely`'s test sources via a test->test dependency in build.sbt, so the
  * decision logic under test has exactly one definition.
  */
@Suite(
  featureDirs         = Array("conformance-zio-bdd/src/test/resources/features/optimizely"),
  reporters           = Array("pretty"),
  parallelism         = 1,
  scenarioParallelism = 1,
  logLevel            = "warning"
)
object FlagMatrixSpec extends ZIOSteps[FeatureFlags, World] {

  /** Build a per-scenario FeatureFlags layer from the tag's flag values.
    *
    * Called once per `@flags(...)` tag occurrence on a scenario — not once per key inside a tag.
    * A single `@flags(datafile=X, plan=Y)` tag therefore produces one call with both keys in
    * `flags`, while two separate `@flags(datafile=X) @flags(datafile=Y)` tags on the same scenario
    * produce two independent calls (one per tag), each running the scenario body once.
    */
  override def flagLayer(meta: ScenarioMetadata, flags: Map[String, String]): ZLayer[Any, Throwable, FeatureFlags] = {
    val datafile = flags.getOrElse("datafile", "empty")
    MatrixHarness.layerForDatafile(datafile, plan = flags.get("plan"))
  }

  // ── Assertions ──────────────────────────────────────────────────────────

  Then("the recommendation service returns kind " / string) { (expected: String) =>
    ZIO.serviceWithZIO[FeatureFlags] { ff =>
      val svc = new RecommendationService(ff)
      for {
        kind <- svc.recommend
        _    <- assertTrue(kind == expected, s"recommendation kind '$kind' != '$expected'")
      } yield ()
    }
  }

  Then("the recommendation kind is " / string) { (expected: String) =>
    for {
      world <- ScenarioContext.get
      result = world.lastResult.getOrElse(RecommendationResult("__none__", -1))
      _     <- assertTrue(result.kind == expected, s"kind '${result.kind}' != '$expected'")
    } yield ()
  }

  And("the rate limit is " / int) { (expected: Int) =>
    for {
      world <- ScenarioContext.get
      result = world.lastResult.getOrElse(RecommendationResult("__none__", -1))
      _     <- assertTrue(result.rateLimit == expected, s"rateLimit ${result.rateLimit} != $expected")
    } yield ()
  }

  // ── Tag-driven context (no plan in step text — sourced from @flags(plan=...)) ────────────

  When("a recommendation is requested") {
    ZIO.serviceWithZIO[FeatureFlags] { ff =>
      val svc = new RecommendationService(ff)
      for {
        result <- svc.recommendWithContext(EvaluationContext("test-user"))
        _      <- ScenarioContext.update(_.copy(lastResult = Some(result)))
      } yield ()
    }
  }

  // ── User-context step (audience-gated scenarios) ─────────────────────────

  When("user " / string / " with plan " / string / " requests a recommendation") {
    (userId: String, plan: String) =>
      ZIO.serviceWithZIO[FeatureFlags] { ff =>
        val svc = new RecommendationService(ff)
        val ctx = EvaluationContext(userId).withAttribute("plan", AttributeValue.StringValue(plan))
        for {
          result <- svc.recommendWithContext(ctx)
          _      <- ScenarioContext.update(_.copy(lastResult = Some(result)))
        } yield ()
      }
  }
}
