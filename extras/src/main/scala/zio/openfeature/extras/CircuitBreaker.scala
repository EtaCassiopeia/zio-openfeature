package zio.openfeature.extras

import zio._
import java.util.concurrent.atomic.AtomicReference

/** A monotonic time source for the circuit breaker's elapsed-time decisions.
  *
  * Uses `System.nanoTime()` rather than a wall clock so an NTP step or manual clock adjustment cannot corrupt open /
  * half-open timing. Injectable so tests can drive transitions deterministically.
  */
trait Ticker {
  def nanos(): Long
}
object Ticker {
  val system: Ticker = new Ticker {
    def nanos(): Long = java.lang.System.nanoTime()
  }
}

/** Configuration for the circuit breaker state machine.
  *
  * @param failureThreshold
  *   Number of consecutive failures before the circuit opens
  * @param resetTimeout
  *   How long the circuit stays open before transitioning to half-open
  * @param halfOpenMaxCalls
  *   Number of successful probes in half-open state required to close the circuit
  * @param probeTimeout
  *   How long a single half-open probe may hold the probe slot before another caller may steal it. Guards against a
  *   probe that dies without recording an outcome pinning the circuit half-open forever.
  */
final case class CircuitBreakerConfig(
  failureThreshold: Int = CircuitBreaker.DefaultFailureThreshold,
  resetTimeout: Duration = CircuitBreaker.DefaultResetTimeout,
  halfOpenMaxCalls: Int = CircuitBreaker.DefaultHalfOpenMaxCalls,
  probeTimeout: Duration = CircuitBreaker.DefaultProbeTimeout
)

/** Why the circuit was opened — determines which recovery applies. */
sealed private[extras] trait OpenReason extends Product with Serializable
private[extras] object OpenReason {
  case object Failures extends OpenReason

  /** The delegate said it is unhealthy by event. Cleared by an event READY, or the reset-timeout probe. */
  case object External extends OpenReason

  /** A poll of the delegate's `getState()` said it is unhealthy. Also cleared by a later polled READY (#447). */
  case object Polled extends OpenReason
}

sealed private[extras] trait CircuitState extends Product with Serializable
private[extras] object CircuitState {
  case object Closed                                          extends CircuitState
  final case class Open(sinceNanos: Long, reason: OpenReason) extends CircuitState
  // probeStartNanos records when the in-flight probe acquired the slot; it only matters when probing == true.
  final case class HalfOpen(successes: Int, probing: Boolean, probeStartNanos: Long) extends CircuitState
}

/** @param epoch
  *   bumped on every circuit transition (not on Closed failure-count changes), so an outcome recorded against a
  *   [[CircuitBreaker.Permit]] from an earlier state can be recognised as stale and ignored (#447).
  */
final private[extras] case class CircuitBreakerState(
  circuit: CircuitState,
  consecutiveFailures: Int,
  epoch: Long = 0L
)

/** Result of a circuit breaker gate check. */
sealed private[extras] trait GateResult extends Product with Serializable
private[extras] object GateResult {
  case object Allowed  extends GateResult
  case object Rejected extends GateResult
}

/** The state machine behind [[CircuitBreakerProvider]]: three states, Closed, Open and Half-Open. It is an internal
  * engine — use [[CircuitBreakerProvider]]; standalone construction is deprecated, since the shape of its gate result
  * is internal and still changing.
  *
  * This class manages only the state transitions and failure counting. It has no knowledge of OpenFeature, providers,
  * or ZIO effects — it is a pure concurrency-safe state machine backed by `AtomicReference` with CAS.
  *
  * Use [[CircuitBreaker.tryAcquire]] to check whether a call should proceed, then call [[CircuitBreaker.recordSuccess]]
  * or [[CircuitBreaker.recordFailure]] based on the outcome.
  *
  * State transitions:
  *   - '''Closed → Open''': after `failureThreshold` consecutive failures
  *   - '''Open → Half-Open''': after `resetTimeout` elapses
  *   - '''Half-Open → Closed''': after `halfOpenMaxCalls` successful probes
  *   - '''Half-Open → Open''': on any probe failure
  *   - '''Any → Open''': via `trip()` (external signal)
  *   - '''Open(External or Polled) → Closed''': via `reset()` (external recovery)
  *
  * Every transition starts a new epoch. Outcomes recorded with the [[CircuitBreaker.Permit]] a call was admitted under
  * only count while the circuit is still in that epoch, so a slow call admitted earlier cannot close, re-open or free
  * the probe slot of a later state.
  */
