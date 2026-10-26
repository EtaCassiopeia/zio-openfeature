package zio.openfeature

/** The type of the provider resolver an evaluation called — what hooks scope themselves to via
  * `FeatureHook.supportedFlagTypes`. It mirrors the provider's resolvers, not the Scala type: a `Float` flag is
  * resolved as a `Double` and a scalar-backed custom type as its scalar, so a hook scoped to `Double` also sees `Float`
  * flags.
  */
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

  /** Reads [[FlagType.wire]], not `typeName`, so this reports the type the provider was actually asked for. A
    * scalar-backed custom type, and `Option` over a scalar, therefore report the scalar here, which is what lets hooks
    * scoped to that type (via `FeatureHook.supportedFlagTypes`) see the evaluation at all.
    */
  def fromFlagType[A](implicit ft: FlagType[A]): FlagValueType =
    ft.wire match {
      case WireType.Boolean => Boolean
      case WireType.String  => String
      case WireType.Int     => Int
      case WireType.Long    => Long
      case WireType.Float   => Double
      case WireType.Double  => Double
      case WireType.Object  => Object
    }
}
