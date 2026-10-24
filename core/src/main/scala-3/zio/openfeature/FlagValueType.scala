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

  /** Reads [[FlagType.wire]], not `typeName`, so this reports the type the provider was actually asked for. A
    * scalar-backed custom type, and `Option` over a scalar, therefore report the scalar here, which is what lets hooks
    * scoped to that type (via `FeatureHook.supportedFlagTypes`) see the evaluation at all.
    */
  def fromFlagType[A](using ft: FlagType[A]): FlagValueType =
    ft.wire match
      case WireType.Boolean => Boolean
      case WireType.String  => String
      case WireType.Int     => Int
      case WireType.Long    => Long
      case WireType.Float   => Double
      case WireType.Double  => Double
      case WireType.Object  => Object
