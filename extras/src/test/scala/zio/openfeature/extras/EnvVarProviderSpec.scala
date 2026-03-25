package zio.openfeature.extras

import zio._
import zio.test._
import dev.openfeature.sdk.ImmutableContext

object EnvVarProviderSpec extends ZIOSpecDefault {

  private val testEnv = Map(
    "FF_NEW_CHECKOUT" -> "true",
    "FF_MAX_ITEMS"    -> "50",
    "FF_RATE_LIMIT"   -> "2.5",
    "FF_WELCOME_MSG"  -> "Hello!",
    "FF_DISABLED"     -> "false",
    "FF_BAD_NUMBER"   -> "not-a-number"
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
      test("returns default for missing key") {
        val result = provider.getBooleanEvaluation("missing", true, ctx)
        assertTrue(result.getValue == true) &&
        assertTrue(result.getReason == "DEFAULT")
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
      test("returns default for unparseable value") {
        val result = provider.getIntegerEvaluation("bad-number", 10, ctx)
        assertTrue(result.getValue == 10)
      }
    ),
    suite("double evaluation")(
      test("returns parsed double") {
        val result = provider.getDoubleEvaluation("rate-limit", 1.0, ctx)
        assertTrue(result.getValue == 2.5)
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
