package zio.openfeature

sealed trait FlagValueType extends Product with Serializable {
  def name: String = this match {
    case FlagValueType.Boolean => "Boolean"
    case FlagValueType.String  => "String"
    case FlagValueType.Int     => "Int"
    case FlagValueType.Double  => "Double"
    case FlagValueType.Object  => "Object"
  }
}

object FlagValueType {
  case object Boolean extends FlagValueType
  case object String  extends FlagValueType
  case object Int     extends FlagValueType
  case object Double  extends FlagValueType
  case object Object  extends FlagValueType

  val allTypes: Set[FlagValueType] = Set(Boolean, String, Int, Double, Object)

  def fromFlagType[A](implicit ft: FlagType[A]): FlagValueType =
    ft.typeName match {
      case "Boolean" => Boolean
      case "String"  => String
      case "Int"     => Int
      case "Long"    => Int
      case "Float"   => Double
      case "Double"  => Double
      case _         => Object
    }
}
