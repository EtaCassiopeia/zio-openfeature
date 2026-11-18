package zio.openfeature.internal

import java.math.{BigDecimal => JBigDecimal, BigInteger}
import java.util.concurrent.atomic.{
  AtomicInteger,
  AtomicLong,
  DoubleAccumulator,
  DoubleAdder,
  LongAccumulator,
  LongAdder
}

/** The exact value of a `java.lang.Number`, for the built-in decoders, which must convert a number losslessly or fail.
  *
  * `Number.doubleValue()` cannot serve that purpose: it rounds a `BigDecimal`, a `BigInteger` or a `Long` beyond 2^53
  * before any check can see the value, so a check on the double passes for a neighbouring number.
  */
private[openfeature] object ExactNumber {

  /** `n`'s exact value; `None` for NaN, an infinity, or a `Number` whose value cannot be read exactly.
    *
    * A `Number` type not listed here is read from its `toString`, which every JDK and Scala number type writes as a
    * decimal; one whose `toString` is not a decimal has no exact value.
    */
  def apply(n: Number): Option[JBigDecimal] = n match {
    case b: JBigDecimal           => Some(b)
    case b: BigInteger            => Some(new JBigDecimal(b))
    case b: scala.math.BigDecimal => Some(b.bigDecimal)
    case b: scala.math.BigInt     => Some(new JBigDecimal(b.bigInteger))
    case _: java.lang.Byte | _: java.lang.Short | _: java.lang.Integer | _: java.lang.Long | _: AtomicInteger |
        _: AtomicLong | _: LongAdder | _: LongAccumulator =>
      Some(JBigDecimal.valueOf(n.longValue()))
    case _: java.lang.Float | _: java.lang.Double | _: DoubleAdder | _: DoubleAccumulator =>
      val d = n.doubleValue()
      if (d.isNaN || d.isInfinite) None else Some(new JBigDecimal(d))
    case other =>
      Option(other.toString).flatMap { s =>
        try Some(new JBigDecimal(s))
        catch { case _: NumberFormatException => None }
      }
  }

  /** `n` as an `Int` when it is exactly one, with no fraction and within range. */
  def toInt(n: Number): Option[Int] =
    apply(n).flatMap { b =>
      try Some(b.intValueExact())
      catch { case _: ArithmeticException => None }
    }

  /** `n` as a `Long` when it is exactly one, with no fraction and within range. */
  def toLong(n: Number): Option[Long] =
    apply(n).flatMap { b =>
      try Some(b.longValueExact())
      catch { case _: ArithmeticException => None }
    }
}
