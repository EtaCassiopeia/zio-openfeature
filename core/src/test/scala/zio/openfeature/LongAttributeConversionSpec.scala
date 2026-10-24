package zio.openfeature

import zio.openfeature.internal.ContextConverter
import dev.openfeature.sdk.{MutableContext, Value}
import zio.test._

import scala.jdk.CollectionConverters._

// #471: a Long attribute beyond the int range reaches the OpenFeature SDK as a java.lang.Long (not a lossy Double), at
// the top level and nested, and a Long arriving from the SDK converts back to LongValue.
object LongAttributeConversionSpec extends ZIOSpecDefault {

  private val Big = 9007199254740993L // 2^53 + 1: not representable as a Double

  private def sent(attr: AttributeValue): Value =
    ContextConverter.toOpenFeature(EvaluationContext(None, Map("k" -> attr))).getValue("k")

  def spec = suite("LongAttributeConversionSpec (#471)")(
    test("a top-level out-of-int-range Long arrives as a java.lang.Long with its exact value") {
      val v = sent(AttributeValue.LongValue(Big))
      assertTrue(v.asObject.isInstanceOf[java.lang.Long], v.asLong.longValue == Big)
    },
    test("inside a list") {
      val v = sent(AttributeValue.ListValue(List(AttributeValue.LongValue(Big)))).asList.get(0)
      assertTrue(v.asObject.isInstanceOf[java.lang.Long], v.asLong.longValue == Big)
    },
    test("inside a structure") {
      val v = sent(AttributeValue.StructValue(Map("n" -> AttributeValue.LongValue(Big)))).asStructure.getValue("n")
      assertTrue(v.asObject.isInstanceOf[java.lang.Long], v.asLong.longValue == Big)
    },
    test("an int-range Long still arrives as a java.lang.Integer (#249)") {
      assertTrue(sent(AttributeValue.LongValue(42L)).asObject.isInstanceOf[java.lang.Integer])
    },
    test("a Long arriving from the SDK converts back to LongValue, not DoubleValue") {
      val ctx  = new MutableContext("user-1", Map[String, Value]("k" -> new Value(java.lang.Long.valueOf(Big))).asJava)
      val back = ContextConverter.fromOpenFeature(ctx).attributes.get("k")
      assertTrue(back.contains(AttributeValue.LongValue(Big)))
    },
    test("an int-range Long arriving from the SDK converts back to IntValue") {
      val ctx  = new MutableContext("user-1", Map[String, Value]("k" -> new Value(java.lang.Long.valueOf(7L))).asJava)
      val back = ContextConverter.fromOpenFeature(ctx).attributes.get("k")
      assertTrue(back.contains(AttributeValue.IntValue(7)))
    }
  )
}
