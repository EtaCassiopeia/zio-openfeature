package zio.openfeature.optimizely

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.optimizely.ab.Optimizely
import com.optimizely.ab.config.HttpProjectConfigManager
import com.optimizely.ab.notification.NotificationCenter
import dev.openfeature.sdk.{ImmutableContext, OpenFeatureAPI, ProviderState}
import zio._
import zio.test._

import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

/** Race and retry tests for `OptimizelyFeatureProvider` covering the three lifecycle defects fixed in #265:
  *   1. A failed `initialize()` leaves the provider retryable (`Failed -> Initialized`) instead of a silent no-op.
  *   1. `shutdown()` racing an in-flight `initialize()` never leaves the provider reporting READY.
  *   1. A handler fire before the provider is READY (the initial datafile load) does not emit a spurious
  *      `PROVIDER_CONFIGURATION_CHANGED`; only fires after READY (genuine revisions) do.
  *
  * WireMock-backed so the suite runs on every PR without Docker.
  */
object OptimizelyLifecycleRaceSpec extends ZIOSpecDefault {

  private val DatafilePath    = "/datafiles/race-key.json"
  private val ValidDatafileV1 = readResource("/test-datafile-with-flag.json")
  private val targetedContext = new ImmutableContext("user-race")

  private def readResource(path: String): String =
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(path)).mkString

  private def withMockServer[A](body: WireMockServer => A): A = {
    val server = new WireMockServer(WireMockConfiguration.options().dynamicPort())
    server.start()
    try body(server)
    finally server.stop()
  }

  private def datafileUrl(server: WireMockServer): String =
    s"http://localhost:${server.port()}$DatafilePath"

  @scala.annotation.nowarn("msg=deprecated")
  private def stateOf(p: OptimizelyFeatureProvider): ProviderState = p.getState

  private def tryInit(provider: OptimizelyFeatureProvider): Either[Throwable, Unit] =
    try { provider.initialize(new ImmutableContext()); Right(()) }
    catch { case t: Throwable => Left(t) }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("OptimizelyFeatureProvider lifecycle races (#265)")(
    test("failed init is retryable — a second initialize after the datafile recovers succeeds (#265.1)") {
      withMockServer { server =>
        // The datafile source is broken (500) to start; the SDK's continuous poller survives fetch failures (the
        // fetch task catches and logs), so a later re-stub is picked up on a subsequent tick.
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(aResponse().withStatus(500).withBody("boom")))
        // Share one NotificationCenter between the manager and the client (as production `buildClient` does) so the
        // handler our `initialize` registers actually sees the poller's datafile-update notification on recovery.
        val sharedCenter = new NotificationCenter()
        // `.build()` runs a continuous poller (not paused); a short interval means recovery is observed quickly.
        val mgr = HttpProjectConfigManager
          .builder()
          .withSdkKey("race-key")
          .withUrl(datafileUrl(server))
          .withBlockingTimeout(1000L, TimeUnit.MILLISECONDS)
          .withPollingInterval(1L, TimeUnit.SECONDS)
          .withNotificationCenter(sharedCenter)
          .withOptimizelyHttpClient(TestHttpClient.failFast())
          .build()
        val client   = Optimizely.builder().withConfigManager(mgr).withNotificationCenter(sharedCenter).build()
        val provider = new OptimizelyFeatureProvider(client, java.time.Duration.ofSeconds(5), closeOnShutdown = true)
        try {
          val first          = tryInit(provider) // throws: datafile never loads within initWait
          val stateAfterFail = stateOf(provider)
          // Recover the datafile source; the still-running poller fetches a valid file on its next tick. A newest-wins
          // re-stub (not resetAll) avoids dropping the poller's keep-alive connections.
          server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafileV1)))
          val second          = tryInit(provider) // clean retry from Failed
          val stateAfterRetry = stateOf(provider)
          val eval = provider.getBooleanEvaluation("lifecycle_flag", java.lang.Boolean.FALSE, targetedContext)
          assertTrue(
            first.isLeft,
            stateAfterFail == ProviderState.ERROR,
            // Against the pre-#265 code the retry silently no-ops (matches neither Fresh nor ShutDown) and the
            // provider stays not-ready forever. The fix re-attempts cleanly from the Failed state.
            second.isRight,
            stateAfterRetry == ProviderState.READY,
            eval.getValue == java.lang.Boolean.TRUE
          )
        } finally provider.shutdown()
      }
    },
    test("handler fire for the initial datafile load emits no CONFIGURATION_CHANGED event (#265.3)") {
      withMockServer { server =>
        // Serve v1 with a fixed delay so `initialize` registers its handler BEFORE the initial datafile load arrives —
        // the handler therefore genuinely catches the initial-load notification (the case the old code emitted a
        // spurious event on). Only v1 is ever served: its revision never changes, so the SDK notifies exactly once (the
        // initial load) and no legitimate revision event can occur. Post-fix that single fire is suppressed (pre-READY);
        // pre-fix it emitted a spurious CONFIGURATION_CHANGED. This makes the assertion deterministic — the only
        // possible event is the very one under test.
        server.stubFor(
          get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafileV1).withFixedDelay(800))
        )

        val sharedCenter = new NotificationCenter()
        // `build()` here would block on the delayed first fetch; `build(true)` starts the continuous poller without
        // blocking, so the provider can register its handler before the (delayed) initial load arrives.
        val configManager = HttpProjectConfigManager
          .builder()
          .withSdkKey("race-event-key")
          .withUrl(datafileUrl(server))
          .withBlockingTimeout(2000L, TimeUnit.MILLISECONDS)
          .withPollingInterval(1L, TimeUnit.SECONDS)
          .withNotificationCenter(sharedCenter)
          // Timeout comfortably above the 800ms datafile delay so the initial fetch reliably completes (a socket
          // timeout racing the delay would otherwise fail the initial load nondeterministically).
          .withOptimizelyHttpClient(TestHttpClient.failFast(2000))
          .build(true)
        val optimizely =
          Optimizely.builder().withConfigManager(configManager).withNotificationCenter(sharedCenter).build()
        val provider =
          new OptimizelyFeatureProvider(optimizely, java.time.Duration.ofSeconds(5), closeOnShutdown = true)
        val api      = OpenFeatureAPI.getInstance()
        val domain   = s"optimizely-firstfire-${UUID.randomUUID()}"
        val received = new AtomicInteger(0)
        val bumped   = new CountDownLatch(1)
        try {
          val client = api.getClient(domain)
          client.onProviderConfigurationChanged { _ =>
            received.incrementAndGet()
            bumped.countDown()
          }
          // Blocks until the provider transitions to READY against the (delayed) initial v1 load, which the handler
          // catches while init is still parked pre-READY. Since only v1 is ever served, the initial load is the ONLY
          // notification the SDK can raise — so if any CONFIGURATION_CHANGED is delivered, it is the spurious initial
          // one. Wait up to 2s for such a delivery: post-fix none arrives (suppressed); pre-fix the initial-load emit
          // does.
          api.setProviderAndWait(domain, provider)
          val anyEvent = bumped.await(2, TimeUnit.SECONDS)
          assertTrue(!anyEvent, received.get() == 0)
        } finally {
          scala.util.Try(api.shutdown())
          ()
        }
      }
    },
    test("shutdown racing initialize never leaves the provider READY (#265.2)") {
      withMockServer { server =>
        // A delayed datafile keeps initialize in-flight so a concurrent shutdown genuinely interleaves.
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafileV1).withFixedDelay(300)))
        val mgr = HttpProjectConfigManager
          .builder()
          .withSdkKey("race-key")
          .withUrl(datafileUrl(server))
          .withBlockingTimeout(2000L, TimeUnit.MILLISECONDS)
          .withPollingInterval(3600L, TimeUnit.SECONDS)
          .withOptimizelyHttpClient(TestHttpClient.failFast())
          .build(true)
        mgr.stop()
        val client = Optimizely.builder().withConfigManager(mgr).build()
        val provider =
          new OptimizelyFeatureProvider(
            client,
            java.time.Duration.ofSeconds(2),
            closeOnShutdown = true,
            configManager = Some(mgr)
          )
        try {
          val release  = new CountDownLatch(1)
          val initDone = new AtomicInteger(0)
          val initThread = new Thread(() => {
            release.await()
            try provider.initialize(new ImmutableContext())
            catch { case _: Throwable => () } // failInitialize against the closing client is an acceptable outcome
            initDone.incrementAndGet()
            ()
          })
          val shutdownThread = new Thread(() => {
            release.await()
            provider.shutdown()
          })
          initThread.start()
          shutdownThread.start()
          release.countDown()
          initThread.join(10000)
          shutdownThread.join(10000)
          val finalState = stateOf(provider)
          // Invariant across every interleaving: a provider that saw a shutdown must never end up READY. It is
          // NOT_READY (abort or post-success revert) or ERROR (failed load against the closing client).
          assertTrue(
            initDone.get() == 1,
            finalState != ProviderState.READY,
            finalState == ProviderState.NOT_READY || finalState == ProviderState.ERROR
          )
        } finally provider.shutdown()
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds) @@ TestAspect.withLiveClock
}
