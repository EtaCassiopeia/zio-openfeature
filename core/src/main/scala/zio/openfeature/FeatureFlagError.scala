package zio.openfeature

sealed trait FeatureFlagError extends Product with Serializable:
  def message: String
  def cause: Option[Throwable] = None

object FeatureFlagError:
  final case class FlagNotFound(key: String) extends FeatureFlagError:
    def message: String = s"Flag '$key' not found"

  final case class TypeMismatch(key: String, expected: String, actual: String) extends FeatureFlagError:
    def message: String = s"Flag '$key' type mismatch: expected $expected, got $actual"

  final case class ParseError(key: String, underlying: Throwable) extends FeatureFlagError:
    def message: String                   = s"Failed to parse flag '$key': ${underlying.getMessage}"
    override def cause: Option[Throwable] = Some(underlying)

  final case class EmptyFlagVariables(key: String) extends FeatureFlagError:
    def message: String = s"Flag '$key' has no variables but non-boolean type requested"

  final case class TargetingKeyMissing(key: String) extends FeatureFlagError:
    def message: String = s"Targeting key required for flag '$key' but not provided"

  final case class InvalidContext(reason: String) extends FeatureFlagError:
    def message: String = s"Invalid evaluation context: $reason"

  final case class ProviderNotReady(status: ProviderStatus) extends FeatureFlagError:
    def message: String = s"Provider not ready: $status"

  final case class ProviderInitializationFailed(underlying: Throwable) extends FeatureFlagError:
    def message: String                   = s"Provider initialization failed: ${underlying.getMessage}"
    override def cause: Option[Throwable] = Some(underlying)

  final case class ProviderError(underlying: Throwable) extends FeatureFlagError:
    def message: String                   = s"Provider error: ${underlying.getMessage}"
    override def cause: Option[Throwable] = Some(underlying)

  final case class InvalidConfiguration(reason: String) extends FeatureFlagError:
    def message: String = s"Invalid provider configuration: $reason"

  case object NestedTransactionNotAllowed extends FeatureFlagError:
    def message: String = "Nested transactions are not allowed"

  final case class OverrideTypeMismatch(key: String, expected: String, actual: String) extends FeatureFlagError:
    def message: String = s"Override for flag '$key' type mismatch: expected $expected, got $actual"

  def isRecoverable(error: FeatureFlagError): Boolean = error match
    case _: FlagNotFound        => true
    case _: TypeMismatch        => true
    case _: ParseError          => true
    case _: EmptyFlagVariables  => true
    case _: TargetingKeyMissing => true
    case _: InvalidContext      => true
    case _                      => false

  def isProviderError(error: FeatureFlagError): Boolean = error match
    case _: ProviderNotReady             => true
    case _: ProviderInitializationFailed => true
    case _: ProviderError                => true
    case _: InvalidConfiguration         => true
    case _                               => false

  def toErrorCode(error: FeatureFlagError): ErrorCode = error match
    case _: FlagNotFound                 => ErrorCode.FlagNotFound
    case _: TypeMismatch                 => ErrorCode.TypeMismatch
    case _: ParseError                   => ErrorCode.ParseError
    case _: EmptyFlagVariables           => ErrorCode.TypeMismatch
    case _: TargetingKeyMissing          => ErrorCode.TargetingKeyMissing
    case _: InvalidContext               => ErrorCode.InvalidContext
    case _: ProviderNotReady             => ErrorCode.ProviderNotReady
    case _: ProviderInitializationFailed => ErrorCode.ProviderNotReady
    case _: ProviderError                => ErrorCode.General
    case _: InvalidConfiguration         => ErrorCode.General
    case NestedTransactionNotAllowed     => ErrorCode.General
    case _: OverrideTypeMismatch         => ErrorCode.TypeMismatch
