package zio.openfeature.optimizely

import dev.openfeature.sdk.{ImmutableContext, MutableContext, Structure, Value}
import zio.test._

import java.time.Instant
import scala.jdk.CollectionConverters._

/** Hostile-input sweeps through `ContextTransformer.attributes` (#469): the normalisation that feeds Optimizely's
  * audience evaluator. Scalars must arrive with their exact value and an Optimizely-matchable type (`String`,
  * `Boolean`, `Number`); collections are dropped by design (#266) and must stay dropped however hostile their content.
  * Optimizely variable READS are not swept here: their typing is being changed by #456.
  */
object HostileContextTransformerSpec extends ZIOSpecDefault {

  private val hostileStrings: List[String] = List(
    "",
    "\u0000",
    "a\u0000b",
    "\u0001\u001f\u007f",
    "\u0085  ",
    0xd83d.toChar.toString, // lone high surrogate (a literal escape does not parse in scalafmt)
    "😀",
    "👨‍👩‍👧",
    "日本語",
    "\\\"'",
    "null"
  )

  private val hostileString: Gen[Any, String] =
    Gen.oneOf(Gen.fromIterable(hostileStrings), Gen.string(Gen.unicodeChar), Gen.string(Gen.char))

  private val negativeZero: Double = java.lang.Double.longBitsToDouble(0x8000000000000000L)

  private val hostileDouble: Gen[Any, Double] = Gen.oneOf(
    Gen.fromIterable(
      List(
        Double.NaN,
        Double.PositiveInfinity,
        Double.NegativeInfinity,
        negativeZero,
        0.0,
        Double.MinValue,
        Double.MaxValue,
        java.lang.Double.MIN_VALUE,
        9007199254740992.0
      )
    ),
    Gen.double(-1e300, 1e300)
  )

  private val boundaryLongs: List[Long] = List(
    Long.MinValue,
    Long.MaxValue,
    Int.MinValue.toLong - 1,
    Int.MaxValue.toLong + 1,
    -(1L << 53) - 1,
    (1L << 53) + 1
  )

  private val hostileLong: Gen[Any, Long] = Gen.oneOf(Gen.fromIterable(boundaryLongs), Gen.long)

  def spec = suite("ContextTransformer.attributes hostile input (#469)")(
    test("a string value or key survives exactly, including empty, emoji and control characters") {
      check(hostileString, hostileString) { (key, value) =>
        val out = ContextTransformer.attributes(new MutableContext("u").add(key, value))
        // The targeting key is surfaced by the SDK as an attribute too; only the caller's own key is asserted.
        val got = out.get(key)
        assertTrue(got == value)
      }
    },
    test("Double attributes arrive bit-for-bit: NaN, +-Infinity, -0.0, subnormals, extremes") {
      check(hostileDouble) { d =>
        val out = ContextTransformer.attributes(new MutableContext("u").add("d", d))
        val got = out.get("d")
        assertTrue(
          got.isInstanceOf[java.lang.Double],
          java.lang.Double.compare(got.asInstanceOf[Number].doubleValue, d) == 0
        )
      }
    },
    test("Integer attributes stay Integer with the exact value at every Int bound") {
      check(Gen.oneOf(Gen.fromIterable(List(Int.MinValue, -1, 0, Int.MaxValue)), Gen.int)) { i =>
        val out = ContextTransformer.attributes(new MutableContext("u").add("i", java.lang.Integer.valueOf(i)))
        val got = out.get("i")
        assertTrue(got.isInstanceOf[java.lang.Integer], got.asInstanceOf[Number].intValue == i)
      }
    },
    test("a Long-valued Value keeps full 64-bit precision -- never narrowed to a Double") {
      check(hostileLong) { l =>
        val ctx = new ImmutableContext(java.util.Map.of("l", new Value(java.lang.Long.valueOf(l): Object)))
        val out = ContextTransformer.attributes(ctx)
        val got = out.get("l")
        assertTrue(got.isInstanceOf[java.lang.Long], got.asInstanceOf[Number].longValue == l)
      }
    },
    test("Instants become ISO-8601 strings that parse back to the exact Instant, extremes included") {
      val instants = List(Instant.MIN, Instant.MAX, Instant.EPOCH, Instant.ofEpochSecond(-1L, 999999999L))
      val parsed   =
        instants.map(i =>
          Instant.parse(ContextTransformer.attributes(new MutableContext("u").add("t", i)).get("t").toString)
        )
      assertTrue(parsed == instants)
    },
    test("lists and structures stay dropped however hostile their content, without disturbing scalar siblings") {
      check(hostileString, hostileDouble) { (s, d) =>
        val list   = java.util.Arrays.asList(new Value(s), new Value(d), new Value())
        val struct = Structure.mapToStructure(java.util.Map.of(s, new Value(d).asObject()))
        val ctx    = new MutableContext("u").add("list", list).add("struct", struct).add("keep", s)
        val out    = ContextTransformer.attributes(ctx)
        assertTrue(!out.containsKey("list"), !out.containsKey("struct"), out.get("keep") == s)
      }
    },
    test("a very large context (5000 hostile-keyed attributes) is normalised without loss") {
      val ctx = new MutableContext("u")
      (0 until 5000).foreach(i => ctx.add(s"k$i\u0000😀", java.lang.Integer.valueOf(i)))
      val out = ContextTransformer.attributes(ctx)
      val ok  = (0 until 5000).forall(i => out.get(s"k$i\u0000😀") == java.lang.Integer.valueOf(i))
      assertTrue(ok, out.asScala.count { case (k, _) => k.startsWith("k") } == 5000)
    }
  ) @@ TestAspect.samples(100)
}
