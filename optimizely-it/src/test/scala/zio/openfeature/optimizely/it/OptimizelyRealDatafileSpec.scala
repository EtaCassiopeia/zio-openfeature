package zio.openfeature.optimizely.it

import dev.openfeature.sdk.{ErrorCode, ProviderState, Reason, Value}
import zio._
import zio.openfeature.optimizely.it.RealOptimizelySupport._
import zio.test.Assertion._
import zio.test._

/** Drives the real Optimizely Java SDK against a docker-compose'd nginx + Toxiproxy stack serving a committed datafile.
  * Covers the boolean / string / int / double / object decision paths, the unknown-flag and variable-type-mismatch
  * branches, and variation-key fallback when a flag has no `"value"` variable.
  *
  * Fixture: `optimizely-it/src/test/resources/datafiles/it_basic.json` (5 flags, all rolled out 100%) and
  * `it_variations.json` (1 flag with no variables, used for variation-key access).
  *
  * Sequential and live-clock for the same reasons as `OptimizelyProviderIntegrationSpec` — the Optimizely SDK keeps
  * order-sensitive state on its internal executor and these tests need wall-clock for the datafile init wait.
  */
object OptimizelyRealDatafileSpec extends ZIOSpecDefault {

  private val TargetingKey = "it-user-1"

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Optimizely real-datafile decisions")(
    test("initialize against committed datafile -> READY") {
      withReadyProvider(BasicSdkKey) { provider =>
        @scala.annotation.nowarn("msg=deprecated")
        val state = provider.getState
        assert(state)(equalTo(ProviderState.READY))
      }
    },
    test("boolean evaluation returns the rolled-out variation's featureEnabled") {
      withReadyProvider(BasicSdkKey) { provider =>
        val ev = provider.getBooleanEvaluation(BoolFlagKey, java.lang.Boolean.FALSE, userContext(TargetingKey))
        assert(ev.getValue)(equalTo(java.lang.Boolean.valueOf(BoolFlagExpectedEnabled))) &&
        assert(ev.getVariant)(equalTo(BoolFlagExpectedVariant)) &&
        assert(ev.getReason)(equalTo(Reason.TARGETING_MATCH.name())) &&
        assert(ev.getErrorCode)(isNull)
      }
    },
    test("string evaluation returns the variation's `value` variable") {
      withReadyProvider(BasicSdkKey) { provider =>
        val ev = provider.getStringEvaluation(StringFlagKey, "default", userContext(TargetingKey))
        assert(ev.getValue)(equalTo(StringFlagExpectedValue)) &&
        assert(ev.getReason)(equalTo(Reason.TARGETING_MATCH.name())) &&
        assert(ev.getErrorCode)(isNull)
      }
    },
    test("integer evaluation returns the variation's typed `value` variable") {
      withReadyProvider(BasicSdkKey) { provider =>
        val ev = provider.getIntegerEvaluation(IntFlagKey, java.lang.Integer.valueOf(-1), userContext(TargetingKey))
        assert(ev.getValue)(equalTo(java.lang.Integer.valueOf(IntFlagExpectedValue))) &&
        assert(ev.getReason)(equalTo(Reason.TARGETING_MATCH.name()))
      }
    },
    test("double evaluation returns the variation's typed `value` variable") {
      withReadyProvider(BasicSdkKey) { provider =>
        val ev = provider.getDoubleEvaluation(DoubleFlagKey, java.lang.Double.valueOf(-1.0), userContext(TargetingKey))
        assert(ev.getValue)(equalTo(java.lang.Double.valueOf(DoubleFlagExpectedValue))) &&
        assert(ev.getReason)(equalTo(Reason.TARGETING_MATCH.name()))
      }
    },
    test("object evaluation returns a Structure with all variation variables") {
      withReadyProvider(BasicSdkKey) { provider =>
        val ev        = provider.getObjectEvaluation(ObjectFlagKey, new Value(), userContext(TargetingKey))
        val structure = ev.getValue.asStructure()
        assert(structure.getValue("name").asString())(equalTo(ObjectFlagExpectedName)) &&
        assert(structure.getValue("level").asInteger().intValue())(equalTo(ObjectFlagExpectedLevel)) &&
        assert(ev.getReason)(equalTo(Reason.TARGETING_MATCH.name()))
      }
    },
    test("unknown flag key -> FLAG_NOT_FOUND, default returned") {
      withReadyProvider(BasicSdkKey) { provider =>
        val ev = provider.getBooleanEvaluation(UnknownFlagKey, java.lang.Boolean.TRUE, userContext(TargetingKey))
        assert(ev.getValue)(equalTo(java.lang.Boolean.TRUE)) &&
        assert(ev.getErrorCode)(equalTo(ErrorCode.FLAG_NOT_FOUND)) &&
        assert(ev.getReason)(equalTo(Reason.ERROR.name()))
      }
    },
    test("type mismatch (string flag queried as integer) -> default with reason DEFAULT") {
      withReadyProvider(BasicSdkKey) { provider =>
        val default = java.lang.Integer.valueOf(99)
        val ev      = provider.getIntegerEvaluation(StringFlagKey, default, userContext(TargetingKey))
        assert(ev.getValue)(equalTo(default)) &&
        assert(ev.getReason)(equalTo(Reason.DEFAULT.name())) &&
        assert(ev.getErrorCode)(isNull)
      }
    },
    test("variation-key fallback when the flag has no `value` variable") {
      withReadyProvider(VariationsSdkKey) { provider =>
        val ev = provider.getStringEvaluation(VariationFlagKey, "fallback", userContext(TargetingKey))
        assert(ev.getValue)(equalTo(VariationFlagExpectedKey)) &&
        assert(ev.getVariant)(equalTo(VariationFlagExpectedKey)) &&
        assert(ev.getReason)(equalTo(Reason.TARGETING_MATCH.name()))
      }
    }
  ) @@ OptimizelyItStack.ifDockerAvailable @@ TestAspect.sequential @@ TestAspect.timeout(
    120.seconds
  ) @@ TestAspect.withLiveClock
}
