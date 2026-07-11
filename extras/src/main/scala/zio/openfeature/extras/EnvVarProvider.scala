package zio.openfeature.extras

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  Value
}
import dev.openfeature.sdk.exceptions.ParseError
import zio.openfeature.internal.ProviderEvaluations

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

  private def envKey(flagKey: String): String = prefix + keyTransform(flagKey)

  private def lookup(flagKey: String): Option[String] = envLookup(envKey(flagKey))

  @scala.annotation.nowarn("msg=deprecated")
  override def getMetadata: Metadata = new Metadata {
    override def getName: String = "EnvVarProvider"
  }

  override def getState: ProviderState = ProviderState.READY

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] =
    lookup(key) match {
      case Some(v) =>
        v.toLowerCase match {
          case "true" | "1" | "yes" | "on"  => ProviderEvaluations.of(java.lang.Boolean.TRUE, "STATIC")
          case "false" | "0" | "no" | "off" => ProviderEvaluations.of(java.lang.Boolean.FALSE, "STATIC")
          // Set-but-unparsable: surface a spec PARSE_ERROR rather than silently returning the default (worse, the old
          // code labeled that fallback STATIC, claiming it came from the environment). See #262.
          case _ => throw new ParseError(s"Env var ${envKey(key)}='$v' is not a valid boolean for flag '$key'")
        }
      case None =>
        ProviderEvaluations.of(defaultValue, "DEFAULT")
    }

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    context: OFEvaluationContext
  ): ProviderEvaluation[String] =
    lookup(key) match {
      case Some(v) =>
        ProviderEvaluations.of(v, "STATIC")
      case None =>
        ProviderEvaluations.of(defaultValue, "DEFAULT")
    }

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] =
    lookup(key) match {
      case Some(v) =>
        scala.util.Try(v.toInt) match {
          case scala.util.Success(n) => ProviderEvaluations.of(java.lang.Integer.valueOf(n), "STATIC")
          case scala.util.Failure(_) =>
            throw new ParseError(s"Env var ${envKey(key)}='$v' is not a valid int for flag '$key'")
        }
      case None =>
        ProviderEvaluations.of(defaultValue, "DEFAULT")
    }

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] =
    lookup(key) match {
      case Some(v) =>
        scala.util.Try(v.toDouble) match {
          case scala.util.Success(n) => ProviderEvaluations.of(java.lang.Double.valueOf(n), "STATIC")
          case scala.util.Failure(_) =>
            throw new ParseError(s"Env var ${envKey(key)}='$v' is not a valid double for flag '$key'")
        }
      case None =>
        ProviderEvaluations.of(defaultValue, "DEFAULT")
    }

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    context: OFEvaluationContext
  ): ProviderEvaluation[Value] =
    lookup(key) match {
      case Some(v) =>
        ProviderEvaluations.of(new Value(v), "STATIC")
      case None =>
        ProviderEvaluations.of(defaultValue, "DEFAULT")
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
