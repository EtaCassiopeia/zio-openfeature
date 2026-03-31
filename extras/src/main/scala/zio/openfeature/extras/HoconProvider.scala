package zio.openfeature.extras

import com.typesafe.config.{Config, ConfigFactory, ConfigValueType}
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  Structure,
  Value
}
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

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] =
    if (config.hasPath(key))
      ProviderEvaluation
        .builder[java.lang.Boolean]()
        .value(config.getBoolean(key))
        .reason("STATIC")
        .build()
    else
      ProviderEvaluation.builder[java.lang.Boolean]().value(defaultValue).reason("DEFAULT").build()

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    context: OFEvaluationContext
  ): ProviderEvaluation[String] =
    if (config.hasPath(key))
      ProviderEvaluation.builder[String]().value(config.getString(key)).reason("STATIC").build()
    else
      ProviderEvaluation.builder[String]().value(defaultValue).reason("DEFAULT").build()

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] =
    if (config.hasPath(key))
      ProviderEvaluation.builder[java.lang.Integer]().value(config.getInt(key)).reason("STATIC").build()
    else
      ProviderEvaluation.builder[java.lang.Integer]().value(defaultValue).reason("DEFAULT").build()

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] =
    if (config.hasPath(key))
      ProviderEvaluation.builder[java.lang.Double]().value(config.getDouble(key)).reason("STATIC").build()
    else
      ProviderEvaluation.builder[java.lang.Double]().value(defaultValue).reason("DEFAULT").build()

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    context: OFEvaluationContext
  ): ProviderEvaluation[Value] =
    if (config.hasPath(key)) {
      val value = configValueToSdkValue(config.getValue(key))
      ProviderEvaluation.builder[Value]().value(value).reason("STATIC").build()
    } else
      ProviderEvaluation.builder[Value]().value(defaultValue).reason("DEFAULT").build()

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

  /** Reload the config by re-parsing from the original source. */
  def reload(path: String = "feature-flags"): Task[Unit] = ZIO.attempt {
    ConfigFactory.invalidateCaches()
    val root   = ConfigFactory.load()
    val newCfg = if (root.hasPath(path)) root.getConfig(path) else ConfigFactory.empty()
    configRef.set(newCfg)
  }
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
