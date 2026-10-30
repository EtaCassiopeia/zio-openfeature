package zio.openfeature.ofrep

import dev.openfeature.contrib.providers.ofrep.OfrepProvider
import dev.openfeature.sdk.exceptions.{ParseError, TypeMismatchError}
import dev.openfeature.sdk.{
  ErrorCode,
  EvaluationContext,
  FeatureProvider,
  Hook,
  ImmutableMetadata,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  Reason,
  TrackingEventDetails,
  Value
}

/** The OFREP provider the [[OFREPProvider]] factories return: the contrib
  * [[dev.openfeature.contrib.providers.ofrep.OfrepProvider]] plus a `getLongEvaluation` of its own.
  *
  * The contrib provider (0.0.2) does not override `getLongEvaluation`, so the SDK's default answers a long read from
  * `getDoubleEvaluation`, and the contrib double resolver serves only values Jackson parsed as a `Double`: every JSON
  * integer flag read with `FeatureFlags.long` came back `TYPE_MISMATCH`. This wrapper resolves long reads through the
  * object resolver instead, which keeps the parsed number exact:
  *   - an `Integer` or `Long` value is served exactly, with the response's reason, variant and metadata;
  *   - any other value (a `Double`, including a whole one such as `2.0`, a string, a structure, or an integer beyond
  *     the `Long` range) is `TYPE_MISMATCH` with the caller's default. Nothing is widened or truncated;
  *   - an error from the server (404, 401, ...) keeps its error code, message and metadata, with the caller's default.
  *
  * A response that is not OFREP JSON (an HTML error page from a proxy, an empty body, truncated JSON) is `GENERAL` with
  * the caller's default, whatever its status, on every resolver. The contrib provider parses the body before it reads
  * the status and throws `ParseError` for such a body, which would tell a circuit breaker the server is reachable and
  * answered bad data; the exception carries no status, so a 200 with malformed JSON is `GENERAL` too. The contrib
  * provider also throws `ParseError` for an evaluation context it cannot serialize (a `java.time.Instant` attribute,
  * say), before any request is sent: that one is left as it is, since the server was never asked.
  *
  * Every other `FeatureProvider` method delegates to [[underlying]] unchanged.
  *
  * @param underlying
  *   the contrib provider this wraps, for code that needs the contrib type itself
  */
final class OFREPFeatureProvider private[ofrep] (val underlying: OfrepProvider) extends FeatureProvider {

  override def getMetadata: Metadata = underlying.getMetadata

  override def getProviderHooks: java.util.List[Hook[_]] = underlying.getProviderHooks

  override def initialize(context: EvaluationContext): Unit = underlying.initialize(context)

  override def initialize(context: EvaluationContext, domain: String): Unit = underlying.initialize(context, domain)

  override def isDomainScoped(): Boolean = underlying.isDomainScoped()

  override def shutdown(): Unit = underlying.shutdown()

  @scala.annotation.nowarn("msg=deprecated")
  override def getState: ProviderState = underlying.getState

  override def track(eventName: String, context: EvaluationContext, details: TrackingEventDetails): Unit =
    underlying.track(eventName, context, details)

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    ctx: EvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] =
    ofrepJson(defaultValue, ctx)(underlying.getBooleanEvaluation(key, defaultValue, ctx))

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    ctx: EvaluationContext
  ): ProviderEvaluation[String] =
    ofrepJson(defaultValue, ctx)(underlying.getStringEvaluation(key, defaultValue, ctx))

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    ctx: EvaluationContext
  ): ProviderEvaluation[java.lang.Integer] =
    ofrepJson(defaultValue, ctx)(underlying.getIntegerEvaluation(key, defaultValue, ctx))

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    ctx: EvaluationContext
  ): ProviderEvaluation[java.lang.Double] =
    ofrepJson(defaultValue, ctx)(underlying.getDoubleEvaluation(key, defaultValue, ctx))

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    ctx: EvaluationContext
  ): ProviderEvaluation[Value] =
    ofrepJson(defaultValue, ctx)(underlying.getObjectEvaluation(key, defaultValue, ctx))

  override def getLongEvaluation(
    key: String,
    defaultValue: java.lang.Long,
    ctx: EvaluationContext
  ): ProviderEvaluation[java.lang.Long] = {
    // The contrib resolver wraps the parsed value with `Value.objectToValue`, which throws for a type it has no case
    // for: Jackson's `BigInteger`, for a JSON integer beyond the `Long` range. That is a type mismatch, not a failure.
    val resolved =
      try
        Right(
          ofrepJson(new Value(defaultValue), ctx)(underlying.getObjectEvaluation(key, new Value(defaultValue), ctx))
        )
      catch { case e: TypeMismatchError => Left(e.getMessage) }

    resolved match {
      case Left(message) =>
        mismatch(defaultValue, message, ImmutableMetadata.builder().build())
      case Right(evaluation) if evaluation.getErrorCode != null =>
        new ProviderEvaluation[java.lang.Long](
          defaultValue,
          evaluation.getVariant,
          evaluation.getReason,
          evaluation.getErrorCode,
          evaluation.getErrorMessage,
          evaluation.getFlagMetadata
        )
      case Right(evaluation) =>
        val raw: Any = Option(evaluation.getValue).map(_.asObject).orNull
        raw match {
          case i: java.lang.Integer => served(java.lang.Long.valueOf(i.longValue), evaluation)
          case l: java.lang.Long    => served(l, evaluation)
          case other                =>
            val found = Option(other).map(_.getClass.getSimpleName).getOrElse("null")
            mismatch(defaultValue, s"Type mismatch: expected Long but got $found", evaluation.getFlagMetadata)
        }
    }
  }

  private def ofrepJson[T](defaultValue: T, ctx: EvaluationContext)(
    resolve: => ProviderEvaluation[T]
  ): ProviderEvaluation[T] =
    try resolve
    catch {
      case e: ParseError if requestSerializes(ctx) =>
        new ProviderEvaluation[T](
          defaultValue,
          null,
          Reason.ERROR.toString,
          ErrorCode.GENERAL,
          s"response is not OFREP JSON (${e.getMessage})",
          ImmutableMetadata.builder().build()
        )
    }

  // The contrib provider serializes the request body with a plain `ObjectMapper`; a context that serializes with one
  // means the failed processing was the response's.
  private def requestSerializes(ctx: EvaluationContext): Boolean =
    scala.util
      .Try(OFREPFeatureProvider.RequestMapper.writeValueAsBytes(Option(ctx).map(_.asObjectMap()).orNull))
      .isSuccess

  private def served(value: java.lang.Long, evaluation: ProviderEvaluation[Value]): ProviderEvaluation[java.lang.Long] =
    new ProviderEvaluation[java.lang.Long](
      value,
      evaluation.getVariant,
      evaluation.getReason,
      null,
      null,
      evaluation.getFlagMetadata
    )

  private def mismatch(
    defaultValue: java.lang.Long,
    message: String,
    metadata: ImmutableMetadata
  ): ProviderEvaluation[java.lang.Long] =
    new ProviderEvaluation[java.lang.Long](
      defaultValue,
      null,
      Reason.ERROR.toString,
      ErrorCode.TYPE_MISMATCH,
      message,
      metadata
    )

  override def toString: String = s"OFREPFeatureProvider($underlying)"
}

private object OFREPFeatureProvider {
  private val RequestMapper = new com.fasterxml.jackson.databind.ObjectMapper()
}
