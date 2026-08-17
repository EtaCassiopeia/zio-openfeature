package zio.openfeature.extras

import com.typesafe.config.{Config, ConfigException, ConfigFactory, ConfigValueType}
import dev.openfeature.sdk.{
  ErrorCode,
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  Structure,
  Value
}
import dev.openfeature.sdk.exceptions.{ParseError, TypeMismatchError}
import zio.openfeature.internal.ProviderEvaluations
import zio._
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters._

/** A feature flag provider that reads values from Typesafe Config (HOCON).
  *
  * Reads flags from a configurable path in `application.conf` / `reference.conf`. Supports all flag types (boolean,
  * string, int, double, object). Returns `ResolutionReason.Static` since values are loaded from config.
  *
  * @param config
  *   The Typesafe Config object scoped to the feature flags path
  */
final class HoconProvider private (
  private val configRef: AtomicReference[Config],
  // Re-reads the config from the ORIGINAL construction source, so `reload()` refreshes what the provider was built
  // from — the classpath path for `apply`, or the injected `Config` (which has no external source) for `fromConfig`.
  private val reloadSource: () => Config
) extends FeatureProvider {

  private val state = new AtomicReference[ProviderState](ProviderState.READY)

  private def config: Config = configRef.get()

  @scala.annotation.nowarn("msg=deprecated")
  override def getMetadata: Metadata = new Metadata {
    override def getName: String = "HoconProvider"
  }

  override def getState: ProviderState = state.get()

  // Translate Typesafe Config's typed-read failures into the OpenFeature error model so callers see
  // TYPE_MISMATCH / PARSE_ERROR instead of a GENERAL error wrapping a ConfigException (spec 7.3.6).
  private def typedRead[A](key: String, read: => A): A =
    try read
    catch {
      case e: ConfigException.WrongType => throw new TypeMismatchError(s"Flag '$key': ${e.getMessage}")
      case e: ConfigException.BadValue  => throw new ParseError(s"Flag '$key': ${e.getMessage}")
    }

  /** A key this config does not contain is `FLAG_NOT_FOUND`, not a `DEFAULT`-reason answer (#355).
    *
    * That is what lets a `MultiProvider` chain distinguish "not configured here" from "configured to this value" and
    * move on to the next provider, and what lets an operator tell the two apart.
    *
    * The returned '''value''' is still the caller's default and no evaluation fails. Two things do change for
    * observers, though: the resolution carries `reason = Error` with `errorCode = FlagNotFound`, and hooks see the
    * `error` stage rather than `after` — so `Hook.logging()` reports an absent key at error level where it previously
    * reported it at info. That is the spec-correct stage for an error-coded resolution (§4.3.6/§4.4.6).
    *
    * The message names the flag key rather than a config path: `key` is relative to whatever sub-config this provider
    * was scoped to, so it is not the line an operator would add to `application.conf`.
    */
  private def notFound[T](key: String, defaultValue: T): ProviderEvaluation[T] =
    ProviderEvaluations.error(defaultValue, ErrorCode.FLAG_NOT_FOUND, s"Flag '$key' is not present in this config")

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] =
    if (config.hasPath(key))
      ProviderEvaluations.of(java.lang.Boolean.valueOf(typedRead(key, config.getBoolean(key))), "STATIC")
    else
      notFound(key, defaultValue)

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    context: OFEvaluationContext
  ): ProviderEvaluation[String] =
    if (config.hasPath(key))
      ProviderEvaluations.of(typedRead(key, config.getString(key)), "STATIC")
    else
      notFound(key, defaultValue)

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] =
    if (config.hasPath(key))
      ProviderEvaluations.of(java.lang.Integer.valueOf(typedRead(key, config.getInt(key))), "STATIC")
    else
      notFound(key, defaultValue)

  override def getLongEvaluation(
    key: String,
    defaultValue: java.lang.Long,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Long] =
    if (config.hasPath(key))
      ProviderEvaluations.of(java.lang.Long.valueOf(typedRead(key, config.getLong(key))), "STATIC")
    else
      notFound(key, defaultValue)

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] =
    if (config.hasPath(key))
      ProviderEvaluations.of(java.lang.Double.valueOf(typedRead(key, config.getDouble(key))), "STATIC")
    else
      notFound(key, defaultValue)

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    context: OFEvaluationContext
  ): ProviderEvaluation[Value] =
    if (config.hasPath(key)) {
      val value = typedRead(key, configValueToSdkValue(config.getValue(key)))
      ProviderEvaluations.of(value, "STATIC")
    } else
      notFound(key, defaultValue)

  private def configValueToSdkValue(cv: com.typesafe.config.ConfigValue): Value =
    cv.valueType() match {
      case ConfigValueType.BOOLEAN => new Value(cv.unwrapped().asInstanceOf[java.lang.Boolean])
      case ConfigValueType.NUMBER =>
        cv.unwrapped() match {
          case i: java.lang.Integer => new Value(i.intValue())
          // int-range long → Integer so it keeps an integer type (matching provider `instanceof Integer` targeting),
          // rather than a Double; out-of-int-range longs fall back to Double (lossy beyond 2^53). Mostly defensive:
          // HOCON parses int-range numbers as Integer already, so this branch typically sees only large values.
          case l: java.lang.Long =>
            if (l.longValue().isValidInt) new Value(l.intValue()) else new Value(l.doubleValue())
          case d: java.lang.Double => new Value(d)
          case other               => new Value(other.toString)
        }
      case ConfigValueType.STRING => new Value(cv.unwrapped().asInstanceOf[String])
      case ConfigValueType.OBJECT =>
        val obj = cv.asInstanceOf[com.typesafe.config.ConfigObject]
        val javaMap: java.util.Map[String, Object] = obj
          .entrySet()
          .asScala
          .map(e => e.getKey -> configValueToSdkValue(e.getValue).asObject())
          .toMap
          .asJava
        new Value(Structure.mapToStructure(javaMap))
      case ConfigValueType.LIST =>
        val list = cv.asInstanceOf[com.typesafe.config.ConfigList]
        new Value(list.asScala.map(configValueToSdkValue).asJava)
      case ConfigValueType.NULL => new Value()
    }

  /** Reload the config by re-parsing from the original construction source (the classpath path passed to `apply`, or
    * the injected `Config` from `fromConfig` — which has no external source, so `reload` keeps it rather than
    * discarding it to the classpath).
    *
    * On failure, the existing config is preserved and provider state transitions to `ERROR`. The state remains `ERROR`
    * until a subsequent `reload` succeeds — callers must re-invoke to recover.
    */
  def reload(): Task[Unit] =
    ZIO
      .attempt(reloadSource())
      .tapBoth(
        _ => ZIO.succeed(state.set(ProviderState.ERROR)),
        newCfg => ZIO.succeed { configRef.set(newCfg); state.set(ProviderState.READY) }
      )
      .unit
}

object HoconProvider {

  /** Create from the default `application.conf` at the given path. `reload()` re-reads this same path (after
    * invalidating the config cache, so on-disk/classpath changes are picked up).
    */
  def apply(path: String = "feature-flags"): HoconProvider = {
    def extract(root: Config): Config = if (root.hasPath(path)) root.getConfig(path) else ConfigFactory.empty()
    new HoconProvider(
      new AtomicReference(extract(ConfigFactory.load())),
      () => { ConfigFactory.invalidateCaches(); extract(ConfigFactory.load()) }
    )
  }

  /** Create from a specific `Config` object. An injected config has no external source, so `reload()` keeps it as-is
    * rather than discarding it — use `apply(path)` for a reloadable classpath-backed provider.
    */
  def fromConfig(config: Config): HoconProvider =
    new HoconProvider(new AtomicReference(config), () => config)
}
