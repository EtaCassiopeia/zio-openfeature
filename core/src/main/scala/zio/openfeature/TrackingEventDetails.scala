package zio.openfeature

/** Details for a tracking event.
  *
  * @param value
  *   Optional numeric value associated with the event (e.g., revenue, count)
  * @param attributes
  *   Additional attributes for the event
  */
final case class TrackingEventDetails(
  value: Option[Double] = None,
  attributes: Map[String, Any] = Map.empty
):
  def withValue(v: Double): TrackingEventDetails = copy(value = Some(v))
  def withAttribute(key: String, v: Any): TrackingEventDetails =
    copy(attributes = attributes + (key -> v))

object TrackingEventDetails:
  val empty: TrackingEventDetails = TrackingEventDetails()

  def apply(value: Double): TrackingEventDetails =
    TrackingEventDetails(value = Some(value))

  def apply(attributes: Map[String, Any]): TrackingEventDetails =
    TrackingEventDetails(attributes = attributes)
