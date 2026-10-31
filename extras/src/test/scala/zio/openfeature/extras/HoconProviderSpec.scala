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

  private val numbers = HoconProvider.fromConfig(ConfigFactory.parseString("""
    fraction = 1.5
    whole = 2.0
    huge = 1e300
    beyond-int = 9999999999
    beyond-double = 9007199254740993
    nan = "NaN"
    small = 7
  """))
  private def mismatch[A](read: => A): Boolean =
    scala.util.Try(read).failed.toOption.exists {
      case e: dev.openfeature.sdk.exceptions.OpenFeatureError =>
        e.getErrorCode == dev.openfeature.sdk.ErrorCode.TYPE_MISMATCH
      case _ => false
    }

  def spec = suite("HoconProvider")(
    suite("boolean evaluation")(
      test("returns configured boolean") {
        val result = provider.getBooleanEvaluation("new-checkout", false, ctx)
        assertTrue(result.getValue == true) &&
        assertTrue(result.getReason == "STATIC")
      },
      test("returns the caller's default with FLAG_NOT_FOUND for a missing key") {
        // The value is still the caller's default; the reason is no longer DEFAULT, so a MultiProvider chain and an
        // operator can both tell "not configured here" from "configured to this value".
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
    suite("number reads never change the value")(
      test("a fraction read as an int or a long is TYPE_MISMATCH, not truncated") {
        val asInt  = mismatch(numbers.getIntegerEvaluation("fraction", 0, ctx))
        val asLong = mismatch(numbers.getLongEvaluation("fraction", 0L, ctx))
        assertTrue(asInt, asLong)
      },
      test("a double beyond the long range read as a long is TYPE_MISMATCH, not saturated") {
        val asLong = mismatch(numbers.getLongEvaluation("huge", 0L, ctx))
        assertTrue(asLong)
      },
      test("a long beyond the int range read as an int is TYPE_MISMATCH") {
        val asInt = mismatch(numbers.getIntegerEvaluation("beyond-int", 0, ctx))
        assertTrue(asInt)
      },
      test("a long a double cannot hold exactly read as a double is TYPE_MISMATCH, not rounded") {
        val asDouble = mismatch(numbers.getDoubleEvaluation("beyond-double", 0.0, ctx))
        assertTrue(asDouble)
      },
      test("NaN read as an int is TYPE_MISMATCH, not 0") {
        val asInt = mismatch(numbers.getIntegerEvaluation("nan", 0, ctx))
        assertTrue(asInt)
      },
      test("an exact conversion is still served: 2.0 as an int, 7 as a long and a double") {
        val two    = numbers.getIntegerEvaluation("whole", 0, ctx).getValue.intValue
        val long   = numbers.getLongEvaluation("small", 0L, ctx).getValue.longValue
        val double = numbers.getDoubleEvaluation("small", 0.0, ctx).getValue.doubleValue
        assertTrue(two == 2, long == 7L, double == 7.0)
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
      },
      test("fromConfig resolves substitutions, so a config parsed without resolve() still reads") {
        val cfg = ConfigFactory.parseString(
          "base = 10\nlimit = ${base}\noptional = ${?ZIO_OPENFEATURE_SURELY_UNSET}\nflag = true"
        )
        val p = HoconProvider.fromConfig(cfg)
        assertTrue(
          p.getBooleanEvaluation("flag", false, ctx).getValue == true,
          p.getIntegerEvaluation("limit", 0, ctx).getValue == 10
        )
      },
      test("fromConfig rejects a substitution it cannot resolve when constructed, not on every read") {
        val cfg = ConfigFactory.parseString("limit = ${ZIO_OPENFEATURE_SURELY_UNSET}")
        assertTrue(scala.util.Try(HoconProvider.fromConfig(cfg)).isFailure)
      }
    ),
    suite("reload")(
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
    ) @@ TestAspect.sequential,
    test("a key that is not a valid HOCON path is FLAG_NOT_FOUND with the default, on every resolver, never a throw") {
      val hocon    = HoconProvider.fromConfig(ConfigFactory.parseString("a = true"))
      val keys     = List("a:b", "flag@v2", "a//b", "x#y", "a$b", "", "a..b", "a,b", "a=b", "a[b", "a\"b")
      val notFound = dev.openfeature.sdk.ErrorCode.FLAG_NOT_FOUND
      val outcomes = keys.map { key =>
        scala.util.Try {
          val b = hocon.getBooleanEvaluation(key, true, ctx)
          val s = hocon.getStringEvaluation(key, "d", ctx)
          val i = hocon.getIntegerEvaluation(key, 7, ctx)
          val l = hocon.getLongEvaluation(key, 8L, ctx)
          val d = hocon.getDoubleEvaluation(key, 9.5, ctx)
          val o = hocon.getObjectEvaluation(key, new dev.openfeature.sdk.Value("o"), ctx)
          List(b, s, i, l, d, o).forall(_.getErrorCode == notFound) &&
          b.getValue == true && s.getValue == "d" && i.getValue == 7 && l.getValue == 8L && d.getValue == 9.5 &&
          o.getValue.asString == "o"
        }
      }
      val wrong = keys.zip(outcomes).collect { case (k, r) if !r.toOption.contains(true) => s"'$k': $r" }
      assertTrue(wrong.isEmpty)
    },
    test("a dotted key still reads a nested path") {
      val hocon = HoconProvider.fromConfig(ConfigFactory.parseString("feature { sub = true }"))
      assertTrue(hocon.getBooleanEvaluation("feature.sub", false, ctx).getValue == true)
    }
  )
}
