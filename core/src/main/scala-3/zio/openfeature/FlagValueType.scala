package zio.openfeature

/** The type of the provider resolver an evaluation called — what hooks scope themselves to via
  * `FeatureHook.supportedFlagTypes`. It mirrors the provider's resolvers, not the Scala type: a `Float` flag is
  * resolved as a `Double` and a scalar-backed custom type as its scalar, so a hook scoped to `Double` also sees `Float`
  * flags.
  */
enum FlagValueType:
  case Boolean
  case String
  case Int
  case Long
  case Double
  case Object

  def name: String = toString

object FlagValueType:
  val allTypes: Set[FlagValueType] = FlagValueType.values.toSet

  /** Reads `wireType`, not `typeName`, so this reports the type the provider was actually asked for. A scalar-backed
    * custom type therefore reports its scalar here, which is what lets hooks scoped to that type (via
    * `FeatureHook.supportedFlagTypes`) see the evaluation at all.
    */
  def fromFlagType[A](using ft: FlagType[A]): FlagValueType =
    ft.wireType match
      case "Boolean" => Boolean
      case "String"  => String
      case "Int"     => Int
      case "Long"    => Long
      case "Float"   => Double
      case "Double"  => Double
      case _         => Object
