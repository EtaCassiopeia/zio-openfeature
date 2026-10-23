package zio.openfeature.internal

import dev.openfeature.sdk.{MutableStructure, MutableTrackingEventDetails, Structure, Value}
import zio.openfeature.{AttributeValue, TrackingEventDetails}

import scala.jdk.CollectionConverters._

/** Converts [[TrackingEventDetails]] to the SDK's tracking details by type (#441). Total: an attribute it cannot
  * represent is reported, never sent as its `toString`. `null` and `None` attributes are dropped.
  */
private[openfeature] object TrackingConverter {

  def toOpenFeature(details: TrackingEventDetails): Either[String, MutableTrackingEventDetails] = {
    val result = details.value match {
      case Some(v) => new MutableTrackingEventDetails(v)
      case None    => new MutableTrackingEventDetails()
    }
    details.attributes.foldLeft[Either[String, MutableTrackingEventDetails]](Right(result)) {
      case (acc, (key, value)) =>
        for {
          d <- acc
          v <- toValue(value).left.map(problem => s"tracking attribute '$key' $problem")
        } yield {
          v.foreach(d.add(key, _))
          d
        }
    }
  }

  private def toValue(value: Any): Either[String, Option[Value]] = value match {
    case null                       => Right(None)
    case None                       => Right(None)
    case Some(inner)                => toValue(inner)
    case v: Value                   => Right(Some(v))
    case b: Boolean                 => Right(Some(new Value(java.lang.Boolean.valueOf(b))))
    case s: String                  => Right(Some(new Value(s)))
    case i: Int                     => Right(Some(new Value(Integer.valueOf(i))))
    case l: Long                    => Right(Some(longValue(l)))
    case d: Double                  => Right(Some(new Value(java.lang.Double.valueOf(d))))
    case f: Float                   => Right(Some(new Value(java.lang.Double.valueOf(f.toDouble))))
    case instant: java.time.Instant => Right(Some(new Value(instant)))
    case attr: AttributeValue       => Right(Some(attributeValue(attr)))
    case list: List[_]              => sequence(list.map(toValue)).map(vs => Some(new Value(vs.flatten.asJava)))
    case map: Map[_, _] =>
      val entries = map.toList.map {
        case (k: String, v) => toValue(v).map(_.map(k -> _))
        case (k, _)         => Left(s"has a map key of unsupported type ${className(k)}")
      }
      sequence(entries).map(kvs => Some(new Value(structure(kvs.flatten))))
    case other => Left(s"has unsupported type ${className(other)}")
  }

  private def attributeValue(attr: AttributeValue): Value = attr match {
    case AttributeValue.BoolValue(b)    => new Value(java.lang.Boolean.valueOf(b))
    case AttributeValue.StringValue(s)  => new Value(s)
    case AttributeValue.IntValue(i)     => new Value(Integer.valueOf(i))
    case AttributeValue.LongValue(l)    => longValue(l)
    case AttributeValue.DoubleValue(d)  => new Value(java.lang.Double.valueOf(d))
    case AttributeValue.InstantValue(t) => new Value(t)
    case AttributeValue.ListValue(list) => new Value(list.map(attributeValue).asJava)
    case AttributeValue.StructValue(m)  => new Value(structure(m.toList.map { case (k, v) => k -> attributeValue(v) }))
  }

  // Int-range longs go as Integer (matches integer targeting); larger ones keep full precision through Value's native
  // Long support rather than a lossy Double.
  private def longValue(l: Long): Value =
    if (l.isValidInt) new Value(Integer.valueOf(l.toInt)) else new Value(java.lang.Long.valueOf(l))

  private def structure(entries: List[(String, Value)]): Structure =
    new MutableStructure(new java.util.HashMap[String, Value](entries.toMap.asJava))

  private def sequence[A](xs: List[Either[String, A]]): Either[String, List[A]] =
    xs.foldRight[Either[String, List[A]]](Right(Nil))((x, acc) => for { a <- x; as <- acc } yield a :: as)

  private def className(v: Any): String = if (v == null) "null" else v.getClass.getName
}
