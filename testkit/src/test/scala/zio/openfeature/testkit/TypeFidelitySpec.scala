package zio.openfeature.testkit

import zio._
import zio.test._
import zio.openfeature._
import dev.openfeature.sdk.{ErrorCode => OFErrorCode, ImmutableContext}

/** The test provider answers like a real provider: a stored value of the wrong type is `TYPE_MISMATCH`, never a silent
  * coercion, a truncation, or a `GENERAL` from a failed cast.
  */
object TypeFidelitySpec extends ZIOSpecDefault {

  private val ctx = new ImmutableContext()

  private def isTypeMismatch(e: Either[FeatureFlagError, Any]): Boolean =
    e.left.toOption.exists(_.isInstanceOf[FeatureFlagError.TypeMismatch])

  def spec = suite("TypeFidelitySpec")(
    suite("through FeatureFlags")(
      test("a Long outside Int range read as int is TYPE_MISMATCH, never truncated") {
        for {
          tp    <- ZIO.service[TestFeatureProvider]
          _     <- tp.setFlag("limit", 5000000000L)
          typed <- FeatureFlags.intDetails("limit", 7).either
          total <- FeatureFlags.resolveOrDefault[Int]("limit", default = 7)
        } yield assertTrue(
          isTypeMismatch(typed),
          total.errorCode.contains(ErrorCode.TypeMismatch),
          total.value == 7
        )
      },
      test("a Boolean read as string is TYPE_MISMATCH, not \"true\"") {
        for {
          tp    <- ZIO.service[TestFeatureProvider]
          _     <- tp.setFlag("b", true)
          typed <- FeatureFlags.stringDetails("b", "fallback").either
          total <- FeatureFlags.resolveOrDefault[String]("b", default = "fallback")
        } yield assertTrue(
          isTypeMismatch(typed),
          total.errorCode.contains(ErrorCode.TypeMismatch),
          total.value == "fallback"
        )
      },
      test("a String read as boolean is TYPE_MISMATCH, not GENERAL") {
        for {
          tp    <- ZIO.service[TestFeatureProvider]
          _     <- tp.setFlag("s", "true")
          typed <- FeatureFlags.booleanDetails("s", default = false).either
          total <- FeatureFlags.resolveOrDefault[Boolean]("s", default = false)
        } yield assertTrue(
          isTypeMismatch(typed),
          total.errorCode.contains(ErrorCode.TypeMismatch),
          total.value == false
        )
      },
      test("same-type reads are unchanged") {
        for {
          tp <- ZIO.service[TestFeatureProvider]
          _  <- tp.setFlags(Map("b" -> true, "s" -> "hello", "i" -> 42, "l" -> 5000000000L, "d" -> 2.5))
          b  <- FeatureFlags.boolean("b", default = false)
          s  <- FeatureFlags.string("s", "fallback")
          i  <- FeatureFlags.int("i", 0)
          l  <- FeatureFlags.long("l", 0L)
          d  <- FeatureFlags.double("d", 0.0)
        } yield assertTrue(b == true, s == "hello", i == 42, l == 5000000000L, d == 2.5)
      },
      test("a double beyond Float range read as Float is TYPE_MISMATCH, never Infinity") {
        for {
          tp    <- ZIO.service[TestFeatureProvider]
          _     <- tp.setFlags(Map("f" -> 1e300, "neg" -> -1e300))
          typed <- FeatureFlags.value[Float]("f", 0f).either
          negT  <- FeatureFlags.value[Float]("neg", 0f).either
          total <- FeatureFlags.resolveOrDefault[Float]("f", default = 0.5f)
        } yield assertTrue(
          isTypeMismatch(typed),
          isTypeMismatch(negT),
          total.errorCode.contains(ErrorCode.TypeMismatch),
          total.value == 0.5f
        )
      },
      test("a custom Float-wire type still receives a Float for every in-range value") {
        // Accepts only a Float: a Double reaching it would be a TYPE_MISMATCH.
        implicit val floatOnly: FlagType[Float] = new FlagType[Float] {
          def typeName: String                          = "FloatOnly"
          override def wire: WireType                   = WireType.Float
          def defaultValue: Float                       = 0f
          def decode(value: Any): Either[String, Float] = value match {
            case f: Float => Right(f)
            case other    => Left(s"not a Float: $other")
          }
        }
        for {
          tp  <- ZIO.service[TestFeatureProvider]
          _   <- tp.setFlags(Map("small" -> 2.5, "max" -> Float.MaxValue.toDouble))
          sm  <- FeatureFlags.value[Float]("small", 0f).either
          max <- FeatureFlags.value[Float]("max", 0f).either
        } yield assertTrue(sm == Right(2.5f), max == Right(Float.MaxValue))
      },
      test("Float reads at and inside the range edge keep their value") {
        for {
          tp  <- ZIO.service[TestFeatureProvider]
          _   <- tp.setFlags(Map("max" -> Float.MaxValue.toDouble, "small" -> 2.5, "neg" -> -1.0e30))
          max <- FeatureFlags.value[Float]("max", 0f)
          sm  <- FeatureFlags.value[Float]("small", 0f)
          neg <- FeatureFlags.value[Float]("neg", 0f)
        } yield assertTrue(max == Float.MaxValue, sm == 2.5f, neg == -1.0e30f)
      },
      test("an Instant flag reads back as an Instant, not as its text") {
        val t                                                 = java.time.Instant.parse("2026-01-01T00:00:00Z")
        implicit val instantType: FlagType[java.time.Instant] = FlagType.from[java.time.Instant](
          "Instant",
          java.time.Instant.EPOCH,
          {
            case i: java.time.Instant => Right(i)
            case other                => Left(s"not an Instant: $other")
          }
        )
        for {
          tp   <- ZIO.service[TestFeatureProvider]
          _    <- tp.setFlag("t", t)
          read <- FeatureFlags.value[java.time.Instant]("t", java.time.Instant.EPOCH).either
        } yield assertTrue(read == Right(t))
      }
    ).provide(TestFeatureProvider.layer),
    suite("on the provider directly")(
      test("a String read as int is TYPE_MISMATCH with the default") {
        for {
          provider <- TestFeatureProvider.make(Map("n" -> "5"))
          result = provider.getIntegerEvaluation("n", 9, ctx)
        } yield assertTrue(result.getErrorCode == OFErrorCode.TYPE_MISMATCH, result.getValue.intValue == 9)
      },
      test("a Double read as int or long is TYPE_MISMATCH") {
        for {
          provider <- TestFeatureProvider.make(Map("d" -> 1.5))
          asInt  = provider.getIntegerEvaluation("d", 9, ctx)
          asLong = provider.getLongEvaluation("d", 9L, ctx)
        } yield assertTrue(
          asInt.getErrorCode == OFErrorCode.TYPE_MISMATCH,
          asInt.getValue.intValue == 9,
          asLong.getErrorCode == OFErrorCode.TYPE_MISMATCH,
          asLong.getValue.longValue == 9L
        )
      },
      test("no widening: an Int read as long or double is TYPE_MISMATCH") {
        for {
          provider <- TestFeatureProvider.make(Map("i" -> 3))
          asLong   = provider.getLongEvaluation("i", 9L, ctx)
          asDouble = provider.getDoubleEvaluation("i", 9.0, ctx)
        } yield assertTrue(
          asLong.getErrorCode == OFErrorCode.TYPE_MISMATCH,
          asLong.getValue.longValue == 9L,
          asDouble.getErrorCode == OFErrorCode.TYPE_MISMATCH,
          asDouble.getValue.doubleValue == 9.0
        )
      },
      test("an Int read as string is TYPE_MISMATCH, not \"42\"") {
        for {
          provider <- TestFeatureProvider.make(Map("s" -> 42))
          result = provider.getStringEvaluation("s", "fallback", ctx)
        } yield assertTrue(result.getErrorCode == OFErrorCode.TYPE_MISMATCH, result.getValue == "fallback")
      },
      test("the mismatch message names the stored and the requested type") {
        for {
          provider <- TestFeatureProvider.make(Map("limit" -> 5000000000L))
          result = provider.getIntegerEvaluation("limit", 0, ctx)
        } yield assertTrue(result.getErrorMessage == "Flag 'limit' holds a Long, read as Integer")
      },
      test("a FlagDef[Float] round-trips: its Float encoding is served by the double resolver") {
        val ratio = FlagDef[Float]("ratio", 0.0f)
        for {
          provider <- TestFeatureProvider.make
          _        <- provider.setFlag(ratio, 0.25f)
          result = provider.getDoubleEvaluation("ratio", 0.0, ctx)
        } yield assertTrue(result.getErrorCode == null, result.getValue.doubleValue == 0.25)
      }
    ),
    suite("layerFrom readiness")(
      // The bridge used to get a second, late PROVIDER_READY that overwrote the status as recovery.
      test("a status set right after layerFrom builds is not overwritten by a late PROVIDER_READY") {
        ZIO.scoped {
          for {
            p      <- TestFeatureProvider.make
            ff     <- TestFeatureProvider.layerFrom(p).build.map(_.get[FeatureFlags])
            _      <- p.setStatus(ProviderStatus.Error)
            _      <- Live.live(ZIO.sleep(20.millis))
            status <- ff.providerStatus
          } yield assertTrue(status == ProviderStatus.Error)
        }
      } @@ TestAspect.nonFlaky(500),
      test("a status set right after TestFeatureProvider.layer builds is not overwritten by a late PROVIDER_READY") {
        ZIO.scoped {
          for {
            env    <- TestFeatureProvider.layer.build
            _      <- env.get[TestFeatureProvider].setStatus(ProviderStatus.Error)
            _      <- Live.live(ZIO.sleep(20.millis))
            status <- env.get[FeatureFlags].providerStatus
          } yield assertTrue(status == ProviderStatus.Error)
        }
      } @@ TestAspect.nonFlaky(500)
    )
  )
}
