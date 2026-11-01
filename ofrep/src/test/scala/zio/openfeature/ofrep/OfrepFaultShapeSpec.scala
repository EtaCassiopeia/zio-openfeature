package zio.openfeature.ofrep

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import dev.openfeature.sdk.{ImmutableContext, ProviderState}
import zio._
import zio.openfeature._
import zio.openfeature.extras.{CircuitBreakerProvider, CircuitBreakerProviderConfig}
import zio.test._

/** OFREP error responses over every combination of status, body and headers.
  *
  * Both expectations are functions of the shape, so the spec is a table rather than a list of fixtures:
  *   - the typed error: a 429 or a 5xx is a server error whatever it carries; a 4xx with a JSON body is classified by
  *     its status (400 `InvalidContext`, 401 and 403 `Unauthorized`, 404 `FlagNotFound`), and a 4xx whose body is not
  *     JSON is a server error, since a proxy or a dead backend sends those;
  *   - the circuit breaker over the provider: open after its threshold for everything but the two application errors, a
  *     400 or a 404 with a JSON body. A 401 or 403 opens it too: the provider reports it as a general error.
  *
  * Headers change neither: `Retry-After` and `Content-Type` are not read for the class.
  */
object OfrepFaultShapeSpec extends ZIOSpecDefault {

  import FaultShape.Body

  private def jsonBody(shape: FaultShape): Boolean = shape.body == Body.OfrepJson || shape.body == Body.OtherJson

  /** The typed error a shape must fail `booleanDetails` with. */
  private def expectedError(shape: FaultShape): String =
    if (shape.status == 429 || shape.status >= 500 || !jsonBody(shape)) "ProviderError"
    else
      shape.status match {
        case 400       => "InvalidContext"
        case 401 | 403 => "Unauthorized"
        case 404       => "FlagNotFound"
        case other     => throw new IllegalArgumentException(s"no expectation for status $other")
      }

  /** Whether the breaker over the provider is open after its threshold of calls answered with `shape`. */
  private def expectedOpen(shape: FaultShape): Boolean =
    !((shape.status == 400 || shape.status == 404) && jsonBody(shape))

  private val Threshold = 2

  private def server: URIO[Scope, WireMockServer] =
    ZIO.acquireRelease(ZIO.succeed {
      val s = new WireMockServer(WireMockConfiguration.options().dynamicPort())
      s.start()
      s
    })(s => ZIO.succeed(s.stop()))

  private def errorName(r: Either[FeatureFlagError, FlagResolution[Boolean]]): String = r match {
    case Left(e)  => e.getClass.getSimpleName
    case Right(v) => s"served ${v.value} with ${v.errorCode}"
  }

  @scala.annotation.nowarn("msg=deprecated")
  private def stateOf(p: CircuitBreakerProvider): ProviderState = p.getState

  private def typedError(shape: FaultShape): Task[String] =
    ZIO.scoped {
      for {
        s        <- server
        _        <- ZIO.succeed(FaultShape.install(s, shape))
        provider <- OFREPProvider
          .scoped(s"http://localhost:${s.port()}")
          .orDieWith(e => new IllegalArgumentException(e.toString))
        env <- FeatureFlags.fromProviderAsync(provider).build
        ff = env.get[FeatureFlags]
        _ <- ff.providerStatus
          .repeat(Schedule.recurUntil[ProviderStatus](_ == ProviderStatus.Ready) && Schedule.spaced(20.millis))
          .timeoutFail(new IllegalStateException("not ready"))(10.seconds)
        r <- ff.booleanDetails(shape.key, default = false).either
      } yield errorName(r)
    }

  private def breakerOpen(shape: FaultShape): Task[Boolean] =
    ZIO.scoped {
      for {
        s        <- server
        _        <- ZIO.succeed(FaultShape.install(s, shape))
        provider <- OFREPProvider
          .scoped(s"http://localhost:${s.port()}")
          .orDieWith(e => new IllegalArgumentException(e.toString))
        // No state polling: only the evaluations' outcomes move the circuit.
        breaker = CircuitBreakerProvider(
          provider,
          CircuitBreakerProviderConfig(
            failureThreshold = Threshold,
            resetTimeout = 1.hour,
            stateCheckInterval = Duration.Infinity
          )
        )
        _ <- ZIO.addFinalizer(ZIO.attemptBlocking(breaker.shutdown()).ignore)
        _ <- ZIO.attemptBlocking(breaker.initialize(new ImmutableContext()))
        _ <- ZIO.foreachDiscard(1 to Threshold)(_ =>
          ZIO.attemptBlocking(breaker.getBooleanEvaluation(shape.key, false, new ImmutableContext())).either
        )
        // The breaker's only public view. While closed it reports the delegate's state, READY here after a clean start
        // and never polled again, so ERROR means the circuit opened.
        state <- ZIO.succeed(stateOf(breaker))
      } yield state == ProviderState.ERROR
    }

  private val shapes = Gen.fromIterable(FaultShape.all)

  def spec = suite("OfrepFaultShapeSpec")(
    test("every fault shape fails with the typed error its status and body give, whatever the headers") {
      checkAll(shapes) { shape =>
        typedError(shape).map(actual => assertTrue(actual == expectedError(shape)))
      }
    },
    test("the circuit breaker opens for every server error and every non-JSON 4xx, and only for those") {
      checkAll(shapes) { shape =>
        breakerOpen(shape).map(open => assertTrue(open == expectedOpen(shape)))
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(10.minutes)
}
