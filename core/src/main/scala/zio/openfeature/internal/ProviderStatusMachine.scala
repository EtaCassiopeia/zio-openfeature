package zio.openfeature.internal

import zio.openfeature.ProviderStatus

/** Pure provider-status state machine (#244). Every status write in FeatureFlagsLive and the async init watchdog goes
  * through `transition`, so the legal-transition table lives in exactly one place.
  */
private[openfeature] object ProviderStatusMachine {

  /** Why a status write is being attempted. `Event*` signals originate from Java SDK emitter threads and are only
    * applied after the event's provider identity has been checked; the rest are lifecycle signals from this library's
    * own code paths and are authoritative.
    */
  sealed trait Signal
  object Signal {
    case object EventReady extends Signal

    /** `fatal = true` when the event carries ErrorCode.PROVIDER_FATAL — the Java SDK's state manager maps that to FATAL
      * (spec 1.7.6); mirror it.
      */
    final case class EventError(fatal: Boolean) extends Signal
    case object EventStale                      extends Signal
    case object SwapStarted                     extends Signal
    case object SwapSucceeded                   extends Signal
    case object SwapFailed                      extends Signal
    case object InitTimeout                     extends Signal
    case object ShutdownStarted                 extends Signal
    case object ShutdownCompleted               extends Signal

    /** fromAcquireAsync: a failed hot-swap rolled back to a still-live fallback. */
    case object ForceReady extends Signal
  }

  /** Cross-thread context the table needs but the public ProviderStatus cannot represent. */
  final case class Context(
    everReady: Boolean,
    swapInProgress: Boolean,
    shutdownCompleted: Boolean
  )

  /** Some(next) => apply the transition (and any transition-gated side effects); None => ignore the signal. Lifecycle
    * signals always yield Some — they are this library's own authoritative writes.
    */
  def transition(current: ProviderStatus, signal: Signal, ctx: Context): Option[ProviderStatus] = {
    import ProviderStatus._
    signal match {
      // Lifecycle: authoritative from anywhere. setProvider is the ONLY exit from Fatal and from
      // post-shutdown NotReady — it installs a NEW provider, so the old one's irrecoverability
      // (spec 1.7.6) no longer applies.
      case Signal.ShutdownStarted   => Some(ShuttingDown)
      case Signal.ShutdownCompleted => Some(NotReady)
      case Signal.SwapStarted       => Some(NotReady)
      case Signal.SwapSucceeded     => Some(Ready)
      case Signal.SwapFailed        => Some(Error)
      case Signal.ForceReady        => Some(Ready)

      // Non-lifecycle signals (the init watchdog and every bridge event) never take effect during a swap (the swap
      // sets its own outcome) nor after an explicit shutdown has completed (post-shutdown NotReady is terminal; a
      // late event — or the still-scheduled watchdog fiber, which `shutdown` does not cancel — must not resurrect the
      // instance). Checked before InitTimeout so the watchdog cannot Fatal a swapped/shut-down provider.
      case _ if ctx.swapInProgress || ctx.shutdownCompleted => None

      // Watchdog: escalate ONLY a provider that never became usable. Ready-then-transient-Error must
      // never be Fataled (the core #244 bug).
      case Signal.InitTimeout =>
        current match {
          case NotReady | Error if !ctx.everReady => Some(Fatal)
          case _                                  => None
        }

      case Signal.EventReady =>
        current match {
          // Error => Ready is genuine recovery (identity-checked, so no time guard needed);
          // Fatal/ShuttingDown are sticky; Ready is already there.
          case NotReady | Stale | Error     => Some(Ready)
          case Ready | Fatal | ShuttingDown => None
        }

      case Signal.EventError(fatal) =>
        current match {
          case Fatal | ShuttingDown => None
          case _ if fatal           => Some(Fatal)
          case Error                => None        // already there
          case _                    => Some(Error) // NotReady | Ready | Stale
        }

      case Signal.EventStale =>
        current match {
          // STALE means "serving cached data" — only meaningful from Ready (never over Fatal/ShuttingDown,
          // which staleHandler can do today).
          case Ready => Some(Stale)
          case _     => None
        }
    }
  }
}
