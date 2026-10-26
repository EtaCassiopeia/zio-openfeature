package zio.openfeature.ofrep

import dev.openfeature.contrib.providers.ofrep.OfrepProvider
import dev.openfeature.sdk.exceptions.TypeMismatchError
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
  * integer flag read with `FeatureFlags.long` came back `TYPE_MISMATCH` (#529). This wrapper resolves long reads
  * through the object resolver instead, which keeps the parsed number exact:
  *   - an `Integer` or `Long` value is served exactly, with the response's reason, variant and metadata;
  *   - any other value (a `Double`, including a whole one such as `2.0`, a string, a structure, or an integer beyond
  *     the `Long` range) is `TYPE_MISMATCH` with the caller's default. Nothing is widened or truncated;
  *   - an error from the server (404, 401, ...) keeps its error code, message and metadata, with the caller's default.
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
    underlying.getBooleanEvaluation(key, defaultValue, ctx)

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    ctx: EvaluationContext
  ): ProviderEvaluation[String] =
    underlying.getStringEvaluation(key, defaultValue, ctx)

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    ctx: EvaluationContext
  ): ProviderEvaluation[java.lang.Integer] =
    underlying.getIntegerEvaluation(key, defaultValue, ctx)

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    ctx: EvaluationContext
  ): ProviderEvaluation[java.lang.Double] =
    underlying.getDoubleEvaluation(key, defaultValue, ctx)

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    ctx: EvaluationContext
  ): ProviderEvaluation[Value] =
    underlying.getObjectEvaluation(key, defaultValue, ctx)

  override def getLongEvaluation(
    key: String,
    defaultValue: java.lang.Long,
    ctx: EvaluationContext
  ): ProviderEvaluation[java.lang.Long] = {
    // The contrib resolver wraps the parsed value with `Value.objectToValue`, which throws for a type it has no case
    // for: Jackson's `BigInteger`, for a JSON integer beyond the `Long` range. That is a type mismatch, not a failure.
    val resolved =
      try Right(underlying.getObjectEvaluation(key, new Value(defaultValue), ctx))
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
