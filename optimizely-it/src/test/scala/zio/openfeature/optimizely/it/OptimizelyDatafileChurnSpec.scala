package zio.openfeature.optimizely.it

import dev.openfeature.sdk.{Client, ImmutableContext, OpenFeatureAPI, ProviderState}
import zio._
import zio.openfeature.optimizely.FastFailProviderHarness
import zio.openfeature.optimizely.it.RealOptimizelySupport._
import zio.test.Assertion._
import zio.test._

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Datafile-churn resilience scenarios.
  *
  * Each test uses its own SDK key (so the runtime file is isolated per test) to avoid the cross-test ordering issue
  * that previously made the swap-to-v2 follow-up flaky: a long-running poll-driven test can leave its polling thread in
  * an awkward state for the next test, but per-key files mean the next test's provider hits a clean URL.
  *
  * The SDK enforces a hardcoded 30-second minimum polling interval, so each polling-dependent scenario takes ~45–90 s.
  */
object OptimizelyDatafileChurnSpec extends ZIOSpecDefault {

  private val InitWait: Duration        = Duration.ofSeconds(10)
  private val BlockingTimeout: Duration = Duration.ofSeconds(5)
  // SDK enforces a minimum polling interval of 30 seconds.
  private val PollingInterval: Duration = Duration.ofSeconds(30)
  // Two poll intervals + jitter — enough wall-clock for the SDK to fetch, parse, and apply a new datafile.
  private val ConvergenceMs: Long = 75_000L
  // Time to wait for at least one poll to attempt (and fail or no-op) on the malformed / disabled-proxy cases.
  private val OnePollPlusMs: Long = PollingInterval.toMillis + 5_000L

  @scala.annotation.nowarn("msg=deprecated")
  private def stateOf(p: zio.openfeature.optimizely.OptimizelyFeatureProvider): ProviderState = p.getState

