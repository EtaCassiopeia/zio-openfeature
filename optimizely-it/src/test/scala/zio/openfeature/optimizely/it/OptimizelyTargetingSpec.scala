package zio.openfeature.optimizely.it

import dev.openfeature.sdk.{ErrorCode, ImmutableContext, Reason}
import zio._
import zio.openfeature.optimizely.it.RealOptimizelySupport._
import zio.test.Assertion._
import zio.test._

/** Drives the real Optimizely Java SDK against `it_targeting.json` to exercise audience-based decisions.
  *
  * The fixture defines `it_audience_flag` with two rollout rules: rule 1 is gated by the `Country US` audience
  * (`country == "US"`) and serves the `us` variation; rule 2 is the unconditional default and serves the `off`
  * variation. Tests assert that the user's attributes drive which rule fires.
  */
object OptimizelyTargetingSpec extends ZIOSpecDefault {

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Optimizely audience targeting")(
    test("user matching the US audience -> `us` variation, featureEnabled=true") {
      withReadyProvider(TargetingSdkKey) { provider =>
        val context   = userContext("user-us").add("country", "US")
        val boolEval  = provider.getBooleanEvaluation(AudienceFlagKey, java.lang.Boolean.FALSE, context)
        val valueEval = provider.getStringEvaluation(AudienceFlagKey, "fallback", context)
        assert(boolEval.getValue)(equalTo(java.lang.Boolean.TRUE)) &&
        assert(boolEval.getVariant)(equalTo("us")) &&
        assert(valueEval.getValue)(equalTo("us")) &&
        assert(valueEval.getReason)(equalTo(Reason.TARGETING_MATCH.name()))
      }
    },
    test("user NOT matching the US audience -> falls through to default rule, `off` variation") {
      withReadyProvider(TargetingSdkKey) { provider =>
        val context   = userContext("user-fr").add("country", "FR")
        val boolEval  = provider.getBooleanEvaluation(AudienceFlagKey, java.lang.Boolean.TRUE, context)
        val valueEval = provider.getStringEvaluation(AudienceFlagKey, "fallback", context)
        assert(boolEval.getValue)(equalTo(java.lang.Boolean.FALSE)) &&
        assert(boolEval.getVariant)(equalTo("off")) &&
        assert(valueEval.getValue)(equalTo("other"))
      }
    },
    test("missing targeting key -> TARGETING_KEY_MISSING, default returned") {
      withReadyProvider(TargetingSdkKey) { provider =>
        val ev = provider.getBooleanEvaluation(AudienceFlagKey, java.lang.Boolean.TRUE, new ImmutableContext())
        assert(ev.getValue)(equalTo(java.lang.Boolean.TRUE)) &&
        assert(ev.getErrorCode)(equalTo(ErrorCode.TARGETING_KEY_MISSING)) &&
        assert(ev.getReason)(equalTo(Reason.ERROR.name()))
      }
    }
  ) @@ OptimizelyItStack.ifDockerAvailable @@ TestAspect.sequential @@ TestAspect.timeout(
    120.seconds
  ) @@ TestAspect.withLiveClock
}
