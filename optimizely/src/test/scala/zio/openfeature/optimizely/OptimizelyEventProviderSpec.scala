package zio.openfeature.optimizely

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.optimizely.ab.Optimizely
import com.optimizely.ab.config.HttpProjectConfigManager
import dev.openfeature.sdk.OpenFeatureAPI
import zio._
import zio.test._

import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

/** Verifies OpenFeature spec compliance for the event-provider surface of `OptimizelyFeatureProvider`:
  *
  *   - `getMetadata().getName()` returns the expected `"Optimizely"` name.
  *   - `PROVIDER_READY` event fires when the provider transitions to READY through `OpenFeatureAPI.setProvider`.
  *   - `PROVIDER_ERROR` event fires when init fails.
  *
  * WireMock-backed so it runs without Docker on every PR. Each test uses a unique domain name to avoid cross-test
  * leakage in the global `OpenFeatureAPI` state and explicitly tears the API down on cleanup.
  */
object OptimizelyEventProviderSpec extends ZIOSpecDefault {

  private val DatafilePath  = "/datafiles/events-key.json"
  private val ValidDatafile = readResource("/test-datafile-with-flag.json")

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

  private def buildClient(
    server: WireMockServer,
    blockingTimeout: java.time.Duration = java.time.Duration.ofSeconds(2),
    pollingInterval: java.time.Duration = java.time.Duration.ofSeconds(3600)
  ): Optimizely = {
    val mgr = HttpProjectConfigManager
      .builder()
      .withSdkKey("events-key")
      .withUrl(datafileUrl(server))
      .withBlockingTimeout(blockingTimeout.toMillis, TimeUnit.MILLISECONDS)
      .withPollingInterval(pollingInterval.toSeconds, TimeUnit.SECONDS)
      .build()
    Optimizely.builder().withConfigManager(mgr).build()
  }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("OptimizelyFeatureProvider OpenFeature compliance")(
    test("provider metadata exposes the canonical name 'Optimizely'") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafile)))
        val provider =
          new OptimizelyFeatureProvider(buildClient(server), java.time.Duration.ofSeconds(3), closeOnShutdown = true)
        try {
          @scala.annotation.nowarn("msg=deprecated")
          val name = provider.getMetadata.getName
          assertTrue(name == OptimizelyFeatureProvider.Name, name == "Optimizely")
        } finally provider.shutdown()
      }
    },
    test("PROVIDER_READY event fires on successful init through OpenFeatureAPI") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafile)))
        val provider =
          new OptimizelyFeatureProvider(buildClient(server), java.time.Duration.ofSeconds(3), closeOnShutdown = true)
        val api    = OpenFeatureAPI.getInstance()
        val domain = s"optimizely-ready-${UUID.randomUUID()}"
        val ready  = new CountDownLatch(1)
        try {
          val client = api.getClient(domain)
          client.onProviderReady(_ => ready.countDown())
          api.setProvider(domain, provider)
          val received = ready.await(10, TimeUnit.SECONDS)
          assertTrue(received)
        } finally {
          scala.util.Try(api.shutdown())
          ()
        }
      }
    },
    test("PROVIDER_ERROR event fires when init fails (404 datafile)") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(aResponse().withStatus(404).withBody("Not Found")))
        val provider = new OptimizelyFeatureProvider(
          buildClient(server, blockingTimeout = java.time.Duration.ofMillis(500)),
          java.time.Duration.ofMillis(800),
          closeOnShutdown = true
        )
        val api    = OpenFeatureAPI.getInstance()
        val domain = s"optimizely-error-${UUID.randomUUID()}"
        val error  = new CountDownLatch(1)
        val ready  = new AtomicInteger(0)
        try {
          val client = api.getClient(domain)
          client.onProviderError(_ => error.countDown())
          client.onProviderReady { _ =>
            val _ = ready.incrementAndGet()
          }
          api.setProvider(domain, provider)
          val gotError    = error.await(5, TimeUnit.SECONDS)
          val gotReadyAny = ready.get()
          assertTrue(gotError, gotReadyAny == 0)
        } finally {
          scala.util.Try(api.shutdown())
          ()
        }
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds) @@ TestAspect.withLiveClock
}
