package zio.openfeature.conformance

import zio.*
import zio.openfeature.*
import dev.openfeature.sdk.{
  EvaluationContext as OFEvaluationContext,
  ImmutableMetadata,
  OpenFeatureAPI,
  OpenFeatureAPIFactory,
  Structure,
  Value
}
import dev.openfeature.sdk.providers.memory.{ContextEvaluator, Flag, InMemoryProvider}

import scala.jdk.CollectionConverters.*

/** Flag fixtures and provider wiring for the OpenFeature spec-conformance suites.
  *
  * The flag definitions mirror the canonical gherkin fixtures from the OpenFeature spec repo at
  * `specification/assets/gherkin/test-flags.json` (upstream main @ 203c25f93495). They are seeded into the Java SDK's
  * own `InMemoryProvider`, which reports proper variants, `STATIC`/`TARGETING_MATCH`/`DEFAULT`/`DISABLED` reasons, and
  * flag metadata — behavior the plain-value testkit provider does not model.
  *
  * Targeted flags use hardcoded [[ContextEvaluator]] lambdas (the spec's JEXL `contextEvaluator` strings are not
  * interpreted, exactly as the Java SDK's own e2e harness does). An evaluator returns the matched variant's value, or
  * `null` to fall back to the default variant (which `InMemoryProvider` reports with reason `DEFAULT`).
  */
