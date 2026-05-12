package zio.openfeature.extras

import com.typesafe.config.ConfigFactory
import zio._
import zio.test._
import dev.openfeature.sdk.ImmutableContext

object HoconProviderSpec extends ZIOSpecDefault {

  private val config = ConfigFactory.parseString("""
    new-checkout = true
    max-items = 50
    rate-limit = 2.5
    welcome-message = "Hello!"
    settings {
      timeout = 30
      retries = 3
    }
    allowed-regions = ["us", "eu", "ap"]
    primes = [2, 3, 5, 7]
  """)

  private val provider = HoconProvider.fromConfig(config)
  private val ctx      = new ImmutableContext()

  def spec = suite("HoconProvider")(
    suite("boolean evaluation")(
      test("returns configured boolean") {
        val result = provider.getBooleanEvaluation("new-checkout", false, ctx)
        assertTrue(result.getValue == true) &&
        assertTrue(result.getReason == "STATIC")
      },
      test("returns default for missing key") {
        val result = provider.getBooleanEvaluation("missing", true, ctx)
        assertTrue(result.getValue == true) &&
        assertTrue(result.getReason == "DEFAULT")
      }
    ),
    suite("string evaluation")(
      test("returns configured string") {
        val result = provider.getStringEvaluation("welcome-message", "default", ctx)
        assertTrue(result.getValue == "Hello!")
      }
    ),
    suite("integer evaluation")(
      test("returns configured integer") {
        val result = provider.getIntegerEvaluation("max-items", 0, ctx)
        assertTrue(result.getValue == 50)
      }
    ),
    suite("double evaluation")(
      test("returns configured double") {
        val result = provider.getDoubleEvaluation("rate-limit", 0.0, ctx)
        assertTrue(result.getValue == 2.5)
      }
    ),
    suite("object evaluation")(
      test("returns nested config as object") {
        val result = provider.getObjectEvaluation("settings", new dev.openfeature.sdk.Value(), ctx)
        assertTrue(result.getReason == "STATIC")
      },
      test("list of strings unwraps to non-null Value elements") {
        import scala.jdk.CollectionConverters._
        val result = provider.getObjectEvaluation("allowed-regions", new dev.openfeature.sdk.Value(), ctx)
        val list   = result.getValue.asList().asScala.toList
        assertTrue(result.getReason == "STATIC") &&
        assertTrue(list.size == 3) &&
        assertTrue(list.forall(v => v != null && v.asString() != null)) &&
        assertTrue(list.map(_.asString()) == List("us", "eu", "ap"))
      },
      test("list of numbers unwraps to non-null numeric Values") {
        import scala.jdk.CollectionConverters._
        val result = provider.getObjectEvaluation("primes", new dev.openfeature.sdk.Value(), ctx)
        val list   = result.getValue.asList().asScala.toList
        assertTrue(list.size == 4) &&
        assertTrue(list.forall(_ != null)) &&
        assertTrue(list.map(_.asInteger().intValue()) == List(2, 3, 5, 7))
      }
    ),
    suite("metadata")(
      test("provider name is HoconProvider") {
        assertTrue(provider.getMetadata.getName == "HoconProvider")
      }
    ),
    suite("factory methods")(
      test("fromConfig creates provider from Config object") {
        val cfg = ConfigFactory.parseString("flag = true")
        val p   = HoconProvider.fromConfig(cfg)
        val r   = p.getBooleanEvaluation("flag", false, ctx)
        assertTrue(r.getValue == true)
      }
    )
  )
}
