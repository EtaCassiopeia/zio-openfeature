package zio.openfeature.optimizely.it

import dev.openfeature.sdk.{ImmutableContext, ProviderState}
import zio._
import zio.openfeature.optimizely.FastFailProviderHarness
import zio.openfeature.optimizely.it.RealOptimizelySupport._
import zio.test.Assertion._
import zio.test._

import java.time.Duration

/** Datafile-churn resilience scenarios.
  *
  * Only the baseline scenario is enabled here. The originally-planned scenarios — swap-to-v2 picked up by the next
  * poll, swap-to-malformed staying READY on last-good, and Toxiproxy-disabled staying READY on cache — all depend on
  * the SDK's polling thread. The Optimizely Java SDK enforces a hardcoded minimum polling interval of 30 seconds, so
  * those tests need ~45–90 seconds per case and have proven flaky across sequential runs (the second + third providers
  * intermittently fail to load v1 within their init window after the first provider's prolonged convergence wait).
  *
  * They're left as TODOs so the foundation work in this module can ship; the in-process WireMock spec already covers
  * datafile-revision polling (`datafile revision change triggers a second fetch with the new revision` in
  * `OptimizelyProviderIntegrationSpec`) so the gap is bounded.
  */
object OptimizelyDatafileChurnSpec extends ZIOSpecDefault {

  private val InitWait: Duration        = Duration.ofSeconds(10)
  private val BlockingTimeout: Duration = Duration.ofSeconds(5)
  // SDK enforces a minimum polling interval of 30 seconds.
  private val PollingInterval: Duration = Duration.ofSeconds(30)

  @scala.annotation.nowarn("msg=deprecated")
  private def stateOf(p: zio.openfeature.optimizely.OptimizelyFeatureProvider): ProviderState = p.getState

  private def fixtureContent(sdkKey: String): String =
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(s"/datafiles/$sdkKey.json")).mkString

  private def withChurnProvider[A](
    body: zio.openfeature.optimizely.OptimizelyFeatureProvider => A
  ): A = {
    // Reset the runtime file to v1 so the test starts from a known state regardless of what the previous test did.
    OptimizelyItStack.swapDatafile(BasicSdkKey, fixtureContent(BasicSdkKey))
    val client = FastFailProviderHarness.buildFastFailClient(
      BasicSdkKey,
      OptimizelyItStack.datafileUrl(BasicSdkKey),
      BlockingTimeout,
      PollingInterval
    )
    val provider = FastFailProviderHarness.newProvider(client, InitWait)
    try {
      provider.initialize(new ImmutableContext())
      body(provider)
    } finally provider.shutdown()
  }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Optimizely datafile churn")(
    test("baseline: v1 loaded via the docker-compose stack and decisions match v1 values") {
      withChurnProvider { provider =>
        val ctx     = userContext("user-churn-1")
        val string1 = provider.getStringEvaluation(StringFlagKey, "fallback", ctx).getValue
        val state   = stateOf(provider)
        assert(state)(equalTo(ProviderState.READY)) &&
        assert(string1)(equalTo(StringFlagExpectedValue))
      }
    }
  ) @@ OptimizelyItStack.ifDockerAvailable @@ TestAspect.sequential @@ TestAspect.timeout(
    2.minutes
  ) @@ TestAspect.withLiveClock
}
