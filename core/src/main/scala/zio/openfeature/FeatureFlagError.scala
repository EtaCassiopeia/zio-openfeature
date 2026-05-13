package zio.openfeature

sealed trait FeatureFlagError extends Product with Serializable {
  def message: String
  def cause: Option[Throwable] = None
}

object FeatureFlagError {
  final case class FlagNotFound(key: String) extends FeatureFlagError {
    def message: String = s"Flag '$key' not found"
  }

  final case class TypeMismatch(key: String, expected: String, actual: String) extends FeatureFlagError {
    def message: String = s"Flag '$key' type mismatch: expected $expected, got $actual"
  }

  final case class ParseError(key: String, underlying: Throwable) extends FeatureFlagError {
    def message: String                   = s"Failed to parse flag '$key': ${underlying.getMessage}"
    override def cause: Option[Throwable] = Some(underlying)
  }

  final case class EmptyFlagVariables(key: String) extends FeatureFlagError {
    def message: String = s"Flag '$key' has no variables but non-boolean type requested"
  }

  final case class TargetingKeyMissing(key: String) extends FeatureFlagError {
    def message: String = s"Targeting key required for flag '$key' but not provided"
  }

  final case class InvalidContext(reason: String) extends FeatureFlagError {
    def message: String = s"Invalid evaluation context: $reason"
  }

  final case class ProviderNotReady(status: ProviderStatus) extends FeatureFlagError {
    def message: String = s"Provider not ready: $status"
  }

  final case class ProviderInitializationFailed(underlying: Throwable) extends FeatureFlagError {
    def message: String                   = s"Provider initialization failed: ${underlying.getMessage}"
    override def cause: Option[Throwable] = Some(underlying)
  }

  final case class ProviderError(underlying: Throwable) extends FeatureFlagError {
    def message: String                   = s"Provider error: ${underlying.getMessage}"
    override def cause: Option[Throwable] = Some(underlying)
  }

  /** The remote provider refused the request because of an authentication or authorization failure (HTTP 401 / 403, or
    * an analogous SDK signal). Operators should alert on this distinctly from generic provider errors — it almost
    * always indicates a misconfigured SDK key.
    */
  final case class Unauthorized(reason: String) extends FeatureFlagError {
    def message: String = s"Provider rejected request: $reason"
  }

  /** The remote provider could not be reached at the network layer (DNS failure, connection refused, no route).
    * Distinct from a slow response (`ProviderError(TimeoutException)`) or an HTTP-level rejection (`Unauthorized`).
    */
  final case class Unreachable(cause0: Throwable) extends FeatureFlagError {
    def message: String                   = s"Provider unreachable: ${cause0.getMessage}"
    override def cause: Option[Throwable] = Some(cause0)
  }

  final case class InvalidConfiguration(reason: String) extends FeatureFlagError {
    def message: String = s"Invalid provider configuration: $reason"
  }

  case object NestedTransactionNotAllowed extends FeatureFlagError {
    def message: String = "Nested transactions are not allowed"
  }

  case object ProviderFatal extends FeatureFlagError {
    def message: String = "Provider is in an irrecoverable error state"
  }

  final case class OverrideTypeMismatch(key: String, expected: String, actual: String) extends FeatureFlagError {
    def message: String = s"Override for flag '$key' type mismatch: expected $expected, got $actual"
  }

  def isRecoverable(error: FeatureFlagError): Boolean = error match {
    case _: FlagNotFound        => true
    case _: TypeMismatch        => true
    case _: ParseError          => true
    case _: EmptyFlagVariables  => true
    case _: TargetingKeyMissing => true
    case _: InvalidContext      => true
    case _                      => false
  }

  def isProviderError(error: FeatureFlagError): Boolean = error match {
    case _: ProviderNotReady             => true
    case ProviderFatal                   => true
    case _: ProviderInitializationFailed => true
    case _: ProviderError                => true
    case _: Unauthorized                 => true
    case _: Unreachable                  => true
    case _: InvalidConfiguration         => true
    case _                               => false
  }

  def toErrorCode(error: FeatureFlagError): ErrorCode = error match {
    case _: FlagNotFound                 => ErrorCode.FlagNotFound
    case _: TypeMismatch                 => ErrorCode.TypeMismatch
    case _: ParseError                   => ErrorCode.ParseError
    case _: EmptyFlagVariables           => ErrorCode.TypeMismatch
    case _: TargetingKeyMissing          => ErrorCode.TargetingKeyMissing
    case _: InvalidContext               => ErrorCode.InvalidContext
    case _: ProviderNotReady             => ErrorCode.ProviderNotReady
    case ProviderFatal                   => ErrorCode.ProviderFatal
    case _: ProviderInitializationFailed => ErrorCode.ProviderNotReady
    case _: ProviderError                => ErrorCode.General
    case _: Unauthorized                 => ErrorCode.General
    case _: Unreachable                  => ErrorCode.General
    case _: InvalidConfiguration         => ErrorCode.General
    case NestedTransactionNotAllowed     => ErrorCode.General
    case _: OverrideTypeMismatch         => ErrorCode.TypeMismatch
  }

  /** Classify a Throwable raised by an underlying provider (Java SDK, contrib provider, or transport library) into a
    * typed `FeatureFlagError`. The classifier is intentionally conservative — only well-known network and HTTP failure
    * shapes get specific cases; everything else falls back to `ProviderError` with the original cause preserved.
    *
    * Used by both `core` (wrapping evaluation/tracking failures) and downstream provider modules (OFREP, Optimizely) so
    * operators see consistent error types regardless of which provider is in use.
    */
  def classify(t: Throwable): FeatureFlagError = {
    val msg = Option(t.getMessage).getOrElse("")
    t match {
      case _: java.net.UnknownHostException                                                   => Unreachable(t)
      case _: java.net.NoRouteToHostException                                                 => Unreachable(t)
      case _: java.net.ConnectException                                                       => Unreachable(t)
      case _: java.nio.channels.ClosedChannelException if msg.toLowerCase.contains("connect") => Unreachable(t)
      case _ if isHttpAuthFailure(t, msg) => Unauthorized(extractAuthReason(t, msg))
      case _                              => ProviderError(t)
    }
  }

  // The HTTP client used by contrib providers (java.net.http) doesn't expose status codes via a fixed exception type,
  // so we fall back to scanning the exception message for the canonical "401"/"403" tokens. This is permissive — if a
  // provider message happens to contain those substrings for another reason it will be misclassified, but the cost is
  // low (caller still sees the original Throwable on Unauthorized.cause-equivalent flows when needed).
  private def isHttpAuthFailure(t: Throwable, msg: String): Boolean = {
    val cls    = t.getClass.getName
    val msgLow = msg.toLowerCase
    cls.endsWith("HttpResponseException") ||
    cls.endsWith("UnauthorizedException") ||
    cls.endsWith("ForbiddenException") ||
    msgLow.contains("401") ||
    msgLow.contains("403") ||
    msgLow.contains("unauthorized") ||
    msgLow.contains("forbidden")
  }

  private def extractAuthReason(t: Throwable, msg: String): String =
    if (msg.nonEmpty) msg else t.getClass.getSimpleName
}