object ConformanceFixtures:

  private val TargetEmail = "ballmer@macrosoft.com"

  private def metadataValue(ctx: OFEvaluationContext, key: String): Option[Value] =
    Option(ctx.getValue(key))

  private def emailMatches(ctx: OFEvaluationContext): Boolean =
    metadataValue(ctx, "email").flatMap(v => Option(v.asString())).contains(TargetEmail)

  /** Build a structure-typed [[Value]] from a plain Scala map (object-flag variants). */
  private def structValue(map: Map[String, Any]): Value =
    new Value(Structure.mapToStructure(map.map { case (k, v) => k -> anyToObject(v) }.asJava))

  private def anyToObject(value: Any): Object = value match
    case b: Boolean => java.lang.Boolean.valueOf(b)
    case s: String  => s
    case i: Int     => java.lang.Integer.valueOf(i)
    case d: Double  => java.lang.Double.valueOf(d)
    case other      => other.toString

  private val templateObject: Map[String, Any] =
    Map("showImages" -> true, "title" -> "Check out these pics!", "imagesPerPage" -> 100)

  /** Evaluator that returns `matched` when the target email is present, else `null` (→ DEFAULT). */
  private def emailTargeted[T <: AnyRef](matched: T): ContextEvaluator[T] =
    new ContextEvaluator[T]:
      def evaluate(flag: Flag[?], ctx: OFEvaluationContext): T =
        if emailMatches(ctx) then matched else null.asInstanceOf[T]

  private def flag[T](
    variants: Map[String, T],
    defaultVariant: String,
    disabled: Boolean = false,
    metadata: Option[ImmutableMetadata] = None,
    evaluator: Option[ContextEvaluator[T]] = None
  ): Flag[T] =
    val builder = Flag.builder[T]()
    variants.foreach { case (k, v) => builder.variant(k, v.asInstanceOf[Object]) }
    builder.defaultVariant(defaultVariant).disabled(disabled)
    metadata.foreach(builder.flagMetadata)
    evaluator.foreach(builder.contextEvaluator)
    builder.build()

  private val metadataFlagMetadata: ImmutableMetadata =
    ImmutableMetadata
      .builder()
      .addString("string", "1.0.2")
      .addInteger("integer", 2)
      .addBoolean("boolean", true)
      .addFloat("float", 0.1f)
      .build()

  /** The full fixture set, keyed by flag name, as a Java map for [[InMemoryProvider]]. */
  val flags: java.util.Map[String, Flag[_]] =
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

    val entries: Map[String, Flag[_]] = Map(
      // Boolean
      "boolean-flag"               -> flag(bool, "on"),
      "boolean-disabled-flag"      -> flag(bool, "on", disabled = true),
      "boolean-zero-flag"          -> flag(zeroBool, "zero"),
      "boolean-targeted-zero-flag" -> flag(zeroBool, "zero", evaluator = Some(emailTargeted(java.lang.Boolean.FALSE))),
      // String
      "string-flag"               -> flag(str, "greeting"),
      "string-disabled-flag"      -> flag(str, "greeting", disabled = true),
      "string-zero-flag"          -> flag(zeroStr, "zero"),
      "string-targeted-zero-flag" -> flag(zeroStr, "zero", evaluator = Some(emailTargeted(""))),
      // Integer
      "integer-flag"          -> flag(int, "ten"),
      "integer-disabled-flag" -> flag(int, "ten", disabled = true),
      "integer-zero-flag"     -> flag(zeroInt, "zero"),
      "integer-targeted-zero-flag" -> flag(
        zeroInt,
        "zero",
        evaluator = Some(emailTargeted(java.lang.Integer.valueOf(0)))
      ),
      // Float (resolved through doubleDetails)
      "float-flag"          -> flag(dbl, "half"),
      "float-disabled-flag" -> flag(dbl, "half", disabled = true),
      "float-zero-flag"     -> flag(zeroDbl, "zero"),
      "float-targeted-zero-flag" -> flag(
        zeroDbl,
        "zero",
        evaluator = Some(emailTargeted(java.lang.Double.valueOf(0.0)))
      ),
      // Object
      "object-flag"               -> flag(obj, "template"),
      "object-disabled-flag"      -> flag(obj, "template", disabled = true),
      "object-zero-flag"          -> flag(zeroObj, "zero"),
      "object-targeted-zero-flag" -> flag(zeroObj, "zero", evaluator = Some(emailTargeted(structValue(Map.empty)))),
      // Metadata + targeting
      "metadata-flag" -> flag(bool, "on", metadata = Some(metadataFlagMetadata)),
      "wrong-flag"    -> flag(Map("one" -> "uno", "two" -> "dos"), "one"),
      "complex-targeted" -> flag(
        Map("internal" -> "INTERNAL", "external" -> "EXTERNAL"),
        "external",
        evaluator = Some(complexTargetingEvaluator)
      )
    )
    entries.asJava

  /** `!customer && email == target && age > 10` → "INTERNAL", else `null` (→ DEFAULT "EXTERNAL"). */
  private def complexTargetingEvaluator: ContextEvaluator[String] =
    new ContextEvaluator[String]:
      def evaluate(flag: Flag[?], ctx: OFEvaluationContext): String =
        val email    = metadataValue(ctx, "email").flatMap(v => Option(v.asString()))
        val customer = metadataValue(ctx, "customer").flatMap(v => Option(v.asBoolean())).map(_.booleanValue())
        val age = metadataValue(ctx, "age").flatMap { v =>
          Option(v.asInteger()).map(_.intValue()).orElse(Option(v.asDouble()).map(_.intValue()))
        }
        if !customer.getOrElse(false) && email.contains(TargetEmail) && age.exists(_ > 10) then "INTERNAL"
        else null

  /** A fully isolated `FeatureFlags` over the fixture provider.
    *
    * Each build uses its own [[OpenFeatureAPI]] instance and a unique domain (mirroring the testkit layer), so suites
    * run in parallel without sharing global SDK state. `InMemoryProvider` is synchronously ready, so the status ref is
    * seeded `Ready` directly.
    */
  def layer: ZLayer[Any, Throwable, FeatureFlags] =
    ZLayer.scoped {
      for
        statusRef <- Ref.make[ProviderStatus](ProviderStatus.Ready)
        api    = OpenFeatureAPIFactory.create()
        domain = s"conformance-${java.util.UUID.randomUUID()}"
        ff <- FeatureFlags
          .fromProviderWithDomain(new InMemoryProvider(flags), domain, statusRef, api = Some(api))
          .build
          .map(_.get)
      yield ff
    }
