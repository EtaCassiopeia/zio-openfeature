package zio.openfeature.testkit

import zio._
import zio.openfeature._
import zio.test._

/** `FlagType[Int]`/`FlagType[Long]` accept "any whole Number" (ConversionBoundarySpec), and must not go through
  * `Number.doubleValue()`, which rounds above 2^53 and drops a tiny fraction. A `java.math.BigDecimal` or `BigInteger`
  * override (the natural result of parsing a JSON override file with Jackson's
  * `USE_BIG_DECIMAL_FOR_FLOATS`/`USE_BIG_INTEGER_FOR_INTS`, or a Scala `BigDecimal`) would otherwise decode to a
  * different number.
  */
object WholeNumberDecodeSpec extends ZIOSpecDefault {

  private val big = (1L << 53) + 1 // 9007199254740993, not a Double

  def spec = suite("WholeNumberDecodeSpec")(
    test("control: a java.lang.Long beyond 2^53 decodes exactly") {
      assertTrue(FlagType[Long].decode(java.lang.Long.valueOf(big)) == Right(big))
    },
    test("Long: a BigInteger beyond 2^53 decodes to itself or fails, never to a neighbour") {
      val r = FlagType[Long].decode(java.math.BigInteger.valueOf(big))
      assertTrue(r == Right(big))
    },
    test("Long: a java.math.BigDecimal beyond 2^53 decodes to itself or fails") {
      val r = FlagType[Long].decode(new java.math.BigDecimal(big))
      assertTrue(r == Right(big))
    },
    test("Int: a BigDecimal with a fraction below double precision is rejected like 5.5") {
      val r = FlagType[Int].decode(new java.math.BigDecimal("5.0000000000000001"))
      assertTrue(r.isLeft)
    },
    test("end to end: a Long override given as a Scala BigDecimal is served as that number or rejected") {
      for {
        p <- TestFeatureProvider.make(Map("limit" -> 0L))
        r <- FeatureFlags
          .fromProvider(p)
          .build
          .flatMap { env =>
            env
              .get[FeatureFlags]
              .transactionEither(overrides = Map("limit" -> BigDecimal(big)))(
                env.get[FeatureFlags].long("limit", 0L)
              )
          }
          .either
        served = r.map(_.result)
      } yield assertTrue(served == Right(big))
    }
  ) @@ TestAspect.withLiveClock
}
