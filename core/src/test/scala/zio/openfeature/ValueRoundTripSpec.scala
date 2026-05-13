package zio.openfeature

import zio._
import zio.openfeature.internal.ContextConverter
import zio.test._

/** Property-based coverage of value round-tripping through the OpenFeature Java SDK boundary (workstream B3).
  *
  * The cross-boundary conversion is intentionally lossy because the Java SDK normalises every numeric to a single
  * `Double` type, so `IntValue` and `LongValue` are mediated through `Double` and may come back as a different concrete
  * `AttributeValue` case. The properties below pin the contract callers actually rely on — that the value round-trips
  * as observed through the typed accessors (`asBoolean`, `asString`, `asLong`, `asDouble`, etc.).
  *
  * If a property finds a counterexample, fix the converter (or the property), then add a non-property regression test
  * pinning the exact case so it never resurfaces.
  */
object ValueRoundTripSpec extends ZIOSpecDefault {

  private def roundTrip(attr: AttributeValue): AttributeValue = {
    val ctx     = EvaluationContext(None, Map("k" -> attr))
    val javaCtx = ContextConverter.toOpenFeature(ctx)
    val backCtx = ContextConverter.fromOpenFeature(javaCtx)
    backCtx.attributes("k")
  }

  /** Doubles that round-trip predictably: skip NaN (NaN != NaN), skip ±Infinity (Java SDK normalises these), and skip
    * whole-number doubles (they come back as `IntValue` / `LongValue`, not `DoubleValue`).
    */
  private val finiteFractionalDouble: Gen[Any, Double] =
    Gen.double(-1e10, 1e10).filter(d => !d.isWhole && !d.isNaN && !d.isInfinity)

  /** Longs that round-trip exactly through `Double`. Doubles have 53 bits of mantissa, so any Long in `[Long.MinValue,
    * 2^53)` is representable exactly. Outside that window we'd lose precision. We also bound below at a value the
    * converter actually maps back as `LongValue` rather than `IntValue` (above Int.MaxValue).
    */
  private val largeLong: Gen[Any, Long] =
    Gen.long(Int.MaxValue.toLong + 1L, (1L << 53) - 1L)

  /** Strings the converter accepts losslessly. Non-empty so the Java SDK's empty-string treatment isn't asserted here
    * (a corner case orthogonal to the round-trip property).
    */
  private val nonEmptyString: Gen[Any, String] =
    Gen.string1(Gen.unicodeChar)

