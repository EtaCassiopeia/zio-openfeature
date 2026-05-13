package zio.openfeature.optimizely.it

import dev.openfeature.sdk.{ImmutableContext, ProviderState}
import eu.rekawek.toxiproxy.model.ToxicDirection
import zio._
import zio.openfeature.optimizely.FastFailProviderHarness
import zio.openfeature.optimizely.it.RealOptimizelySupport._
import zio.test.Assertion._
import zio.test._

import java.time.Duration

/** Drives the real Optimizely Java SDK at the real Toxiproxy-fronted nginx, injecting network-level faults to verify
  * the provider surfaces them as init failures rather than silent fallbacks.
  *
  * Synthesized HTTP status codes (401 / 403 / 500) are explicitly NOT covered here — those belong to
  * `OptimizelyProviderIntegrationSpec` (WireMock), which is fast, in-process, and runs on every PR. This suite covers
  * transport-layer faults that need a real TCP proxy in the path.
  *
  * The provider is built via [[FastFailProviderHarness]] with a short SDK `blockingTimeout`, otherwise
  * `optimizely.isValid()` (which the provider calls during `initialize`) absorbs the injected latency within its own
  * default 10-second wait and the outer `initWait` never wins the race.
  */
object OptimizelyAuthFailureSpec extends ZIOSpecDefault {

  private val ShortInitWait: Duration = Duration.ofMillis(500)
  private val ShortBlocking: Duration = Duration.ofMillis(300)

  @scala.annotation.nowarn("msg=deprecated")
  private def stateOf(p: zio.openfeature.optimizely.OptimizelyFeatureProvider): ProviderState = p.getState

  private def withFastFailProvider[A](
    sdkKey: String,
    initWait: Duration = ShortInitWait,
    blockingTimeout: Duration = ShortBlocking
  )(body: zio.openfeature.optimizely.OptimizelyFeatureProvider => A): A = {
    val client =
      FastFailProviderHarness.buildFastFailClient(sdkKey, OptimizelyItStack.datafileUrl(sdkKey), blockingTimeout)
    val provider = FastFailProviderHarness.newProvider(client, initWait)
    try body(provider)
    finally provider.shutdown()
  }

  private def tryInit(provider: zio.openfeature.optimizely.OptimizelyFeatureProvider): Either[Throwable, Unit] =
    try { provider.initialize(new ImmutableContext()); Right(()) }
    catch { case t: Throwable => Left(t) }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Optimizely auth / transport failures")(
    test("unknown SDK key (nginx 404) -> init fails, state not READY") {
      // The filename `it_not_present.json` doesn't exist under datafiles/, so nginx returns a real 404.
      withFastFailProvider("it_not_present") { provider =>
        val outcome = tryInit(provider)
        val state   = stateOf(provider)
        assert(outcome.isLeft)(isTrue) &&
        assert(state)(not(equalTo(ProviderState.READY)))
      }
    },
    test("latency toxic past initWait -> init throws, state not READY") {
      OptimizelyItStack.withToxic(_.toxics().latency("slow", ToxicDirection.DOWNSTREAM, 5000)) {
        withFastFailProvider(BasicSdkKey) { provider =>
          val outcome = tryInit(provider)
          val state   = stateOf(provider)
          assert(outcome.isLeft)(isTrue) &&
          assert(state)(not(equalTo(ProviderState.READY)))
        }
      }
    },
    test("RESET_PEER toxic -> init throws, state not READY") {
      OptimizelyItStack.withToxic(_.toxics().resetPeer("reset", ToxicDirection.DOWNSTREAM, 0)) {
        withFastFailProvider(BasicSdkKey) { provider =>
          val outcome = tryInit(provider)
          val state   = stateOf(provider)
          assert(outcome.isLeft)(isTrue) &&
          assert(state)(not(equalTo(ProviderState.READY)))
        }
      }
    },
    test("proxy disabled (connection refused) -> init throws, state not READY") {
      val handle = OptimizelyItStack.disableProxy()
      try
        withFastFailProvider(BasicSdkKey) { provider =>
          val outcome = tryInit(provider)
          val state   = stateOf(provider)
          assert(outcome.isLeft)(isTrue) &&
          assert(state)(not(equalTo(ProviderState.READY)))
        }
      finally handle.close()
    }
  ) @@ OptimizelyItStack.ifDockerAvailable @@ TestAspect.sequential @@ TestAspect.timeout(
    120.seconds
  ) @@ TestAspect.withLiveClock
}
