package zio.openfeature.optimizely

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.stubbing.Scenario
import com.optimizely.ab.Optimizely
import com.optimizely.ab.config.HttpProjectConfigManager
import dev.openfeature.sdk.{ImmutableContext, ProviderState}
import zio._
import zio.test._
import java.util.concurrent.TimeUnit

/** Failure-mode integration suite for the Optimizely provider, driven by a WireMock server impersonating the Optimizely
  * CDN. Each test owns its own WireMock instance so the stubs are isolated.
  *
  * What this spec covers (issue #136):
  *   - Happy path: valid datafile → provider reaches READY.
  *   - 403 / 404 / 500: HTTP errors → provider fails to initialize.
  *   - Slow response past `initWait` → provider fails to initialize.
  *   - Connection reset (WireMock Fault) → provider fails to initialize.
  *   - Datafile change (revision 1 → 2) → SDK observes a second fetch with the new revision.
  *
  * The Optimizely SDK polls its datafile URL on a background thread; tests use short `blockingTimeout` values so
  * failure-mode tests fail fast. Tests are sequenced and individually time-bounded to keep wall-clock under control
  * across the full suite.
  *
  * Everything happens synchronously inside `withMockServer` — the server must be live for the duration of any provider
  * call, so deferring work to a ZIO effect that runs after the `finally` block would deadlock/explode against a stopped
  * server.
  */
object OptimizelyProviderIntegrationSpec extends ZIOSpecDefault {

  private val DatafilePath = "/datafiles/test-sdk-key.json"

  private val ValidDatafileV1 = readResource("/test-datafile-v1.json")
  private val ValidDatafileV2 = readResource("/test-datafile-v2.json")

  private val emptyContext = new ImmutableContext()

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

  /** Build an Optimizely client pointed at WireMock with aggressive timeouts so failure-mode tests fail fast. */
  private def buildClient(
    server: WireMockServer,
    blockingTimeout: java.time.Duration = java.time.Duration.ofMillis(800),
    pollingInterval: java.time.Duration = java.time.Duration.ofSeconds(3600),
    keepPolling: Boolean = false
  ): Optimizely = {
    val configManager = HttpProjectConfigManager
      .builder()
      .withSdkKey("test-sdk-key")
      .withUrl(datafileUrl(server))
      .withBlockingTimeout(blockingTimeout.toMillis, TimeUnit.MILLISECONDS)
      .withPollingInterval(pollingInterval.toSeconds, TimeUnit.SECONDS)
      .build()
    // The blocking build() has already attempted the initial datafile fetch. Unless a test specifically exercises
    // ongoing polling, halt the poller now so it cannot keep retrying against a stopped WireMock server and leave a
    // non-daemon Apache HttpClient thread that prevents the test JVM from exiting (hanging CI until its timeout).
    if (!keepPolling) configManager.stop()
    Optimizely.builder().withConfigManager(configManager).build()
  }

  @scala.annotation.nowarn("msg=deprecated")
  private def stateOf(p: OptimizelyFeatureProvider): ProviderState = p.getState

  /** Build a provider via `fromOptimizelyClient` and run a synchronous body with it, ensuring shutdown on every path.
    *
    * The body MUST return a `TestResult` (or any plain value), not a ZIO — if it returned a ZIO the deferred work would
    * execute after `shutdown()` flips state back to `NOT_READY` and any state-based assertion would lie.
    */
  private def withProvider[A](
    client: Optimizely,
    initWait: java.time.Duration = java.time.Duration.ofMillis(800)
  )(body: OptimizelyFeatureProvider => A): A = {
    val provider = new OptimizelyFeatureProvider(client, initWait, closeOnShutdown = true)
    try body(provider)
    finally provider.shutdown()
  }

  /** Call `initialize()` and capture either the throw or the success. */
  private def tryInit(provider: OptimizelyFeatureProvider): Either[Throwable, Unit] =
    try { provider.initialize(emptyContext); Right(()) }
    catch { case e: Throwable => Left(e) }

