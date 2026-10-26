package zio.openfeature

/** Details for a tracking event.
  *
  * @param value
  *   Optional numeric value associated with the event (e.g., revenue, count)
  * @param attributes
  *   Additional attributes for the event. Accepted values: `Boolean`, `String`, `Int`, `Long`, `Double`, `Float`,
  *   `java.time.Instant`, any [[AttributeValue]], an OpenFeature `Value`, `Option`s of these, and `List`s /
  *   `Map[String, _]`s of these (nested). A `null` or `None` attribute is dropped. Any other type makes `track` fail
  *   with `FeatureFlagError.InvalidContext` naming the attribute, rather than being sent as its `toString`.
  */
final case class TrackingEventDetails(
  value: Option[Double] = None,
  attributes: Map[String, Any] = Map.empty
) {
  def withValue(v: Double): TrackingEventDetails               = copy(value = Some(v))
  def withAttribute(key: String, v: Any): TrackingEventDetails =
    copy(attributes = attributes + (key -> v))
}

object TrackingEventDetails {
  val empty: TrackingEventDetails = TrackingEventDetails()

  def apply(value: Double): TrackingEventDetails =
    TrackingEventDetails(value = Some(value))

  def apply(attributes: Map[String, Any]): TrackingEventDetails =
    new TrackingEventDetails(value = None, attributes = attributes)
}
