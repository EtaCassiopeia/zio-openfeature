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

  def transform(ctx: OFEvaluationContext): Transformed =
    Transformed(userId(ctx), attributes(ctx))

  /** The Optimizely user identifier (the OF `targetingKey`), or "" when absent. Cheap — no attribute normalization, so
    * the caller can gate on provider state / targeting key before paying for the full attribute conversion.
    */
  def userId(ctx: OFEvaluationContext): String =
    Option(ctx).flatMap(c => Option(c.getTargetingKey)).getOrElse("")

  /** The normalized attribute map ready to hand to Optimizely. */
  def attributes(ctx: OFEvaluationContext): java.util.Map[String, Object] =
    normalize(Option(ctx).map(_.asObjectMap()).getOrElse(java.util.Collections.emptyMap[String, AnyRef]()))

  /** Normalize OpenFeature `Value`-wrapped attributes into the Java types Optimizely's audience evaluator can actually
    * match: `String`, `Boolean`, and `Number` (`Integer`/`Double`). Anything else evaluates every audience condition to
    * UNKNOWN (silently dead targeting, one WARN log per evaluation), so:
    *   - `Instant` is converted to its ISO-8601 string (matchable by string audience conditions);
    *   - integral numbers are preserved as `Integer` (not coerced to `Double`);
    *   - lists and structures are DROPPED — Optimizely has no way to match a collection attribute.
    * A dropped/absent attribute simply doesn't participate in targeting, rather than poisoning every condition.
    */
  private def normalize(attrs: java.util.Map[String, AnyRef]): java.util.Map[String, Object] = {
    val out = new java.util.HashMap[String, Object](attrs.size)
    attrs.asScala.foreach { case (k, v) =>
      val converted = convert(v)
      // null means either an unset attribute or an unmatchable type (list/structure) — omit it rather than pass an
      // attribute Optimizely can only ever evaluate to UNKNOWN.
      if (converted != null) out.put(k, converted)
    }
    out
  }

  // `EvaluationContext.asObjectMap()` yields the RAW wrapped objects (Boolean/String/Integer/Double/Instant, and
  // List[Value]/Structure for collections), so we convert by concrete type. A `Value` wrapper is handled defensively.
  private def convert(v: Object): Object = v match {
    case null                    => null
    case b: java.lang.Boolean    => b
    case s: String               => s
    case i: java.lang.Integer    => i             // preserve integral numbers rather than coercing to Double
    case l: java.lang.Long       => l
    case n: java.lang.Number     => java.lang.Double.valueOf(n.doubleValue)
    case inst: java.time.Instant => inst.toString // ISO-8601; Optimizely can't match a raw Instant
    case x: Value                => convert(x.asObject)
    case _ => null // lists, structures, and anything else Optimizely's evaluator can't match
  }
}
