package zio.openfeature.optimizely

import dev.openfeature.sdk.{MutableContext, Structure, Value}
import zio.test._
import scala.jdk.CollectionConverters._

/** Unit tests for `ContextTransformer.transform`. Optimizely's audience evaluator matches only `String`, `Boolean`, and
  * `Number` attributes, so the transformer normalizes an OpenFeature context accordingly: integral numbers stay
  * `Integer` (not coerced to `Double`), an `Instant` becomes its ISO-8601 string, and lists/structures — which no
  * Optimizely audience condition can match — are dropped rather than passed through to evaluate every condition
  * UNKNOWN.
  */
object ContextTransformerSpec extends ZIOSpecDefault {

  def spec = suite("ContextTransformer.transform")(
    test("null context produces empty result without throwing") {
      val out = ContextTransformer.transform(null)
      assertTrue(out.userId.isEmpty, out.attributes.isEmpty)
    },
    test("targeting key maps to userId") {
      val ctx = new MutableContext("user-42")
      val out = ContextTransformer.transform(ctx)
      assertTrue(out.userId == "user-42")
    },
    test("missing targeting key produces empty userId (not null)") {
      val out = ContextTransformer.transform(new MutableContext())
      assertTrue(out.userId.isEmpty)
    },
    test("scalar attributes unwrap to Java primitives Optimizely can match") {
      val ctx = new MutableContext("u").add("isAdmin", true).add("plan", "premium").add("score", 42d)
      val out = ContextTransformer.transform(ctx)
      assertTrue(
        out.attributes.get("isAdmin") == java.lang.Boolean.TRUE,
        out.attributes.get("plan") == "premium",
        out.attributes.get("score") == java.lang.Double.valueOf(42d)
      )
    },
    test("integral numbers are preserved as Integer, not coerced to Double (#266)") {
      val ctx = new MutableContext("u").add("age", java.lang.Integer.valueOf(30))
      val out = ContextTransformer.transform(ctx)
      val age = out.attributes.get("age")
      assertTrue(age.isInstanceOf[java.lang.Integer], age == java.lang.Integer.valueOf(30))
    },
    test("an Instant attribute becomes an ISO-8601 string Optimizely can match (#266)") {
      val instant = java.time.Instant.parse("2024-01-15T00:00:00Z")
      val ctx     = new MutableContext("u").add("signupDate", instant)
      val out     = ContextTransformer.transform(ctx)
      val v       = out.attributes.get("signupDate")
      assertTrue(v.isInstanceOf[String], v == "2024-01-15T00:00:00Z")
    },
    test("a list attribute is dropped — Optimizely cannot match a collection (#266)") {
      val list = java.util.Arrays.asList(new Value("a"), new Value("b"))
      val ctx  = new MutableContext("u").add("tags", list)
      val out  = ContextTransformer.transform(ctx)
      // Previously this passed through a java.util.List that evaluated every audience condition to UNKNOWN.
      assertTrue(!out.attributes.containsKey("tags"))
    },
    test("a structure attribute is dropped — Optimizely cannot match it (#266)") {
      val struct = Structure.mapToStructure(
        java.util.Map.of("city", "NYC".asInstanceOf[Object], "zip", "10001".asInstanceOf[Object])
      )
      val ctx = new MutableContext("u").add("address", struct)
      val out = ContextTransformer.transform(ctx)
      assertTrue(!out.attributes.containsKey("address"))
    }
  )
}
