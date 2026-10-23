package zio.openfeature.extras

import zio._
import zio.test._

// #462: the tuning defaults now come from named constants; their values must not have moved, and the two circuit
// breaker configs must keep agreeing.
object TuningDefaultsSpec extends ZIOSpecDefault {

  def spec = suite("TuningDefaultsSpec (#462)")(
    test("CircuitBreakerConfig defaults are unchanged") {
      assertTrue(CircuitBreakerConfig() == CircuitBreakerConfig(5, 30.seconds, 1, 1.second))
    },
    test("CircuitBreakerProviderConfig defaults are unchanged, and its probe timeout is the evaluation timeout + 1s") {
      val c = CircuitBreakerProviderConfig()
      assertTrue(
        c.evaluationTimeout == 500.millis,
        c.stateCheckInterval == 1.second,
        c.toCircuitBreakerConfig.probeTimeout == 1500.millis
      )
    },
    test("the two circuit breaker configs share their common defaults") {
      val breaker  = CircuitBreakerConfig()
      val provider = CircuitBreakerProviderConfig()
      assertTrue(
        provider.failureThreshold == breaker.failureThreshold,
        provider.resetTimeout == breaker.resetTimeout,
        provider.halfOpenMaxCalls == breaker.halfOpenMaxCalls
      )
    },
    test("CachingConfig defaults are unchanged") {
      val c = CachingConfig()
      assertTrue(c.maxEntries == 1000, c.ttl == 5.minutes)
    }
  )
}
