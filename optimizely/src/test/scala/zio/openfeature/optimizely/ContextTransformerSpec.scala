package zio.openfeature.optimizely

import dev.openfeature.sdk.{MutableContext, Structure, Value}
import zio.test._
import scala.jdk.CollectionConverters._

/** Unit tests for `ContextTransformer.transform`. Optimizely's user-context attributes accept Java primitives, lists,
  * and maps — `Value`-wrapped attributes from the OpenFeature SDK must be unwrapped before they cross the boundary,
  * otherwise Optimizely's targeting engine sees opaque `Value` objects and silently mismatches.
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
    test("scalar attributes unwrap to Java primitives") {
      val ctx = new MutableContext("u").add("isAdmin", true).add("plan", "premium").add("score", 42d)
      val out = ContextTransformer.transform(ctx)
      assertTrue(
        out.attributes.get("isAdmin") == java.lang.Boolean.TRUE,
        out.attributes.get("plan") == "premium",
        out.attributes.get("score") == java.lang.Double.valueOf(42d)
      )
    },
    test("list attribute unwraps to a Java List of primitives") {
      val list    = java.util.Arrays.asList(new Value("a"), new Value("b"))
      val ctx     = new MutableContext("u").add("tags", list)
      val out     = ContextTransformer.transform(ctx)
      val tags    = out.attributes.get("tags")
      val isList  = tags.isInstanceOf[java.util.List[?]]
      val asScala = tags.asInstanceOf[java.util.List[Object]].asScala.toList
      assertTrue(isList, asScala == List("a", "b"))
    },
    test("structure attribute unwraps to a Java Map of primitives") {
      val struct = Structure.mapToStructure(
        java.util.Map.of("city", "NYC".asInstanceOf[Object], "zip", "10001".asInstanceOf[Object])
      )
      val ctx     = new MutableContext("u").add("address", struct)
      val out     = ContextTransformer.transform(ctx)
      val v       = out.attributes.get("address")
      val isMap   = v.isInstanceOf[java.util.Map[?, ?]]
      val cityVal = v.asInstanceOf[java.util.Map[String, AnyRef]].get("city")
      assertTrue(isMap, cityVal == "NYC")
    }
  )
}
