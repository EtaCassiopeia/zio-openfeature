package zio.openfeature.optimizely

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import dev.openfeature.sdk.ImmutableContext
import zio._
import zio.test._

/** Pins the datafile-polling lifecycle fixed in #208: a factory-built provider performs no network activity at
  * construction, polls only between `initialize()` and `shutdown()`/scope close, and is therefore resilient to an
  * unreachable CDN or bad keys (403): no background retry loop survives the provider's lifecycle.
  */
object OptimizelyPollingLifecycleSpec extends ZIOSpecDefault {

  private val DatafilePath  = "/datafiles/polling-key.json"
  private val ValidDatafile = readResource("/test-datafile-with-flag.json")
  private val emptyContext  = new ImmutableContext()

  private def readResource(path: String): String =
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(path)).mkString

  // ZIO-friendly variant of the other specs' `withMockServer`: those run synchronous bodies, but here the
  // bodies are effects — the server must live until the effect completes, not until the builder returns.
  private def withMockServer[R, A](body: WireMockServer => ZIO[R, Any, A]): ZIO[R, Any, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking {
        val server = new WireMockServer(WireMockConfiguration.options().dynamicPort())
        server.start()
        server
      }
    )(server => ZIO.attemptBlocking(server.stop()).ignore)(body)

  private def datafileUrl(server: WireMockServer): String =
    s"http://localhost:${server.port()}$DatafilePath"

  private def requestCount(server: WireMockServer): Int =
    server.getAllServeEvents.size()

  private def sleepBlocking(d: Duration): UIO[Unit] =
    ZIO.attemptBlocking(Thread.sleep(d.toMillis)).orDie

  private def tryInit(provider: OptimizelyFeatureProvider): Either[Throwable, Unit] =
    try { provider.initialize(emptyContext); Right(()) }
    catch { case t: Throwable => Left(t) }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Optimizely datafile polling lifecycle (#208)")(
    test("make performs no datafile fetch and does not block, even against a 403 CDN") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(aResponse().withStatus(403)))
        for {
          start <- ZIO.succeed(java.lang.System.nanoTime())
          provider <- OptimizelyProvider.make(
            "polling-key-1",
            Some(datafileUrl(server)),
            java.time.Duration.ofSeconds(1)
          )
          tookMs = (java.lang.System.nanoTime() - start) / 1000000L
          // Give any (buggy) background poller a chance to fire before asserting near-silence
          _        <- sleepBlocking(500.millis)
          requests <- ZIO.succeed(requestCount(server))
          _        <- ZIO.succeed(provider.shutdown())
        } yield assertTrue(
          // The old construction path blocked up to the SDK's 10s getConfig timeout
          tookMs < 5000L,
          // `buildClient` uses `build(true)` then `stop()`; the SDK's first scheduled fetch may be submitted in the
          // instant before `stop()` cancels it, so the contract is "at most one aborted request" — not zero. Asserting
          // `== 0` raced that window and flaked across both Scala versions (see #211).
          requests <= 1
        )
      }
    },
    test("polling runs only between initialize and shutdown") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafile)))
        val config = OptimizelyProviderConfig(
          sdkKey = "polling-key-2",
          datafileUrl = Some(datafileUrl(server)),
          initWait = java.time.Duration.ofSeconds(3),
          pollingInterval = Some(java.time.Duration.ofMillis(200))
        )
        for {
          provider   <- OptimizelyProvider.make(config)
          beforeInit <- ZIO.succeed(requestCount(server))
          initResult <- ZIO.succeed(tryInit(provider))
          // pollingInterval is honored: several polls land within the window
          _           <- sleepBlocking(1.second)
          whileActive <- ZIO.succeed(requestCount(server))
          _           <- ZIO.succeed(provider.shutdown())
          afterStop   <- ZIO.succeed(requestCount(server))
          _           <- sleepBlocking(700.millis)
          afterWait   <- ZIO.succeed(requestCount(server))
        } yield assertTrue(
          beforeInit == 0,
          initResult.isRight,
          whileActive >= 3,
          afterWait == afterStop
        )
      }
    },
    test("scoped releases the provider even when initialization failed against a 403 CDN") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(aResponse().withStatus(403)))
        val config = OptimizelyProviderConfig(
          sdkKey = "polling-key-3",
          datafileUrl = Some(datafileUrl(server)),
          initWait = java.time.Duration.ofMillis(400),
          pollingInterval = Some(java.time.Duration.ofMillis(150))
        )
        for {
          initResult <- ZIO.scoped {
            for {
              provider <- OptimizelyProvider.scoped(config)
              result   <- ZIO.succeed(tryInit(provider))
            } yield result
          }
          // Scope is closed: the failed provider must not keep retrying the 403 endpoint in the background
          duringInit <- ZIO.succeed(requestCount(server))
          _          <- sleepBlocking(700.millis)
          afterClose <- ZIO.succeed(requestCount(server))
        } yield assertTrue(
          initResult.isLeft, // datafile never loaded
          duringInit >= 1,   // polling genuinely ran during the init window
          afterClose == duringInit
        )
      }
    },
    test("layer finalizer shuts polling down on release") {
      withMockServer { server =>
        server.stubFor(get(urlEqualTo(DatafilePath)).willReturn(okJson(ValidDatafile)))
        val config = OptimizelyProviderConfig(
          sdkKey = "polling-key-4",
          datafileUrl = Some(datafileUrl(server)),
          initWait = java.time.Duration.ofSeconds(3),
          pollingInterval = Some(java.time.Duration.ofMillis(200))
        )
        for {
          _ <- ZIO.scoped {
            OptimizelyProvider.layer(config).build.flatMap { env =>
              val provider = env.get[OptimizelyFeatureProvider]
              ZIO.succeed(tryInit(provider)) *> sleepBlocking(500.millis)
            }
          }
          afterRelease <- ZIO.succeed(requestCount(server))
          _            <- sleepBlocking(700.millis)
          afterWait    <- ZIO.succeed(requestCount(server))
        } yield assertTrue(afterRelease >= 1, afterWait == afterRelease)
      }
    },
    test("non-positive pollingInterval is rejected at construction") {
      val config = OptimizelyProviderConfig(
        sdkKey = "polling-key-5",
        pollingInterval = Some(java.time.Duration.ZERO)
      )
      OptimizelyProvider.make(config).exit.map { exit =>
        assertTrue(exit.isFailure)
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
}
