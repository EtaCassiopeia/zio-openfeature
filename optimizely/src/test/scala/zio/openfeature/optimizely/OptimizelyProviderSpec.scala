package zio.openfeature.optimizely

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

  def spec = suite("OptimizelyProvider.make — input validation")(
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
    )
  )
}
