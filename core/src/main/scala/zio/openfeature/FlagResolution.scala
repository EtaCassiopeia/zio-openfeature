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

final case class FlagMetadata(values: Map[String, String]):
  def get(key: String): Option[String] = values.get(key)
  def isEmpty: Boolean                 = values.isEmpty
  def nonEmpty: Boolean                = values.nonEmpty

object FlagMetadata:
  val empty: FlagMetadata                             = FlagMetadata(Map.empty)
  def apply(entries: (String, String)*): FlagMetadata = FlagMetadata(entries.toMap)

enum ErrorCode:
  case ProviderNotReady
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
