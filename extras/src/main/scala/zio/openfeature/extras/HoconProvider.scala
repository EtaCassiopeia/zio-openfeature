package zio.openfeature.extras

import com.typesafe.config.{Config, ConfigException, ConfigFactory, ConfigValueType}
import dev.openfeature.sdk.{
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
  private val configRef: AtomicReference[Config]
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

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] =
    if (config.hasPath(key))
      ProviderEvaluations.of(java.lang.Boolean.valueOf(typedRead(key, config.getBoolean(key))), "STATIC")
    else
      ProviderEvaluations.of(defaultValue, "DEFAULT")

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    context: OFEvaluationContext
  ): ProviderEvaluation[String] =
    if (config.hasPath(key))
      ProviderEvaluations.of(typedRead(key, config.getString(key)), "STATIC")
    else
      ProviderEvaluations.of(defaultValue, "DEFAULT")

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] =
    if (config.hasPath(key))
      ProviderEvaluations.of(java.lang.Integer.valueOf(typedRead(key, config.getInt(key))), "STATIC")
    else
      ProviderEvaluations.of(defaultValue, "DEFAULT")

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] =
    if (config.hasPath(key))
      ProviderEvaluations.of(java.lang.Double.valueOf(typedRead(key, config.getDouble(key))), "STATIC")
    else
      ProviderEvaluations.of(defaultValue, "DEFAULT")

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    context: OFEvaluationContext
  ): ProviderEvaluation[Value] =
    if (config.hasPath(key)) {
      val value = typedRead(key, configValueToSdkValue(config.getValue(key)))
      ProviderEvaluations.of(value, "STATIC")
    } else
      ProviderEvaluations.of(defaultValue, "DEFAULT")

  private def configValueToSdkValue(cv: com.typesafe.config.ConfigValue): Value =
    cv.valueType() match {
      case ConfigValueType.BOOLEAN => new Value(cv.unwrapped().asInstanceOf[java.lang.Boolean])
      case ConfigValueType.NUMBER =>
        cv.unwrapped() match {
          case i: java.lang.Integer => new Value(i.intValue())
          case l: java.lang.Long    => new Value(l.doubleValue())
          case d: java.lang.Double  => new Value(d)
          case other                => new Value(other.toString)
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

  /** Reload the config by re-parsing from the original source.
    *
    * On failure, the existing config is preserved and provider state transitions to `ERROR`. The state remains `ERROR`
    * until a subsequent `reload` succeeds — callers must re-invoke to recover.
    */
  def reload(path: String = "feature-flags"): Task[Unit] =
    ZIO
      .attempt {
        ConfigFactory.invalidateCaches()
        val root = ConfigFactory.load()
        if (root.hasPath(path)) root.getConfig(path) else ConfigFactory.empty()
      }
      .tapBoth(
        _ => ZIO.succeed(state.set(ProviderState.ERROR)),
        newCfg => ZIO.succeed { configRef.set(newCfg); state.set(ProviderState.READY) }
      )
      .unit
}

object HoconProvider {

  /** Create from the default `application.conf` at the given path. */
  def apply(path: String = "feature-flags"): HoconProvider = {
    val root   = ConfigFactory.load()
    val config = if (root.hasPath(path)) root.getConfig(path) else ConfigFactory.empty()
    new HoconProvider(new AtomicReference(config))
  }

  /** Create from a specific Config object. */
  def fromConfig(config: Config): HoconProvider =
    new HoconProvider(new AtomicReference(config))
}