  def spec = suite("ValueRoundTripSpec")(
    test("BoolValue round-trips exactly") {
      check(Gen.boolean) { b =>
        roundTrip(AttributeValue.BoolValue(b)) match {
          case AttributeValue.BoolValue(v) => assertTrue(v == b)
          case other                       => assertTrue(false: Boolean) ?? s"expected BoolValue, got $other"
        }
      }
    },
    test("StringValue round-trips for non-empty unicode strings") {
      check(nonEmptyString) { s =>
        roundTrip(AttributeValue.StringValue(s)) match {
          case AttributeValue.StringValue(v) => assertTrue(v == s)
          case other                         => assertTrue(false: Boolean) ?? s"expected StringValue, got $other"
        }
      }
    },
    test("IntValue round-trips exactly") {
      check(Gen.int) { i =>
        // Int always fits in Double's mantissa (53 bits), and our converter maps small whole numbers back to IntValue.
        roundTrip(AttributeValue.IntValue(i)) match {
          case AttributeValue.IntValue(v) => assertTrue(v == i)
          case other                      => assertTrue(false: Boolean) ?? s"expected IntValue, got $other"
        }
      }
    },
    test("LongValue (large enough to be distinct from Int) round-trips as LongValue") {
      check(largeLong) { l =>
        roundTrip(AttributeValue.LongValue(l)) match {
          case AttributeValue.LongValue(v) => assertTrue(v == l)
          case other                       => assertTrue(false: Boolean) ?? s"expected LongValue($l), got $other"
        }
      }
    },
    test("DoubleValue (finite, fractional) round-trips exactly") {
      check(finiteFractionalDouble) { d =>
        roundTrip(AttributeValue.DoubleValue(d)) match {
          case AttributeValue.DoubleValue(v) => assertTrue(v == d)
          case other                         => assertTrue(false: Boolean) ?? s"expected DoubleValue, got $other"
        }
      }
    },
    test("DoubleValue NaN survives as a NaN double (not necessarily equal-by-==)") {
      // NaN is its own equivalence class — only `isNaN` is reliable.
      val r = roundTrip(AttributeValue.DoubleValue(Double.NaN))
      val isNaN = r match {
        case AttributeValue.DoubleValue(v) => v.isNaN
        case _                             => false
      }
      assertTrue(isNaN)
    },
    test("LongValue(Long.MaxValue) lands as DoubleValue (documents the converter's strict-upper-bound)") {
      // `valueToAttribute` rejects values >= Long.MaxValue.toDouble because the conversion saturates at that point.
      // We don't assert exact equality on the resulting Double — the point is that no information is silently lost as
      // an out-of-range Long; the caller sees a Double they can detect and handle.
      val r = roundTrip(AttributeValue.LongValue(Long.MaxValue))
      val isDouble = r match {
        case _: AttributeValue.DoubleValue => true
        case _                             => false
      }
      assertTrue(isDouble)
    },
    test("Nested struct round-trip preserves leaf values via their getters") {
      val structGen: Gen[Any, AttributeValue.StructValue] = for {
        b <- Gen.boolean
        s <- nonEmptyString
        i <- Gen.int
        d <- finiteFractionalDouble
      } yield AttributeValue.StructValue(
        Map(
          "bool"   -> AttributeValue.BoolValue(b),
          "string" -> AttributeValue.StringValue(s),
          "int"    -> AttributeValue.IntValue(i),
          "double" -> AttributeValue.DoubleValue(d)
        )
      )
      check(structGen) { struct =>
        roundTrip(struct) match {
          case AttributeValue.StructValue(fields) =>
            assertTrue(
              fields("bool").asBoolean.contains(struct.fields("bool").asBoolean.get),
              fields("string").asString.contains(struct.fields("string").asString.get),
              fields("int").asInt.contains(struct.fields("int").asInt.get),
              fields("double").asDouble.contains(struct.fields("double").asDouble.get)
            )
          case other =>
            assertTrue(false: Boolean) ?? s"expected StructValue, got $other"
        }
      }
    },
    test("ListValue round-trip preserves the leaf values") {
      val listGen: Gen[Any, AttributeValue.ListValue] =
        Gen.listOfBounded(0, 8)(Gen.int.map(AttributeValue.IntValue.apply)).map(AttributeValue.ListValue.apply)
      check(listGen) { list =>
        roundTrip(list) match {
          case AttributeValue.ListValue(items) =>
            val expected = list.values.flatMap(_.asInt)
            val actual   = items.flatMap(_.asInt)
            assertTrue(actual == expected)
          case other =>
            assertTrue(false: Boolean) ?? s"expected ListValue, got $other"
        }
      }
    },
    test("EvaluationContext with targetingKey + mixed attributes round-trips") {
      val ctxGen: Gen[Any, EvaluationContext] = for {
        tk <- Gen.option(nonEmptyString)
        b  <- Gen.boolean
        s  <- nonEmptyString
        i  <- Gen.int
      } yield EvaluationContext(
        targetingKey = tk,
        attributes = Map(
          "bool"   -> AttributeValue.BoolValue(b),
          "string" -> AttributeValue.StringValue(s),
          "int"    -> AttributeValue.IntValue(i)
        )
      )
      check(ctxGen) { ctx =>
        val rt = ContextConverter.fromOpenFeature(ContextConverter.toOpenFeature(ctx))
        assertTrue(
          rt.targetingKey == ctx.targetingKey,
          rt.attributes("bool").asBoolean == ctx.attributes("bool").asBoolean,
          rt.attributes("string").asString == ctx.attributes("string").asString,
          rt.attributes("int").asInt == ctx.attributes("int").asInt
        )
      }
    }
  ) @@ TestAspect.samples(200)
}
