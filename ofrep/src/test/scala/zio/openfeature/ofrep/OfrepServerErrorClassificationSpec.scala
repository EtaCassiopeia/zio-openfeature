package zio.openfeature.ofrep

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import zio._
import zio.openfeature._
import zio.openfeature.testsupport.LoopbackWireMock
import zio.test._
import zio.test.TestAspect.{sequential, timeout, withLiveClock}

/** A 5xx must fail with `ProviderError` whatever the flag key (docs/ofrep.md). The OFREP provider quotes the key in its
  * `GENERAL` message, and the auth heuristic must not read words such as "forbidden" or a hyphen-bounded "403" in that
  * key as an auth failure.
  */
object OfrepServerErrorClassificationSpec extends ZIOSpecDefault {

  private val server: ZIO[Scope, Throwable, WireMockServer] =
    ZIO.acquireRelease(ZIO.attempt { val s = LoopbackWireMock.server(); s.start(); s })(s => ZIO.succeed(s.stop()))

  private def flags(s: WireMockServer): ZIO[Scope, Throwable, FeatureFlags] =
    for {
      provider <- OFREPProvider.make(LoopbackWireMock.baseUrl(s))
      env      <- FeatureFlags.fromProvider(provider).build
      ff = env.get[FeatureFlags]
      _ <- ff.providerStatus
        .repeat(Schedule.recurUntil[ProviderStatus](_ == ProviderStatus.Ready) && Schedule.spaced(20.millis))
        .timeoutFail(new IllegalStateException("not ready"))(10.seconds)
    } yield ff

  private def stub(s: WireMockServer, key: String, status: Int, body: String): Unit = {
    val _ = s.stubFor(
      post(urlEqualTo(s"/ofrep/v1/evaluate/flags/$key"))
        .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body))
    )
  }

  private def isProviderError(e: Either[FeatureFlagError, Any]): Boolean = e match {
    case Left(_: FeatureFlagError.ProviderError) => true
    case _                                       => false
  }

  private val serverError = """{"errorCode":"GENERAL","errorDetails":"boom"}"""

  def spec = suite("OfrepServerErrorClassificationSpec")(
    test("control: a 500 for an ordinary key is ProviderError") {
      ZIO.scoped {
        for {
          s <- server
          _ = stub(s, "checkout-banner", 500, serverError)
          ff <- flags(s)
          r  <- ff.booleanDetails("checkout-banner", default = false).either
        } yield assertTrue(isProviderError(r))
      }
    },
    test("a 500 for the key `forbidden-words-filter` is ProviderError") {
      ZIO.scoped {
        for {
          s <- server
          _ = stub(s, "forbidden-words-filter", 500, serverError)
          ff <- flags(s)
          r  <- ff.booleanDetails("forbidden-words-filter", default = false).either
        } yield assertTrue(isProviderError(r))
      }
    },
    test("a 503 for the key `custom-403-page` is ProviderError") {
      ZIO.scoped {
        for {
          s <- server
          _ = stub(s, "custom-403-page", 503, serverError)
          ff <- flags(s)
          r  <- ff.booleanDetails("custom-403-page", default = false).either
        } yield assertTrue(isProviderError(r))
      }
    }
  ) @@ sequential @@ withLiveClock @@ timeout(120.seconds)
}