  private def fixtureContent(sdkKey: String): String =
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(s"/datafiles/$sdkKey.json")).mkString

  /** Seed the runtime mount for `sdkKey` from a fixture, then build a fresh provider pointed at that URL. */
  private def withTestProvider[A](sdkKey: String, seedFromKey: String = BasicSdkKey)(
    body: zio.openfeature.optimizely.OptimizelyFeatureProvider => A
  ): A = {
    OptimizelyItStack.swapDatafile(sdkKey, fixtureContent(seedFromKey))
    val client = FastFailProviderHarness.buildFastFailClient(
      sdkKey,
      OptimizelyItStack.datafileUrl(sdkKey),
      BlockingTimeout,
      PollingInterval
    )
    val provider = FastFailProviderHarness.newProvider(client, InitWait)
    try {
      provider.initialize(new ImmutableContext())
      body(provider)
    } finally provider.shutdown()
  }

  /** Poll until `predicate` is true or the deadline elapses; returns whether it converged. */
  private def eventually(timeoutMs: Long = ConvergenceMs, intervalMs: Long = 250)(predicate: () => Boolean): Boolean = {
    val deadline = java.lang.System.currentTimeMillis() + timeoutMs
    var ok       = predicate()
    while (!ok && java.lang.System.currentTimeMillis() < deadline) {
      Thread.sleep(intervalMs)
      ok = predicate()
    }
    ok
  }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Optimizely datafile churn")(
    test("baseline: v1 loaded via the docker-compose stack and decisions match v1 values") {
      withTestProvider("it_churn_baseline") { provider =>
        val ctx     = userContext("user-churn-1")
        val string1 = provider.getStringEvaluation(StringFlagKey, "fallback", ctx).getValue
        val state   = stateOf(provider)
        assert(state)(equalTo(ProviderState.READY)) &&
        assert(string1)(equalTo(StringFlagExpectedValue))
      }
    },
    test("revision bump on next poll updates decisions") {
      withTestProvider("it_churn_revbump") { provider =>
        val ctx    = userContext("user-revbump")
        val before = provider.getStringEvaluation(StringFlagKey, "fallback", ctx).getValue
        OptimizelyItStack.swapDatafile("it_churn_revbump", fixtureContent(V2SdkKey))
        val converged = eventually() { () =>
          provider.getStringEvaluation(StringFlagKey, "fallback", ctx).getValue == "rolled-out-v2"
        }
        val after = provider.getStringEvaluation(StringFlagKey, "fallback", ctx).getValue
        val state = stateOf(provider)
        assert(before)(equalTo(StringFlagExpectedValue)) &&
        assert(converged)(isTrue) &&
        assert(after)(equalTo("rolled-out-v2")) &&
        assert(state)(equalTo(ProviderState.READY))
      }
    },
    test("swap to malformed JSON mid-poll -> provider stays READY, last-good values still served") {
      withTestProvider("it_churn_malformed") { provider =>
        val ctx      = userContext("user-malformed")
        val baseline = provider.getStringEvaluation(StringFlagKey, "fallback", ctx).getValue
        OptimizelyItStack.swapDatafile("it_churn_malformed", fixtureContent(MalformedSdkKey))
        // Give the SDK time for at least one failed-parse poll.
        Thread.sleep(OnePollPlusMs)
        val after = provider.getStringEvaluation(StringFlagKey, "fallback", ctx).getValue
        val state = stateOf(provider)
        assert(baseline)(equalTo(StringFlagExpectedValue)) &&
        assert(after)(equalTo(StringFlagExpectedValue)) &&
        assert(state)(equalTo(ProviderState.READY))
      }
    },
    test("network recovery: Toxiproxy disabled then re-enabled -> next poll succeeds, last-good preserved while down") {
      withTestProvider("it_churn_recovery") { provider =>
        val ctx     = userContext("user-recovery")
        val initial = provider.getStringEvaluation(StringFlagKey, "fallback", ctx).getValue
        val handle  = OptimizelyItStack.disableProxy()
        try {
          // Hold the proxy down across one full poll attempt.
          Thread.sleep(OnePollPlusMs)
          val whileDown = provider.getStringEvaluation(StringFlagKey, "fallback", ctx).getValue
          assert(initial)(equalTo(StringFlagExpectedValue)) &&
          assert(whileDown)(equalTo(StringFlagExpectedValue)) &&
          assert(stateOf(provider))(equalTo(ProviderState.READY))
        } finally handle.close()
        // After re-enable, swap in v2 and verify the next poll picks it up.
        OptimizelyItStack.swapDatafile("it_churn_recovery", fixtureContent(V2SdkKey))
        val converged = eventually() { () =>
          provider.getStringEvaluation(StringFlagKey, "fallback", ctx).getValue == "rolled-out-v2"
        }
        assert(converged)(isTrue)
      }
    },
    test("PROVIDER_CONFIGURATION_CHANGED event fires on real revision change through OpenFeatureAPI") {
      val sdkKey = "it_churn_event"
      // Seed v1 BEFORE the provider is built so the initial fetch resolves.
      OptimizelyItStack.swapDatafile(sdkKey, fixtureContent(BasicSdkKey))
      val client = FastFailProviderHarness.buildFastFailClient(
        sdkKey,
        OptimizelyItStack.datafileUrl(sdkKey),
        BlockingTimeout,
        PollingInterval
      )
      val provider = FastFailProviderHarness.newProvider(client, InitWait)
      val api      = OpenFeatureAPI.getInstance()
      val domain   = s"optimizely-event-test-$sdkKey"
      val received = new AtomicInteger(0)
      val gate     = new CountDownLatch(1)
      try {
        api.setProviderAndWait(domain, provider)
        val ofClient: Client = api.getClient(domain)
        ofClient.onProviderConfigurationChanged { _ =>
          received.incrementAndGet()
          gate.countDown()
        }
        // Trigger a revision change.
        OptimizelyItStack.swapDatafile(sdkKey, fixtureContent(V2SdkKey))
        val fired = gate.await(ConvergenceMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        assert(fired)(isTrue) &&
        assert(received.get())(isGreaterThanEqualTo(1))
      } finally {
        scala.util.Try(api.shutdown())
        scala.util.Try(provider.shutdown())
        ()
      }
    }
  ) @@ OptimizelyItStack.ifDockerAvailable @@ TestAspect.sequential @@ TestAspect.timeout(
    8.minutes
  ) @@ TestAspect.withLiveClock
}
