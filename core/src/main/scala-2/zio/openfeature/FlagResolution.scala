package zio.openfeature

sealed trait ResolutionReason extends Product with Serializable
object ResolutionReason {
  case object Static         extends ResolutionReason
  case object Default        extends ResolutionReason
  case object TargetingMatch extends ResolutionReason
  case object Split          extends ResolutionReason
  case object Cached         extends ResolutionReason
  case object Disabled       extends ResolutionReason
  case object Unknown        extends ResolutionReason
  case object Stale          extends ResolutionReason
  case object Error          extends ResolutionReason
  // Provider-specific reason passed through verbatim (spec 1.4.7 — reasons are provider-extensible strings). `Unknown`
  // is reserved for a genuinely absent (null) reason.
  final case class Other(value: String) extends ResolutionReason
}

sealed trait MetadataValue extends Product with Serializable {
  def asBoolean: Option[Boolean] = this match {
    case MetadataValue.BooleanValue(v) => Some(v)
    case _                             => None
  }

  def asString: Option[String] = this match {
    case MetadataValue.StringValue(v) => Some(v)
    case _                            => None
  }

  def asInt: Option[Int] = this match {
    case MetadataValue.IntValue(v) => Some(v)
    case _                         => None
  }

  def asLong: Option[Long] = this match {
    case MetadataValue.LongValue(v) => Some(v)
    case MetadataValue.IntValue(v)  => Some(v.toLong)
    case _                          => None
  }

  def asDouble: Option[Double] = this match {
    case MetadataValue.DoubleValue(v) => Some(v)
    case MetadataValue.FloatValue(v)  => Some(v.toDouble)
    case MetadataValue.IntValue(v)    => Some(v.toDouble)
    case MetadataValue.LongValue(v)   => Some(v.toDouble)
    case _                            => None
  }
}

object MetadataValue {
  final case class BooleanValue(value: Boolean) extends MetadataValue
  final case class StringValue(value: String)   extends MetadataValue
  final case class IntValue(value: Int)         extends MetadataValue
  final case class LongValue(value: Long)       extends MetadataValue
  final case class DoubleValue(value: Double)   extends MetadataValue
  final case class FloatValue(value: Float)     extends MetadataValue

  implicit def boolToMetadataValue(value: Boolean): MetadataValue = BooleanValue(value)
  implicit def stringToMetadataValue(value: String): MetadataValue = StringValue(value)
  implicit def intToMetadataValue(value: Int): MetadataValue       = IntValue(value)
  implicit def longToMetadataValue(value: Long): MetadataValue     = LongValue(value)
  implicit def doubleToMetadataValue(value: Double): MetadataValue = DoubleValue(value)
  implicit def floatToMetadataValue(value: Float): MetadataValue   = FloatValue(value)
}

final case class FlagMetadata(values: Map[String, MetadataValue]) {
  def get(key: String): Option[MetadataValue]  = values.get(key)
  def getString(key: String): Option[String]   = values.get(key).flatMap(_.asString)
  def getBoolean(key: String): Option[Boolean] = values.get(key).flatMap(_.asBoolean)
  def getInt(key: String): Option[Int]         = values.get(key).flatMap(_.asInt)
  def getLong(key: String): Option[Long]       = values.get(key).flatMap(_.asLong)
  def getDouble(key: String): Option[Double]   = values.get(key).flatMap(_.asDouble)
  def isEmpty: Boolean                         = values.isEmpty
  def nonEmpty: Boolean                        = values.nonEmpty
}

object FlagMetadata {
  val empty: FlagMetadata = FlagMetadata(Map.empty[String, MetadataValue])

  def apply(entries: (String, MetadataValue)*): FlagMetadata = FlagMetadata(entries.toMap)

  def fromStrings(entries: (String, String)*): FlagMetadata =
    FlagMetadata(entries.map { case (k, v) => k -> MetadataValue.StringValue(v) }.toMap)
}

sealed trait ErrorCode extends Product with Serializable
object ErrorCode {
  case object ProviderNotReady    extends ErrorCode
  case object ProviderFatal       extends ErrorCode
  case object FlagNotFound        extends ErrorCode
  case object ParseError          extends ErrorCode
  case object TypeMismatch        extends ErrorCode
  case object TargetingKeyMissing extends ErrorCode
  case object InvalidContext      extends ErrorCode
  case object General             extends ErrorCode
}

final case class FlagResolution[+A](
  value: A,
  variant: Option[String],
  reason: ResolutionReason,
  metadata: FlagMetadata,
  flagKey: String,
  errorCode: Option[ErrorCode] = None,
  errorMessage: Option[String] = None
) {
  def isError: Boolean                      = errorCode.isDefined || reason == ResolutionReason.Error
  def isSuccess: Boolean                    = !isError
  def isDefault: Boolean                    = reason == ResolutionReason.Default
  def isCached: Boolean                     = reason == ResolutionReason.Cached
  def map[B](f: A => B): FlagResolution[B] = copy(value = f(value))
}

object FlagResolution {
  def targetingMatch[A](
    flagKey: String,
    value: A,
    variant: Option[String] = None,
    metadata: FlagMetadata = FlagMetadata.empty
  ): FlagResolution[A] =
    FlagResolution(value, variant, ResolutionReason.TargetingMatch, metadata, flagKey)

  def default[A](flagKey: String, value: A): FlagResolution[A] =
    FlagResolution(value, None, ResolutionReason.Default, FlagMetadata.empty, flagKey)

  def cached[A](flagKey: String, value: A): FlagResolution[A] =
    FlagResolution(value, None, ResolutionReason.Cached, FlagMetadata.empty, flagKey)

  def error[A](flagKey: String, defaultValue: A, errorCode: ErrorCode, errorMessage: String): FlagResolution[A] =
    FlagResolution(
      value = defaultValue,
      variant = None,
      reason = ResolutionReason.Error,
      metadata = FlagMetadata.empty,
      flagKey = flagKey,
      errorCode = Some(errorCode),
      errorMessage = Some(errorMessage)
    )
}
