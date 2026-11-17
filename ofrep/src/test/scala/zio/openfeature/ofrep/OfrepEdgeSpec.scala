package zio.openfeature.ofrep

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import zio._
import zio.openfeature._
import zio.openfeature.testsupport.LoopbackWireMock
import zio.test._
import zio.test.TestAspect.{sequential, timeout, withLiveClock}

/** OFREP wire edges with no other test: Retry-After suppression, a request attribute beyond 2^53, and flag metadata
  * round-tripping, each through the public `FeatureFlags` API.
  */
object OfrepEdgeSpec extends ZIOSpecDefault {

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

  def spec = suite("OfrepEdgeSpec")(
    suite("429 Retry-After")(
      test("Retry-After in seconds suppresses the next request until it elapses") {
        ZIO.scoped {
          for {
            s <- server
            _ = s.stubFor(
              post(urlEqualTo("/ofrep/v1/evaluate/flags/rl"))
                .willReturn(
                  aResponse()
                    .withStatus(429)
                    .withHeader("Retry-After", "30")
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")
                )
            )
            ff <- flags(s)
            r1 <- ff.booleanDetails("rl", default = false).either
            r2 <- ff.booleanDetails("rl", default = false).either
            n  <- ZIO.succeed(s.findAll(postRequestedFor(urlEqualTo("/ofrep/v1/evaluate/flags/rl"))).size)
          } yield assertTrue(isProviderError(r1), isProviderError(r2), n == 1)
        }
      }
    ),
    suite("values")(
      test("a Long attribute beyond 2^53 reaches the server exactly") {
        ZIO.scoped {
          for {
            s <- server
            _ = stub(s, "ctx", 200, """{"key":"ctx","value":true,"reason":"STATIC"}""")
            ff <- flags(s)
            big = (1L << 53) + 1
            _ <- ff.booleanDetails(
              "ctx",
              default = false,
              ctx = EvaluationContext("u1").withAttribute("big", AttributeValue.LongValue(big))
            )
            body = s.getAllServeEvents.get(0).getRequest.getBodyAsString
          } yield assertTrue(body.contains(big.toString))
        }
      },
      test("flag metadata, variant and reason round-trip") {
        ZIO.scoped {
          for {
            s <- server
            _ = stub(
              s,
              "m",
              200,
              """{"key":"m","value":"x","variant":"v1","reason":"TARGETING_MATCH","metadata":{"a":1,"b":"s","c":true,"d":2.5}}"""
            )
            ff <- flags(s)
            r  <- ff.stringDetails("m", default = "")
          } yield assertTrue(
            r.value == "x",
            r.variant.contains("v1"),
            r.reason == ResolutionReason.TargetingMatch,
            r.metadata.getInt("a").contains(1),
            r.metadata.getString("b").contains("s"),
            r.metadata.getBoolean("c").contains(true),
            r.metadata.getDouble("d").contains(2.5)
          )
        }
      }
    )
  ) @@ sequential @@ withLiveClock @@ timeout(120.seconds)
}
