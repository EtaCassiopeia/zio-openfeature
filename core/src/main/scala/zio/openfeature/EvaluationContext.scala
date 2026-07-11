package zio.openfeature

final case class EvaluationContext(
  targetingKey: Option[String],
  attributes: Map[String, AttributeValue]
) {
  def merge(other: EvaluationContext): EvaluationContext =
    // Short-circuit the common per-evaluation case (most layers are empty): merging with an empty context is identity,
    // so return an existing instance instead of allocating a new context + merged map.
    if (other.isEmpty) this
    else if (isEmpty) other
    else
      EvaluationContext(
        targetingKey = other.targetingKey.orElse(targetingKey),
        attributes = EvaluationContext.mergeAttributes(attributes, other.attributes)
      )

  def withTargetingKey(key: String): EvaluationContext =
    copy(targetingKey = Some(key))

  def withAttribute(key: String, value: AttributeValue): EvaluationContext =
    copy(attributes = attributes + (key -> value))

  def withAttributes(attrs: (String, AttributeValue)*): EvaluationContext =
    copy(attributes = attributes ++ attrs)

  def withoutAttribute(key: String): EvaluationContext =
    copy(attributes = attributes - key)

  def get(key: String): Option[AttributeValue] = attributes.get(key)
  def getBoolean(key: String): Option[Boolean] = attributes.get(key).flatMap(_.asBoolean)
  def getString(key: String): Option[String]   = attributes.get(key).flatMap(_.asString)
  def getInt(key: String): Option[Int]         = attributes.get(key).flatMap(_.asInt)
  def getLong(key: String): Option[Long]       = attributes.get(key).flatMap(_.asLong)
  def getDouble(key: String): Option[Double]   = attributes.get(key).flatMap(_.asDouble)

  def isEmpty: Boolean  = targetingKey.isEmpty && attributes.isEmpty
  def nonEmpty: Boolean = !isEmpty
}

object EvaluationContext {
  private def mergeAttributes(
    base: Map[String, AttributeValue],
    overrides: Map[String, AttributeValue]
  ): Map[String, AttributeValue] =
    if (overrides.isEmpty) base
    else if (base.isEmpty) overrides // no key collisions against an empty base, so no struct-merge is possible
    else
      base ++ overrides.map { case (key, overrideVal) =>
        key -> (base.get(key) match {
          case Some(AttributeValue.StructValue(baseFields)) =>
            overrideVal match {
              case AttributeValue.StructValue(overrideFields) =>
                AttributeValue.StructValue(mergeAttributes(baseFields, overrideFields))
              case other => other
            }
          case _ => overrideVal
        })
      }

  val empty: EvaluationContext = EvaluationContext(None, Map.empty)

  def apply(targetingKey: String): EvaluationContext =
    EvaluationContext(Some(targetingKey), Map.empty)

  def forEntity(entityId: String, entityType: String = "user"): EvaluationContext =
    EvaluationContext(
      targetingKey = Some(entityId),
      attributes = Map(
        "entityId"   -> AttributeValue.StringValue(entityId),
        "entityType" -> AttributeValue.StringValue(entityType)
      )
    )

  def withAttributes(attributes: (String, AttributeValue)*): EvaluationContext =
    EvaluationContext(None, attributes.toMap)

  def builder: Builder = Builder(None, Map.empty)

  @scala.annotation.nowarn
  final case class Builder private[EvaluationContext] (
    targetingKey: Option[String],
    attributes: Map[String, AttributeValue]
  ) {
    def targetingKey(key: String): Builder                     = copy(targetingKey = Some(key))
    def attribute(key: String, value: AttributeValue): Builder = copy(attributes = attributes + (key -> value))
    def attribute(key: String, value: String): Builder         = attribute(key, AttributeValue.StringValue(value))
    def attribute(key: String, value: Boolean): Builder        = attribute(key, AttributeValue.BoolValue(value))
    def attribute(key: String, value: Int): Builder            = attribute(key, AttributeValue.IntValue(value))
    def attribute(key: String, value: Double): Builder         = attribute(key, AttributeValue.DoubleValue(value))
    def attributes(attrs: (String, AttributeValue)*): Builder  = copy(attributes = attributes ++ attrs)
    def build: EvaluationContext                               = EvaluationContext(targetingKey, attributes)
  }
}
