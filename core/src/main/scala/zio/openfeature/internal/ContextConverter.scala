package zio.openfeature.internal

import zio.openfeature.{AttributeValue, EvaluationContext}
import dev.openfeature.sdk.{
  EvaluationContext as OFEvaluationContext,
  MutableContext,
  Value,
  Structure
}
import scala.jdk.CollectionConverters.*
import java.time.Instant

/** Internal utility for converting between zio-openfeature and OpenFeature SDK evaluation contexts. */
private[openfeature] object ContextConverter:

  /** Convert a zio-openfeature EvaluationContext to an OpenFeature SDK EvaluationContext. */
  def toOpenFeature(ctx: EvaluationContext): OFEvaluationContext =
    val mutableCtx = new MutableContext()

    ctx.targetingKey.foreach(key => mutableCtx.setTargetingKey(key))

    ctx.attributes.foreach { case (key, value) =>
      addAttributeToContext(mutableCtx, key, value)
    }

    mutableCtx

  private def addAttributeToContext(ctx: MutableContext, key: String, attr: AttributeValue): Unit =
    attr match
      case AttributeValue.BoolValue(b)     => ctx.add(key, b)
      case AttributeValue.StringValue(s)   => ctx.add(key, s)
      case AttributeValue.IntValue(i)      => ctx.add(key, Integer.valueOf(i))
      case AttributeValue.LongValue(l)     => ctx.add(key, java.lang.Double.valueOf(l.toDouble))
      case AttributeValue.DoubleValue(d)   => ctx.add(key, d)
      case AttributeValue.InstantValue(dt) => ctx.add(key, dt)
      case AttributeValue.ListValue(list) =>
        val javaList = list.map(attributeToValue).asJava
        ctx.add(key, javaList)
      case AttributeValue.StructValue(map) =>
        val javaMap: java.util.Map[String, Object] = map.map { case (k, v) =>
          k -> attributeToValue(v).asObject()
        }.asJava
        ctx.add(key, Structure.mapToStructure(javaMap))

  private def attributeToValue(attr: AttributeValue): Value = attr match
    case AttributeValue.BoolValue(b)     => new Value(b)
    case AttributeValue.StringValue(s)   => new Value(s)
    case AttributeValue.IntValue(i)      => new Value(i)
    case AttributeValue.LongValue(l)     => new Value(l.toDouble)
    case AttributeValue.DoubleValue(d)   => new Value(d)
    case AttributeValue.InstantValue(dt) => new Value(dt.toString)
    case AttributeValue.ListValue(list) =>
      val javaList = list.map(attributeToValue).asJava
      new Value(javaList)
    case AttributeValue.StructValue(map) =>
      val javaMap: java.util.Map[String, Object] = map.map { case (k, v) =>
        k -> attributeToValue(v).asObject()
      }.asJava
      new Value(Structure.mapToStructure(javaMap))

  /** Convert an OpenFeature SDK EvaluationContext to a zio-openfeature EvaluationContext. */
  def fromOpenFeature(ctx: OFEvaluationContext): EvaluationContext =
    val targetingKey = Option(ctx.getTargetingKey).filter(_.nonEmpty)

    val attributes = ctx
      .asMap()
      .asScala
      .map { case (key, value) =>
        key -> valueToAttribute(value)
      }
      .toMap

    EvaluationContext(targetingKey, attributes)

  private def valueToAttribute(value: Value): AttributeValue =
    if value.isBoolean then AttributeValue.BoolValue(value.asBoolean())
    else if value.isString then AttributeValue.StringValue(value.asString())
    else if value.isNumber then
      val num = value.asDouble()
      if num == num.toLong.toDouble then AttributeValue.IntValue(num.toInt)
      else AttributeValue.DoubleValue(num)
    else if value.isList then
      val list = value.asList().asScala.map(valueToAttribute).toList
      AttributeValue.ListValue(list)
    else if value.isStructure then
      val struct = value
        .asStructure()
        .asMap()
        .asScala
        .map { case (k, v) =>
          k -> valueToAttribute(v)
        }
        .toMap
      AttributeValue.StructValue(struct)
    else if value.isInstant then AttributeValue.InstantValue(value.asInstant())
    else AttributeValue.StringValue(value.asString())
