package zio.openfeature.extras

import zio._
import zio.test._
import java.util.concurrent.atomic.AtomicReference

object CircuitBreakerSpec extends ZIOSpecDefault {

  private class TestClock extends java.time.Clock {
    private val currentMillis = new AtomicReference[Long](java.lang.System.currentTimeMillis())

    def advance(duration: Duration): Unit =
      currentMillis.updateAndGet(_ + duration.toMillis)

    override def millis(): Long                                    = currentMillis.get()
    override def getZone: java.time.ZoneId                         = java.time.ZoneId.of("UTC")
    override def withZone(zone: java.time.ZoneId): java.time.Clock = this
    override def instant(): java.time.Instant                      = java.time.Instant.ofEpochMilli(millis())
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
        val clock = new TestClock()
        val cb    = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.minute), clock)
        cb.recordFailure()
        assertTrue(cb.tryAcquire == GateResult.Rejected)
      },
      test("transitions to half-open after resetTimeout") {
        val clock = new TestClock()
        val cb    = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.second), clock)
        cb.recordFailure()
        clock.advance(2.seconds)
        assertTrue(cb.tryAcquire == GateResult.Allowed)
      }
    ),
    suite("Half-open state")(
      test("closes after halfOpenMaxCalls successes") {
        val clock = new TestClock()
        val cb = CircuitBreaker(
          CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.second, halfOpenMaxCalls = 2),
          clock
        )
        cb.recordFailure()
        clock.advance(2.seconds)
        cb.tryAcquire // transition to half-open, first probe
        cb.recordSuccess()
        cb.tryAcquire // second probe
        val didClose = cb.recordSuccess()
        assertTrue(didClose) &&
        assertTrue(cb.isClosed)
      },
      test("re-opens on failed probe") {
        val clock = new TestClock()
        val cb    = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.second), clock)
        cb.recordFailure()
        clock.advance(2.seconds)
        cb.tryAcquire // transition to half-open
        val didOpen = cb.recordFailure()
        assertTrue(didOpen) &&
        assertTrue(cb.isOpen)
      },
      test("only one probe at a time") {
        val clock = new TestClock()
        val cb    = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.second), clock)
        cb.recordFailure()
        clock.advance(2.seconds)
        val first  = cb.tryAcquire
        val second = cb.tryAcquire
        assertTrue(first == GateResult.Allowed) &&
        assertTrue(second == GateResult.Rejected)
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
        val clock = new TestClock()
        val cb    = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.minute), clock)
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
        val clock = new TestClock()
        val cb    = CircuitBreaker(CircuitBreakerConfig(halfOpenMaxCalls = 3), clock)
        cb.trip()
        cb.transitionToHalfOpen()
        cb.tryAcquire // acquire probe slot
        cb.recordReachable()
        // Probe slot freed: next caller can probe; circuit still half-open.
        assertTrue(cb.tryAcquire == GateResult.Allowed) &&
        assertTrue(cb.isHalfOpen)
      },
      test("repeated reachability does not close the circuit") {
        val clock = new TestClock()
        val cb    = CircuitBreaker(CircuitBreakerConfig(halfOpenMaxCalls = 2), clock)
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
        val clock = new TestClock()
        val cb    = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, resetTimeout = 1.minute), clock)
        cb.recordFailure() // trip
        cb.recordReachable()
        assertTrue(cb.isOpen)
      }
    )
  )
}
