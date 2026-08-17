package zio.openfeature.extras

import dev.openfeature.sdk.{
  ErrorCode,
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

  /** An unset environment variable is `FLAG_NOT_FOUND`, not a `DEFAULT`-reason answer (#355).
    *
    * That is what lets a `MultiProvider` chain distinguish "not set here" from "set to this value" and move on to the
    * next provider. The message names the resolved environment variable, since that is what an operator has to set.
    *
    * The returned '''value''' is still the caller's default and no evaluation fails. Two things do change for
    * observers: the resolution carries `reason = Error` with `errorCode = FlagNotFound`, and hooks see the `error`
    * stage rather than `after` — so `Hook.logging()` reports an unset variable at error level where it previously
    * reported it at info. That is spec-correct for an error-coded resolution (§4.3.6/§4.4.6), but it is worth knowing
    * for this provider in particular: it is often used as an opt-in override source where most variables are
    * deliberately unset, so consider tuning `logError`/`errorLevel` on your hooks.
    */
  private def notFound[T](flagKey: String, defaultValue: T): ProviderEvaluation[T] =
    ProviderEvaluations.error(defaultValue, ErrorCode.FLAG_NOT_FOUND, s"Env var ${envKey(flagKey)} is not set")

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
        notFound(key, defaultValue)
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
        notFound(key, defaultValue)
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
        notFound(key, defaultValue)
    }

  override def getLongEvaluation(
    key: String,
    defaultValue: java.lang.Long,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Long] =
    lookup(key) match {
      case Some(v) =>
        scala.util.Try(v.toLong) match {
          case scala.util.Success(n) => ProviderEvaluations.of(java.lang.Long.valueOf(n), "STATIC")
          case scala.util.Failure(_) =>
            throw new ParseError(s"Env var ${envKey(key)}='$v' is not a valid long for flag '$key'")
        }
      case None =>
        notFound(key, defaultValue)
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
        notFound(key, defaultValue)
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
        notFound(key, defaultValue)
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
