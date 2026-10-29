package zio.openfeature.ofrep

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import dev.openfeature.sdk.{ErrorCode, ImmutableContext}
import zio._
import zio.openfeature._
import zio.openfeature.internal.ProviderReportedError
import zio.test._
import zio.test.TestAspect.{sequential, timeout, withLiveClock}

/** The OFREP contrib provider does not throw on a 401/403: it returns an evaluation with `errorCode = GENERAL` and the
  * message "authentication/authorization error for flag: <key>". Core maps that message to `Unauthorized`. The canary
  * pins the upstream literal, so a contrib bump that rewords it fails here rather than silently in production.
  *
  * Tests run sequentially because the contrib provider 0.0.2 has order-sensitive internal executor handling.
  */
object OfrepAuthFailureSpec extends ZIOSpecDefault {

  private val flagKey = "protected-flag"

  private val server: ZIO[Scope, Nothing, WireMockServer] =
    ZIO.acquireRelease(ZIO.succeed {
      val s = new WireMockServer(WireMockConfiguration.options().dynamicPort())
      s.start()
      s
    })(s => ZIO.succeed(s.stop()))

  private def stubStatus(server: WireMockServer, status: Int): UIO[Unit] =
    ZIO.succeed {
      server.stubFor(
        post(urlEqualTo(s"/ofrep/v1/evaluate/flags/$flagKey"))
          .willReturn(
            aResponse()
              .withStatus(status)
              .withHeader("Content-Type", "application/json")
              .withBody("""{"errorCode":"GENERAL","errorDetails":"rejected"}""")
          )
      )
    }.unit

  /** A `FeatureFlags` over the OFREP provider answering `status`, plus the errors its `error` hook stage received. */
  private def flagsAnswering(status: Int): ZIO[Scope, Any, (FeatureFlags, Ref[List[FeatureFlagError]])] =
    for {
      s        <- server
      _        <- stubStatus(s, status)
      seen     <- Ref.make(List.empty[FeatureFlagError])
      provider <- OFREPProvider.scoped(s"http://localhost:${s.port()}")
      hook = new FeatureHook {
        override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
          seen.update(_ :+ err)
      }
      env <- FeatureFlags.fromProvider(provider, FeatureFlagsConfig().withHooks(List(hook))).build
    } yield (env.get[FeatureFlags], seen)

  private def authFailureTest(status: Int) =
    test(s"$status fails the typed tier with Unauthorized, serves the default, and reaches the error hook") {
      ZIO.scoped {
        for {
          pair <- flagsAnswering(status)
          (flags, seen) = pair
          typed  <- flags.booleanDetails(flagKey, default = true).either
          total  <- flags.booleanOrDefault(flagKey, default = true)
          hooked <- seen.get
        } yield {
          val reason = typed match {
            case Left(e: FeatureFlagError.Unauthorized) => Some(e.reason)
            case _                                      => None
          }
          val cause           = typed.left.toOption.flatMap(_.cause)
          val causeIsReported = cause match {
            case Some(c: ProviderReportedError) =>
              c.getMessage == s"authentication/authorization error for flag: $flagKey"
            case _ => false
          }
          val hookSawUnauthorized = hooked.exists(_.isInstanceOf[FeatureFlagError.Unauthorized])
          assertTrue(
            reason.contains(s"authentication/authorization error for flag: $flagKey"),
            causeIsReported,
            total,
            hookSawUnauthorized
          )
        }
      }
    }

  private def typedFailure(status: Int): ZIO[Any, Any, Either[FeatureFlagError, FlagResolution[Boolean]]] =
    ZIO.scoped(flagsAnswering(status).flatMap { case (flags, _) =>
      flags.booleanDetails(flagKey, default = true).either
    })

  def spec = suite("OFREP auth failures (WireMock)")(
    test("canary: the contrib provider reports a 401 as GENERAL with the authentication/authorization literal") {
      ZIO.scoped {
        for {
          s        <- server
          _        <- stubStatus(s, 401)
          provider <- OFREPProvider.scoped(s"http://localhost:${s.port()}")
          result   <- ZIO.attemptBlocking(
            provider.getBooleanEvaluation(flagKey, java.lang.Boolean.FALSE, new ImmutableContext())
          )
        } yield {
          val code    = result.getErrorCode
          val message = Option(result.getErrorMessage).getOrElse("")
          assertTrue(code == ErrorCode.GENERAL, message.startsWith("authentication/authorization error"))
        }
      }
    },
    authFailureTest(401),
    authFailureTest(403),
    test("500 stays a ProviderError") {
      typedFailure(500).map { typed =>
        val isProviderError = typed match {
          case Left(_: FeatureFlagError.ProviderError) => true
          case _                                       => false
        }
        assertTrue(isProviderError)
      }
    },
    test("429 (rate limited) stays a ProviderError") {
      typedFailure(429).map { typed =>
        val isProviderError = typed match {
          case Left(_: FeatureFlagError.ProviderError) => true
          case _                                       => false
        }
        assertTrue(isProviderError)
      }
    },
    test("404 is FlagNotFound") {
      typedFailure(404).map { typed =>
        val isNotFound = typed match {
          case Left(FeatureFlagError.FlagNotFound(key)) => key == flagKey
          case _                                        => false
        }
        assertTrue(isNotFound)
      }
    }
  ) @@ sequential @@ withLiveClock @@ timeout(2.minutes)
}
