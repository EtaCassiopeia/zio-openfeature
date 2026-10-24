package zio.openfeature

import java.util.Locale

/** The representation a provider is asked for when a flag is evaluated: which SDK resolver evaluation calls. Read from
  * [[FlagType.wire]].
  *
  * Each scalar case selects the matching resolver (`Float` goes through the double resolver); `Object` selects the
  * object resolver, which is also where every domain type without a scalar wire form is evaluated.
  */
enum WireType:
  case Boolean
  case String
  case Int
  case Long
  case Float
  case Double
  case Object

  /** The case's name as written: `"Boolean"`, `"String"`, ... `"Object"`. */
  def name: String = toString

object WireType:

  /** Every case except `Object`: the wire types with a scalar resolver of their own. */
  val scalars: Set[WireType] = Set(Boolean, String, Int, Long, Float, Double)

  /** Reads a legacy `FlagType.wireType` string. The match ignores case, so `"string"` is [[WireType.String]]; any other
    * name (a domain type name such as `"Rollout"`, or `null`) is [[WireType.Object]], the documented meaning of "not
    * one of the scalars".
    */
  def fromName(name: String): WireType =
    if name == null then Object
    else
      name.toLowerCase(Locale.ROOT) match
        case "boolean" => Boolean
        case "string"  => String
        case "int"     => Int
        case "long"    => Long
        case "float"   => Float
        case "double"  => Double
        case _         => Object
