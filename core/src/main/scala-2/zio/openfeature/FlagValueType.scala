package zio.openfeature

sealed trait FlagValueType extends Product with Serializable {
  def name: String = this match {
    case FlagValueType.Boolean => "Boolean"
    case FlagValueType.String  => "String"
    case FlagValueType.Int     => "Int"
    case FlagValueType.Long    => "Long"
    case FlagValueType.Double  => "Double"
    case FlagValueType.Object  => "Object"
  }
}

object FlagValueType {
  case object Boolean extends FlagValueType
  case object String  extends FlagValueType
  case object Int     extends FlagValueType
  case object Long    extends FlagValueType
  case object Double  extends FlagValueType
  case object Object  extends FlagValueType

  // Hand-built rather than derived (no `values` on a sealed trait), so every new case must be added here too —
  // `FeatureHook.supportedFlagTypes` defaults to this set, and a case missing from it would silently exclude that
  // flag type from every hook.
  val allTypes: Set[FlagValueType] = Set(Boolean, String, Int, Long, Double, Object)

  def fromFlagType[A](implicit ft: FlagType[A]): FlagValueType =
    ft.typeName match {
      case "Boolean" => Boolean
      case "String"  => String
      case "Int"     => Int
      case "Long"    => Long
      case "Float"   => Double
      case "Double"  => Double
      case _         => Object
    }
}
