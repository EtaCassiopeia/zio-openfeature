package zio.openfeature

/** Where a [[FeatureFlags.fromAcquireAsync]] instance stands with its real provider. Transitions are `Constructing →
  * Live` or `Constructing → Failed`, once; `Live` and `Failed` are terminal.
  */
enum AcquireState:
  /** The fallback is serving; `acquire` (including retries and `verify`) is in flight. */
  case Constructing

  /** The real provider was acquired, verified, and swapped in — it is serving. */
  case Live

  /** Construction, verification, or the swap failed terminally; the fallback serves for the life of the layer. */
  case Failed(cause: Throwable)

  def isLive: Boolean = this == Live
