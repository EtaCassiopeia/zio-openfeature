package zio.openfeature

enum FlagValueType:
  case Boolean
  case String
  case Int
  case Double
  case Object

  def name: String = toString

object FlagValueType:
  def fromFlagType[A](using ft: FlagType[A]): FlagValueType =
    ft.typeName match
      case "Boolean" => Boolean
      case "String"  => String
      case "Int"     => Int
      case "Double"  => Double
      case _         => Object
