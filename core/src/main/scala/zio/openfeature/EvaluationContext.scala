package zio.openfeature

final case class EvaluationContext(
  targetingKey: Option[String],
  attributes: Map[String, AttributeValue]
):
  def merge(other: EvaluationContext): EvaluationContext =
    EvaluationContext(
      targetingKey = other.targetingKey.orElse(targetingKey),
      attributes = attributes ++ other.attributes
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

object EvaluationContext:
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

  final case class Builder private[EvaluationContext] (
    targetingKey: Option[String],
    attributes: Map[String, AttributeValue]
  ):
    def targetingKey(key: String): Builder                     = copy(targetingKey = Some(key))
    def attribute(key: String, value: AttributeValue): Builder = copy(attributes = attributes + (key -> value))
    def attribute(key: String, value: String): Builder         = attribute(key, AttributeValue.StringValue(value))
    def attribute(key: String, value: Boolean): Builder        = attribute(key, AttributeValue.BoolValue(value))
    def attribute(key: String, value: Int): Builder            = attribute(key, AttributeValue.IntValue(value))
    def attribute(key: String, value: Double): Builder         = attribute(key, AttributeValue.DoubleValue(value))
    def attributes(attrs: (String, AttributeValue)*): Builder  = copy(attributes = attributes ++ attrs)
    def build: EvaluationContext                               = EvaluationContext(targetingKey, attributes)
