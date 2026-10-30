package zio.openfeature.internal

import zio.openfeature.ProviderStatus
import zio.openfeature.internal.ProviderRegistration.Settled

/** Pure provider-status state machine. Every status write in FeatureFlagsLive and the async init watchdog goes through
  * `transition`, so the legal-transition table lives in exactly one place.
  */
private[openfeature] object ProviderStatusMachine {

  /** Why a status write is being attempted. `Event*` signals originate from Java SDK emitter threads and are only
    * applied after the event's provider identity has been checked; the rest are lifecycle signals from this library's
    * own code paths and are authoritative.
    */
  sealed trait Signal
  object Signal {
    case object EventReady extends Signal

    /** `fatal = true` when the event carries ErrorCode.PROVIDER_FATAL — the Java SDK's state manager maps that to
      * FATAL; mirror it.
      */
    final case class EventError(fatal: Boolean) extends Signal
    case object EventStale                      extends Signal
    case object SwapStarted                     extends Signal

    /** The new provider initialized, and the SDK reports it in `settled`: an error it reported after initializing is
      * its status, not a failed swap.
      */
    final case class SwapSucceeded(settled: Settled) extends Signal
    case object SwapFailed                           extends Signal
    case object InitTimeout                          extends Signal
    case object ShutdownStarted                      extends Signal
    case object ShutdownCompleted                    extends Signal

    /** setProvider: a failed hot-swap was rolled back and the previous provider re-registered with the SDK, verified to
      * be routing again. Same transition as a successful swap outcome, applied by `setProvider` itself once the
      * rollback re-registration confirms the old provider is bound.
      */
    final case class RollbackSucceeded(settled: Settled) extends Signal

    /** A failed swap whose registration threw before the SDK bound the new provider changed nothing in the SDK: the
      * status goes back to `previous`, the one before the swap, and the SDK's state for the slot is applied after it as
      * an event. Not a readiness: the init watchdog stays armed.
      */
    final case class RollbackUnbound(previous: ProviderStatus) extends Signal
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
      // Lifecycle: authoritative from anywhere. setProvider is the ONLY exit from Fatal: it installs a NEW provider, so
      // the old one's irrecoverability no longer applies. Post-shutdown NotReady has no exit: `setProvider` is refused
      // before it signals.
      case Signal.ShutdownStarted            => Some(ShuttingDown)
      case Signal.ShutdownCompleted          => Some(NotReady)
      case Signal.SwapStarted                => Some(NotReady)
      case Signal.SwapSucceeded(settled)     => Some(statusOf(settled))
      case Signal.SwapFailed                 => Some(Error)
      case Signal.RollbackSucceeded(settled) => Some(statusOf(settled))
      case Signal.RollbackUnbound(prev)      => Some(prev)

      // Non-lifecycle signals (the init watchdog and every bridge event) never take effect during a swap (the swap
      // sets its own outcome) nor after an explicit shutdown has completed (post-shutdown NotReady is terminal; a
      // late event — or the still-scheduled watchdog fiber, which `shutdown` does not cancel — must not resurrect the
      // instance). Checked before InitTimeout so the watchdog cannot Fatal a swapped/shut-down provider.
      case _ if ctx.swapInProgress || ctx.shutdownCompleted => None

      // Watchdog: escalate ONLY a provider that never became usable. Ready-then-transient-Error must
      // never be Fataled.
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
          // The specification maps PROVIDER_STALE to STALE from any status (§5.3.5), and so does the SDK; a provider
          // recovering from an error to cached data goes Error -> Stale. Fatal and ShuttingDown stay final.
          case NotReady | Ready | Error     => Some(Stale)
          case Stale | Fatal | ShuttingDown => None
        }
    }
  }

  /** The status of a provider that initialized and settled in `settled`. */
  def statusOf(settled: Settled): ProviderStatus = settled match {
    case Settled.Ready    => ProviderStatus.Ready
    case Settled.Stale    => ProviderStatus.Stale
    case Settled.Error(_) => ProviderStatus.Error
    case Settled.Fatal(_) => ProviderStatus.Fatal
  }
}
