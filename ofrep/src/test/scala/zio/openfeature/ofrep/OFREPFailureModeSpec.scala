package zio.openfeature.ofrep

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.http.Fault
import zio._
import zio.openfeature._
import zio.test._
import zio.test.TestAspect.{sequential, timeout, withLiveClock}

/** Failure-mode coverage for the OFREP integration (workstream B1). The existing [[OFREPProviderIntegrationSpec]]
  * covers the happy path; this spec exercises the error paths against a WireMock server impersonating an OFREP endpoint
  * and documents how the OFREP contrib provider surfaces each failure shape:
  *
  *   - HTTP `4xx`/`5xx`: the contrib provider catches the response inside its evaluation method and returns a
  *     `ProviderEvaluation` with the `errorCode` and `errorMessage` populated. The ZIO `FeatureFlags` layer hands this
  *     back to callers as a successful `FlagResolution[A]` whose `errorCode` is set — operators are expected to alert
  *     on resolutions with non-empty `errorCode`.
  *   - Network-level failures (connection refused, connection reset): the contrib provider's HTTP client throws
  *     synchronously, the throw bubbles out of `attemptBlocking`, and `FeatureFlagError.classify` maps it to a typed
  *     error — `Unreachable` for known network exception types, `ProviderError` otherwise.
  *   - Slow responses: when an evaluation timeout is configured on the `FeatureFlags` layer, slow responses surface as
  *     `ProviderError(TimeoutException)`, whether the server stalls before answering or dribbles the body out.
  *   - Broken responses (random bytes, a malformed chunk, an empty response, truncated JSON, an HTML page served with
  *     status 200): a typed error or an error-coded resolution, never a silent default.
  *
  * Tests run sequentially because the contrib provider 0.0.2 has order-sensitive internal executor handling.
  */
object OFREPFailureModeSpec extends ZIOSpecDefault {

  private def withMockServer[A](body: WireMockServer => A): A = {
    val server = new WireMockServer(WireMockConfiguration.options().dynamicPort())
    server.start()
    try body(server)
    finally server.stop()
  }

  /** Wait for the async initialization to finish, so a cold first test does not evaluate before `PROVIDER_READY`. */
  private def awaitReady(ff: FeatureFlags): Task[Unit] =
    ff.providerStatus
      .repeat(Schedule.recurUntil[ProviderStatus](_ == ProviderStatus.Ready) && Schedule.spaced(20.millis))
      .timeoutFail(new IllegalStateException("the OFREP provider did not become ready"))(10.seconds)
      .unit

  private def baseUrl(server: WireMockServer): String = s"http://localhost:${server.port()}"

  /** Build a `FeatureFlags` service against the given OFREP base URL and run a synchronous body with it. Uses
    * `fromProviderAsync` because the contrib provider doesn't perform a blocking init — async is the recommended path
    * for any remote provider.
    */
  private def withFeatureFlags[A](
    server: WireMockServer,
    evaluationTimeout: Option[Duration] = None
  )(body: FeatureFlags => A): A = Unsafe.unsafe { implicit u =>
    Runtime.default.unsafe
      .run(
        ZIO
          .scoped {
            for {
              provider <- OFREPProvider.make(baseUrl(server))
              env      <- evaluationTimeout match {
                case Some(d) =>
                  FeatureFlags
                    .fromProvider(provider, FeatureFlagsConfig(initMode = InitMode.Async).withEvaluationTimeout(d))
                    .build
                case None => FeatureFlags.fromProviderAsync(provider).build
              }
              ff = env.get[FeatureFlags]
              _ <- awaitReady(ff)
              r <- ZIO.attempt(body(ff))
            } yield r
          }
          .withClock(Clock.ClockLive)
      )
      .getOrThrowFiberFailure()
  }

