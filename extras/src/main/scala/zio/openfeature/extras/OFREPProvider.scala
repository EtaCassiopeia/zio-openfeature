package zio.openfeature.extras

import dev.openfeature.contrib.providers.ofrep.{OfrepProvider, OfrepProviderOptions}
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  Value
}

/** A thin Scala wrapper around the Java SDK's OFREP contrib provider
  * ([[dev.openfeature.contrib.providers.ofrep.OfrepProvider]]).
  *
  * OFREP (OpenFeature Remote Evaluation Protocol) is the standard HTTP protocol for vendor-neutral remote flag
  * evaluation. The underlying provider handles HTTP requests, polling, caching, and state transitions; this wrapper
  * provides Scala-friendly factory methods and lives alongside the other `extras` providers.
  *
  * Note: the underlying contrib provider artifact is at version 0.0.1 — the API may evolve as OFREP itself matures.
  *
  * @see
  *   https://github.com/open-feature/protocol for the OFREP spec
  * @see
  *   https://github.com/open-feature/java-sdk-contrib/tree/main/providers/ofrep for the underlying implementation
  */
final class OFREPProvider private (private val underlying: OfrepProvider) extends FeatureProvider {

  override def getMetadata: Metadata = underlying.getMetadata

  @scala.annotation.nowarn("msg=deprecated")
  override def getState: ProviderState = underlying.getState

  override def initialize(ctx: OFEvaluationContext): Unit = underlying.initialize(ctx)

  override def shutdown(): Unit = underlying.shutdown()

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] =
    underlying.getBooleanEvaluation(key, defaultValue, ctx)

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[String] =
    underlying.getStringEvaluation(key, defaultValue, ctx)

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] =
    underlying.getIntegerEvaluation(key, defaultValue, ctx)

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] =
    underlying.getDoubleEvaluation(key, defaultValue, ctx)

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    ctx: OFEvaluationContext
  ): ProviderEvaluation[Value] =
    underlying.getObjectEvaluation(key, defaultValue, ctx)
}

object OFREPProvider {

  /** Create an OFREPProvider pointed at the given OFREP endpoint with otherwise-default options. */
  def apply(baseUrl: String): OFREPProvider = {
    val opts = OfrepProviderOptions.builder().baseUrl(baseUrl).build()
    new OFREPProvider(OfrepProvider.constructProvider(opts))
  }

  /** Create an OFREPProvider with the contrib provider's default options (defaults to `http://localhost:8016`). */
  def default(): OFREPProvider =
    new OFREPProvider(OfrepProvider.constructProvider())

  /** Create an OFREPProvider with a fully configured [[OfrepProviderOptions]] (auth headers, timeouts, executor, etc.).
    */
  def fromOptions(options: OfrepProviderOptions): OFREPProvider =
    new OFREPProvider(OfrepProvider.constructProvider(options))
}
