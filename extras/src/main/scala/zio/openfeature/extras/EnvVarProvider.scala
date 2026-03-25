package zio.openfeature.extras

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  Value
}
import java.util.concurrent.atomic.AtomicReference

/** A feature flag provider that reads values from environment variables.
  *
  * Flag keys are mapped to environment variable names using a configurable prefix and key transformation. For example,
  * with the default prefix `FF_`, the flag key `new-checkout` maps to `FF_NEW_CHECKOUT`.
  *
  * @param prefix
  *   Environment variable prefix (default: `FF_`)
  * @param keyTransform
  *   Function to transform flag keys to env var suffixes (default: uppercase + replace `-` with `_`)
  * @param envLookup
  *   Function to look up env vars (default: `sys.env.get`). Override for testing.
  */
final class EnvVarProvider private (
  prefix: String,
  keyTransform: String => String,
  envLookup: String => Option[String]
) extends FeatureProvider {

  private val state = new AtomicReference[ProviderState](ProviderState.READY)

  private def envKey(flagKey: String): String = prefix + keyTransform(flagKey)

  private def lookup(flagKey: String): Option[String] = envLookup(envKey(flagKey))

  @scala.annotation.nowarn("msg=deprecated")
  override def getMetadata: Metadata = new Metadata {
    override def getName: String = "EnvVarProvider"
  }

  override def getState: ProviderState = state.get()

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] =
    lookup(key) match {
      case Some(v) =>
        val parsed = v.toLowerCase match {
          case "true" | "1" | "yes" | "on"  => true
          case "false" | "0" | "no" | "off" => false
          case _                            => defaultValue.booleanValue()
        }
        ProviderEvaluation.builder[java.lang.Boolean]().value(parsed).reason("STATIC").build()
      case None =>
        ProviderEvaluation.builder[java.lang.Boolean]().value(defaultValue).reason("DEFAULT").build()
    }

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    context: OFEvaluationContext
  ): ProviderEvaluation[String] =
    lookup(key) match {
      case Some(v) =>
        ProviderEvaluation.builder[String]().value(v).reason("STATIC").build()
      case None =>
        ProviderEvaluation.builder[String]().value(defaultValue).reason("DEFAULT").build()
    }

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] =
    lookup(key).flatMap(v => scala.util.Try(v.toInt).toOption) match {
      case Some(v) =>
        ProviderEvaluation.builder[java.lang.Integer]().value(v).reason("STATIC").build()
      case None =>
        ProviderEvaluation.builder[java.lang.Integer]().value(defaultValue).reason("DEFAULT").build()
    }

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] =
    lookup(key).flatMap(v => scala.util.Try(v.toDouble).toOption) match {
      case Some(v) =>
        ProviderEvaluation.builder[java.lang.Double]().value(v).reason("STATIC").build()
      case None =>
        ProviderEvaluation.builder[java.lang.Double]().value(defaultValue).reason("DEFAULT").build()
    }

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    context: OFEvaluationContext
  ): ProviderEvaluation[Value] =
    lookup(key) match {
      case Some(v) =>
        ProviderEvaluation.builder[Value]().value(new Value(v)).reason("STATIC").build()
      case None =>
        ProviderEvaluation.builder[Value]().value(defaultValue).reason("DEFAULT").build()
    }
}

object EnvVarProvider {

  val defaultKeyTransform: String => String = _.toUpperCase.replace("-", "_").replace(".", "_")

  def apply(
    prefix: String = "FF_",
    keyTransform: String => String = defaultKeyTransform
  ): EnvVarProvider =
    new EnvVarProvider(prefix, keyTransform, key => sys.env.get(key))

  /** Create with a custom env lookup function (useful for testing). */
  def withLookup(
    envLookup: String => Option[String],
    prefix: String = "FF_",
    keyTransform: String => String = defaultKeyTransform
  ): EnvVarProvider =
    new EnvVarProvider(prefix, keyTransform, envLookup)
}
