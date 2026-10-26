package zio.openfeature.optimizely

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import zio._
import zio.openfeature.{FeatureFlags, FeatureFlagsConfig}
import zio.test._

// #455: on an unreachable / rejecting CDN, the provider's own "datafile did not load" error is what the caller sees —
// not the outer FeatureFlags init timeout — because initialize() no longer blocks on `optimizely.isValid` (which waits
// up to the SDK's blocking timeout) before its own bounded wait.
object OptimizelyInitTimeoutSpec extends ZIOSpecDefault {

  private def causeText(t: Throwable): String =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).take(10).map(e => String.valueOf(e.getMessage)).mkString(" | ")

  def spec = suite("OptimizelyInitTimeoutSpec (#455)")(
    test("a rejected datafile fetch fails the build with the provider's specific error, within its init wait") {
      val server = new WireMockServer(WireMockConfiguration.options().dynamicPort())
      ZIO.scoped {
        ZIO
          .acquireRelease(ZIO.succeed { server.start(); server })(s => ZIO.succeed(s.stop()))
          .flatMap { server =>
            server.stubFor(get(urlMatching("/datafiles/.*")).willReturn(aResponse().withStatus(403)))
            val config = OptimizelyProviderConfig(
              sdkKey = "rejected-key",
              datafileUrl = Some(s"http://localhost:${server.port()}/datafiles/rejected-key.json"),
              // These durations are the subject: init must fail with the provider's own error within initWait,
              // before blockingTimeout and the 4s FeatureFlags init timeout. Do not widen them.
              initWait = java.time.Duration.ofSeconds(2),
              blockingTimeout = Some(java.time.Duration.ofSeconds(3))
            )
            for {
              provider <- OptimizelyProvider.scoped(config, Some(TestHttpClient.failFast()))
              start    <- Clock.nanoTime
              result   <- ZIO
                .scoped(
                  FeatureFlags
                    .fromProvider(
                      provider,
                      FeatureFlagsConfig().withInitTimeout(4.seconds),
                      statusRef = None,
                      apiOverride = Some(dev.openfeature.sdk.OpenFeatureAPI.createIsolated())
                    )
                    .build
                )
                .either
              end <- Clock.nanoTime
              text = result.left.toOption.map(causeText).getOrElse("")
            } yield assertTrue(
              result.isLeft,
              text.contains("datafile did not load"),
              !text.contains("Provider initialization exceeded"),
              (end - start) / 1000000L < 3800L
            )
          }
      }
    } @@ TestAspect.withLiveClock,
    test("the provider's default init wait is shorter than the FeatureFlags default init timeout") {
      assertTrue(OptimizelyProvider.DefaultInitWait.toMillis < FeatureFlags.DefaultInitTimeout.toMillis)
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
}
