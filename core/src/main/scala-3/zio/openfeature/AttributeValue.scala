package zio.openfeature

import java.time.Instant

enum AttributeValue:
  case BoolValue(value: Boolean)
  case StringValue(value: String)
  case IntValue(value: Int)
  case LongValue(value: Long)
  case DoubleValue(value: Double)
  case InstantValue(value: Instant)
  case ListValue(values: List[AttributeValue])
  case StructValue(fields: Map[String, AttributeValue])

  def asBoolean: Option[Boolean] = this match
    case BoolValue(v) => Some(v)
    case _            => None

  def asString: Option[String] = this match
    case StringValue(v) => Some(v)
    case _              => None

  def asInt: Option[Int] = this match
    case IntValue(v) => Some(v)
    case _           => None

  def asLong: Option[Long] = this match
    case LongValue(v) => Some(v)
    case IntValue(v)  => Some(v.toLong)
    case _            => None

  def asDouble: Option[Double] = this match
    case DoubleValue(v) => Some(v)
    case IntValue(v)    => Some(v.toDouble)
    case LongValue(v)   => Some(v.toDouble)
    case _              => None

  def asInstant: Option[Instant] = this match
    case InstantValue(v) => Some(v)
    case _               => None

  def asList: Option[List[AttributeValue]] = this match
    case ListValue(v) => Some(v)
    case _            => None

  def asStruct: Option[Map[String, AttributeValue]] = this match
    case StructValue(v) => Some(v)
    case _              => None

  def isNull: Boolean = this match
    case StringValue("")             => true
    case ListValue(Nil)              => true
    case StructValue(m) if m.isEmpty => true
    case _                           => false

object AttributeValue:
  def bool(value: Boolean): AttributeValue                      = BoolValue(value)
  def string(value: String): AttributeValue                     = StringValue(value)
  def int(value: Int): AttributeValue                           = IntValue(value)
  def long(value: Long): AttributeValue                         = LongValue(value)
  def double(value: Double): AttributeValue                     = DoubleValue(value)
  def instant(value: Instant): AttributeValue                   = InstantValue(value)
  def list(values: AttributeValue*): AttributeValue             = ListValue(values.toList)
  def struct(fields: (String, AttributeValue)*): AttributeValue = StructValue(fields.toMap)

  given Conversion[Boolean, AttributeValue] = BoolValue(_)
  given Conversion[String, AttributeValue]  = StringValue(_)
  given Conversion[Int, AttributeValue]     = IntValue(_)
  given Conversion[Long, AttributeValue]    = LongValue(_)
  given Conversion[Double, AttributeValue]  = DoubleValue(_)
  given Conversion[Instant, AttributeValue] = InstantValue(_)
