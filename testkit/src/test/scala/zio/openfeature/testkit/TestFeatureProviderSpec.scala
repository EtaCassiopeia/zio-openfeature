package zio.openfeature.testkit

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.openfeature.*

object TestFeatureProviderSpec extends ZIOSpecDefault:

  def spec = suite("TestFeatureProviderSpec")(
    suite("initialization")(
      test("starts with NotReady status") {
        for
          provider <- TestFeatureProvider.make
          status   <- provider.status
        yield assertTrue(status == ProviderStatus.NotReady)
      },
      test("initialize sets Ready status") {
        for
          provider <- TestFeatureProvider.make
          _        <- provider.initialize
          status   <- provider.status
        yield assertTrue(status == ProviderStatus.Ready)
      },
      test("shutdown sets NotReady status") {
        for
          provider <- TestFeatureProvider.make
          _        <- provider.initialize
          _        <- provider.shutdown
          status   <- provider.status
        yield assertTrue(status == ProviderStatus.NotReady)
      },
      test("metadata is correct") {
        for provider <- TestFeatureProvider.make
        yield assertTrue(provider.metadata.name == "TestProvider") &&
          assertTrue(provider.metadata.version == Some("1.0.0"))
      }
    ),
    suite("flag resolution")(
      test("returns default value for unknown flag") {
        for
          provider   <- TestFeatureProvider.make
          resolution <- provider.resolveBooleanValue("unknown", true, EvaluationContext.empty)
        yield assertTrue(resolution.value == true) &&
          assertTrue(resolution.reason == ResolutionReason.Default)
      },
      test("returns configured value for known flag") {
        for
          provider   <- TestFeatureProvider.make(Map("my-flag" -> false))
          resolution <- provider.resolveBooleanValue("my-flag", true, EvaluationContext.empty)
        yield assertTrue(resolution.value == false) &&
          assertTrue(resolution.reason == ResolutionReason.TargetingMatch)
      },
      test("resolves string values") {
        for
          provider   <- TestFeatureProvider.make(Map("name" -> "test-value"))
          resolution <- provider.resolveStringValue("name", "default", EvaluationContext.empty)
        yield assertTrue(resolution.value == "test-value")
      },
      test("resolves int values") {
        for
          provider   <- TestFeatureProvider.make(Map("count" -> 42))
          resolution <- provider.resolveIntValue("count", 0, EvaluationContext.empty)
        yield assertTrue(resolution.value == 42)
      },
      test("resolves double values") {
        for
          provider   <- TestFeatureProvider.make(Map("rate" -> 3.14))
          resolution <- provider.resolveDoubleValue("rate", 0.0, EvaluationContext.empty)
        yield assertTrue(resolution.value == 3.14)
      },
      test("resolves object values") {
        val obj = Map("key" -> "value")
        for
          provider   <- TestFeatureProvider.make(Map("config" -> obj))
          resolution <- provider.resolveObjectValue("config", Map.empty, EvaluationContext.empty)
        yield assertTrue(resolution.value == obj)
      }
    ),
    suite("flag management")(
      test("setFlag adds a flag") {
        for
          provider   <- TestFeatureProvider.make
          _          <- provider.setFlag("new-flag", "value")
          resolution <- provider.resolveStringValue("new-flag", "default", EvaluationContext.empty)
        yield assertTrue(resolution.value == "value")
      },
      test("setFlags replaces all flags") {
        for
          provider  <- TestFeatureProvider.make(Map("old" -> 1))
          _         <- provider.setFlags(Map("new" -> 2))
          oldResult <- provider.resolveIntValue("old", 0, EvaluationContext.empty)
          newResult <- provider.resolveIntValue("new", 0, EvaluationContext.empty)
        yield assertTrue(oldResult.value == 0) && // default, flag no longer exists
          assertTrue(newResult.value == 2)
      },
      test("removeFlag removes a flag") {
        for
          provider   <- TestFeatureProvider.make(Map("flag" -> true))
          _          <- provider.removeFlag("flag")
          resolution <- provider.resolveBooleanValue("flag", false, EvaluationContext.empty)
        yield assertTrue(resolution.value == false) &&
          assertTrue(resolution.reason == ResolutionReason.Default)
      },
      test("clearFlags removes all flags") {
        for
          provider <- TestFeatureProvider.make(Map("a" -> 1, "b" -> 2))
          _        <- provider.clearFlags
          resultA  <- provider.resolveIntValue("a", 0, EvaluationContext.empty)
          resultB  <- provider.resolveIntValue("b", 0, EvaluationContext.empty)
        yield assertTrue(resultA.reason == ResolutionReason.Default) &&
          assertTrue(resultB.reason == ResolutionReason.Default)
      }
    ),
    suite("status management")(
      test("setStatus changes provider status") {
        for
          provider <- TestFeatureProvider.make
          _        <- provider.setStatus(ProviderStatus.Error)
          status   <- provider.status
        yield assertTrue(status == ProviderStatus.Error)
      }
    ),
    suite("evaluation tracking")(
      test("tracks evaluations") {
        for
          provider <- TestFeatureProvider.make
          _        <- provider.resolveBooleanValue("flag1", true, EvaluationContext.empty)
          _        <- provider.resolveStringValue("flag2", "test", EvaluationContext.empty)
          evals    <- provider.getEvaluations
        yield assertTrue(evals.map(_._1).toSet == Set("flag1", "flag2"))
      },
      test("wasEvaluated returns true for evaluated flags") {
        for
          provider <- TestFeatureProvider.make
          _        <- provider.resolveBooleanValue("checked", true, EvaluationContext.empty)
          was      <- provider.wasEvaluated("checked")
          wasNot   <- provider.wasEvaluated("not-checked")
        yield assertTrue(was) && assertTrue(!wasNot)
      },
      test("evaluationCount returns correct count") {
        for
          provider <- TestFeatureProvider.make
          _        <- provider.resolveBooleanValue("repeat", true, EvaluationContext.empty)
          _        <- provider.resolveBooleanValue("repeat", true, EvaluationContext.empty)
          _        <- provider.resolveBooleanValue("repeat", true, EvaluationContext.empty)
          count    <- provider.evaluationCount("repeat")
        yield assertTrue(count == 3)
      },
      test("clearEvaluations clears tracking") {
        for
          provider <- TestFeatureProvider.make
          _        <- provider.resolveBooleanValue("flag", true, EvaluationContext.empty)
          _        <- provider.clearEvaluations
          evals    <- provider.getEvaluations
        yield assertTrue(evals.isEmpty)
      },
      test("tracks context with evaluations") {
        val ctx = EvaluationContext("user-123").withAttribute("role", "admin")
        for
          provider <- TestFeatureProvider.make
          _        <- provider.resolveBooleanValue("flag", true, ctx)
          evals    <- provider.getEvaluations
        yield assertTrue(evals.head._2.targetingKey == Some("user-123"))
      }
    ),
    suite("layer")(
      test("layer provides TestFeatureProvider") {
        val effect = for
          provider <- ZIO.service[TestFeatureProvider]
          _        <- provider.setFlag("test", true)
          result   <- provider.resolveBooleanValue("test", false, EvaluationContext.empty)
        yield assertTrue(result.value == true)

        effect.provide(TestFeatureProvider.layer)
      },
      test("layer with flags provides pre-configured provider") {
        val effect = for
          provider <- ZIO.service[FeatureProvider]
          result   <- provider.resolveBooleanValue("preset", false, EvaluationContext.empty)
        yield assertTrue(result.value == true)

        effect.provide(TestFeatureProvider.layer(Map("preset" -> true)))
      }
    )
  )
