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
  failureThreshold: Int = 5,
  resetTimeout: Duration = 30.seconds,
  halfOpenMaxCalls: Int = 1,
  probeTimeout: Duration = 1.second
)

/** Why the circuit was opened — determines whether external recovery applies. */
sealed private[extras] trait OpenReason extends Product with Serializable
private[extras] object OpenReason {
  case object Failures extends OpenReason
  case object External extends OpenReason
}

sealed private[extras] trait CircuitState extends Product with Serializable
private[extras] object CircuitState {
  case object Closed                                          extends CircuitState
  final case class Open(sinceNanos: Long, reason: OpenReason) extends CircuitState
  // probeStartNanos records when the in-flight probe acquired the slot; it only matters when probing == true.
  final case class HalfOpen(successes: Int, probing: Boolean, probeStartNanos: Long) extends CircuitState
}

final private[extras] case class CircuitBreakerState(
  circuit: CircuitState,
  consecutiveFailures: Int
)

/** Result of a circuit breaker gate check. */
sealed private[extras] trait GateResult extends Product with Serializable
private[extras] object GateResult {
  case object Allowed  extends GateResult
  case object Rejected extends GateResult
}

/** A standalone circuit breaker state machine with three states: Closed, Open, and Half-Open.
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
  *   - '''Open(External) → Closed''': via `reset()` (external recovery)
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
  def tryAcquire: GateResult = {
    val state = stateRef.get()
    state.circuit match {
      case Closed => GateResult.Allowed

      case open: Open =>
        val elapsed = ticker.nanos() - open.sinceNanos
        if (elapsed >= config.resetTimeout.toNanos) {
          val halfOpen =
            CircuitBreakerState(
              HalfOpen(successes = 0, probing = true, probeStartNanos = ticker.nanos()),
              state.consecutiveFailures
            )
          if (stateRef.compareAndSet(state, halfOpen)) GateResult.Allowed
          else GateResult.Rejected
        } else {
          GateResult.Rejected
        }

      case ho: HalfOpen =>
        if (!ho.probing) {
          val probing =
            CircuitBreakerState(
              HalfOpen(ho.successes, probing = true, probeStartNanos = ticker.nanos()),
              state.consecutiveFailures
            )
          if (stateRef.compareAndSet(state, probing)) GateResult.Allowed
          else GateResult.Rejected
        } else if (ticker.nanos() - ho.probeStartNanos >= config.probeTimeout.toNanos) {
          // Steal a wedged probe slot: a probe that won the CAS but died without recording an outcome would
          // otherwise pin the circuit half-open forever. After probeTimeout, let a fresh caller take the slot.
          val stolen =
            CircuitBreakerState(
              HalfOpen(ho.successes, probing = true, probeStartNanos = ticker.nanos()),
              state.consecutiveFailures
            )
          if (stateRef.compareAndSet(state, stolen)) GateResult.Allowed
          else GateResult.Rejected
        } else {
          GateResult.Rejected
        }
    }
  }

  /** Record a successful call. Resets consecutive failures in Closed state. In Half-Open state, increments the success
    * counter and closes the circuit when `halfOpenMaxCalls` is reached.
    *
    * @return
    *   true if this call caused the circuit to close (transition from Half-Open to Closed)
    */
  def recordSuccess(): Boolean = {
    var done     = false
    var didClose = false
    while (!done) {
      val current = stateRef.get()
      current.circuit match {
        case Closed =>
          if (current.consecutiveFailures == 0) {
            done = true
          } else {
            val next = CircuitBreakerState(Closed, consecutiveFailures = 0)
            done = stateRef.compareAndSet(current, next)
          }

        case ho: HalfOpen =>
          val newSuccesses = ho.successes + 1
          if (newSuccesses >= config.halfOpenMaxCalls) {
            val next = CircuitBreakerState(Closed, consecutiveFailures = 0)
            done = stateRef.compareAndSet(current, next)
            if (done) didClose = true
          } else {
            val next = CircuitBreakerState(HalfOpen(newSuccesses, probing = false, probeStartNanos = 0L), 0)
            done = stateRef.compareAndSet(current, next)
          }

        case _: Open =>
          done = true
      }
    }
    didClose
  }

  /** Record that the delegate is reachable (e.g., it returned an application-level error). Resets `consecutiveFailures`
    * in Closed state, and in Half-Open clears the `probing` flag so the next caller can probe — but does NOT increment
    * the success counter, since reachability is not the same as a successful evaluation.
    */
  def recordReachable(): Unit = {
    var done = false
    while (!done) {
      val current = stateRef.get()
      current.circuit match {
        case Closed =>
          if (current.consecutiveFailures == 0) {
            done = true
          } else {
            val next = CircuitBreakerState(Closed, consecutiveFailures = 0)
            done = stateRef.compareAndSet(current, next)
          }

        case ho: HalfOpen =>
          if (!ho.probing) {
            done = true
          } else {
            val next =
              CircuitBreakerState(
                HalfOpen(ho.successes, probing = false, probeStartNanos = 0L),
                current.consecutiveFailures
              )
            done = stateRef.compareAndSet(current, next)
          }

        case _: Open =>
          done = true
      }
    }
  }

  /** Record a failed call. Increments consecutive failures and opens the circuit when `failureThreshold` is reached.
    *
    * @return
    *   true if this call caused the circuit to open
    */
  def recordFailure(): Boolean = {
    var done    = false
    var didOpen = false
    while (!done) {
      val current     = stateRef.get()
      val newFailures = current.consecutiveFailures + 1
      current.circuit match {
        case Closed =>
          if (newFailures >= config.failureThreshold) {
            val next = CircuitBreakerState(Open(ticker.nanos(), OpenReason.Failures), newFailures)
            done = stateRef.compareAndSet(current, next)
            if (done) didOpen = true
          } else {
            val next = CircuitBreakerState(Closed, newFailures)
            done = stateRef.compareAndSet(current, next)
          }

        case _: HalfOpen =>
          val next = CircuitBreakerState(Open(ticker.nanos(), OpenReason.Failures), newFailures)
          done = stateRef.compareAndSet(current, next)
          if (done) didOpen = true

        case _: Open =>
          done = true
      }
    }
    didOpen
  }

  /** Force the circuit open (external signal, e.g., delegate provider reported ERROR). */
  def trip(): Unit = {
    var done = false
    while (!done) {
      val current = stateRef.get()
      current.circuit match {
        case _: Open => done = true
        case _ =>
          val next = CircuitBreakerState(Open(ticker.nanos(), OpenReason.External), current.consecutiveFailures)
          done = stateRef.compareAndSet(current, next)
      }
    }
  }

  /** Reset the circuit if it was opened externally. Failure-count opens recover through the half-open probe mechanism
    * instead.
    */
  def reset(): Unit = {
    var done = false
    while (!done) {
      val current = stateRef.get()
      current.circuit match {
        case Closed if current.consecutiveFailures == 0 => done = true
        case Open(_, OpenReason.External) =>
          val next = CircuitBreakerState(Closed, consecutiveFailures = 0)
          done = stateRef.compareAndSet(current, next)
        case _ =>
          done = true
      }
    }
  }

  /** Transition to half-open state (e.g., for stale delegate). Only transitions from Open — a Closed circuit is already
    * healthy and should not be demoted.
    */
  def transitionToHalfOpen(): Unit = {
    var done = false
    while (!done) {
      val current = stateRef.get()
      current.circuit match {
        case _: HalfOpen => done = true
        case Closed      => done = true
        case _: Open =>
          val next =
            CircuitBreakerState(
              HalfOpen(successes = 0, probing = false, probeStartNanos = 0L),
              current.consecutiveFailures
            )
          done = stateRef.compareAndSet(current, next)
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
