package zio.openfeature.ofrep

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import dev.openfeature.sdk.{ImmutableContext, Value}
import zio._
import zio.openfeature._
import zio.test._
import zio.test.TestAspect.{sequential, timeout, withLiveClock}

import java.util.concurrent.{ExecutorService, Executors, ThreadFactory}

/** `FeatureFlags.long` over the OFREP provider. The contrib provider has no `getLongEvaluation`, so the SDK's
  * double-backed default used to answer every JSON integer flag with TYPE_MISMATCH. `OFREPFeatureProvider` resolves
  * long reads through the object resolver: JSON integers in the `Long` range are served exactly, anything else is
  * TYPE_MISMATCH with the default, and server errors keep their meaning.
  *
  * Tests run sequentially because the contrib provider 0.0.2 has order-sensitive internal executor handling.
  */
object OfrepLongEvaluationSpec extends ZIOSpecDefault {

  private val server: ZIO[Scope, Nothing, WireMockServer] =
    ZIO.acquireRelease(ZIO.succeed {
      val s = new WireMockServer(WireMockConfiguration.options().dynamicPort())
      s.start()
      s
    })(s => ZIO.succeed(s.stop()))

  private def path(key: String): String = s"/ofrep/v1/evaluate/flags/$key"

  private def serve(s: WireMockServer, key: String, valueJson: String): UIO[Unit] =
    ZIO.succeed {
      s.stubFor(
        post(urlEqualTo(path(key)))
          .willReturn(okJson(s"""{"key":"$key","value":$valueJson,"reason":"STATIC","variant":"v1"}"""))
      )
    }.unit

  private def answer(s: WireMockServer, key: String, status: Int, errorCode: String): UIO[Unit] =
    ZIO.succeed {
      s.stubFor(
        post(urlEqualTo(path(key)))
          .willReturn(
            aResponse()
              .withStatus(status)
              .withHeader("Content-Type", "application/json")
              .withBody(s"""{"errorCode":"$errorCode","errorDetails":"stubbed"}""")
          )
      )
    }.unit

  private def flagsOver(s: WireMockServer): ZIO[Scope, Any, FeatureFlags] =
    for {
      provider <- OFREPProvider.scoped(s"http://localhost:${s.port()}")
      env      <- FeatureFlags.fromProvider(provider).build
    } yield env.get[FeatureFlags]

  /** Serve `valueJson` for one flag and read it with `FeatureFlags.long` on both tiers. */
  private def readLong(
    valueJson: String,
    default: Long
  ): ZIO[Any, Any, (Either[FeatureFlagError, Long], FlagResolution[Long])] =
    ZIO.scoped {
      for {
        s     <- server
        _     <- serve(s, "long-flag", valueJson)
        flags <- flagsOver(s)
        typed <- flags.long("long-flag", default).either
        total <- flags.resolveOrDefault[Long]("long-flag", default)
      } yield (typed, total)
    }

  private def servedExactly(valueJson: String, expected: Long) =
    test(s"a JSON $valueJson is served exactly with reason STATIC") {
      readLong(valueJson, default = -1L).map { case (typed, total) =>
        val value   = total.value
        val reason  = total.reason
        val variant = total.variant
        val code    = total.errorCode
        assertTrue(typed == Right(expected), value == expected, reason == ResolutionReason.Static) &&
        assertTrue(variant.contains("v1"), code.isEmpty)
      }
    }

  private def typeMismatch(valueJson: String) =
    test(s"a JSON $valueJson is TYPE_MISMATCH with the default") {
      readLong(valueJson, default = 7L).map { case (typed, total) =>
        val typedIsMismatch = typed match {
          case Left(_: FeatureFlagError.TypeMismatch) => true
          case _                                      => false
        }
        val value = total.value
        val code  = total.errorCode
        assertTrue(typedIsMismatch, value == 7L, code.contains(ErrorCode.TypeMismatch))
      }
    }

  private val daemonFactory: ThreadFactory = (r: Runnable) => {
    val t = new Thread(r, "ofrep-long-spec")
    t.setDaemon(true)
    t
  }

