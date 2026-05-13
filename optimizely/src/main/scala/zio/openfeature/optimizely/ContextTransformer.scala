package zio.openfeature.optimizely

import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, Value}
import scala.jdk.CollectionConverters._

/** Converts an OpenFeature `EvaluationContext` into the `(userId, attributes)` pair that Optimizely's
  * `Optimizely.createUserContext(userId, attributes)` expects.
  *
  * Optimizely strictly requires a non-empty user identifier. If the OpenFeature context lacks a `targetingKey`, we
  * substitute an empty string so the resulting evaluation falls through to the flag's "default" rule rather than
  * throwing. The caller (the FeatureProvider implementation) translates that scenario into a typed evaluation result
  * downstream.
  */
private[optimizely] object ContextTransformer {

  /** Result of transforming an OpenFeature context. `userId` is the Optimizely user identifier (from the OF
    * `targetingKey`). `attributes` is the typed attribute map ready to hand to Optimizely.
    */
  final case class Transformed(userId: String, attributes: java.util.Map[String, Object])

  def transform(ctx: OFEvaluationContext): Transformed = {
    val userId = Option(ctx).flatMap(c => Option(c.getTargetingKey)).getOrElse("")
    val attrs  = Option(ctx).map(_.asObjectMap()).getOrElse(java.util.Collections.emptyMap[String, AnyRef]())
    Transformed(userId, normalize(attrs))
  }

  /** Normalize OpenFeature `Value`-wrapped attributes into the Java primitives Optimizely's targeting engine
    * understands. Optimizely's `OptimizelyUserContext` accepts `String`, `Boolean`, `Number`, plus collections, but
    * does NOT accept the `Value` wrapper.
    */
  private def normalize(attrs: java.util.Map[String, AnyRef]): java.util.Map[String, Object] = {
    val out = new java.util.HashMap[String, Object](attrs.size)
    attrs.asScala.foreach { case (k, v) =>
      val unwrapped: Object = v match {
        case null     => null
        case x: Value => unwrapValue(x)
        case other    => other
      }
      out.put(k, unwrapped)
    }
    out
  }

  private def unwrapValue(v: Value): Object =
    if (v == null) null
    else if (v.isBoolean) java.lang.Boolean.valueOf(v.asBoolean())
    else if (v.isString) v.asString()
    else if (v.isNumber) java.lang.Double.valueOf(v.asDouble())
    else if (v.isInstant) v.asInstant()
    else if (v.isList) v.asList().asScala.map(unwrapValue).asJava
    else if (v.isStructure) {
      val javaMap = new java.util.HashMap[String, Object]()
      v.asStructure().asMap().asScala.foreach { case (k, vv) => javaMap.put(k, unwrapValue(vv)) }
      javaMap
    } else null
}
