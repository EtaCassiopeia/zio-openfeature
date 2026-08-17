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
      test("returns the caller's default with FLAG_NOT_FOUND for a missing key") {
        // The value is still the caller's default; the reason is no longer DEFAULT, so a MultiProvider chain and an
        // operator can both tell "not configured here" from "configured to this value" (#355).
        val result = provider.getBooleanEvaluation("missing", true, ctx)
        assertTrue(result.getValue == true) &&
        assertTrue(result.getErrorCode == dev.openfeature.sdk.ErrorCode.FLAG_NOT_FOUND)
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
    suite("error codes (spec 7.3.6)")(
      test("wrong-typed value evaluated as boolean throws TypeMismatchError") {
        val result = ZIO.attempt(provider.getBooleanEvaluation("welcome-message", false, ctx)).exit
        result.map { exit =>
          assertTrue(exit.isFailure) &&
          assertTrue(exit.causeOption.flatMap(_.failureOption).exists {
            case e: dev.openfeature.sdk.exceptions.OpenFeatureError =>
              e.getErrorCode == dev.openfeature.sdk.ErrorCode.TYPE_MISMATCH
            case _ => false
          })
        }
      },
      test("wrong-typed value evaluated as int throws TypeMismatchError") {
        val result = ZIO.attempt(provider.getIntegerEvaluation("new-checkout", 0, ctx)).exit
        result.map { exit =>
          assertTrue(exit.isFailure) &&
          assertTrue(exit.causeOption.flatMap(_.failureOption).exists {
            case e: dev.openfeature.sdk.exceptions.OpenFeatureError =>
              e.getErrorCode == dev.openfeature.sdk.ErrorCode.TYPE_MISMATCH
            case _ => false
          })
        }
      },
      test("wrong-typed value evaluated as double throws TypeMismatchError") {
        val result = ZIO.attempt(provider.getDoubleEvaluation("welcome-message", 0.0, ctx)).exit
        result.map { exit =>
          assertTrue(exit.isFailure) &&
          assertTrue(exit.causeOption.flatMap(_.failureOption).exists {
            case e: dev.openfeature.sdk.exceptions.OpenFeatureError =>
              e.getErrorCode == dev.openfeature.sdk.ErrorCode.TYPE_MISMATCH
            case _ => false
          })
        }
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
    ),
    suite("reload (#260)")(
      test("fromConfig reload preserves the injected config instead of discarding it to the classpath") {
        val cfg = ConfigFactory.parseString("only-here = true")
        val p   = HoconProvider.fromConfig(cfg)
        for {
          before <- ZIO.succeed(p.getBooleanEvaluation("only-here", false, ctx))
          _      <- p.reload()
          after  <- ZIO.succeed(p.getBooleanEvaluation("only-here", false, ctx))
        } yield assertTrue(
          before.getValue == true,
          after.getValue == true, // pre-fix: reload discarded cfg → classpath has no "only-here" → false/DEFAULT
          after.getReason == "STATIC"
        )
      },
      test("apply(path) reload re-reads the constructed path, not the hardcoded 'feature-flags'") {
        java.lang.System.setProperty("issue260-flags.limit", "10")
        ConfigFactory.invalidateCaches()
        val p = HoconProvider("issue260-flags")
        (for {
          before <- ZIO.succeed(p.getIntegerEvaluation("limit", 0, ctx))
          _      <- ZIO.succeed(java.lang.System.setProperty("issue260-flags.limit", "20"))
          _      <- p.reload()
          after  <- ZIO.succeed(p.getIntegerEvaluation("limit", 0, ctx))
        } yield assertTrue(
          before.getValue == 10,
          after.getValue == 20 // proves reload re-read "issue260-flags"; the hardcoded "feature-flags" would give 0
        )).ensuring(ZIO.succeed(java.lang.System.clearProperty("issue260-flags.limit")))
      }
    ) @@ TestAspect.sequential
  )
}
