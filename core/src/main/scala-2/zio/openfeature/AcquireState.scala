package zio.openfeature

/** Where a [[FeatureFlags.fromAcquireAsync]] instance stands with its real provider. Transitions are `Constructing →
  * Live` or `Constructing → Failed`, once; `Live` and `Failed` are terminal.
  */
sealed trait AcquireState extends Product with Serializable {
  def isLive: Boolean = this == AcquireState.Live
}

object AcquireState {

  /** The fallback is serving; `acquire` (including retries and `verify`) is in flight. */
  case object Constructing extends AcquireState

  /** The real provider was acquired, verified, and swapped in — it is serving. */
  case object Live extends AcquireState

  /** Construction, verification, or the swap failed terminally; the fallback serves for the life of the layer. */
  final case class Failed(cause: Throwable) extends AcquireState
}