  def spec = suite("OFREP long evaluation (WireMock)")(
    servedExactly("5", 5L),
    servedExactly("5000000000", 5000000000L),
    servedExactly("-9223372036854775808", Long.MinValue),
    typeMismatch("2.0"),
    typeMismatch("\"5\""),
    typeMismatch("9223372036854775808"),
    typeMismatch("""{"n":5}"""),
    test("an integer beyond Long is TYPE_MISMATCH from the provider itself, not a thrown exception") {
      ZIO.scoped {
        for {
          s        <- server
          _        <- serve(s, "huge", "9223372036854775808")
          provider <- OFREPProvider.scoped(s"http://localhost:${s.port()}")
          result   <- ZIO.attemptBlocking(
            provider.getLongEvaluation("huge", java.lang.Long.valueOf(3L), new ImmutableContext())
          )
        } yield {
          val value = result.getValue
          val code  = result.getErrorCode
          assertTrue(value == java.lang.Long.valueOf(3L), code == dev.openfeature.sdk.ErrorCode.TYPE_MISMATCH)
        }
      }
    },
    test("404 is FLAG_NOT_FOUND with the default") {
      ZIO.scoped {
        for {
          s     <- server
          _     <- answer(s, "missing", 404, "FLAG_NOT_FOUND")
          flags <- flagsOver(s)
          typed <- flags.long("missing", 11L).either
          total <- flags.resolveOrDefault[Long]("missing", 11L)
        } yield {
          val typedIsNotFound = typed match {
            case Left(FeatureFlagError.FlagNotFound(key)) => key == "missing"
            case _                                        => false
          }
          val value = total.value
          val code  = total.errorCode
          assertTrue(typedIsNotFound, value == 11L, code.contains(ErrorCode.FlagNotFound))
        }
      }
    },
    test("401 fails the typed tier with Unauthorized and serves the default on the total tier") {
      ZIO.scoped {
        for {
          s     <- server
          _     <- answer(s, "protected", 401, "GENERAL")
          flags <- flagsOver(s)
          typed <- flags.long("protected", 13L).either
          total <- flags.longOrDefault("protected", 13L)
        } yield {
          val reason = typed match {
            case Left(e: FeatureFlagError.Unauthorized) => Some(e.reason)
            case _                                      => None
          }
          assertTrue(reason.contains("authentication/authorization error for flag: protected"), total == 13L)
        }
      }
    },
    suite("delegation to the contrib provider")(
      test("every getter reaches the OFREP server through the wrapped provider") {
        ZIO.scoped {
          for {
            s        <- server
            _        <- serve(s, "b", "true")
            _        <- serve(s, "s", "\"hello\"")
            _        <- serve(s, "i", "42")
            _        <- serve(s, "d", "2.5")
            _        <- serve(s, "o", """{"k":"v"}""")
            _        <- serve(s, "l", "9")
            provider <- OFREPProvider.scoped(s"http://localhost:${s.port()}")
            ctx = new ImmutableContext()
            results <- ZIO.attemptBlocking {
              (
                provider.getBooleanEvaluation("b", java.lang.Boolean.FALSE, ctx).getValue,
                provider.getStringEvaluation("s", "x", ctx).getValue,
                provider.getIntegerEvaluation("i", Integer.valueOf(0), ctx).getValue,
                provider.getDoubleEvaluation("d", java.lang.Double.valueOf(0.0), ctx).getValue,
                provider.getObjectEvaluation("o", new Value(), ctx).getValue.asStructure.getValue("k").asString,
                provider.getLongEvaluation("l", java.lang.Long.valueOf(0L), ctx).getValue
              )
            }
            hits <- ZIO.succeed(
              List("b", "s", "i", "d", "o", "l").map(k => s.findAll(postRequestedFor(urlEqualTo(path(k)))).size)
            )
          } yield {
            val (b, str, i, d, o, l) = results
            assertTrue(
              b == java.lang.Boolean.TRUE,
              str == "hello",
              i == Integer.valueOf(42),
              d == java.lang.Double.valueOf(2.5),
              o == "v",
              l == java.lang.Long.valueOf(9L),
              hits == List(1, 1, 1, 1, 1, 1)
            )
          }
        }
      },
      test("metadata and domain scoping come from the contrib provider") {
        ZIO.scoped {
          OFREPProvider.scoped("http://localhost:9999").map { provider =>
            val name           = provider.getMetadata.getName
            val underlyingName = provider.underlying.getMetadata.getName
            val scoped         = provider.isDomainScoped()
            val hooks          = provider.getProviderHooks.size
            assertTrue(name == underlyingName, name == "ofrep", !scoped, hooks == 0)
          }
        }
      },
      test("shutdown reaches the contrib provider, which stops its executor") {
        val executor: ExecutorService = Executors.newCachedThreadPool(daemonFactory)
        val options = OFREPProvider.daemonOptionsBuilder().baseUrl("http://localhost:9999").executor(executor).build()
        for {
          provider   <- OFREPProvider.make(options)
          before     <- ZIO.succeed(executor.isShutdown)
          _          <- ZIO.attemptBlocking(provider.shutdown())
          afterwards <- ZIO.succeed(executor.isShutdown)
        } yield assertTrue(!before, afterwards)
      }
    )
  ) @@ sequential @@ withLiveClock @@ timeout(2.minutes)
}