final class CircuitBreaker private[extras] (
  val config: CircuitBreakerConfig,
  private[extras] val stateRef: AtomicReference[CircuitBreakerState],
  private[extras] val ticker: Ticker
) {

  import CircuitState._

  /** Check whether a call should proceed.
    *
    * Returns `Allowed` if the circuit is closed, or if the circuit is half-open/open-past-timeout and this thread won
    * the CAS to become the probe. Returns `Rejected` if the circuit is open or a probe is already in progress.
    */
  def tryAcquire: GateResult = if (acquire().isDefined) GateResult.Allowed else GateResult.Rejected

  /** Like [[tryAcquire]], but returns the permit the call was admitted under. Record the call's outcome with that
    * permit, so it only counts if the circuit is still in the state that admitted it.
    */
  private[extras] def acquire(): Option[CircuitBreaker.Permit] = {
    val state = stateRef.get()
    state.circuit match {
      case Closed => Some(CircuitBreaker.Permit(state.epoch))

      case open: Open =>
        if (ticker.nanos() - open.sinceNanos >= config.resetTimeout.toNanos) startProbe(state, successes = 0)
        else None

      case ho: HalfOpen =>
        if (!ho.probing) startProbe(state, ho.successes)
        // Steal a wedged probe slot: a probe that won the CAS but died without recording an outcome would otherwise
        // pin the circuit half-open forever. After probeTimeout, let a fresh caller take the slot; the new epoch makes
        // the abandoned probe's late outcome stale.
        else if (ticker.nanos() - ho.probeStartNanos >= config.probeTimeout.toNanos) startProbe(state, ho.successes)
        else None
    }
  }

  private def startProbe(state: CircuitBreakerState, successes: Int): Option[CircuitBreaker.Permit] = {
    val next = transition(state, HalfOpen(successes, probing = true, probeStartNanos = ticker.nanos()))
    if (stateRef.compareAndSet(state, next)) Some(CircuitBreaker.Permit(next.epoch)) else None
  }

  private def transition(
    from: CircuitBreakerState,
    to: CircuitState,
    failures: Int = -1
  ): CircuitBreakerState =
    CircuitBreakerState(to, if (failures < 0) from.consecutiveFailures else failures, from.epoch + 1)

  // `permit = None` is the permit-less public API: the outcome applies to whatever the current state is.
  private def current(permit: Option[CircuitBreaker.Permit]): Option[CircuitBreakerState] = {
    val state = stateRef.get()
    if (permit.forall(_.epoch == state.epoch)) Some(state) else None
  }

  /** Record a successful call. Resets consecutive failures in Closed state. In Half-Open state, increments the success
    * counter and closes the circuit when `halfOpenMaxCalls` is reached.
    *
    * @return
    *   true if this call caused the circuit to close (transition from Half-Open to Closed)
    */
  def recordSuccess(): Boolean = recordSuccess(None)

  /** [[recordSuccess()]] for a call admitted under `permit`; a no-op if the circuit has moved on since. */
  private[extras] def recordSuccess(permit: CircuitBreaker.Permit): Boolean = recordSuccess(Some(permit))

  private def recordSuccess(permit: Option[CircuitBreaker.Permit]): Boolean = {
    var done     = false
    var didClose = false
    while (!done)
      current(permit) match {
        case None => done = true
        case Some(state) =>
          state.circuit match {
            case Closed =>
              done = state.consecutiveFailures == 0 ||
                stateRef.compareAndSet(state, state.copy(consecutiveFailures = 0))

            case ho: HalfOpen =>
              val newSuccesses = ho.successes + 1
              if (newSuccesses >= config.halfOpenMaxCalls) {
                done = stateRef.compareAndSet(state, transition(state, Closed, failures = 0))
                if (done) didClose = true
              } else
                done = stateRef.compareAndSet(
                  state,
                  transition(state, HalfOpen(newSuccesses, probing = false, probeStartNanos = 0L), failures = 0)
                )

            case _: Open => done = true
          }
      }
    didClose
  }

  /** Record that the delegate is reachable (e.g., it returned an application-level error). Resets `consecutiveFailures`
    * in Closed state, and in Half-Open clears the `probing` flag so the next caller can probe — but does NOT increment
    * the success counter, since reachability is not the same as a successful evaluation.
    */
  def recordReachable(): Unit = recordReachable(None)

  /** [[recordReachable()]] for a call admitted under `permit`; a no-op if the circuit has moved on since. */
  private[extras] def recordReachable(permit: CircuitBreaker.Permit): Unit = recordReachable(Some(permit))

  private def recordReachable(permit: Option[CircuitBreaker.Permit]): Unit = {
    var done = false
    while (!done)
      current(permit) match {
        case None => done = true
        case Some(state) =>
          state.circuit match {
            case Closed =>
              done = state.consecutiveFailures == 0 ||
                stateRef.compareAndSet(state, state.copy(consecutiveFailures = 0))

            case ho: HalfOpen =>
              done = !ho.probing ||
                stateRef.compareAndSet(state, transition(state, HalfOpen(ho.successes, probing = false, 0L)))

            case _: Open => done = true
          }
      }
  }

  /** Record a failed call. Increments consecutive failures and opens the circuit when `failureThreshold` is reached.
    *
    * @return
    *   true if this call caused the circuit to open
    */
  def recordFailure(): Boolean = recordFailure(None)

  /** [[recordFailure()]] for a call admitted under `permit`; a no-op if the circuit has moved on since. */
  private[extras] def recordFailure(permit: CircuitBreaker.Permit): Boolean = recordFailure(Some(permit))

  private def recordFailure(permit: Option[CircuitBreaker.Permit]): Boolean = {
    var done    = false
    var didOpen = false
    while (!done)
      current(permit) match {
        case None => done = true
        case Some(state) =>
          val newFailures = state.consecutiveFailures + 1
          state.circuit match {
            case Closed if newFailures < config.failureThreshold =>
              done = stateRef.compareAndSet(state, state.copy(consecutiveFailures = newFailures))

            case Closed | _: HalfOpen =>
              done = stateRef.compareAndSet(
                state,
                transition(state, Open(ticker.nanos(), OpenReason.Failures), failures = newFailures)
              )
              if (done) didOpen = true

            case _: Open => done = true
          }
      }
    didOpen
  }

  /** Force the circuit open (external signal, e.g., delegate provider reported ERROR). */
  def trip(): Unit = trip(OpenReason.External)

  /** Open the circuit for `reason`. An event trip also takes over an open circuit a poll had tripped, so a later polled
    * READY cannot undo what the delegate said by event.
    */
  private[extras] def trip(reason: OpenReason): Unit = {
    var done = false
    while (!done) {
      val state = stateRef.get()
      state.circuit match {
        case Open(since, OpenReason.Polled) if reason == OpenReason.External =>
          done = stateRef.compareAndSet(state, transition(state, Open(since, OpenReason.External)))
        case _: Open => done = true
        case _ =>
          done = stateRef.compareAndSet(state, transition(state, Open(ticker.nanos(), reason)))
      }
    }
  }

  /** Reset the circuit if it was opened by the delegate's own signal (event or poll). Failure-count opens recover
    * through the half-open probe mechanism instead.
    */
  def reset(): Unit = resetIf(r => r == OpenReason.External || r == OpenReason.Polled)

  /** Reset only a circuit that `reason` opened (#447: a polled READY must not undo an event-driven trip). */
  private[extras] def resetIfOpenedBy(reason: OpenReason): Unit = resetIf(_ == reason)

  private def resetIf(opener: OpenReason => Boolean): Unit = {
    var done = false
    while (!done) {
      val state = stateRef.get()
      state.circuit match {
        case Open(_, reason) if opener(reason) =>
          done = stateRef.compareAndSet(state, transition(state, Closed, failures = 0))
        case _ => done = true
      }
    }
  }

  /** Transition to half-open state (e.g., for stale delegate). Only transitions from Open — a Closed circuit is already
    * healthy and should not be demoted.
    */
  def transitionToHalfOpen(): Unit = {
    var done = false
    while (!done) {
      val state = stateRef.get()
      state.circuit match {
        case _: Open =>
          done = stateRef.compareAndSet(
            state,
            transition(state, HalfOpen(successes = 0, probing = false, probeStartNanos = 0L))
          )
        case _ => done = true
      }
    }
  }

  /** Current circuit state. */
  def currentState: CircuitState = stateRef.get().circuit

  /** Whether the circuit is closed (normal operation). */
  def isClosed: Boolean = stateRef.get().circuit == Closed

  /** Whether the circuit is open (rejecting calls). */
  def isOpen: Boolean = stateRef.get().circuit.isInstanceOf[Open]

  /** Whether the circuit is half-open (probing). */
  def isHalfOpen: Boolean = stateRef.get().circuit.isInstanceOf[HalfOpen]
}

object CircuitBreaker {

  // Defaults shared by CircuitBreakerConfig and CircuitBreakerProviderConfig, so the two cannot drift apart.
  val DefaultFailureThreshold: Int  = 5
  val DefaultResetTimeout: Duration = 30.seconds
  val DefaultHalfOpenMaxCalls: Int  = 1
  val DefaultProbeTimeout: Duration = 1.second

  /** Proof that a call was admitted, tied to the circuit state that admitted it (#447). */
  final private[extras] case class Permit(epoch: Long)

  @deprecated("Use CircuitBreakerProvider; the standalone breaker's gate result is internal", "1.2.0")
  def apply(
    config: CircuitBreakerConfig = CircuitBreakerConfig()
  ): CircuitBreaker =
    apply(config, Ticker.system)

  private[extras] def apply(
    config: CircuitBreakerConfig,
    ticker: Ticker
  ): CircuitBreaker = {
    val state = new AtomicReference(CircuitBreakerState(CircuitState.Closed, consecutiveFailures = 0))
    new CircuitBreaker(config, state, ticker)
  }
}
