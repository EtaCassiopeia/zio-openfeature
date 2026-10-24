package zio.openfeature

import java.util.Locale

/** The representation a provider is asked for when a flag is evaluated: which SDK resolver evaluation calls. Read from
  * [[FlagType.wire]].
  *
  * Each scalar case selects the matching resolver (`Float` goes through the double resolver); `Object` selects the
  * object resolver, which is also where every domain type without a scalar wire form is evaluated.
  */
sealed abstract class WireType(val name: String) extends Product with Serializable

object WireType {
  case object Boolean extends WireType("Boolean")
  case object String  extends WireType("String")
  case object Int     extends WireType("Int")
  case object Long    extends WireType("Long")
  case object Float   extends WireType("Float")
  case object Double  extends WireType("Double")
  case object Object  extends WireType("Object")

  /** Every case except `Object`: the wire types with a scalar resolver of their own. */
  val scalars: Set[WireType] = Set(Boolean, String, Int, Long, Float, Double)

  /** Reads a legacy `FlagType.wireType` string. The match ignores case, so `"string"` is [[WireType.String]]; any other
    * name (a domain type name such as `"Rollout"`, or `null`) is [[WireType.Object]], the documented meaning of "not
    * one of the scalars".
    */
  def fromName(name: java.lang.String): WireType =
    if (name == null) Object
    else
      name.toLowerCase(Locale.ROOT) match {
        case "boolean" => Boolean
        case "string"  => String
        case "int"     => Int
        case "long"    => Long
        case "float"   => Float
        case "double"  => Double
        case _         => Object
      }
}
