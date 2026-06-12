package zio.openfeature

import java.time.Instant

sealed trait AttributeValue extends Product with Serializable {
  def asBoolean: Option[Boolean] = this match {
    case AttributeValue.BoolValue(v) => Some(v)
    case _                           => None
  }

  def asString: Option[String] = this match {
    case AttributeValue.StringValue(v) => Some(v)
    case _                             => None
  }

  def asInt: Option[Int] = this match {
    case AttributeValue.IntValue(v) => Some(v)
    case _                          => None
  }

  def asLong: Option[Long] = this match {
    case AttributeValue.LongValue(v) => Some(v)
    case AttributeValue.IntValue(v)  => Some(v.toLong)
    case _                           => None
  }

  def asDouble: Option[Double] = this match {
    case AttributeValue.DoubleValue(v) => Some(v)
    case AttributeValue.IntValue(v)    => Some(v.toDouble)
    case AttributeValue.LongValue(v)   => Some(v.toDouble)
    case _                             => None
  }

  def asInstant: Option[Instant] = this match {
    case AttributeValue.InstantValue(v) => Some(v)
    case _                              => None
  }

  def asList: Option[List[AttributeValue]] = this match {
    case AttributeValue.ListValue(v) => Some(v)
    case _                           => None
  }

  def asStruct: Option[Map[String, AttributeValue]] = this match {
    case AttributeValue.StructValue(v) => Some(v)
    case _                             => None
  }

  /** True when the value is an "empty" container: empty string, empty list, or empty struct. */
  def isEmptyValue: Boolean = this match {
    case AttributeValue.StringValue("")             => true
    case AttributeValue.ListValue(Nil)              => true
    case AttributeValue.StructValue(m) if m.isEmpty => true
    case _                                          => false
  }

  @deprecated("isNull tests emptiness, not null-ness; use isEmptyValue", "0.9.2")
  def isNull: Boolean = isEmptyValue
}

object AttributeValue {
  final case class BoolValue(value: Boolean)                       extends AttributeValue
  final case class StringValue(value: String)                      extends AttributeValue
  final case class IntValue(value: Int)                            extends AttributeValue
  final case class LongValue(value: Long)                          extends AttributeValue
  final case class DoubleValue(value: Double)                      extends AttributeValue
  final case class InstantValue(value: Instant)                    extends AttributeValue
  final case class ListValue(values: List[AttributeValue])         extends AttributeValue
  final case class StructValue(fields: Map[String, AttributeValue]) extends AttributeValue

  def bool(value: Boolean): AttributeValue                      = BoolValue(value)
  def string(value: String): AttributeValue                     = StringValue(value)
  def int(value: Int): AttributeValue                           = IntValue(value)
  def long(value: Long): AttributeValue                         = LongValue(value)
  def double(value: Double): AttributeValue                     = DoubleValue(value)
  def instant(value: Instant): AttributeValue                   = InstantValue(value)
  def list(values: AttributeValue*): AttributeValue             = ListValue(values.toList)
  def struct(fields: (String, AttributeValue)*): AttributeValue = StructValue(fields.toMap)

  implicit def boolToAttributeValue(value: Boolean): AttributeValue   = BoolValue(value)
  implicit def stringToAttributeValue(value: String): AttributeValue  = StringValue(value)
  implicit def intToAttributeValue(value: Int): AttributeValue        = IntValue(value)
  implicit def longToAttributeValue(value: Long): AttributeValue      = LongValue(value)
  implicit def doubleToAttributeValue(value: Double): AttributeValue  = DoubleValue(value)
  implicit def instantToAttributeValue(value: Instant): AttributeValue = InstantValue(value)
}
