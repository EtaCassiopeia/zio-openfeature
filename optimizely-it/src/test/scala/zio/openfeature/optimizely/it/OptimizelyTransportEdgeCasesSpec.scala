package zio.openfeature.optimizely.it

import dev.openfeature.sdk.{ImmutableContext, ProviderState}
import eu.rekawek.toxiproxy.model.ToxicDirection
import zio._
import zio.openfeature.optimizely.FastFailProviderHarness
import zio.openfeature.optimizely.it.RealOptimizelySupport._
import zio.test.Assertion._
import zio.test._

import java.time.Duration

/** Extra transport-layer fault scenarios beyond what `OptimizelyAuthFailureSpec` already covers (404, latency, reset,
  * proxy disabled). Uses Toxiproxy's bandwidth/limit_data/upstream-direction toxics to probe how the SDK reacts to
  * misbehaving CDNs that don't fail outright but cripple the response.
  */
object OptimizelyTransportEdgeCasesSpec extends ZIOSpecDefault {

  private val ShortInitWait: Duration  = Duration.ofMillis(800)
  private val ShortBlocking: Duration  = Duration.ofMillis(500)

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

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Optimizely extra transport edge cases")(
    test("bandwidth toxic (1 KB/s) past initWait -> init fails, state not READY") {
      // 1 KB/s downstream — even our small datafile takes seconds to stream. Should outlast the 800ms initWait.
      OptimizelyItStack.withToxic(_.toxics().bandwidth("slow-bw", ToxicDirection.DOWNSTREAM, 1L)) {
        withFastFailProvider(BasicSdkKey) { provider =>
          val outcome = tryInit(provider)
          val state   = stateOf(provider)
          assert(outcome.isLeft)(isTrue) &&
          assert(state)(not(equalTo(ProviderState.READY)))
        }
      }
    },
    test("limit_data toxic — connection closes after 64 bytes; partial JSON should fail parse -> init not READY") {
      // 64 bytes is enough to start the response but not finish parsing. Either the connection breaks mid-read
      // (SDK sees an IOException) or the SDK reads partial JSON and throws JsonParseException. Either way init
      // can't reach READY.
      OptimizelyItStack.withToxic(_.toxics().limitData("truncate", ToxicDirection.DOWNSTREAM, 64L)) {
        withFastFailProvider(BasicSdkKey) { provider =>
          val outcome = tryInit(provider)
          val state   = stateOf(provider)
          assert(outcome.isLeft)(isTrue) &&
          assert(state)(not(equalTo(ProviderState.READY)))
        }
      }
    },
    test("upstream-direction latency toxic — request takes longer than initWait to reach nginx -> init not READY") {
      // 5 seconds upstream latency means the request takes that long to arrive. With initWait=800ms we time out.
      OptimizelyItStack.withToxic(_.toxics().latency("slow-up", ToxicDirection.UPSTREAM, 5000L)) {
        withFastFailProvider(BasicSdkKey) { provider =>
          val outcome = tryInit(provider)
          val state   = stateOf(provider)
          assert(outcome.isLeft)(isTrue) &&
          assert(state)(not(equalTo(ProviderState.READY)))
        }
      }
    }
  ) @@ OptimizelyItStack.ifDockerAvailable @@ TestAspect.sequential @@ TestAspect.timeout(2.minutes) @@ TestAspect.withLiveClock
}