  private def details(ff: FeatureFlags, key: String): Either[FeatureFlagError, FlagResolution[Boolean]] =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe
        .run(ff.booleanDetails(key, default = false).either.withClock(Clock.ClockLive))
        .getOrThrowFiberFailure()
    }

  /** The failure surfaced: a typed error, or a resolution carrying an error code. Never a silent default. */
  private def flagged(result: Either[FeatureFlagError, FlagResolution[Boolean]]): Boolean =
    result.fold(_ => true, _.errorCode.isDefined)

  /** A server error: the typed tier fails with `ProviderError`, which the circuit breaker counts. */
  private def general(result: Either[FeatureFlagError, FlagResolution[Boolean]]): Boolean =
    result match {
      case Left(_: FeatureFlagError.ProviderError) => true
      case _                                       => false
    }

  // Responses a proxy, a crashing server or a half-dead connection can hand back in place of an OFREP answer.
  // Each pairs the response with what must come back: a body that arrives but is not OFREP JSON, whatever the status,
  // is a server error, so a dead backend behind a proxy opens the circuit.
  private val brokenResponses
    : List[(String, ResponseDefinitionBuilder, Either[FeatureFlagError, FlagResolution[Boolean]] => Boolean)] = List(
    ("random bytes, then the connection closes", aResponse().withFault(Fault.RANDOM_DATA_THEN_CLOSE), flagged),
    ("a malformed chunk", aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK), flagged),
    ("an empty response", aResponse().withFault(Fault.EMPTY_RESPONSE), flagged),
    ("truncated JSON", okJson("""{"key":"broken","value":tr"""), general),
    (
      "an HTML page with status 200",
      aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "text/html")
        .withBody("<html><body><h1>Gateway</h1></body></html>"),
      general
    ),
    (
      "a 502 with an HTML body",
      aResponse()
        .withStatus(502)
        .withHeader("Content-Type", "text/html")
        .withBody("<html><body>Bad Gateway</body></html>"),
      general
    ),
    ("a 503 with an empty body", aResponse().withStatus(503), general),
    (
      "a 401 with an HTML body",
      aResponse().withStatus(401).withHeader("Content-Type", "text/html").withBody("<html>Forbidden</html>"),
      general
    ),
    (
      "a 429 with an HTML body",
      aResponse().withStatus(429).withHeader("Retry-After", "1").withBody("<html>Too Many Requests</html>"),
      general
    )
  )

  private def stubBroken(server: WireMockServer, response: ResponseDefinitionBuilder): Unit = {
    val _ = server.stubFor(post(urlEqualTo("/ofrep/v1/evaluate/flags/broken")).willReturn(response))
  }

  def spec = suite("OFREP failure-mode integration (WireMock)")(
    suite("broken responses surface as failures, not as a silent default")(
      brokenResponses.map { case (name, response, expected) =>
        test(name) {
          withMockServer { server =>
            stubBroken(server, response)
            // Evaluate inside the scope: `assertTrue` is lazy, so an expression left in it would run after shutdown.
            val result = withFeatureFlags(server, evaluationTimeout = Some(5.seconds))(details(_, "broken"))
            assertTrue(expected(result))
          }
        }
      }: _*
    ),
    test("a context the client cannot serialize is not blamed on a healthy server") {
      withMockServer { server =>
        stubBroken(server, okJson("""{"key":"broken","value":true,"reason":"STATIC"}"""))
        // The contrib provider's request mapper has no java.time support, so it fails before sending anything.
        val ctx    = zio.openfeature.EvaluationContext.builder.attribute("at", java.time.Instant.EPOCH).build
        val result = withFeatureFlags(server, evaluationTimeout = Some(5.seconds)) { ff =>
          Unsafe.unsafe { implicit u =>
            Runtime.default.unsafe
              .run(ff.booleanDetails("broken", default = false, ctx = ctx).either.withClock(Clock.ClockLive))
              .getOrThrowFiberFailure()
          }
        }
        assertTrue(result match {
          case Left(_: FeatureFlagError.ParseError) => true
          case _                                    => false
        })
      }
    },
    test("a long read of an HTML error page is a server error too") {
      withMockServer { server =>
        stubBroken(
          server,
          aResponse().withStatus(502).withHeader("Content-Type", "text/html").withBody("<html>Bad Gateway</html>")
        )
        val result = withFeatureFlags(server, evaluationTimeout = Some(5.seconds)) { ff =>
          Unsafe.unsafe { implicit u =>
            Runtime.default.unsafe
              .run(ff.longDetails("broken", default = 7L).either.withClock(Clock.ClockLive))
              .getOrThrowFiberFailure()
          }
        }
        assertTrue(result match {
          case Left(_: FeatureFlagError.ProviderError) => true
          case _                                       => false
        })
      }
    },
    test("a response dribbled out slower than the evaluation timeout fails with a timeout") {
      withMockServer { server =>
        stubBroken(
          server,
          okJson("""{"key":"broken","value":true,"reason":"TARGETING_MATCH"}""").withChunkedDribbleDelay(10, 3000)
        )
        val result = withFeatureFlags(server, evaluationTimeout = Some(500.millis))(details(_, "broken"))
        assertTrue(result match {
          case Left(FeatureFlagError.ProviderError(t)) => t.isInstanceOf[java.util.concurrent.TimeoutException]
          case _                                       => false
        })
      }
    },
    test("a response dribbled out within the evaluation timeout is served") {
      withMockServer { server =>
        stubBroken(
          server,
          okJson("""{"key":"broken","value":true,"reason":"TARGETING_MATCH"}""").withChunkedDribbleDelay(5, 300)
        )
        val result = withFeatureFlags(server, evaluationTimeout = Some(5.seconds))(details(_, "broken"))
        assertTrue(result.exists(r => r.value && r.errorCode.isEmpty))
      }
    },
    test(
      "[B1] 401 -> typed failure on the typed tier; the total tier's FlagResolution carries a non-empty errorCode (operator-actionable)"
    ) {
      withMockServer { server =>
        server.stubFor(
          post(urlEqualTo("/ofrep/v1/evaluate/flags/protected-flag"))
            .willReturn(
              aResponse()
                .withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("""{"errorCode":"GENERAL","errorDetails":"unauthorized"}""")
            )
        )
        withFeatureFlags(server) { ff =>
          // The typed tier surfaces the provider's error code as a typed failure; the total tier is where
          // the operator-actionable resolution (default value + code) lives.
          val (typed, total) = Unsafe.unsafe { implicit u =>
            Runtime.default.unsafe
              .run(
                ff.booleanDetails("protected-flag", default = false)
                  .either
                  .zip(ff.resolveOrDefault[Boolean]("protected-flag", default = false))
                  .withClock(Clock.ClockLive)
              )
              .getOrThrowFiberFailure()
          }
          assertTrue(typed.isLeft, total.errorCode.isDefined, total.value == false)
        }
      }
    },
    test(
      "[B1] 500 -> typed failure on the typed tier; the total tier's FlagResolution carries a non-empty errorCode (operator-actionable)"
    ) {
      withMockServer { server =>
        server.stubFor(
          post(urlEqualTo("/ofrep/v1/evaluate/flags/server-error-flag"))
            .willReturn(
              aResponse()
                .withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("""{"errorCode":"GENERAL","errorDetails":"upstream is sick"}""")
            )
        )
        withFeatureFlags(server) { ff =>
          // The typed tier surfaces the provider's error code as a typed failure; the total tier is where
          // the operator-actionable resolution (default value + code) lives.
          val (typed, total) = Unsafe.unsafe { implicit u =>
            Runtime.default.unsafe
              .run(
                ff.booleanDetails("server-error-flag", default = false)
                  .either
                  .zip(ff.resolveOrDefault[Boolean]("server-error-flag", default = false))
                  .withClock(Clock.ClockLive)
              )
              .getOrThrowFiberFailure()
          }
          assertTrue(typed.isLeft, total.errorCode.isDefined, total.value == false)
        }
      }
    },
    test("[B1] connection reset -> ZIO failure (typed FeatureFlagError, not a defect)") {
      withMockServer { server =>
        server.stubFor(
          post(urlEqualTo("/ofrep/v1/evaluate/flags/reset-flag"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
        )
        withFeatureFlags(server) { ff =>
          val result = Unsafe.unsafe { implicit u =>
            Runtime.default.unsafe
              .run(ff.booleanDetails("reset-flag", default = false).either.withClock(Clock.ClockLive))
              .getOrThrowFiberFailure()
          }
          // Either a successful resolution with errorCode populated (contrib provider caught it internally), or a
          // typed FeatureFlagError (provider rethrew, classifier mapped it). Both are acceptable outcomes — the goal
          // is to prove the connection-level failure does NOT become a silent default-value evaluation.
          assertTrue(
            result match {
              case Right(resolution)                       => resolution.errorCode.isDefined
              case Left(_: FeatureFlagError.Unreachable)   => true
              case Left(_: FeatureFlagError.ProviderError) => true
              case _                                       => false
            }
          )
        }
      }
    },
    test("[B1] evaluation timeout -> ProviderError(TimeoutException)") {
      withMockServer { server =>
        server.stubFor(
          post(urlEqualTo("/ofrep/v1/evaluate/flags/slow-flag"))
            .willReturn(
              okJson("""{"key":"slow-flag","value":true,"reason":"TARGETING_MATCH"}""")
                .withFixedDelay(3000)
            )
        )
        withFeatureFlags(server, evaluationTimeout = Some(200.millis)) { ff =>
          val result = Unsafe.unsafe { implicit u =>
            Runtime.default.unsafe
              .run(ff.booleanDetails("slow-flag", default = false).either.withClock(Clock.ClockLive))
              .getOrThrowFiberFailure()
          }
          assertTrue(
            result match {
              case Left(FeatureFlagError.ProviderError(t)) =>
                t.isInstanceOf[java.util.concurrent.TimeoutException]
              case _ => false
            }
          )
        }
      }
    },
    test("[B1] connection refused after server stop -> typed FeatureFlagError") {
      // Build the FeatureFlags layer against the live server first; once we have a working evaluation, stop the
      // server and prove the next evaluation surfaces a typed failure rather than hanging.
      withMockServer { server =>
        server.stubFor(
          post(urlEqualTo("/ofrep/v1/evaluate/flags/probe"))
            .willReturn(okJson("""{"key":"probe","value":true,"reason":"TARGETING_MATCH"}"""))
        )
        val (preStopOk, postStopOk) = Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe
            .run(
              ZIO
                .scoped {
                  for {
                    provider <- OFREPProvider.make(baseUrl(server))
                    env      <- FeatureFlags
                      .fromProvider(
                        provider,
                        FeatureFlagsConfig(initMode = InitMode.Async).withEvaluationTimeout(1.second)
                      )
                      .build
                    ff = env.get[FeatureFlags]
                    _      <- awaitReady(ff)
                    before <- ff.booleanDetails("probe", default = false).either
                    _      <- ZIO.attempt(server.stop())
                    after  <- ff.booleanDetails("probe", default = false).either
                  } yield (before, after)
                }
                .withClock(Clock.ClockLive)
            )
            .getOrThrowFiberFailure()
        }
        // `before` should succeed; `after` should fail with a typed error OR succeed with errorCode populated.
        // (Contrib provider behaviour varies on whether it propagates the IOException or swallows it.)
        val beforeOk = preStopOk match {
          case Right(r) => r.errorCode.isEmpty
          case _        => false
        }
        val afterFlagged = postStopOk match {
          case Right(r)                                => r.errorCode.isDefined
          case Left(_: FeatureFlagError.Unreachable)   => true
          case Left(_: FeatureFlagError.ProviderError) => true
          case _                                       => false
        }
        assertTrue(beforeOk, afterFlagged)
      }
    }
  ) @@ sequential @@ timeout(45.seconds) @@ withLiveClock
}
