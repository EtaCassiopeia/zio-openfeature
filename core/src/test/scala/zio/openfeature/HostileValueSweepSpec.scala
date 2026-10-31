package zio.openfeature

import zio.openfeature.testsupport.PropertyBudget

import dev.openfeature.sdk.Value
import zio.openfeature.internal.{ContextConverter, TrackingConverter, ValueBridge}
import zio.test._

import java.time.Instant
import scala.jdk.CollectionConverters._

/** Hostile-input property sweeps through the value and context conversions.
  *
  * Tidy fixtures miss untidy bugs: a value past 2^53 passes a round-trip check when both sides round identically. Every
  * property here compares against an INDEPENDENT canonical form (exact `BigDecimal` for numbers, class-tagged strings
  * for SDK scalars) so a silent rounding, truncation or stringification fails the property instead of hiding behind
  * symmetric loss. The generators are deliberately hostile: Long at and beyond +-2^53 and at the Int/Long bounds, NaN,
  * \+-Infinity, -0.0, subnormals, empty / emoji / control-character / lone-surrogate strings and keys, nulls in lists
  * and maps, deep nesting and very large containers.
  *
  * Longs beyond +-2^53 are swept separately: they round-trip exactly.
  */
object HostileValueSweepSpec extends ZIOSpecDefault {

  // Generators

  private val hostileStrings: List[String] = List(
    "",
    " ",
    "\u0000",
    "a\u0000b",
    "\u0001\u001f\u007f",
    "\u0085  ",
    "﻿",
    0xd83d.toChar.toString, // lone high surrogate (a literal escape does not parse in scalafmt)
    0xde00.toChar.toString, // lone low surrogate
    "é",
    "é",
    "日本語",
    "😀",
    "👨‍👩‍👧‍👦",
    "\\\"'",
    "null",
    "NaN",
    "%00",
    "\r\n\t"
  )

  private val hostileString: Gen[Any, String] =
    Gen.oneOf(Gen.fromIterable(hostileStrings), Gen.string(Gen.unicodeChar), Gen.string(Gen.char))

  private val negativeZero: Double = java.lang.Double.longBitsToDouble(0x8000000000000000L)

  private val specialDoubles: List[Double] = List(
    Double.NaN,
    Double.PositiveInfinity,
    Double.NegativeInfinity,
    negativeZero,
    0.0,
    Double.MinValue,
    Double.MaxValue,
    java.lang.Double.MIN_VALUE,
    1e-320,
    9007199254740992.0,
    9007199254740994.0,
    9.223372036854775807e18,
    -9.223372036854775808e18,
    1e19
  )

  private val hostileDouble: Gen[Any, Double] =
    Gen.oneOf(Gen.fromIterable(specialDoubles), Gen.double(-1e18, 1e18), Gen.double(-1e300, 1e300))

  private val safeBound: Long = 1L << 53

  private val boundaryLongs: List[Long] = List(
    Long.MinValue,
    Long.MinValue + 1,
    Long.MaxValue,
    Long.MaxValue - 1,
    Int.MinValue.toLong - 1,
    Int.MinValue.toLong,
    Int.MaxValue.toLong,
    Int.MaxValue.toLong + 1,
    -safeBound - 1,
    -safeBound,
    safeBound,
    safeBound + 1,
    (1L << 62) + 1,
    0L,
    -1L
  )

  private val hostileLong: Gen[Any, Long] = Gen.oneOf(Gen.fromIterable(boundaryLongs), Gen.long)

  /** Longs a Double represents exactly: |l| <= 2^53. */
  private val safeLong: Gen[Any, Long] = Gen.long(-safeBound, safeBound)

  /** Longs a Double can NOT in general represent: |l| > 2^53. */
  private val beyondSafeLong: Gen[Any, Long] =
    Gen.oneOf(Gen.long(safeBound + 1, Long.MaxValue), Gen.long(Long.MinValue, -safeBound - 1))

  private def isNegativeZero(d: Double): Boolean = java.lang.Double.compare(d, negativeZero) == 0

