package zio.openfeature.conformance

import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, ImmutableMetadata, Structure, Value}
import dev.openfeature.sdk.providers.memory.{ContextEvaluator, Flag}

import scala.jdk.CollectionConverters._

/** Flag fixtures mirroring the spec's `specification/assets/gherkin/test-flags.json` (upstream main @ 203c25f93495),
  * built for the Java SDK's `InMemoryProvider`. Targeted flags use hardcoded [[ContextEvaluator]] lambdas (the JEXL
  * `contextEvaluator` strings are not interpreted, matching the Java SDK's own e2e harness): an evaluator returns the
  * matched variant's value, or `null` to fall back to the default variant (reason `DEFAULT`).
  */
object Fixtures {

  private val TargetEmail = "ballmer@macrosoft.com"

  private def emailMatches(ctx: OFEvaluationContext): Boolean =
    Option(ctx.getValue("email")).flatMap(v => Option(v.asString())).contains(TargetEmail)

  private def anyToObject(value: Any): Object = value match {
    case b: Boolean => java.lang.Boolean.valueOf(b)
    case s: String  => s
    case i: Int     => java.lang.Integer.valueOf(i)
    case d: Double  => java.lang.Double.valueOf(d)
    case other      => other.toString
  }

  private def structValue(map: Map[String, Any]): Value =
    new Value(Structure.mapToStructure(map.map { case (k, v) => k -> anyToObject(v) }.asJava))

  private val templateObject: Map[String, Any] =
    Map("showImages" -> true, "title" -> "Check out these pics!", "imagesPerPage" -> 100)

  private def emailTargeted[T <: AnyRef](matched: T): ContextEvaluator[T] =
    new ContextEvaluator[T] {
      def evaluate(flag: Flag[_], ctx: OFEvaluationContext): T =
        if (emailMatches(ctx)) matched else null.asInstanceOf[T]
    }

  private def flag[T](
    variants: Map[String, T],
    defaultVariant: String,
    disabled: Boolean = false,
    metadata: Option[ImmutableMetadata] = None,
    evaluator: Option[ContextEvaluator[T]] = None
  ): Flag[T] = {
    val builder = Flag.builder[T]()
    variants.foreach { case (k, v) => builder.variant(k, v.asInstanceOf[Object]) }
    builder.defaultVariant(defaultVariant).disabled(disabled)
    metadata.foreach(builder.flagMetadata)
    evaluator.foreach(builder.contextEvaluator)
    builder.build()
  }

  private val metadataFlagMetadata: ImmutableMetadata =
    ImmutableMetadata
      .builder()
      .addString("string", "1.0.2")
      .addInteger("integer", 2)
      .addBoolean("boolean", true)
      .addFloat("float", 0.1f)
      .build()

  private def complexTargetingEvaluator: ContextEvaluator[String] =
    new ContextEvaluator[String] {
      def evaluate(flag: Flag[_], ctx: OFEvaluationContext): String = {
        val email    = Option(ctx.getValue("email")).flatMap(v => Option(v.asString()))
        val customer = Option(ctx.getValue("customer")).flatMap(v => Option(v.asBoolean())).map(_.booleanValue())
        val age = Option(ctx.getValue("age")).flatMap { v =>
          Option(v.asInteger()).map(_.intValue()).orElse(Option(v.asDouble()).map(_.intValue()))
        }
        if (!customer.getOrElse(false) && email.contains(TargetEmail) && age.exists(_ > 10)) "INTERNAL"
        else null
      }
    }

  /** The full fixture set keyed by flag name, ready for `new InMemoryProvider(_)`. */
  val inMemoryFlags: java.util.Map[String, Flag[_]] = {
    val bool: Map[String, java.lang.Boolean]     = Map("on" -> true, "off" -> false)
    val zeroBool: Map[String, java.lang.Boolean] = Map("zero" -> false, "non-zero" -> true)
    val str: Map[String, String]                 = Map("greeting" -> "hi", "parting" -> "bye")
    val zeroStr: Map[String, String]             = Map("zero" -> "", "non-zero" -> "str")
    val int: Map[String, java.lang.Integer]      = Map("one" -> 1, "ten" -> 10)
    val zeroInt: Map[String, java.lang.Integer]  = Map("zero" -> 0, "non-zero" -> 1)
    val dbl: Map[String, java.lang.Double]       = Map("tenth" -> 0.1, "half" -> 0.5)
    val zeroDbl: Map[String, java.lang.Double]   = Map("zero" -> 0.0, "non-zero" -> 1.0)
    val obj: Map[String, Value]     = Map("empty" -> structValue(Map.empty), "template" -> structValue(templateObject))
    val zeroObj: Map[String, Value] = Map("zero" -> structValue(Map.empty), "non-zero" -> structValue(templateObject))

    Map[String, Flag[_]](
      "boolean-flag"               -> flag(bool, "on"),
      "boolean-disabled-flag"      -> flag(bool, "on", disabled = true),
      "boolean-zero-flag"          -> flag(zeroBool, "zero"),
      "boolean-targeted-zero-flag" -> flag(zeroBool, "zero", evaluator = Some(emailTargeted(java.lang.Boolean.FALSE))),
      "string-flag"                -> flag(str, "greeting"),
      "string-disabled-flag"       -> flag(str, "greeting", disabled = true),
      "string-zero-flag"           -> flag(zeroStr, "zero"),
      "string-targeted-zero-flag"  -> flag(zeroStr, "zero", evaluator = Some(emailTargeted(""))),
      "integer-flag"               -> flag(int, "ten"),
      "integer-disabled-flag"      -> flag(int, "ten", disabled = true),
      "integer-zero-flag"          -> flag(zeroInt, "zero"),
      "integer-targeted-zero-flag" -> flag(
        zeroInt,
        "zero",
        evaluator = Some(emailTargeted(java.lang.Integer.valueOf(0)))
      ),
      "float-flag"          -> flag(dbl, "half"),
      "float-disabled-flag" -> flag(dbl, "half", disabled = true),
      "float-zero-flag"     -> flag(zeroDbl, "zero"),
      "float-targeted-zero-flag" -> flag(
        zeroDbl,
        "zero",
        evaluator = Some(emailTargeted(java.lang.Double.valueOf(0.0)))
      ),
      "object-flag"               -> flag(obj, "template"),
      "object-disabled-flag"      -> flag(obj, "template", disabled = true),
      "object-zero-flag"          -> flag(zeroObj, "zero"),
      "object-targeted-zero-flag" -> flag(zeroObj, "zero", evaluator = Some(emailTargeted(structValue(Map.empty)))),
      "metadata-flag"             -> flag(bool, "on", metadata = Some(metadataFlagMetadata)),
      "wrong-flag"                -> flag(Map("one" -> "uno", "two" -> "dos"), "one"),
      "complex-targeted" -> flag(
        Map("internal" -> "INTERNAL", "external" -> "EXTERNAL"),
        "external",
        evaluator = Some(complexTargetingEvaluator)
      )
    ).asJava
  }

  /** Plain-value seed for the testkit `TestFeatureProvider` (used by context-merging and provider-status scenarios,
    * which only need a flag to exist — not variants/reasons).
    */
  val testProviderSeed: Map[String, Any] =
    Map("boolean-flag" -> true, "string-flag" -> "hi", "integer-flag" -> 10, "float-flag" -> 0.5, "merge-flag" -> true)
}
