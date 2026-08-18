package zio.openfeature.testkit

import zio._
import zio.stream._
import zio.test._
import zio.test.Assertion._
import zio.openfeature._
import dev.openfeature.sdk.{ErrorCode => OFErrorCode, ImmutableContext, MutableContext, ProviderState}

object TestFeatureProviderSpec extends ZIOSpecDefault {

  def spec = suite("TestFeatureProviderSpec")(
    suite("#272 testkit DX")(
      test("G1: getEvaluations returns the library EvaluationContext (no Java SDK type leak)") {
        for {
          provider <- TestFeatureProvider.make
          jctx = new MutableContext("user-1").add("tier", "gold")
          _    = provider.getBooleanEvaluation("flag", true, jctx)
          evals <- provider.getEvaluations
        } yield {
          val captured: EvaluationContext = evals.head._2
          assertTrue(
            captured.targetingKey.contains("user-1"),
            captured.getString("tier").contains("gold")
          )
        }
      },
      test("G2: getRawEvaluations still exposes the raw Java SDK EvaluationContext") {
        for {
          provider <- TestFeatureProvider.make
          jctx = new MutableContext("user-2")
          _    = provider.getBooleanEvaluation("flag", true, jctx)
          raw <- provider.getRawEvaluations
        } yield {
          val captured: dev.openfeature.sdk.EvaluationContext = raw.head._2
          assertTrue(captured.getTargetingKey == "user-2")
        }
      },
      test("G3: setFlags merges into existing flags — seeded flags survive") {
        for {
          provider <- TestFeatureProvider.make(Map("seeded" -> 1))
          _        <- provider.setFlags(Map("added" -> 2))
          seeded = provider.getIntegerEvaluation("seeded", 0, new ImmutableContext())
          added  = provider.getIntegerEvaluation("added", 0, new ImmutableContext())
        } yield assertTrue(seeded.getValue == 1, added.getValue == 2)
      },
      test("G4: replaceFlags discards existing flags") {
        for {
          provider <- TestFeatureProvider.make(Map("old" -> 1))
          _        <- provider.replaceFlags(Map("new" -> 2))
          old = provider.getIntegerEvaluation("old", 0, new ImmutableContext())
          nw  = provider.getIntegerEvaluation("new", 0, new ImmutableContext())
        } yield assertTrue(old.getValue == 0, nw.getValue == 2)
      }
    ),
    suite("initialization")(
      test("starts with Ready status after creation") {
        for {
          provider <- TestFeatureProvider.make
          status   <- provider.status
        } yield assertTrue(status == ProviderStatus.Ready)
      },
      test("setStatus changes provider status") {
        for {
          provider <- TestFeatureProvider.make
          _        <- provider.setStatus(ProviderStatus.Error)
          status   <- provider.status
        } yield assertTrue(status == ProviderStatus.Error)
      },
      test("metadata is correct") {
        for {
          provider <- TestFeatureProvider.make
        } yield assertTrue(provider.metadata.name == "TestFeatureProvider") &&
          assertTrue(provider.metadata.version == Some("1.0.0"))
      }
    ),
    suite("flag resolution via OpenFeature API")(
      test("reports FLAG_NOT_FOUND with the caller's default for an unknown flag") {
        for {
          provider <- TestFeatureProvider.make
          result = provider.getBooleanEvaluation("unknown", true, new ImmutableContext())
        } yield assertTrue(result.getValue == true) &&
          assertTrue(result.getReason == "ERROR") &&
          assertTrue(result.getErrorCode == OFErrorCode.FLAG_NOT_FOUND)
      },
      test("returns configured value for known flag") {
        for {
          provider <- TestFeatureProvider.make(Map("my-flag" -> false))
          result = provider.getBooleanEvaluation("my-flag", true, new ImmutableContext())
        } yield assertTrue(result.getValue == false) &&
          assertTrue(result.getReason == "TARGETING_MATCH")
      },
      test("resolves string values") {
        for {
          provider <- TestFeatureProvider.make(Map("name" -> "test-value"))
          result = provider.getStringEvaluation("name", "default", new ImmutableContext())
        } yield assertTrue(result.getValue == "test-value")
      },
      test("resolves int values") {
        for {
          provider <- TestFeatureProvider.make(Map("count" -> 42))
          result = provider.getIntegerEvaluation("count", 0, new ImmutableContext())
        } yield assertTrue(result.getValue == 42)
      },
      test("resolves double values") {
        for {
          provider <- TestFeatureProvider.make(Map("rate" -> 3.14))
          result = provider.getDoubleEvaluation("rate", 0.0, new ImmutableContext())
        } yield assertTrue(result.getValue == 3.14)
      }
    ),
    suite("flag management")(
      test("setFlag adds a flag") {
        for {
          provider <- TestFeatureProvider.make
          _        <- provider.setFlag("new-flag", "value")
          result = provider.getStringEvaluation("new-flag", "default", new ImmutableContext())
        } yield assertTrue(result.getValue == "value")
      },
      test("replaceFlags replaces all flags") {
        for {
          provider <- TestFeatureProvider.make(Map("old" -> 1))
          _        <- provider.replaceFlags(Map("new" -> 2))
          oldResult = provider.getIntegerEvaluation("old", 0, new ImmutableContext())
          newResult = provider.getIntegerEvaluation("new", 0, new ImmutableContext())
        } yield assertTrue(oldResult.getValue == 0) && // default, flag no longer exists
          assertTrue(newResult.getValue == 2)
      },
      test("removeFlag removes a flag, which then reports FLAG_NOT_FOUND") {
        for {
          provider <- TestFeatureProvider.make(Map("flag" -> true))
          _        <- provider.removeFlag("flag")
          result = provider.getBooleanEvaluation("flag", false, new ImmutableContext())
        } yield assertTrue(result.getValue == false) &&
          assertTrue(result.getReason == "ERROR") &&
          assertTrue(result.getErrorCode == OFErrorCode.FLAG_NOT_FOUND)
      },
      test("clearFlags removes all flags, which then report FLAG_NOT_FOUND") {
        for {
          provider <- TestFeatureProvider.make(Map("a" -> 1, "b" -> 2))
          _        <- provider.clearFlags
          resultA = provider.getIntegerEvaluation("a", 0, new ImmutableContext())
          resultB = provider.getIntegerEvaluation("b", 0, new ImmutableContext())
        } yield assertTrue(resultA.getValue == 0, resultB.getValue == 0) &&
          assertTrue(resultA.getErrorCode == OFErrorCode.FLAG_NOT_FOUND) &&
          assertTrue(resultB.getErrorCode == OFErrorCode.FLAG_NOT_FOUND)
      }
    ),
    suite("status management")(
      test("setStatus updates both ZIO status and OpenFeature state") {
        for {
          provider <- TestFeatureProvider.make
          _        <- provider.setStatus(ProviderStatus.Error)
          status   <- provider.status
          state = provider.getState
        } yield assertTrue(status == ProviderStatus.Error) &&
          assertTrue(state == ProviderState.ERROR)
      },
      test("setStatus to Ready updates state correctly") {
        for {
          provider <- TestFeatureProvider.make
          _        <- provider.setStatus(ProviderStatus.NotReady)
          _        <- provider.setStatus(ProviderStatus.Ready)
          status   <- provider.status
          state = provider.getState
        } yield assertTrue(status == ProviderStatus.Ready) &&
          assertTrue(state == ProviderState.READY)
      }
    ),
    suite("evaluation tracking")(
      test("tracks evaluations") {
        for {
          provider <- TestFeatureProvider.make
          _ = provider.getBooleanEvaluation("flag1", true, new ImmutableContext())
          _ = provider.getStringEvaluation("flag2", "test", new ImmutableContext())
          evals <- provider.getEvaluations
        } yield assertTrue(evals.map(_._1).toSet == Set("flag1", "flag2"))
      },
      test("wasEvaluated returns true for evaluated flags") {
        for {
          provider <- TestFeatureProvider.make
          _ = provider.getBooleanEvaluation("checked", true, new ImmutableContext())
          was    <- provider.wasEvaluated("checked")
          wasNot <- provider.wasEvaluated("not-checked")
        } yield assertTrue(was) && assertTrue(!wasNot)
      },
      test("evaluationCount returns correct count") {
        for {
          provider <- TestFeatureProvider.make
          _ = provider.getBooleanEvaluation("repeat", true, new ImmutableContext())
          _ = provider.getBooleanEvaluation("repeat", true, new ImmutableContext())
          _ = provider.getBooleanEvaluation("repeat", true, new ImmutableContext())
          count <- provider.evaluationCount("repeat")
        } yield assertTrue(count == 3)
      },
      test("clearEvaluations clears tracking") {
        for {
          provider <- TestFeatureProvider.make
          _ = provider.getBooleanEvaluation("flag", true, new ImmutableContext())
          _     <- provider.clearEvaluations
          evals <- provider.getEvaluations
        } yield assertTrue(evals.isEmpty)
      },
      test("tracks context with evaluations") {
        for {
          provider <- TestFeatureProvider.make
          ctx = new ImmutableContext("user-123")
          _   = provider.getBooleanEvaluation("flag", true, ctx)
          evals <- provider.getEvaluations
        } yield assertTrue(evals.head._2.targetingKey.contains("user-123"))
      }
    ),
    suite("layer integration")(
      test("layer provides both TestFeatureProvider and FeatureFlags") {
        val effect = for {
          provider <- ZIO.service[TestFeatureProvider]
          _        <- provider.setFlag("test", true)
          result   <- FeatureFlags.boolean("test", default = false)
        } yield assertTrue(result == true)

        effect.provide(Scope.default >>> TestFeatureProvider.layer)
      },
      test("layer with flags provides pre-configured flags") {
        val effect =
          for {
            result <- FeatureFlags.boolean("preset", default = false)
          } yield assertTrue(result == true)

        effect.provide(Scope.default >>> TestFeatureProvider.layer(Map("preset" -> true)))
      },
      test("providerLayer provides just TestFeatureProvider") {
        val effect = for {
          provider <- ZIO.service[TestFeatureProvider]
          _        <- provider.setFlag("flag", 42)
          result = provider.getIntegerEvaluation("flag", 0, new ImmutableContext())
        } yield assertTrue(result.getValue == 42)

        effect.provide(TestFeatureProvider.providerLayer)
      }
    ),
    suite("event streaming")(
      test("events stream receives emitted events") {
        for {
          provider <- TestFeatureProvider.make
          received <- Promise.make[Nothing, ProviderEvent]
          fiber    <- provider.events.foreach(e => received.succeed(e)).fork
          // Retry emit until the stream subscription is registered on the Hub
          _ <- (provider.emitEvent(ProviderEvent.ConfigurationChanged(Set("x"), provider.metadata)) *>
            received.isDone.flatMap(done => ZIO.fail(()).unless(done))).retry(Schedule.spaced(10.millis))
          event <- received.await.timeout(5.seconds)
          _     <- fiber.interrupt
        } yield assertTrue(event.exists {
          case ProviderEvent.ConfigurationChanged(flags, _, _) => flags == Set("x")
          case _                                               => false
        })
      },
      test("metadata has expected values") {
        for {
          provider <- TestFeatureProvider.make
        } yield assertTrue(provider.metadata.name == "TestFeatureProvider") &&
          assertTrue(provider.metadata.version.contains("1.0.0"))
      }
    ),
    suite("scoped layer")(
      test("scopedLayer provides both TestFeatureProvider and FeatureFlags") {
        val program = for {
          _      <- ZIO.service[TestFeatureProvider]
          _      <- ZIO.service[FeatureFlags]
          result <- FeatureFlags.boolean("flag", default = false)
        } yield assertTrue(result == true)

        program.provide(TestFeatureProvider.scopedLayer(Map("flag" -> true)))
      },
      test("scopedLayer with empty flags works") {
        val program = for {
          result <- FeatureFlags.booleanOrDefault("missing", default = true)
        } yield assertTrue(result == true)

        program.provide(TestFeatureProvider.scopedLayer)
      }
    )
  ) @@ TestAspect.withLiveClock
}
