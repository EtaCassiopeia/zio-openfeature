package zio.openfeature.optimizely.it

import dev.openfeature.sdk.{MutableContext, Reason, Value}
import zio._
import zio.openfeature.optimizely.it.RealOptimizelySupport._
import zio.test.Assertion._
import zio.test._

/** Probes decision-engine edge cases:
  *
  *   - Optimizely's native `json` variable type — single variable, type=json, value is a JSON string the SDK parses
  *     into an object. Distinct from our existing it_object_flag path which uses multiple string/int variables.
  *   - `featureEnabled=false` in the rollout default rule — `getBooleanEvaluation` should return false, but variables
  *     should still be readable.
  *   - `$opt_bucketing_id` reserved attribute — when set, the SDK uses it for bucketing in place of the targeting key.
  *     Two users with different targeting keys but the same `$opt_bucketing_id` should bucket identically.
  */
object OptimizelyDecisionEngineEdgeCasesSpec extends ZIOSpecDefault {

  private val DecisionEdgesSdkKey = "it_decision_edges"

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Optimizely decision-engine edge cases")(
    test("json variable type — getObjectEvaluation returns Structure with parsed JSON tree") {
      withReadyProvider(DecisionEdgesSdkKey) { provider =>
        val ctx       = userContext("user-json")
        val ev        = provider.getObjectEvaluation("it_json_flag", new Value(), ctx)
        val structure = ev.getValue.asStructure()
        // Our provider builds a Structure from `decision.getVariables.toMap`. For a `type: json` variable named
        // `value`, the SDK exposes the parsed object under the "value" key in that map.
        val nested = structure.getValue("value")
        assert(nested)(not(isNull)) &&
        assert(ev.getReason)(equalTo(Reason.TARGETING_MATCH.name())) &&
        assert(ev.getErrorCode)(isNull)
      }
    },
    test("featureEnabled=false in rollout default rule — boolean is false, variable returns flag's default (not variation's override)") {
      withReadyProvider(DecisionEdgesSdkKey) { provider =>
        // Documented Optimizely behaviour: when `featureEnabled=false`, the SDK ignores the variation's variable
        // override and returns the flag-declaration's `defaultValue` ("default" here, not "off-value"). Captured as a
        // test so future SDK upgrades don't silently change it without us noticing.
        val ctx    = userContext("user-disabled")
        val boolEv = provider.getBooleanEvaluation("it_disabled_flag", java.lang.Boolean.TRUE, ctx)
        val strEv  = provider.getStringEvaluation("it_disabled_flag", "fallback", ctx)
        assert(boolEv.getValue)(equalTo(java.lang.Boolean.FALSE)) &&
        assert(boolEv.getVariant)(equalTo("off")) &&
        assert(strEv.getValue)(equalTo("default")) &&
        assert(strEv.getVariant)(equalTo("off"))
      }
    },
    test("$opt_bucketing_id reserved attribute — two users with the same bucketing id land in the same variation") {
      withReadyProvider(DecisionEdgesSdkKey) { provider =>
        val ctxA = new MutableContext("user-a").add("$opt_bucketing_id", "shared-bucket")
        val ctxB = new MutableContext("user-b").add("$opt_bucketing_id", "shared-bucket")
        val variantA = provider.getStringEvaluation("it_bucketing_flag", "fallback", ctxA).getVariant
        val variantB = provider.getStringEvaluation("it_bucketing_flag", "fallback", ctxB).getVariant
        assert(variantA)(isNonEmptyString) &&
        assert(variantA)(equalTo(variantB))
      }
    },
    test("$opt_bucketing_id — different bucketing ids can land in different variations (sanity for the override)") {
      withReadyProvider(DecisionEdgesSdkKey) { provider =>
        // With a 50/50 split, two distinct bucketing ids should give different variations across some sample.
        // The test passes as long as we see at least both variations across many ids — proves the SDK is actually
        // bucketing on $opt_bucketing_id, not on the targeting key.
        val variants = (0 until 50).map { i =>
          val ctx = new MutableContext(s"user-fixed").add("$opt_bucketing_id", s"bucket-$i")
          provider.getStringEvaluation("it_bucketing_flag", "fallback", ctx).getVariant
        }
        val distinct = variants.toSet
        assert(distinct.size)(isGreaterThanEqualTo(2))
      }
    }
  ) @@ OptimizelyItStack.ifDockerAvailable @@ TestAspect.sequential @@ TestAspect.timeout(2.minutes) @@ TestAspect.withLiveClock
}
