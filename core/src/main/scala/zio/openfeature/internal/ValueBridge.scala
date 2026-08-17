package zio.openfeature.internal

import scala.jdk.CollectionConverters._

/** Conversion between plain Scala values and the OpenFeature SDK's `Value`.
  *
  * This is the bridge every object-path evaluation crosses: a flag's default is encoded into a `Value` on the way to
  * the provider, and the provider's answer is decoded back out of one. It is lossy in ways that matter to a
  * `FlagType`'s round-trip law, and those are properties of THIS code rather than of the SDK:
  *   - every number comes back as a `Double`, so a `Long` above 2^53 does not survive;
  *   - a structure member the bridge cannot convert is dropped, so the key reads back as absent;
  *   - `Instant` survives, but only as an `Instant`.
  *
  * Extracted from `FeatureFlagsLive` so that `zio.openfeature.testkit.FlagTypeLaws` can check a `FlagType` against the
  * bridge the library actually uses. A copy in the testkit would let the law pass while production behaved differently,
  * which is the specific failure mode worth avoiding here.
  */
private[openfeature] object ValueBridge {

  def anyToObject(value: Any): Object = value match {
    case b: Boolean    => java.lang.Boolean.valueOf(b)
    case s: String     => s
    case i: Int        => java.lang.Integer.valueOf(i)
    case l: Long       => java.lang.Long.valueOf(l)
    case d: Double     => java.lang.Double.valueOf(d)
    case f: Float      => java.lang.Float.valueOf(f)
    case list: List[_] => list.map(anyToObject).asJava
    case map: Map[_, _] =>
      map.asInstanceOf[Map[String, Any]].map { case (k, v) => k -> anyToObject(v) }.asJava
    case null  => null
    case other => other.toString
  }

  /** Encode an already-decoded value into an SDK `Value`, so a custom type's default can be handed to the provider on
    * the object path. Same shape as [[anyToObject]], one level up.
    */
  def anyToValue(value: Any): dev.openfeature.sdk.Value = value match {
    case null                         => new dev.openfeature.sdk.Value()
    case v: dev.openfeature.sdk.Value => v
    // Option is unwrapped before the `other.toString` fallback below, or a `Some("x")` field is sent as the literal
    // string "Some(x)". `None` becomes an empty Value; as a STRUCTURE member that then reads back as an absent key
    // (and so as `None`) — note it is `valueToMap` below that drops it, NOT the SDK: `Structure.mapToStructure`
    // stores an empty `Value` rather than filtering the entry.
    case Some(inner)   => anyToValue(inner)
    case None          => new dev.openfeature.sdk.Value()
    case b: Boolean    => new dev.openfeature.sdk.Value(b)
    case s: String     => new dev.openfeature.sdk.Value(s)
    case i: Int        => new dev.openfeature.sdk.Value(i)
    case l: Long       => new dev.openfeature.sdk.Value(l)
    case d: Double     => new dev.openfeature.sdk.Value(d)
    case f: Float      => new dev.openfeature.sdk.Value(f.toDouble)
    case list: List[_] => new dev.openfeature.sdk.Value(list.map(anyToValue).asJava)
    case map: Map[_, _] =>
      new dev.openfeature.sdk.Value(
        dev.openfeature.sdk.Structure.mapToStructure(
          map
            .asInstanceOf[Map[String, Any]]
            // Via `anyToValue().asObject()` rather than `anyToObject` directly, so the nested Option/Value handling
            // above applies to structure members too.
            .map { case (k, v) => k -> anyToValue(v).asObject() }
            .asJava
        )
      )
    case other => new dev.openfeature.sdk.Value(other.toString)
  }

  def valueToMap(value: dev.openfeature.sdk.Value): Map[String, Any] =
    if (value == null || !value.isStructure) Map.empty
    else
      value
        .asStructure()
        .asMap()
        .asScala
        .flatMap { case (k, v) => valueToAny(v).map(k -> _) }
        .toMap

  def valueToAny(value: dev.openfeature.sdk.Value): Option[Any] =
    if (value == null) None
    else if (value.isBoolean) Some(value.asBoolean())
    else if (value.isString) Some(value.asString())
    else if (value.isNumber) Some(value.asDouble())
    // Arity-preserving on purpose: an element the bridge cannot convert becomes `null` rather than being dropped.
    // A `flatMap` here silently SHORTENED the list — positional data loss with no error — and a `List[Option[A]]`
    // containing `None` hits exactly that. A `null` element instead reaches the element's own `FlagType`, which
    // either accepts it (`Option` decodes it to `None`) or rejects it loudly.
    else if (value.isList) Some(value.asList().asScala.map(v => valueToAny(v).orNull).toList)
    else if (value.isStructure) Some(valueToMap(value))
    else if (value.isInstant) Some(value.asInstant())
    else None
}
