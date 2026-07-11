package zio.openfeature.optimizely

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import dev.openfeature.sdk.{ImmutableContext, OpenFeatureAPI, ProviderState}
import zio._
import zio.test._

import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}

/** Staleness watchdog tests for `OptimizelyFeatureProvider` (#267).
  *
  * After a successful init, the Optimizely SDK's `HttpProjectConfigManager` keeps polling the datafile but surfaces no
  * signal when those polls start failing — it just serves an aging datafile. The provider adds a watchdog that observes
  * datafile-fetch outcomes (via `com.optimizely.ab.ObservingOptimizelyHttpClient`) and, once fetches stop succeeding
  * past `staleAfter`, transitions to `PROVIDER_STALE`; when fetches resume it transitions back to `PROVIDER_READY`.
  *
  * These tests drive that lifecycle end-to-end against WireMock: a healthy datafile to reach READY, then failing (500)
  * responses to induce STALE, then healthy responses again to recover. The STALE/READY transitions are asserted through
  * the OpenFeature event bridge (deterministic latches on `onProviderStale`/`onProviderReady`) rather than fixed
  * sleeps. The one unavoidably timing-sensitive part — waiting for the watchdog to notice — uses generous windows.
  */
object OptimizelyStalenessSpec extends ZIOSpecDefault {

  private val DatafilePath  = "/datafiles/staleness-key.json"
  private val ValidDatafile = readResource("/test-datafile-with-flag.json")
  // The datafile ships a boolean flag `lifecycle_flag` that evaluates enabled=true; we reuse it to prove a STALE
  // provider still serves the last-known value.
  private val FlagKey         = "lifecycle_flag"
  private val targetedContext = new ImmutableContext("user-stale")

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

  private def serveDatafile(server: WireMockServer): Unit = {
    server.resetAll()
    server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafile)))
    ()
  }

  private def serveFailure(server: WireMockServer): Unit = {
    // Reset then stub 500 so every subsequent poll fails at the HTTP level: the observing client sees status >= 400
    // and does NOT advance the last-successful-fetch timestamp, so the watchdog eventually declares STALE.
    server.resetAll()
    server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(aResponse().withStatus(500).withBody("boom")))
    ()
  }

  @scala.annotation.nowarn("msg=deprecated")
  private def stateOf(p: OptimizelyFeatureProvider): ProviderState = p.getState

  private def makeProvider(config: OptimizelyProviderConfig): OptimizelyFeatureProvider =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe
        // Test seam: inject the fail-fast HTTP client so a poll in flight when WireMock stops fails immediately
        // instead of hanging the JVM. The provider still wraps it in the observing client when the watchdog is on.
        .run(OptimizelyProvider.make(config, Some(TestHttpClient.failFast())))
        .getOrThrowFiberFailure()
    }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("OptimizelyFeatureProvider staleness watchdog")(
    test("failing datafile fetches drive READY -> STALE (still evaluable) -> READY on recovery") {
      withMockServer { server =>
        serveDatafile(server)
        val config = OptimizelyProviderConfig(
          sdkKey = "staleness-key",
          datafileUrl = Some(datafileUrl(server)),
          initWait = java.time.Duration.ofSeconds(5),
          pollingInterval = Some(java.time.Duration.ofMillis(500)),
          blockingTimeout = Some(java.time.Duration.ofSeconds(2)),
          staleAfter = Some(java.time.Duration.ofSeconds(2))
        )
        val provider = makeProvider(config)
        val api      = OpenFeatureAPI.getInstance()
        val domain   = s"optimizely-stale-${UUID.randomUUID()}"

        val staleLatch = new CountDownLatch(1)
        // onProviderReady fires on the INITIAL ready too, so we cannot just await it for recovery. Instead the handler
        // always counts down whatever latch is currently installed; we swap in a fresh one right before triggering
        // recovery, so the awaited latch only opens on the post-STALE ready.
        val recoveryLatchRef = new AtomicReference(new CountDownLatch(1))
        try {
          val client = api.getClient(domain)
          client.onProviderStale(_ => staleLatch.countDown())
          client.onProviderReady(_ => recoveryLatchRef.get().countDown())

          // Blocks until the provider reaches READY against the healthy datafile.
          api.setProviderAndWait(domain, provider)
          val readyState = stateOf(provider)

          // Induce failure; wait for the watchdog to flip STALE.
          serveFailure(server)
          val wentStale  = staleLatch.await(20, TimeUnit.SECONDS)
          val staleState = stateOf(provider)
          // A STALE provider must still serve the last-known datafile.
          val staleEval = provider.getBooleanEvaluation(FlagKey, java.lang.Boolean.FALSE, targetedContext)

          // Arm a fresh recovery latch, then heal the CDN and wait for the watchdog to flip back to READY.
          recoveryLatchRef.set(new CountDownLatch(1))
          serveDatafile(server)
          val recovered      = recoveryLatchRef.get().await(20, TimeUnit.SECONDS)
          val recoveredState = stateOf(provider)

          assertTrue(
            readyState == ProviderState.READY,
            wentStale,
            staleState == ProviderState.STALE,
            staleEval.getValue == java.lang.Boolean.TRUE,
            recovered,
            recoveredState == ProviderState.READY
          )
        } finally {
          scala.util.Try(api.shutdown())
          scala.util.Try(provider.shutdown())
          ()
        }
      }
    },
    test("watchdog is disabled when datafile polling is off — provider never goes STALE") {
      withMockServer { server =>
        serveDatafile(server)
        // No pollingInterval => no fetches to observe => no watchdog. staleAfter is irrelevant here (and would be a
        // no-op even if set), but we set it to prove it does not force the watchdog on.
        val config = OptimizelyProviderConfig(
          sdkKey = "no-poll-key",
          datafileUrl = Some(datafileUrl(server)),
          initWait = java.time.Duration.ofSeconds(5),
          pollingInterval = None,
          blockingTimeout = Some(java.time.Duration.ofSeconds(2)),
          staleAfter = Some(java.time.Duration.ofMillis(500))
        )
        val provider   = makeProvider(config)
        val api        = OpenFeatureAPI.getInstance()
        val domain     = s"optimizely-nopoll-${UUID.randomUUID()}"
        val staleCount = new AtomicInteger(0)
        try {
          val client = api.getClient(domain)
          client.onProviderStale(_ => staleCount.incrementAndGet())
          api.setProviderAndWait(domain, provider)
          val readyState = stateOf(provider)

          // Break the CDN and wait well past the (would-be) staleAfter window. With no watchdog running, nothing can
          // flip the provider to STALE.
          serveFailure(server)
          Thread.sleep(3000)

          val finalState = stateOf(provider)
          assertTrue(
            readyState == ProviderState.READY,
            staleCount.get() == 0,
            finalState == ProviderState.READY
          )
        } finally {
          scala.util.Try(api.shutdown())
          scala.util.Try(provider.shutdown())
          ()
        }
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(90.seconds) @@ TestAspect.withLiveClock
}