  def spec = suite("OptimizelyProvider integration (WireMock)")(
    test("happy path — valid datafile -> provider reaches READY") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafileV1)))
        withProvider(buildClient(server)) { provider =>
          val outcome = tryInit(provider)
          val state   = stateOf(provider)
          assertTrue(outcome.isRight, state == ProviderState.READY)
        }
      }
    },
    test("403 on datafile fetch -> initialize throws after blocking timeout") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(aResponse().withStatus(403).withBody("Forbidden")))
        withProvider(buildClient(server)) { provider =>
          val outcome = tryInit(provider)
          val state   = stateOf(provider)
          assertTrue(outcome.isLeft, state != ProviderState.READY)
        }
      }
    },
    test("404 on datafile fetch -> initialize throws after blocking timeout") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(aResponse().withStatus(404).withBody("Not Found")))
        withProvider(buildClient(server)) { provider =>
          val outcome = tryInit(provider)
          val state   = stateOf(provider)
          assertTrue(outcome.isLeft, state != ProviderState.READY)
        }
      }
    },
    test("500 on datafile fetch -> initialize throws after blocking timeout") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(aResponse().withStatus(500).withBody("Server Error")))
        withProvider(buildClient(server)) { provider =>
          val outcome = tryInit(provider)
          val state   = stateOf(provider)
          assertTrue(outcome.isLeft, state != ProviderState.READY)
        }
      }
    },
    test("slow response past initWait -> initialize throws") {
      withMockServer { server =>
        // The Optimizely client blocking timeout is 200ms; the WireMock delay is 5s. Our initWait is 300ms.
        // Whichever fires first should leave the provider not-READY.
        server.stubFor(
          get(urlEqualTo(DatafilePath))
            .willReturn(okJson(ValidDatafileV1).withFixedDelay(5000))
        )
        val client = buildClient(server, blockingTimeout = java.time.Duration.ofMillis(200), keepPolling = true)
        withProvider(client, initWait = java.time.Duration.ofMillis(300)) { provider =>
          val outcome = tryInit(provider)
          val state   = stateOf(provider)
          assertTrue(outcome.isLeft, state != ProviderState.READY)
        }
      }
    } @@ TestAspect.ifEnvNotSet("CI"),
    test("connection reset by peer -> initialize throws") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))
        withProvider(buildClient(server)) { provider =>
          val outcome = tryInit(provider)
          val state   = stateOf(provider)
          assertTrue(outcome.isLeft, state != ProviderState.READY)
        }
      }
    },
    test("datafile revision change triggers a second fetch with the new revision") {
      withMockServer { server =>
        server.stubFor(
          get(urlEqualTo(DatafilePath))
            .inScenario("revision-bump")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson(ValidDatafileV1))
            .willSetStateTo("v2-served")
        )
        server.stubFor(
          get(urlEqualTo(DatafilePath))
            .inScenario("revision-bump")
            .whenScenarioStateIs("v2-served")
            .willReturn(okJson(ValidDatafileV2))
        )
        val client = buildClient(server, pollingInterval = java.time.Duration.ofSeconds(1), keepPolling = true)
        withProvider(client) { provider =>
          val outcome = tryInit(provider)
          // Poll for a second request triggered by the SDK's background poller. We bound the wait so a real
          // regression surfaces as a test failure instead of a hang.
          val deadline = java.lang.System.currentTimeMillis() + 5000L
          while (
            server.findAll(getRequestedFor(urlEqualTo(DatafilePath))).size() < 2 &&
            java.lang.System.currentTimeMillis() < deadline
          ) Thread.sleep(100)
          val requestCount = server.findAll(getRequestedFor(urlEqualTo(DatafilePath))).size()
          val state        = stateOf(provider)
          assertTrue(
            outcome.isRight,
            state == ProviderState.READY,
            requestCount >= 2
          )
        }
      }
    } @@ TestAspect.ifEnvNotSet("CI")
  ) @@ TestAspect.sequential @@ TestAspect.timeout(45.seconds) @@ TestAspect.withLiveClock
}
