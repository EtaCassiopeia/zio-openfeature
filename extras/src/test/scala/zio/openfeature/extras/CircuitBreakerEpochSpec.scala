package zio.openfeature.extras

import zio._
import zio.test._
import java.util.concurrent.atomic.AtomicReference

// An outcome only counts against the circuit state it was admitted under. A call admitted earlier (in
// Closed, or a probe whose slot was since stolen) must not close, re-open, or free the probe slot of a later state.
object CircuitBreakerEpochSpec extends ZIOSpecDefault {

  private class ManualTicker extends Ticker {
    private val now                = new AtomicReference[Long](0L)
    def advance(d: Duration): Unit = { now.updateAndGet(_ + d.toNanos); () }
    override def nanos(): Long     = now.get()
  }

  private def breaker(
    failureThreshold: Int = 1,
    probeTimeout: Duration = 1.hour
  ): (CircuitBreaker, ManualTicker) = {
    val ticker = new ManualTicker
    val config = CircuitBreakerConfig(
      failureThreshold = failureThreshold,
      resetTimeout = 1.second,
      halfOpenMaxCalls = 1,
      probeTimeout = probeTimeout
    )
    (CircuitBreaker(config, ticker), ticker)
  }

  /** A permit admitted in Closed, then the circuit opened and went half-open with a fresh probe in flight. */
  private def staleClosedPermitWithProbeInFlight(): (CircuitBreaker, CircuitBreaker.Permit, CircuitBreaker.Permit) = {
    val (cb, ticker) = breaker()
    val stale        = cb.acquire().get
    cb.recordFailure(cb.acquire().get) // threshold 1: opens
    ticker.advance(2.seconds)
    val probe = cb.acquire().get
    (cb, stale, probe)
  }

  private def probeInFlight(cb: CircuitBreaker): Boolean = cb.currentState match {
    case CircuitState.HalfOpen(0, true, _) => true
    case _                                 => false
  }

  def spec = suite("CircuitBreakerEpochSpec")(
    test("a stale success from a call admitted in Closed does not close a half-open circuit") {
      val (cb, stale, _) = staleClosedPermitWithProbeInFlight()
      val closed         = cb.recordSuccess(stale)
      assertTrue(!closed, probeInFlight(cb))
    },
    test("a stale reachable outcome does not free the probe slot") {
      val (cb, stale, _) = staleClosedPermitWithProbeInFlight()
      cb.recordReachable(stale)
      assertTrue(probeInFlight(cb), cb.acquire().isEmpty)
    },
    test("a stale failure does not re-open a half-open circuit") {
      val (cb, stale, _) = staleClosedPermitWithProbeInFlight()
      val opened         = cb.recordFailure(stale)
      assertTrue(!opened, probeInFlight(cb))
    },
    test("the probe's own success still closes the circuit") {
      val (cb, _, probe) = staleClosedPermitWithProbeInFlight()
      val closed         = cb.recordSuccess(probe)
      assertTrue(closed, cb.isClosed)
    },
    test("after a probe steal, the original probe's late outcome is ignored") {
      val (cb, ticker) = breaker(probeTimeout = 1.second)
      cb.recordFailure(cb.acquire().get)
      ticker.advance(2.seconds)
      val first = cb.acquire().get
      ticker.advance(2.seconds) // first probe wedged past probeTimeout: the slot is stolen
      val second      = cb.acquire().get
      val firstClosed = cb.recordSuccess(first)
      val stillProbe  = probeInFlight(cb)
      val secondDone  = cb.recordSuccess(second)
      assertTrue(!firstClosed, stillProbe, secondDone, cb.isClosed)
    },
    test("concurrent permits admitted in Closed all count toward the threshold") {
      val (cb, _) = breaker(failureThreshold = 3)
      val a       = cb.acquire().get
      val b       = cb.acquire().get
      val c       = cb.acquire().get
      cb.recordFailure(a)
      cb.recordFailure(b)
      val opened = cb.recordFailure(c)
      assertTrue(opened, cb.isOpen)
    },
    test("the permit-less API keeps its behaviour") {
      val (cb, ticker) = breaker()
      cb.recordFailure()
      ticker.advance(2.seconds)
      val allowed = cb.tryAcquire == GateResult.Allowed
      val closed  = cb.recordSuccess()
      assertTrue(allowed, closed, cb.isClosed)
    }
  )
}
