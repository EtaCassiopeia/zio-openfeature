package zio.openfeature.extras

import zio._
import zio.test._
import java.util.concurrent.atomic.AtomicReference

object CircuitBreakerSpec extends ZIOSpecDefault {

  /** A controllable monotonic ticker for deterministic time-based tests. */
  private class TestTicker extends Ticker {
    private val current = new AtomicReference[Long](0L)

    def advance(duration: Duration): Unit =
      current.updateAndGet(_ + duration.toNanos)

    override def nanos(): Long = current.get()
  }

  def spec = suite("CircuitBreaker")(
    suite("Closed state")(
      test("allows calls when closed") {
        val cb = CircuitBreaker()
        assertTrue(cb.tryAcquire == GateResult.Allowed) &&
        assertTrue(cb.isClosed)
      },
      test("recordSuccess resets failure counter") {
        val cb = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 5))
        cb.recordFailure()
        cb.recordFailure()
        cb.recordSuccess()
        // 2 more failures should not open (counter was reset)
        cb.recordFailure()
        cb.recordFailure()
        assertTrue(cb.isClosed)
      },
      test("recordSuccess returns false when staying closed") {
        val cb = CircuitBreaker()
        assertTrue(!cb.recordSuccess())
      }
    ),
    suite("Opening")(
      test("opens after failureThreshold consecutive failures") {
        val cb = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 3))
        cb.recordFailure()
        cb.recordFailure()
        val didOpen = cb.recordFailure()
        assertTrue(didOpen) &&
        assertTrue(cb.isOpen)
      },
      test("does not open on fewer failures than threshold") {
        val cb = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 5))
        (1 to 4).foreach(_ => cb.recordFailure())
        assertTrue(cb.isClosed)
      },
      test("recordFailure returns true only on the tripping failure") {
        val cb = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 3))
        assertTrue(!cb.recordFailure()) &&
        assertTrue(!cb.recordFailure()) &&
        assertTrue(cb.recordFailure()) // this one trips
      }
    ),
    suite("Open state")(
      test("rejects calls when open") {
        val ticker = new TestTicker()
        val cb     = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.minute), ticker)
        cb.recordFailure()
        assertTrue(cb.tryAcquire == GateResult.Rejected)
      },
      test("transitions to half-open after resetTimeout") {
        val ticker = new TestTicker()
        val cb     = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.second), ticker)
        cb.recordFailure()
        ticker.advance(2.seconds)
        assertTrue(cb.tryAcquire == GateResult.Allowed)
      }
    ),
    suite("Monotonic reset timing (#263.1)")(
      test("stays rejected before resetTimeout, allowed at/after it — elapsed uses the injected ticker") {
        val ticker = new TestTicker()
        val cb =
          CircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 30.seconds), ticker)
        cb.recordFailure() // opens
        ticker.advance(29.seconds)
        val beforeTimeout = cb.tryAcquire
        // Reaching exactly resetTimeout is enough (>= comparison).
        ticker.advance(1.second)
        val atTimeout = cb.tryAcquire
        assertTrue(beforeTimeout == GateResult.Rejected) &&
        assertTrue(atTimeout == GateResult.Allowed) &&
        assertTrue(cb.isHalfOpen)
      },
      test("elapsed is measured from the injected ticker, never a wall clock") {
        // A monotonic nanoTime never runs backward, so open-timing derives solely from ticker deltas:
        // small forward advances keep the circuit open until their sum reaches resetTimeout.
        val ticker = new TestTicker()
        val cb =
          CircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 10.seconds), ticker)
        cb.recordFailure()
        ticker.advance(3.seconds)
        val a = cb.tryAcquire
        ticker.advance(3.seconds)
        val b = cb.tryAcquire
        ticker.advance(5.seconds) // total 11s >= 10s
        val c = cb.tryAcquire
        assertTrue(a == GateResult.Rejected) &&
        assertTrue(b == GateResult.Rejected) &&
        assertTrue(c == GateResult.Allowed)
      }
    ),
    suite("Half-open state")(
      test("closes after halfOpenMaxCalls successes") {
        val ticker = new TestTicker()
        val cb = CircuitBreaker(
          CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.second, halfOpenMaxCalls = 2),
          ticker
        )
        cb.recordFailure()
        ticker.advance(2.seconds)
        cb.tryAcquire // transition to half-open, first probe
        cb.recordSuccess()
        cb.tryAcquire // second probe
        val didClose = cb.recordSuccess()
        assertTrue(didClose) &&
        assertTrue(cb.isClosed)
      },
      test("re-opens on failed probe") {
        val ticker = new TestTicker()
        val cb     = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.second), ticker)
        cb.recordFailure()
        ticker.advance(2.seconds)
        cb.tryAcquire // transition to half-open
        val didOpen = cb.recordFailure()
        assertTrue(didOpen) &&
        assertTrue(cb.isOpen)
      },
      test("only one probe at a time") {
        val ticker = new TestTicker()
        val cb     = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.second), ticker)
        cb.recordFailure()
        ticker.advance(2.seconds)
        val first  = cb.tryAcquire
        val second = cb.tryAcquire
        assertTrue(first == GateResult.Allowed) &&
        assertTrue(second == GateResult.Rejected)
      }
    ),
    suite("Probe-slot steal (#263.3)")(
      test("a wedged probe slot is stolen after probeTimeout") {
        val ticker = new TestTicker()
        val cb = CircuitBreaker(
          CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.second, probeTimeout = 5.seconds),
          ticker
        )
        cb.recordFailure()
        ticker.advance(2.seconds)
        // First caller wins the probe slot but never records an outcome (simulating a probe that died).
        val probe = cb.tryAcquire
        // Before probeTimeout, no other caller may probe.
        val blocked = cb.tryAcquire
        // After probeTimeout elapses, the slot is stolen by the next caller.
        ticker.advance(5.seconds)
        val stolen = cb.tryAcquire
        assertTrue(probe == GateResult.Allowed) &&
        assertTrue(blocked == GateResult.Rejected) &&
        assertTrue(stolen == GateResult.Allowed) &&
        assertTrue(cb.isHalfOpen)
      },
      test("stealing resets the probe window, so an immediately-following caller is rejected") {
        val ticker = new TestTicker()
        val cb = CircuitBreaker(
          CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.second, probeTimeout = 5.seconds),
          ticker
        )
        cb.recordFailure()
        ticker.advance(2.seconds)
        cb.tryAcquire // original probe acquires the slot
        ticker.advance(5.seconds)
        val stolen  = cb.tryAcquire // steals the slot, resets probeStartNanos
        val blocked = cb.tryAcquire // window just reset, so this one is rejected again
        assertTrue(stolen == GateResult.Allowed) &&
        assertTrue(blocked == GateResult.Rejected)
      }
    ),
    suite("External trip/reset")(
      test("trip forces circuit open") {
        val cb = CircuitBreaker()
        cb.trip()
        assertTrue(cb.isOpen) &&
        assertTrue(cb.tryAcquire == GateResult.Rejected)
      },
      test("reset closes externally-tripped circuit") {
        val cb = CircuitBreaker()
        cb.trip()
        cb.reset()
        assertTrue(cb.isClosed)
      },
      test("reset does not close failure-tripped circuit") {
        val ticker = new TestTicker()
        val cb     = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.minute), ticker)
        cb.recordFailure() // trip via failures
        cb.reset()         // should NOT close
        assertTrue(cb.isOpen)
      },
      test("transitionToHalfOpen allows a probe") {
        val cb = CircuitBreaker()
        cb.trip()
        cb.transitionToHalfOpen()
        assertTrue(cb.tryAcquire == GateResult.Allowed)
      }
    ),
    suite("recordReachable")(
      test("frees probe slot in half-open without closing the circuit") {
        val ticker = new TestTicker()
        val cb     = CircuitBreaker(CircuitBreakerConfig(halfOpenMaxCalls = 3), ticker)
        cb.trip()
        cb.transitionToHalfOpen()
        cb.tryAcquire // acquire probe slot
        cb.recordReachable()
        // Probe slot freed: next caller can probe; circuit still half-open.
        assertTrue(cb.tryAcquire == GateResult.Allowed) &&
        assertTrue(cb.isHalfOpen)
      },
      test("repeated reachability does not close the circuit") {
        val ticker = new TestTicker()
        val cb     = CircuitBreaker(CircuitBreakerConfig(halfOpenMaxCalls = 2), ticker)
        cb.trip()
        cb.transitionToHalfOpen()
        (1 to 10).foreach { _ =>
          cb.tryAcquire
          cb.recordReachable()
        }
        assertTrue(cb.isHalfOpen)
      },
      test("resets consecutive failures in closed state") {
        val cb = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 5))
        cb.recordFailure()
        cb.recordFailure()
        cb.recordReachable()
        assertTrue(cb.stateRef.get().consecutiveFailures == 0)
      },
      test("no-op in open state") {
        val ticker = new TestTicker()
        val cb     = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.minute), ticker)
        cb.recordFailure() // trip
        cb.recordReachable()
        assertTrue(cb.isOpen)
      }
    ),
    suite("Probe outcome after acquisition (#263.3b)")(
      test("recordFailure on an acquired probe re-opens the circuit") {
        // Mirrors the provider's VirtualMachineError catch, which calls breaker.recordFailure() before re-throwing:
        // a probe that dies after winning the CAS must re-open the circuit rather than wedge it half-open.
        val ticker = new TestTicker()
        val cb     = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.second), ticker)
        cb.recordFailure()
        ticker.advance(2.seconds)
        val probe = cb.tryAcquire // enters half-open, acquires the probe slot
        cb.recordFailure() // probe dies -> re-open
        assertTrue(probe == GateResult.Allowed) &&
        assertTrue(cb.isOpen) &&
        assertTrue(cb.tryAcquire == GateResult.Rejected)
      }
    )
  )
}