  private def sameBits(a: Double, b: Double): Boolean = java.lang.Double.compare(a, b) == 0

  private def asAttr(a: AttributeValue): AttributeValue = a

  /** Attribute trees with hostile leaves and keys. Longs stay within +-2^53 because they round-trip exactly. */
  private def attrGen(depth: Int): Gen[Any, AttributeValue] = {
    val leaf: Gen[Any, AttributeValue] = Gen.oneOf(
      Gen.boolean.map(b => asAttr(AttributeValue.BoolValue(b))),
      hostileString.map(s => asAttr(AttributeValue.StringValue(s))),
      Gen.int.map(i => asAttr(AttributeValue.IntValue(i))),
      safeLong.map(l => asAttr(AttributeValue.LongValue(l))),
      hostileDouble.map(d => asAttr(AttributeValue.DoubleValue(d))),
      Gen.instant(Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z")).map(i => asAttr(AttributeValue.InstantValue(i)))
    )
    if (depth <= 0) leaf
    else
      Gen.oneOf(
        leaf,
        Gen.listOfBounded(0, 4)(attrGen(depth - 1)).map(l => asAttr(AttributeValue.ListValue(l))),
        Gen.mapOfBounded(0, 4)(hostileString, attrGen(depth - 1)).map(m => asAttr(AttributeValue.StructValue(m)))
      )
  }

  private val anyAttr: Gen[Any, AttributeValue] = Gen.int(0, 3).flatMap(attrGen)

  /** Plain Scala values for the ValueBridge / tracking paths. `None` and `null` appear in lists and maps. */
  private def anyGen(depth: Int, longs: Gen[Any, Long] = safeLong): Gen[Any, Any] = {
    val leaf: Gen[Any, Any] = Gen.oneOf(
      Gen.boolean,
      hostileString,
      Gen.int,
      longs,
      hostileDouble,
      Gen.float,
      Gen.fromIterable(List[Any](null, None))
    )
    if (depth <= 0) leaf
    else
      Gen.oneOf(
        leaf,
        Gen.listOfBounded(0, 4)(anyGen(depth - 1, longs)),
        Gen.mapOfBounded(0, 4)(hostileString, anyGen(depth - 1, longs)),
        anyGen(depth - 1, longs).map(v => Some(v): Any)
      )
  }

  // Canonical forms

  private def exactNumber(d: Double): Any =
    if (d.isNaN || d.isInfinity) ("nonfinite", d.toString) else ("num", BigDecimal.exact(d))

  private def exactNumber(l: Long): Any = ("num", BigDecimal(l))

  /** Context side: numbers compared by exact numeric value, so IntValue(3) == LongValue(3) == DoubleValue(3.0). */
  private def canonAttr(a: AttributeValue): Any = a match {
    case AttributeValue.BoolValue(b)    => ("bool", b)
    case AttributeValue.StringValue(s)  => ("str", s)
    case AttributeValue.IntValue(i)     => exactNumber(i.toLong)
    case AttributeValue.LongValue(l)    => exactNumber(l)
    case AttributeValue.DoubleValue(d)  => exactNumber(d)
    case AttributeValue.InstantValue(i) => ("instant", i)
    case AttributeValue.ListValue(l)    => l.map(canonAttr)
    case AttributeValue.StructValue(m)  => m.map { case (k, v) => k -> canonAttr(v) }
  }

  /** ValueBridge side: `valueToAny` returns Double for every number, so numbers compare by exact numeric value. */
  private def canonAny(x: Any): Any = x match {
    case null           => null
    case None           => null
    case Some(inner)    => canonAny(inner)
    case b: Boolean     => ("bool", b)
    case s: String      => ("str", s)
    case i: Int         => exactNumber(i.toLong)
    case l: Long        => exactNumber(l)
    case d: Double      => exactNumber(d)
    case f: Float       => exactNumber(f.toDouble)
    case i: Instant     => ("instant", i)
    case list: List[_]  => list.map(canonAny)
    case map: Map[_, _] =>
      // A null/None MAP MEMBER reads back as an absent key (documented on ValueBridge); a list element keeps its slot.
      // Evaluated once per member: calling it twice per level is exponential in nesting depth.
      map.toList.flatMap(kv => Option(canonAny(kv._2)).map(kv._1.toString -> _)).toMap
    case other => sys.error(s"unexpected generated value $other")
  }

  private def roundTripAttr(attr: AttributeValue): AttributeValue = {
    val javaCtx = ContextConverter.toOpenFeature(EvaluationContext(None, Map("k" -> attr)))
    ContextConverter.fromOpenFeature(javaCtx).attributes("k")
  }

  /** Class-tagged canonical form of an SDK `Value`, so Integer vs Long vs Double and -0.0 vs 0.0 stay distinguishable
    * (Scala's cooperative boxed equality would otherwise treat `2^53 + 1L == 2^53.toDouble` as equal).
    */
  private def canonValue(v: Value): Any =
    if (v.isList) v.asList().asScala.toList.map(canonValue)
    else if (v.isStructure) v.asStructure().asMap().asScala.toMap.map { case (k, x) => k -> canonValue(x) }
    else
      v.asObject() match {
        case null => null
        case o    => (o.getClass.getSimpleName, o.toString)
      }

  private def expectedTracking(x: Any): Any = x match {
    case null | None       => null
    case Some(inner)       => expectedTracking(inner)
    case b: Boolean        => ("Boolean", b.toString)
    case s: String         => ("String", s)
    case i: Int            => ("Integer", i.toString)
    case l: Long           => if (l.isValidInt) ("Integer", l.toInt.toString) else ("Long", l.toString)
    case d: Double         => ("Double", d.toString)
    case f: Float          => ("Double", f.toDouble.toString)
    case i: Instant        => ("Instant", i.toString)
    case a: AttributeValue =>
      a match {
        case AttributeValue.BoolValue(b)    => ("Boolean", b.toString)
        case AttributeValue.StringValue(s)  => ("String", s)
        case AttributeValue.IntValue(i)     => ("Integer", i.toString)
        case AttributeValue.LongValue(l)    => expectedTracking(l)
        case AttributeValue.DoubleValue(d)  => ("Double", d.toString)
        case AttributeValue.InstantValue(i) => ("Instant", i.toString)
        case AttributeValue.ListValue(l)    => l.map(expectedTracking)
        case AttributeValue.StructValue(m)  => m.map { case (k, v) => k -> expectedTracking(v) }
      }
    case list: List[_]  => list.map(expectedTracking)
    case map: Map[_, _] =>
      // Evaluated once per member: calling it twice per level is exponential in nesting depth.
      map.toList.flatMap(kv => Option(expectedTracking(kv._2)).map(kv._1.toString -> _)).toMap
    case other => sys.error(s"unexpected generated value $other")
  }

  private def trackedAttributes(attrs: Map[String, Any]): Either[String, Map[String, Any]] =
    TrackingConverter
      .toOpenFeature(TrackingEventDetails(value = None, attributes = attrs))
      .map(_.asMap().asScala.toMap.map { case (k, v) => k -> canonValue(v) })

  private def nested(depth: Int, leaf: AttributeValue, asList: Boolean): AttributeValue =
    (1 to depth).foldLeft(leaf) { (acc, _) =>
      if (asList) AttributeValue.ListValue(List(acc)) else AttributeValue.StructValue(Map("k" -> acc))
    }

  def spec = suite("HostileValueSweepSpec")(
    suite("ContextConverter round-trip")(
      test("any string value survives exactly: empty, emoji, control characters, lone surrogates") {
        check(hostileString) { s =>
          val back = roundTripAttr(AttributeValue.StringValue(s))
          assertTrue(back == AttributeValue.StringValue(s))
        }
      },
      test("any string key and the targeting key survive exactly") {
        // `targetingKey` is the one reserved attribute name; every other key round-trips exactly.
        check(hostileString.filter(_ != "targetingKey"), hostileString) { (key, tk) =>
          val ctx  = EvaluationContext(Some(tk), Map(key -> AttributeValue.BoolValue(true)))
          val back = ContextConverter.fromOpenFeature(ContextConverter.toOpenFeature(ctx))
          assertTrue(back == ctx)
        }
      },
      test("Int at and around the bounds, and every Int, survive as the same Int") {
        check(Gen.oneOf(Gen.fromIterable(List(Int.MinValue, Int.MinValue + 1, -1, 0, 1, Int.MaxValue)), Gen.int)) { i =>
          val back = roundTripAttr(AttributeValue.IntValue(i))
          assertTrue(back == AttributeValue.IntValue(i))
        }
      },
      test("every Long within +-2^53 survives with its exact value") {
        check(safeLong) { l =>
          val back = roundTripAttr(AttributeValue.LongValue(l))
          assertTrue(back.asLong.contains(l))
        }
      },
      test("Long.MinValue and the Int-range boundaries survive exactly") {
        val cases = List(Long.MinValue, Int.MinValue.toLong - 1, Int.MinValue.toLong, Int.MaxValue.toLong + 1)
        val backs = cases.map(l => l -> roundTripAttr(AttributeValue.LongValue(l)).asLong)
        assertTrue(backs.forall { case (l, back) => back.contains(l) })
      },
      test("every Long beyond +-2^53 survives with its exact value") {
        check(beyondSafeLong) { l =>
          val back = roundTripAttr(AttributeValue.LongValue(l))
          assertTrue(back.asLong.contains(l))
        }
      },
      test("NaN, +-Infinity, subnormals and extremes survive bit-for-bit") {
        check(hostileDouble.filter(d => !isNegativeZero(d))) { d =>
          val back = roundTripAttr(AttributeValue.DoubleValue(d))
          assertTrue(back.asDouble.exists(sameBits(_, d)))
        }
      },
      test("a whole-valued Double never changes its numeric value") {
        // 2^53+2, 2^63 and 1e19 are whole: the converter may re-type them as Int/Long but must not move them.
        check(Gen.fromIterable(specialDoubles.filter(d => d.isWhole && !d.isNaN && !isNegativeZero(d)))) { d =>
          val back = roundTripAttr(AttributeValue.DoubleValue(d))
          assertTrue(back.asDouble.exists(sameBits(_, d)))
        }
      },
      // Found by this sweep: `-0.0 == 0.0`, so the whole-number check turned DoubleValue(-0.0) into IntValue(0).
      test("DoubleValue(-0.0) keeps its sign") {
        val back = roundTripAttr(AttributeValue.DoubleValue(negativeZero))
        assertTrue(back.asDouble.exists(isNegativeZero))
      },
      test("nested lists and structures with hostile leaves and keys preserve every value exactly") {
        check(anyAttr) { attr =>
          val back = roundTripAttr(attr)
          assertTrue(canonAttr(back) == canonAttr(attr))
        }
      },
      test("empty strings, empty lists and empty structures nested inside containers are preserved") {
        val attr = AttributeValue.StructValue(
          Map(
            "s"  -> AttributeValue.StringValue(""),
            "l"  -> AttributeValue.ListValue(Nil),
            "m"  -> AttributeValue.StructValue(Map.empty),
            "ll" -> AttributeValue.ListValue(List(AttributeValue.StringValue(""), AttributeValue.ListValue(Nil)))
          )
        )
        val back = roundTripAttr(attr)
        assertTrue(canonAttr(back) == canonAttr(attr))
      },
      test("extreme Instants survive exactly, top-level and nested") {
        val instants = List(Instant.MIN, Instant.MAX, Instant.EPOCH, Instant.ofEpochSecond(-1L, 999999999L))
        val backs    = instants.map { i =>
          i -> (
            roundTripAttr(AttributeValue.InstantValue(i)).asInstant,
            roundTripAttr(AttributeValue.ListValue(List(AttributeValue.InstantValue(i)))).asList
              .flatMap(_.headOption.flatMap(_.asInstant))
          )
        }
        assertTrue(backs.forall { case (i, (top, inner)) => top.contains(i) && inner.contains(i) })
      },
      test("deeply nested lists and structures (300 levels) survive") {
        val leaf       = AttributeValue.StringValue("😀")
        val deepList   = nested(300, leaf, asList = true)
        val deepStruct = nested(300, leaf, asList = false)
        val listOk     = canonAttr(roundTripAttr(deepList)) == canonAttr(deepList)
        val structOk   = canonAttr(roundTripAttr(deepStruct)) == canonAttr(deepStruct)
        assertTrue(listOk, structOk)
      },
      test("a very large context (5000 hostile-keyed attributes, a 10000-element list) survives intact") {
        val attrs: Map[String, AttributeValue] =
          (0 until 5000).map(i => s"k$i😀\u0000" -> (AttributeValue.IntValue(i): AttributeValue)).toMap +
            ("big" -> AttributeValue.ListValue(
              (0 until 10000).map(i => AttributeValue.IntValue(i): AttributeValue).toList
            ))
        val ctx  = EvaluationContext(Some("tk"), attrs)
        val back = ContextConverter.fromOpenFeature(ContextConverter.toOpenFeature(ctx))
        assertTrue(back == ctx)
      }
    ),
    suite("ValueBridge round-trip (anyToValue -> valueToAny)")(
      test("scalars, nested lists and maps with None/null members round-trip, preserving list arity") {
        check(Gen.int(0, 3).flatMap(d => anyGen(d))) { x =>
          val back = ValueBridge.valueToAny(ValueBridge.anyToValue(x))
          val want = canonAny(x)
          val got  = back.map(canonAny).orNull
          assertTrue(got == want)
        }
      },
      test("every Long within +-2^53 reads back with its exact value") {
        check(safeLong) { l =>
          val back = ValueBridge.valueToAny(ValueBridge.anyToValue(l))
          assertTrue(back.map(canonAny).contains(exactNumber(l)))
        }
      },
      test("a Long beyond +-2^53 reads back as that exact Long") {
        check(beyondSafeLong) { l =>
          val back = ValueBridge.valueToAny(ValueBridge.anyToValue(l))
          // Typed: Scala's `==` on boxed numbers would equate the Long with its nearest Double.
          assertTrue(back.exists { case b: Long => b == l; case _ => false })
        }
      },
      test("an object flag's Long member beyond +-2^53 survives the Value bridge") {
        check(beyondSafeLong) { l =>
          val back = ValueBridge.valueToAny(ValueBridge.anyToValue(Map("max" -> l)))
          assertTrue(back.exists {
            case m: Map[_, _] =>
              m.asInstanceOf[Map[String, Any]].get("max").exists { case b: Long => b == l; case _ => false }
            case _ => false
          })
        }
      },
      test("NaN, +-Infinity, -0.0 and subnormals survive bit-for-bit") {
        check(hostileDouble) { d =>
          val back = ValueBridge.valueToAny(ValueBridge.anyToValue(d))
          assertTrue(back.exists { case b: Double => sameBits(b, d); case _ => false })
        }
      },
      test("map keys that are empty, emoji or control characters are preserved") {
        check(Gen.listOfBounded(0, 6)(hostileString)) { keys =>
          val m    = keys.zipWithIndex.toMap
          val back = ValueBridge.valueToMap(ValueBridge.anyToValue(m))
          assertTrue(canonAny(back) == canonAny(m))
        }
      },
      test("a null or None list element keeps its slot (no silent shortening)") {
        check(Gen.listOfBounded(0, 12)(Gen.oneOf(Gen.int, Gen.fromIterable(List[Any](null, None))))) { xs =>
          val back = ValueBridge.valueToAny(ValueBridge.anyToValue(xs)).collect { case l: List[_] => l.size }
          assertTrue(back.contains(xs.size))
        }
      },
      test("deeply nested lists and maps (300 levels) survive") {
        val deepList = (1 to 300).foldLeft[Any]("😀")((acc, _) => List(acc))
        val deepMap  = (1 to 300).foldLeft[Any]("😀")((acc, _) => Map("k" -> acc))
        val listOk = ValueBridge.valueToAny(ValueBridge.anyToValue(deepList)).map(canonAny).contains(canonAny(deepList))
        val mapOk  = ValueBridge.valueToAny(ValueBridge.anyToValue(deepMap)).map(canonAny).contains(canonAny(deepMap))
        assertTrue(listOk, mapOk)
      },
      test("a very large map (10000 entries) and list (50000 elements) survive") {
        val bigMap  = (0 until 10000).map(i => s"k$i\u0000😀" -> i).toMap
        val bigList = (0 until 50000).toList
        val mapOk   = ValueBridge.valueToAny(ValueBridge.anyToValue(bigMap)).map(canonAny).contains(canonAny(bigMap))
        val listOk  = ValueBridge.valueToAny(ValueBridge.anyToValue(bigList)).map(canonAny).contains(canonAny(bigList))
        assertTrue(mapOk, listOk)
      }
    ),
    suite("TrackingConverter")(
      test("plain attribute trees convert by type with exact values, or are reported -- never stringified") {
        check(Gen.mapOfBounded(0, 5)(hostileString, Gen.int(0, 3).flatMap(d => anyGen(d, hostileLong)))) { attrs =>
          val got  = trackedAttributes(attrs)
          val want = attrs.collect { case (k, v) if expectedTracking(v) != null => k -> expectedTracking(v) }
          assertTrue(got == Right(want))
        }
      },
      test("AttributeValue trees convert by type with exact values") {
        check(Gen.mapOfBounded(0, 5)(hostileString, anyAttr)) { attrs =>
          val got  = trackedAttributes(attrs)
          val want = attrs.map { case (k, v) => k -> expectedTracking(v) }
          assertTrue(got == Right(want))
        }
      },
      test("a Long keeps full 64-bit precision (Integer in int range, Long beyond) -- no Double narrowing") {
        check(hostileLong) { l =>
          val got = trackedAttributes(Map("id" -> l))
          assertTrue(got == Right(Map("id" -> expectedTracking(l))))
        }
      },
      test("the event value (Double) survives bit-for-bit: NaN, +-Infinity, -0.0, subnormals, extremes") {
        check(hostileDouble) { d =>
          val out = TrackingConverter.toOpenFeature(TrackingEventDetails(d))
          val got = out.map(_.getValue()).map(o => if (o.isPresent) Some(o.get().doubleValue()) else None)
          assertTrue(got.exists(_.exists(sameBits(_, d))))
        }
      },
      test("an unsupported attribute type is a typed Left that names the attribute, at any depth") {
        val unsupported: List[Any] =
          List(BigDecimal(1), 'c', Set(1), Array(1), new Object, Map(1 -> 2), java.util.Arrays.asList(1), BigInt(1))
        check(hostileString, Gen.fromIterable(unsupported)) { (key, bad) =>
          val top    = trackedAttributes(Map(key -> bad))
          val inList = trackedAttributes(Map(key -> List("ok", bad)))
          val inMap  = trackedAttributes(Map(key -> Map("inner" -> bad)))
          val named  = List(top, inList, inMap).map(_.left.exists(_.contains(key)))
          assertTrue(named == List(true, true, true))
        }
      },
      test("a very large attribute map (5000 entries) and deep nesting (300 levels) convert exactly") {
        val big   = (0 until 5000).map(i => s"k$i😀" -> (i.toLong + Int.MaxValue.toLong)).toMap
        val deep  = (1 to 300).foldLeft[Any]("leaf")((acc, _) => List(Map("k" -> acc)))
        val bigOk =
          trackedAttributes(big) == Right(big.map { case (k, v) => k -> expectedTracking(v) })
        val deepOk =
          trackedAttributes(Map("deep" -> deep)) == Right(Map("deep" -> expectedTracking(deep)))
        assertTrue(bigOk, deepOk)
      }
    )
  ) @@ PropertyBudget(100)
}
