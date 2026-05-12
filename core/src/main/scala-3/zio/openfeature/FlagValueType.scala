package zio.openfeature

enum FlagValueType:
  case Boolean
  case String
  case Int
  case Double
  case Object

  def name: String = toString

object FlagValueType:
  val allTypes: Set[FlagValueType] = FlagValueType.values.toSet

  def fromFlagType[A](using ft: FlagType[A]): FlagValueType =
    ft.typeName match
      case "Boolean" => Boolean
      case "String"  => String
      case "Int"     => Int
      case "Long"    => Int
      case "Float"   => Double
      case "Double"  => Double
      case _         => Object
