package zio.openfeature

enum ResolutionReason:
  case Static
  case Default
  case TargetingMatch
  case Split
  case Cached
  case Disabled
  case Unknown
  case Stale
  case Error
  // Provider-specific reason passed through verbatim (spec 1.4.7 — reasons are provider-extensible strings). `Unknown`
  // is reserved for a genuinely absent (null) reason.
  case Other(value: String)

enum MetadataValue:
  case BooleanValue(value: Boolean)
  case StringValue(value: String)
  case IntValue(value: Int)
  case LongValue(value: Long)
  case DoubleValue(value: Double)
  case FloatValue(value: Float)

  def asBoolean: Option[Boolean] = this match
    case BooleanValue(v) => Some(v)
    case _               => None

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
    case FloatValue(v)  => Some(v.toDouble)
    case IntValue(v)    => Some(v.toDouble)
    case LongValue(v)   => Some(v.toDouble)
    case _              => None

object MetadataValue:
  given Conversion[Boolean, MetadataValue] = BooleanValue(_)
  given Conversion[String, MetadataValue]  = StringValue(_)
  given Conversion[Int, MetadataValue]     = IntValue(_)
  given Conversion[Long, MetadataValue]    = LongValue(_)
  given Conversion[Double, MetadataValue]  = DoubleValue(_)
  given Conversion[Float, MetadataValue]   = FloatValue(_)

final case class FlagMetadata(values: Map[String, MetadataValue]):
  def get(key: String): Option[MetadataValue]  = values.get(key)
  def getString(key: String): Option[String]   = values.get(key).flatMap(_.asString)
  def getBoolean(key: String): Option[Boolean] = values.get(key).flatMap(_.asBoolean)
  def getInt(key: String): Option[Int]         = values.get(key).flatMap(_.asInt)
  def getLong(key: String): Option[Long]       = values.get(key).flatMap(_.asLong)
  def getDouble(key: String): Option[Double]   = values.get(key).flatMap(_.asDouble)
  def isEmpty: Boolean                         = values.isEmpty
  def nonEmpty: Boolean                        = values.nonEmpty

object FlagMetadata:
  val empty: FlagMetadata = FlagMetadata(Map.empty[String, MetadataValue])

  def apply(entries: (String, MetadataValue)*): FlagMetadata = FlagMetadata(entries.toMap)

  def fromStrings(entries: (String, String)*): FlagMetadata =
    FlagMetadata(entries.map { case (k, v) => k -> MetadataValue.StringValue(v) }.toMap)

enum ErrorCode:
  case ProviderNotReady
  case ProviderFatal
  case FlagNotFound
  case ParseError
  case TypeMismatch
  case TargetingKeyMissing
  case InvalidContext
  case General

final case class FlagResolution[+A](
  value: A,
  variant: Option[String],
  reason: ResolutionReason,
  metadata: FlagMetadata,
  flagKey: String,
  errorCode: Option[ErrorCode] = None,
  errorMessage: Option[String] = None
):
  def isError: Boolean                     = errorCode.isDefined || reason == ResolutionReason.Error
  def isSuccess: Boolean                   = !isError
  def isDefault: Boolean                   = reason == ResolutionReason.Default
  def isCached: Boolean                    = reason == ResolutionReason.Cached
  def map[B](f: A => B): FlagResolution[B] = copy(value = f(value))

object FlagResolution:
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
