package zio.openfeature.internal

import zio.openfeature.{AttributeValue, EvaluationContext}
import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, MutableContext, Value, Structure}
import scala.jdk.CollectionConverters._
import java.time.Instant

/** Internal utility for converting between zio-openfeature and OpenFeature SDK evaluation contexts. */
private[openfeature] object ContextConverter {

  /** The SDK stores the targeting key as the attribute of this name (`MutableContext.setTargetingKey` writes it,
    * `getTargetingKey` reads it), so the name is reserved at this boundary (#497).
    */
  private val TargetingKey: String = OFEvaluationContext.TARGETING_KEY

  /** Convert a zio-openfeature EvaluationContext to an OpenFeature SDK EvaluationContext.
    *
    * An attribute named `targetingKey` never reaches the SDK as an attribute: it would overwrite the targeting key.
    * When `ctx.targetingKey` is `None`, a `StringValue` attribute of that name becomes the key; any other is dropped.
    */
  def toOpenFeature(ctx: EvaluationContext): OFEvaluationContext = {
    // Built from a map of `Value`s rather than `MutableContext.add`, which has no Long overload (#471).
    val attributes = ctx.attributes.collect {
      case (k, v) if k != TargetingKey => k -> attributeToValue(v)
    }
    val mutableCtx = new MutableContext(attributes.asJava)
    val key = ctx.targetingKey.orElse(ctx.attributes.get(TargetingKey).collect { case AttributeValue.StringValue(s) =>
      s
    })
    key.foreach(k => mutableCtx.setTargetingKey(k))
    mutableCtx
  }

  private def attributeToValue(attr: AttributeValue): Value = attr match {
    case AttributeValue.BoolValue(b)   => new Value(b)
    case AttributeValue.StringValue(s) => new Value(s)
    case AttributeValue.IntValue(i)    => new Value(Integer.valueOf(i))
    // An int-range long goes as Integer, so provider targeting rules that do `instanceof Integer` match (#249); any
    // other long as java.lang.Long, exactly (#471).
    case AttributeValue.LongValue(l) =>
      if (l.isValidInt) new Value(Integer.valueOf(l.toInt)) else new Value(java.lang.Long.valueOf(l))
    case AttributeValue.DoubleValue(d)   => new Value(d)
    case AttributeValue.InstantValue(dt) => new Value(dt)
    case AttributeValue.ListValue(list) =>
      val javaList = list.map(attributeToValue).asJava
      new Value(javaList)
    case AttributeValue.StructValue(map) =>
      val javaMap: java.util.Map[String, Object] = map.map { case (k, v) =>
        k -> attributeToValue(v).asObject()
      }.asJava
      new Value(Structure.mapToStructure(javaMap))
  }

  /** Convert an OpenFeature SDK EvaluationContext to a zio-openfeature EvaluationContext.
    *
    * The SDK's `targetingKey` entry is its storage of the key, not a user attribute, so it is left out of the
    * attributes (#497).
    */
  def fromOpenFeature(ctx: OFEvaluationContext): EvaluationContext = {
    val targetingKey = Option(ctx.getTargetingKey)

    val attributes = ctx
      .asMap()
      .asScala
      .collect {
        case (key, value) if key != TargetingKey => key -> valueToAttribute(value)
      }
      .toMap

    EvaluationContext(targetingKey, attributes)
  }

  private def valueToAttribute(value: Value): AttributeValue =
    if (value.isBoolean) AttributeValue.BoolValue(value.asBoolean())
    else if (value.isString) AttributeValue.StringValue(value.asString())
    else if (value.isNumber) value.asObject() match {
      // Integral boxes convert exactly; only other numbers go through the whole-double check below (#471).
      case i: java.lang.Integer => AttributeValue.IntValue(i.intValue)
      case l: java.lang.Long =>
        if (l.longValue.isValidInt) AttributeValue.IntValue(l.intValue) else AttributeValue.LongValue(l.longValue)
      case _ => doubleToAttribute(value.asDouble())
    }
    else if (value.isList) {
      val list = value.asList().asScala.map(valueToAttribute).toList
      AttributeValue.ListValue(list)
    } else if (value.isStructure) {
      val struct = value
        .asStructure()
        .asMap()
        .asScala
        .map { case (k, v) =>
          k -> valueToAttribute(v)
        }
        .toMap
      AttributeValue.StructValue(struct)
    } else if (value.isInstant) AttributeValue.InstantValue(value.asInstant())
    else AttributeValue.StringValue(value.asString())

  // A whole double in Long range becomes IntValue/LongValue, anything else stays a double.
  private def doubleToAttribute(num: Double): AttributeValue = {
    // Long.MaxValue.toDouble rounds up to 2^63 (strictly greater than Long.MaxValue), so .toLong on
    // out-of-range doubles silently saturates. Strict `<` on the upper bound rejects the saturation point.
    // -0.0 == 0.0, so it is excluded explicitly: as an integer it would lose its sign (#469). Tested via 1 / -0.0 rather
    // than a `-0.0` literal, which stryker4s re-prints as `0.0`.
    val negativeZero = num == 0.0 && (1.0 / num).isNegInfinity
    if (!negativeZero && num == num.toLong.toDouble && num >= Long.MinValue.toDouble && num < Long.MaxValue.toDouble) {
      val asLong = num.toLong
      if (asLong >= Int.MinValue && asLong <= Int.MaxValue) AttributeValue.IntValue(asLong.toInt)
      else AttributeValue.LongValue(asLong)
    } else AttributeValue.DoubleValue(num)
  }
}
