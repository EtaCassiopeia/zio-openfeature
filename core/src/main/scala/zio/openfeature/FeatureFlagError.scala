package zio.openfeature

sealed trait FeatureFlagError extends Product with Serializable {
  def message: String
  def cause: Option[Throwable] = None
}

// The deprecated EmptyFlagVariables case is still part of this ADT, so the compiler's generated members reference it.
@scala.annotation.nowarn("cat=deprecation")
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

  @deprecated("Never raised by any module; kept for binary compatibility", "1.2.0")
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

  /** The remote provider refused the request because of an authentication or authorization failure (HTTP 401 / 403, an
    * analogous SDK signal, or a provider-reported `GENERAL` evaluation error whose message names an auth failure — the
    * OFREP contrib provider reports 401/403 this way). Operators should alert on this distinctly from generic provider
    * errors — it almost always indicates a misconfigured SDK key. Its `toErrorCode` stays `General`, since the
    * OpenFeature spec has no auth error code.
    */
  final case class Unauthorized(reason: String) extends FeatureFlagError {
    def message: String = s"Provider rejected request: $reason"

    // Write-once, set only by `FeatureFlagError.unauthorized` before the instance escapes. Not a case field, so
    // `Unauthorized(reason)` patterns, equality and the binary shape stay as they were; and the factory lives
    // outside the companion because an explicit companion changes its type hierarchy on 2.13. `copy` does not carry it.
    private[FeatureFlagError] var underlying: Option[Throwable] = None

    override def cause: Option[Throwable] = underlying
  }

  /** An [[Unauthorized]] that keeps the throwable it was classified from as its `cause`. */
  def unauthorized(reason: String, cause: Throwable): Unauthorized = {
    val error = Unauthorized(reason)
    error.underlying = Option(cause)
    error
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

  /** Whether `error` is about the flag being evaluated (missing, wrong type, unparsable, bad context) rather than about
    * the provider or this library. Unrelated to [[ProviderStatus.isRecoverable]], and says nothing about whether a
    * retry would help.
    */
  @scala.annotation.nowarn("cat=deprecation")
  def isFlagError(error: FeatureFlagError): Boolean = error match {
    case _: FlagNotFound        => true
    case _: TypeMismatch        => true
    case _: ParseError          => true
    case _: EmptyFlagVariables  => true
    case _: TargetingKeyMissing => true
    case _: InvalidContext      => true
    case _                      => false
  }

  @deprecated(
    "Use isFlagError: this is true for flag-scoped evaluation errors, not about retrying or ProviderStatus.isRecoverable",
    "1.2.0"
  )
  def isRecoverable(error: FeatureFlagError): Boolean = isFlagError(error)

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

  @scala.annotation.nowarn("cat=deprecation")
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
      case _                                                                                  =>
        httpStatus(t) match {
          case Some(status) if AuthStatuses.contains(status) => unauthorized(extractAuthReason(t, msg), t)
          case Some(_)                                       => ProviderError(t)
          case None if isHttpAuthFailure(t, msg)             => unauthorized(extractAuthReason(t, msg), t)
          case None                                          => ProviderError(t)
        }
    }
  }

  private val AuthStatuses: Set[Int] = Set(401, 403)

  // Many HTTP clients' response exceptions (Apache httpclient, Google http-client, ...) carry the status behind a
  // getStatusCode/statusCode accessor. When one is there, it decides — the class name says nothing about which status
  // it carries, so a 503 must not read as an auth failure.
  private val StatusAccessors = List("getStatusCode", "statusCode")

  private def httpStatus(t: Throwable): Option[Int] =
    StatusAccessors.iterator.flatMap(name => statusVia(t, name)).nextOption()

  // Total: classify runs on error paths, so a broken exception class (a LinkageError while resolving its methods, which
  // Try would let through) must not turn one failure into another.
  private def statusVia(t: Throwable, accessor: String): Option[Int] =
    try
      t.getClass.getMethod(accessor).invoke(t) match {
        case i: java.lang.Integer => Some(i.intValue)
        case _                    => None
      }
    catch { case _: LinkageError | scala.util.control.NonFatal(_) => None }

  // Without a status accessor (the java.net.http client used by contrib providers has none), fall back to the
  // exception's name and message: the canonical "401"/"403" tokens, "unauthorized", "forbidden". This is permissive —
  // a message that happens to contain those for another reason is misclassified — so the original throwable is kept as
  // the Unauthorized's cause. Word-boundary match so "401"/"403" only hit as standalone tokens: a port 4013, a byte
  // count, or a flag key containing the digits must not classify as an auth failure.
  private val AuthStatusToken = java.util.regex.Pattern.compile("\\b(401|403)\\b")

  private def isHttpAuthFailure(t: Throwable, msg: String): Boolean = {
    val cls = t.getClass.getName
    cls.endsWith("UnauthorizedException") ||
    cls.endsWith("ForbiddenException") ||
    looksLikeAuthFailure(msg)
  }

  // The literal the OFREP contrib provider (0.0.2, internal/Resolver.java) puts in a GENERAL evaluation error for a
  // 401/403 response. Pinned by OfrepAuthFailureSpec's canary, so a contrib bump that rewords it fails there.
  private val OfrepAuthPrefix = "authentication/authorization error"

  /** Whether an error message names an authentication or authorization failure: a standalone `401`/`403` token,
    * `unauthorized`, `forbidden`, or the OFREP contrib provider's auth literal. Shared by `classify` (thrown
    * exceptions) and the mapping of provider-reported `GENERAL` evaluation errors, which carry only a message.
    */
  private[openfeature] def looksLikeAuthFailure(message: String): Boolean = {
    val msgLow = message.toLowerCase
    AuthStatusToken.matcher(msgLow).find() ||
    msgLow.contains("unauthorized") ||
    msgLow.contains("forbidden") ||
    msgLow.contains(OfrepAuthPrefix)
  }

  private def extractAuthReason(t: Throwable, msg: String): String =
    if (msg.nonEmpty) msg else t.getClass.getSimpleName
}
