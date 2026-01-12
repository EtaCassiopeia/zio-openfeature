package zio.openfeature.testkit

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.openfeature.*
import dev.openfeature.sdk.{ImmutableContext, MutableContext, ProviderState}

object TestFeatureProviderSpec extends ZIOSpecDefault:

  def spec = suite("TestFeatureProviderSpec")(
    suite("initialization")(
      test("starts with Ready status after creation") {
        for
          provider <- TestFeatureProvider.make
          status   <- provider.status
        yield assertTrue(status == ProviderStatus.Ready)
      },
      test("setStatus changes provider status") {
        for
          provider <- TestFeatureProvider.make
          _        <- provider.setStatus(ProviderStatus.Error)
          status   <- provider.status
        yield assertTrue(status == ProviderStatus.Error)
      },
      test("metadata is correct") {
        for provider <- TestFeatureProvider.make
        yield assertTrue(provider.metadata.name == "TestFeatureProvider") &&
          assertTrue(provider.metadata.version == Some("1.0.0"))
      }
    ),
    suite("flag resolution via OpenFeature API")(
      test("returns default value for unknown flag") {
        for
          provider <- TestFeatureProvider.make
          result = provider.getBooleanEvaluation("unknown", true, new ImmutableContext())
        yield assertTrue(result.getValue == true) &&
          assertTrue(result.getReason == "DEFAULT")
      },
      test("returns configured value for known flag") {
        for
          provider <- TestFeatureProvider.make(Map("my-flag" -> false))
          result = provider.getBooleanEvaluation("my-flag", true, new ImmutableContext())
        yield assertTrue(result.getValue == false) &&
          assertTrue(result.getReason == "TARGETING_MATCH")
      },
      test("resolves string values") {
        for
          provider <- TestFeatureProvider.make(Map("name" -> "test-value"))
          result = provider.getStringEvaluation("name", "default", new ImmutableContext())
        yield assertTrue(result.getValue == "test-value")
      },
      test("resolves int values") {
        for
          provider <- TestFeatureProvider.make(Map("count" -> 42))
          result = provider.getIntegerEvaluation("count", 0, new ImmutableContext())
        yield assertTrue(result.getValue == 42)
      },
      test("resolves double values") {
        for
          provider <- TestFeatureProvider.make(Map("rate" -> 3.14))
          result = provider.getDoubleEvaluation("rate", 0.0, new ImmutableContext())
        yield assertTrue(result.getValue == 3.14)
      }
    ),
    suite("flag management")(
      test("setFlag adds a flag") {
        for
          provider <- TestFeatureProvider.make
          _        <- provider.setFlag("new-flag", "value")
          result = provider.getStringEvaluation("new-flag", "default", new ImmutableContext())
        yield assertTrue(result.getValue == "value")
      },
      test("setFlags replaces all flags") {
        for
          provider <- TestFeatureProvider.make(Map("old" -> 1))
          _        <- provider.setFlags(Map("new" -> 2))
          oldResult = provider.getIntegerEvaluation("old", 0, new ImmutableContext())
          newResult = provider.getIntegerEvaluation("new", 0, new ImmutableContext())
        yield assertTrue(oldResult.getValue == 0) && // default, flag no longer exists
          assertTrue(newResult.getValue == 2)
      },
      test("removeFlag removes a flag") {
        for
          provider <- TestFeatureProvider.make(Map("flag" -> true))
          _        <- provider.removeFlag("flag")
          result = provider.getBooleanEvaluation("flag", false, new ImmutableContext())
        yield assertTrue(result.getValue == false) &&
          assertTrue(result.getReason == "DEFAULT")
      },
      test("clearFlags removes all flags") {
        for
          provider <- TestFeatureProvider.make(Map("a" -> 1, "b" -> 2))
          _        <- provider.clearFlags
          resultA = provider.getIntegerEvaluation("a", 0, new ImmutableContext())
          resultB = provider.getIntegerEvaluation("b", 0, new ImmutableContext())
        yield assertTrue(resultA.getReason == "DEFAULT") &&
          assertTrue(resultB.getReason == "DEFAULT")
      }
    ),
    suite("status management")(
      test("setStatus updates both ZIO status and OpenFeature state") {
        for
          provider <- TestFeatureProvider.make
          _        <- provider.setStatus(ProviderStatus.Error)
          status   <- provider.status
          state = provider.getState
        yield assertTrue(status == ProviderStatus.Error) &&
          assertTrue(state == ProviderState.ERROR)
      },
      test("setStatus to Ready updates state correctly") {
        for
          provider <- TestFeatureProvider.make
          _        <- provider.setStatus(ProviderStatus.NotReady)
          _        <- provider.setStatus(ProviderStatus.Ready)
          status   <- provider.status
          state = provider.getState
        yield assertTrue(status == ProviderStatus.Ready) &&
          assertTrue(state == ProviderState.READY)
      }
    ),
    suite("evaluation tracking")(
      test("tracks evaluations") {
        for
          provider <- TestFeatureProvider.make
          _ = provider.getBooleanEvaluation("flag1", true, new ImmutableContext())
          _ = provider.getStringEvaluation("flag2", "test", new ImmutableContext())
          evals <- provider.getEvaluations
        yield assertTrue(evals.map(_._1).toSet == Set("flag1", "flag2"))
      },
      test("wasEvaluated returns true for evaluated flags") {
        for
          provider <- TestFeatureProvider.make
          _ = provider.getBooleanEvaluation("checked", true, new ImmutableContext())
          was    <- provider.wasEvaluated("checked")
          wasNot <- provider.wasEvaluated("not-checked")
        yield assertTrue(was) && assertTrue(!wasNot)
      },
      test("evaluationCount returns correct count") {
        for
          provider <- TestFeatureProvider.make
          _ = provider.getBooleanEvaluation("repeat", true, new ImmutableContext())
          _ = provider.getBooleanEvaluation("repeat", true, new ImmutableContext())
          _ = provider.getBooleanEvaluation("repeat", true, new ImmutableContext())
          count <- provider.evaluationCount("repeat")
        yield assertTrue(count == 3)
      },
      test("clearEvaluations clears tracking") {
        for
          provider <- TestFeatureProvider.make
          _ = provider.getBooleanEvaluation("flag", true, new ImmutableContext())
          _     <- provider.clearEvaluations
          evals <- provider.getEvaluations
        yield assertTrue(evals.isEmpty)
      },
      test("tracks context with evaluations") {
        for
          provider <- TestFeatureProvider.make
          ctx = new ImmutableContext("user-123")
          _   = provider.getBooleanEvaluation("flag", true, ctx)
          evals <- provider.getEvaluations
        yield assertTrue(evals.head._2.getTargetingKey == "user-123")
      }
    ),
    suite("layer integration")(
      test("layer provides both TestFeatureProvider and FeatureFlags") {
        val effect = for
          provider <- ZIO.service[TestFeatureProvider]
          _        <- provider.setFlag("test", true)
          result   <- FeatureFlags.boolean("test", default = false)
        yield assertTrue(result == true)

        effect.provide(Scope.default >>> TestFeatureProvider.layer)
      },
      test("layer with flags provides pre-configured flags") {
        val effect =
          for result <- FeatureFlags.boolean("preset", default = false)
          yield assertTrue(result == true)

        effect.provide(Scope.default >>> TestFeatureProvider.layer(Map("preset" -> true)))
      },
      test("providerLayer provides just TestFeatureProvider") {
        val effect = for
          provider <- ZIO.service[TestFeatureProvider]
          _        <- provider.setFlag("flag", 42)
          result = provider.getIntegerEvaluation("flag", 0, new ImmutableContext())
        yield assertTrue(result.getValue == 42)

        effect.provide(TestFeatureProvider.providerLayer)
      }
    )
  )
