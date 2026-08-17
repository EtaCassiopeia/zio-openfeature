package zio.openfeature.extras

import zio._
import zio.test._
import dev.openfeature.sdk.ImmutableContext
import dev.openfeature.sdk.exceptions.ParseError

object EnvVarProviderSpec extends ZIOSpecDefault {

  private val testEnv = Map(
    "FF_NEW_CHECKOUT" -> "true",
    "FF_MAX_ITEMS"    -> "50",
    "FF_RATE_LIMIT"   -> "2.5",
    "FF_WELCOME_MSG"  -> "Hello!",
    "FF_DISABLED"     -> "false",
    "FF_BAD_NUMBER"   -> "not-a-number",
    "FF_BAD_BOOL"     -> "enabled"
  )

  private val provider = EnvVarProvider.withLookup(testEnv.get)
  private val ctx      = new ImmutableContext()

  def spec = suite("EnvVarProvider")(
    suite("boolean evaluation")(
      test("returns true for 'true'") {
        val result = provider.getBooleanEvaluation("new-checkout", false, ctx)
        assertTrue(result.getValue == true) &&
        assertTrue(result.getReason == "STATIC")
      },
      test("returns false for 'false'") {
        val result = provider.getBooleanEvaluation("disabled", false, ctx)
        assertTrue(result.getValue == false)
      },
      test("returns the caller's default with FLAG_NOT_FOUND for a missing key") {
        // The value is still the caller's default; the reason is no longer DEFAULT, so a MultiProvider chain and an
        // operator can both tell "not set here" from "set to this value" (#355).
        val result = provider.getBooleanEvaluation("missing", true, ctx)
        assertTrue(result.getValue == true) &&
        assertTrue(result.getErrorCode == dev.openfeature.sdk.ErrorCode.FLAG_NOT_FOUND)
      },
      test("throws ParseError for a set-but-unparseable value (#262)") {
        // Previously this returned the default labeled STATIC, falsely claiming it came from the environment.
        ZIO
          .attempt(provider.getBooleanEvaluation("bad-bool", false, ctx))
          .flip
          .map(e => assertTrue(e.isInstanceOf[ParseError]))
      }
    ),
    suite("string evaluation")(
      test("returns env var value") {
        val result = provider.getStringEvaluation("welcome-msg", "default", ctx)
        assertTrue(result.getValue == "Hello!")
      },
      test("returns default for missing key") {
        val result = provider.getStringEvaluation("missing", "default", ctx)
        assertTrue(result.getValue == "default")
      }
    ),
    suite("integer evaluation")(
      test("returns parsed integer") {
        val result = provider.getIntegerEvaluation("max-items", 10, ctx)
        assertTrue(result.getValue == 50)
      },
      test("throws ParseError for a set-but-unparseable value (#262)") {
        // Set-but-unparsable must surface as PARSE_ERROR, not silently collapse to the default (which is
        // indistinguishable from the variable being unset).
        ZIO
          .attempt(provider.getIntegerEvaluation("bad-number", 10, ctx))
          .flip
          .map(e => assertTrue(e.isInstanceOf[ParseError]))
      }
    ),
    suite("double evaluation")(
      test("returns parsed double") {
        val result = provider.getDoubleEvaluation("rate-limit", 1.0, ctx)
        assertTrue(result.getValue == 2.5)
      },
      test("throws ParseError for a set-but-unparseable value (#262)") {
        ZIO
          .attempt(provider.getDoubleEvaluation("bad-number", 1.0, ctx))
          .flip
          .map(e => assertTrue(e.isInstanceOf[ParseError]))
      }
    ),
    suite("key transformation")(
      test("default transform uppercases and replaces dashes") {
        assertTrue(EnvVarProvider.defaultKeyTransform("new-checkout") == "NEW_CHECKOUT")
      },
      test("custom prefix works") {
        val env      = Map("FEAT_ENABLED" -> "true")
        val provider = EnvVarProvider.withLookup(env.get, prefix = "FEAT_")
        val result   = provider.getBooleanEvaluation("enabled", false, ctx)
        assertTrue(result.getValue == true)
      }
    ),
    suite("metadata")(
      test("provider name is EnvVarProvider") {
        assertTrue(provider.getMetadata.getName == "EnvVarProvider")
      }
    )
  )
}
