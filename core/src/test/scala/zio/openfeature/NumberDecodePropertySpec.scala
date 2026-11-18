package zio.openfeature

import zio.openfeature.testsupport.PropertyBudget

import java.math.{BigDecimal => JBigDecimal, BigInteger}
import java.util.concurrent.atomic.AtomicLong

import zio.test._

// The built-in Int and Long decoders take any whole Number losslessly and refuse everything else. Each property states
// the exact expected answer, computed in BigInt/BigDecimal arithmetic, never through a Double.
object NumberDecodePropertySpec extends ZIOSpecDefault {

  /** Every arbitrary-precision and atomic carrier of a whole number `l`. */
  private def carriers(l: BigInt): List[Number] = {
    val base = List[Number](l, l.bigInteger, new JBigDecimal(l.bigInteger), BigDecimal(l))
    if (l.isValidLong) new AtomicLong(l.toLong) :: base else base
  }

  /** Whole numbers anywhere in Long range, past both ends, and near 2^53 where a Double stops being exact. */
  private val whole: Gen[Any, BigInt] = Gen.oneOf(
    Gen.long.map(BigInt(_)),
    Gen.long(-1000L, 1000L).map(d => BigInt(Long.MaxValue) + d),
    Gen.long(-1000L, 1000L).map(d => BigInt(Long.MinValue) + d),
    Gen.long(-1000L, 1000L).map(d => BigInt(1L << 53) + d),
    Gen.long(-1000L, 1000L).map(d => BigInt(Int.MaxValue) + d)
  )

  private def halfUlp(x: Double): JBigDecimal = new JBigDecimal(Math.ulp(x)).divide(JBigDecimal.valueOf(2))

  private val overflowFrom: JBigDecimal = new JBigDecimal(Double.MaxValue).add(halfUlp(Double.MaxValue))

  def spec = suite("NumberDecodePropertySpec")(
    test("a whole number decodes as a Long exactly when it is in Long range, and otherwise fails") {
      check(whole) { l =>
        val expected = if (l.isValidLong) Right(l.toLong) else Left(())
        assertTrue(carriers(l).forall(n => FlagType[Long].decode(n).left.map(_ => ()) == expected))
      }
    },
    test("a whole number decodes as an Int exactly when it is in Int range, and otherwise fails") {
      check(whole) { l =>
        val expected = if (l.isValidInt) Right(l.toInt) else Left(())
        assertTrue(carriers(l).forall(n => FlagType[Int].decode(n).left.map(_ => ()) == expected))
      }
    },
    test("a whole number plus any nonzero fraction, however small, never decodes as an Int or a Long") {
      check(Gen.long(Int.MinValue.toLong, Int.MaxValue.toLong), Gen.int(1, 60), Gen.boolean) { (l, digits, up) =>
        val step     = JBigDecimal.ONE.movePointLeft(digits)
        val n        = new JBigDecimal(l).add(if (up) step else step.negate)
        val scalaBig = BigDecimal(n)
        assertTrue(
          FlagType[Long].decode(n).isLeft,
          FlagType[Int].decode(n).isLeft,
          FlagType[Long].decode(scalaBig).isLeft,
          FlagType[Int].decode(scalaBig).isLeft
        )
      }
    },
    test("Double gets the nearest double to a finite number, and fails only on overflow") {
      check(Gen.long, Gen.int(-400, 400)) { (unscaled, scale) =>
        val n = new JBigDecimal(BigInteger.valueOf(unscaled), scale)
        val r = FlagType[Double].decode(n)
        // Worked out in exact decimals: the value rounds to an infinity from halfway past Double.MaxValue (its last
        // mantissa bit is odd, so the tie rounds up); below that the answer is within half an ulp of the value.
        val overflows          = n.abs.compareTo(overflowFrom) >= 0
        def nearest(x: Double) =
          !x.isInfinite && new JBigDecimal(x).subtract(n).abs.compareTo(halfUlp(x)) <= 0
        assertTrue(if (overflows) r.isLeft else r.exists(nearest))
      }
    }
  ) @@ PropertyBudget(200)
}
