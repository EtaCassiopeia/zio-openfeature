package zio.openfeature.optimizely

import com.optimizely.ab.config.parser.{DefaultConfigParser, JacksonConfigParser, MissingJsonParserException}
import zio._
import zio.openfeature.FeatureFlagError
import zio.test._

/** Validation-only spec. End-to-end behaviour (datafile fetch, decisions, lifecycle, failure modes) is covered by the
  * forthcoming WireMock-backed integration spec (#136). Construction here uses real network calls, so we never let
  * `make()` return a provider that would actually start the Optimizely poller — every "accepts" test stops at the
  * validation boundary by short-circuiting to a dedicated helper.
  */
object OptimizelyProviderSpec extends ZIOSpecDefault {

  private def expectInvalid[A](io: IO[FeatureFlagError.InvalidConfiguration, A], substring: String): UIO[TestResult] =
    io.either.map { result =>
      assertTrue(
        result.isLeft,
        result.left.exists(_.message.toLowerCase.contains(substring.toLowerCase))
      )
    }

  // Exercises validateSdkKey indirectly via the public factory's effect channel, but with a junk URL so that — even
  // if validation passes — the actual Optimizely client construction never starts a real poller. The two-arg `make`
  // validates the SDK key first, so this is safe for sdkKey tests.
  private def validateOnly(
    sdkKey: String,
    datafileUrl: String = "http://invalid.local:1/datafile.json"
  ): IO[FeatureFlagError.InvalidConfiguration, Unit] =
    OptimizelyProvider
      .make(sdkKey, datafileUrl = Some(datafileUrl), initWait = java.time.Duration.ofMillis(50))
      .unit
      .catchAll(e => ZIO.fail(e))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("OptimizelyProvider.make — input validation")(
    suite("sdkKey")(
      test("rejects null") {
        expectInvalid(OptimizelyProvider.make(null: String), "null")
      },
      test("rejects empty string") {
        expectInvalid(OptimizelyProvider.make(""), "empty")
      },
      test("rejects whitespace-only") {
        expectInvalid(OptimizelyProvider.make("   "), "empty")
      },
      test("rejects key with internal whitespace") {
        expectInvalid(OptimizelyProvider.make("abc 1234"), "whitespace")
      },
      test("rejects too-short key (5 chars)") {
        expectInvalid(OptimizelyProvider.make("abcde"), "too short")
      },
      test("rejects too-long key (>128 chars)") {
        expectInvalid(OptimizelyProvider.make("a" * 129), "too long")
      },
      test("rejects key with disallowed characters") {
        expectInvalid(OptimizelyProvider.make("abc$1234"), "disallowed")
      },
      test("rejects 'YOUR_SDK_KEY' placeholder") {
        expectInvalid(OptimizelyProvider.make("YOUR_SDK_KEY"), "placeholder")
      },
      test("rejects '<sdk-key>' (rejected by character set before reaching the placeholder check)") {
        expectInvalid(OptimizelyProvider.make("<sdk-key>"), "disallowed")
      },
      test("rejects 'changeme' placeholder") {
        expectInvalid(OptimizelyProvider.make("changeme"), "placeholder")
      }
    ),
    suite("datafileUrl (two-arg make)")(
      test("rejects empty URL") {
        expectInvalid(OptimizelyProvider.make("valid_key_abc", ""), "empty")
      },
      test("rejects unsupported scheme (ftp)") {
        expectInvalid(OptimizelyProvider.make("valid_key_abc", "ftp://example.com"), "unsupported scheme")
      },
      test("rejects URL with no host") {
        expectInvalid(OptimizelyProvider.make("valid_key_abc", "http:///path"), "no host")
      },
      test("rejects malformed URL") {
        expectInvalid(OptimizelyProvider.make("valid_key_abc", "not a url"), "malformed")
      }
    ),
    suite("ordering")(
      test("sdkKey validation runs before URL validation") {
        // If both are invalid, expect the sdkKey error (validated first).
        for {
          result <- OptimizelyProvider.make("", "ftp://example.com").either
        } yield assertTrue(
          result.isLeft,
          result.left.exists(_.message.toLowerCase.contains("sdkkey"))
        )
      }
    ),
    // #431: core-api picks a JSON parser at runtime and declares none. Without one, `DefaultConfigParser`'s lazy
    // holder fails to initialise, which surfaces as a LinkageError — not an Exception — on first and later calls.
    suite("JSON parser probe (#431)")(
      test("maps ExceptionInInitializerError (first failed lookup) to InvalidConfiguration with the root cause") {
        for {
          result <- OptimizelyProvider
            .requireJsonParser(
              throw new ExceptionInInitializerError(new MissingJsonParserException("unable to locate a JSON parser"))
            )
            .either
        } yield assertTrue(
          result.left.exists(_.message.contains("could not initialise a JSON parser")),
          result.left.exists(_.message.contains("jackson-databind")),
          result.left.exists(_.message.contains("MissingJsonParserException: unable to locate a JSON parser"))
        )
      },
      test("maps NoClassDefFoundError (every later lookup) to InvalidConfiguration") {
        for {
          result <- OptimizelyProvider
            .requireJsonParser(throw new NoClassDefFoundError("DefaultConfigParser$LazyHolder"))
            .either
        } yield assertTrue(
          result.left.exists(_.message.contains("could not initialise a JSON parser")),
          result.left.exists(_.message.contains("java.lang.NoClassDefFoundError: DefaultConfigParser$LazyHolder"))
        )
      },
      test("succeeds when a parser is available") {
        for {
          result <- OptimizelyProvider.requireJsonParser(new JacksonConfigParser()).either
        } yield assertTrue(result == Right(()))
      },
      // Not the #431 gate — WireMock puts Jackson on the Test classpath either way; `checkOptimizelyJsonParser`
      // owns what consumers resolve. This pins which parser these specs run against.
      test("the Test classpath's parser is Jackson") {
        // Hoisted: Scala 2.13's assertTrue macro cannot type a Java static call inline.
        val parser = DefaultConfigParser.getInstance()
        assertTrue(parser.isInstanceOf[JacksonConfigParser])
      },
      test("make fails with the parser error when the probe fails") {
        for {
          result <- OptimizelyProvider
            .makeWithParserProbe(
              OptimizelyProviderConfig("valid-sdk-key-123"),
              Some(TestHttpClient.failFast()),
              throw new NoClassDefFoundError("DefaultConfigParser$LazyHolder")
            )
            .either
        } yield assertTrue(result.left.exists(_.message.contains("could not initialise a JSON parser")))
      },
      test("make validates its inputs before probing for a parser") {
        for {
          result <- OptimizelyProvider
            .makeWithParserProbe(
              OptimizelyProviderConfig(""),
              Some(TestHttpClient.failFast()),
              throw new NoClassDefFoundError("DefaultConfigParser$LazyHolder")
            )
            .either
        } yield assertTrue(result.left.exists(_.message.toLowerCase.contains("sdkkey")))
      }
    )
  )
}
