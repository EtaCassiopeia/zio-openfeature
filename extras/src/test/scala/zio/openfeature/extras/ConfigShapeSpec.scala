package zio.openfeature.extras

import zio._
import zio.test._

/** The wrapper configs are final classes built from `default` with `with*` methods. Their bounds are checked when a
  * provider is built from them (see `WrapperConfigValidationSpec`), not when the config is.
  */
object ConfigShapeSpec extends ZIOSpecDefault {

  def spec = suite("ConfigShapeSpec")(
    test("CachingConfig.default carries the documented defaults") {
      val c = CachingConfig.default
      assertTrue(
        c.maxEntries == CachingProvider.DefaultMaxEntries,
        c.ttl == CachingProvider.DefaultTtl,
        c.contextKeys.isEmpty,
        c.keyByTargetingKey
      )
    },
    test("CachingConfig with* returns a copy and leaves the receiver as it was") {
      val changed = CachingConfig.default.withMaxEntries(5).withTtl(2.minutes).withContextKeys(Set("plan"))
      assertTrue(
        changed.maxEntries == 5,
        changed.ttl == 2.minutes,
        changed.contextKeys.contains(Set("plan")),
        CachingConfig.default.maxEntries == CachingProvider.DefaultMaxEntries,
        CachingConfig.default.contextKeys.isEmpty
      )
    },
    test("withoutTargetingKey returns a copy, and the other setters keep it") {
      val shared = CachingConfig.default.withMaxEntries(5).withoutTargetingKey
      val later  = shared.withTtl(2.minutes).withContextKeys(Set("plan")).withMaxEntries(6)
      assertTrue(
        !shared.keyByTargetingKey,
        shared.maxEntries == 5,
        !later.keyByTargetingKey,
        CachingConfig.default.keyByTargetingKey
      )
    },
    test("CachingConfig.toString names every field") {
      assertTrue(
        CachingConfig.default.withContextKeys(Set("plan")).toString ==
          s"CachingConfig(maxEntries=${CachingProvider.DefaultMaxEntries}, ttl=${CachingProvider.DefaultTtl}, " +
          "contextKeys=Some(Set(plan)), keyByTargetingKey=true)"
      )
    },
    test("CircuitBreakerConfig.default carries the documented defaults") {
      val c = CircuitBreakerConfig.default
      assertTrue(
        c.failureThreshold == CircuitBreaker.DefaultFailureThreshold,
        c.resetTimeout == CircuitBreaker.DefaultResetTimeout,
        c.halfOpenMaxCalls == CircuitBreaker.DefaultHalfOpenMaxCalls,
        c.probeTimeout == CircuitBreaker.DefaultProbeTimeout
      )
    },
    test("CircuitBreakerConfig with* returns a copy and leaves the receiver as it was") {
      val changed = CircuitBreakerConfig.default
        .withFailureThreshold(9)
        .withResetTimeout(1.second)
        .withHalfOpenMaxCalls(4)
        .withProbeTimeout(2.seconds)
      assertTrue(
        changed.failureThreshold == 9,
        changed.resetTimeout == 1.second,
        changed.halfOpenMaxCalls == 4,
        changed.probeTimeout == 2.seconds,
        CircuitBreakerConfig.default.failureThreshold == CircuitBreaker.DefaultFailureThreshold
      )
    },
    test("CircuitBreakerConfig.toString names every field") {
      val text = CircuitBreakerConfig.default.withFailureThreshold(9).toString
      assertTrue(
        text.startsWith("CircuitBreakerConfig(failureThreshold=9, resetTimeout="),
        text.contains("halfOpenMaxCalls="),
        text.contains("probeTimeout=")
      )
    },
    test("CircuitBreakerProviderConfig.default carries the documented defaults") {
      val c = CircuitBreakerProviderConfig.default
      assertTrue(
        c.failureThreshold == CircuitBreaker.DefaultFailureThreshold,
        c.resetTimeout == CircuitBreaker.DefaultResetTimeout,
        c.evaluationTimeout == CircuitBreakerProvider.DefaultEvaluationTimeout,
        c.halfOpenMaxCalls == CircuitBreaker.DefaultHalfOpenMaxCalls,
        c.stalePolicy == StalePolicy.Open,
        c.stateCheckInterval == CircuitBreakerProvider.DefaultStateCheckInterval
      )
    },
    test("CircuitBreakerProviderConfig with* returns a copy and leaves the receiver as it was") {
      val changed = CircuitBreakerProviderConfig.default
        .withFailureThreshold(9)
        .withResetTimeout(1.second)
        .withEvaluationTimeout(300.millis)
        .withHalfOpenMaxCalls(4)
        .withStalePolicy(StalePolicy.Ignore)
        .withStateCheckInterval(Duration.Zero)
      assertTrue(
        changed.failureThreshold == 9,
        changed.resetTimeout == 1.second,
        changed.evaluationTimeout == 300.millis,
        changed.halfOpenMaxCalls == 4,
        changed.stalePolicy == StalePolicy.Ignore,
        changed.stateCheckInterval == Duration.Zero,
        CircuitBreakerProviderConfig.default.stalePolicy == StalePolicy.Open
      )
    },
    test("CircuitBreakerProviderConfig.toString names every field") {
      val text = CircuitBreakerProviderConfig.default.withStalePolicy(StalePolicy.Ignore).toString
      assertTrue(
        text.startsWith("CircuitBreakerProviderConfig(failureThreshold="),
        text.contains("evaluationTimeout="),
        text.contains("stalePolicy=Ignore"),
        text.contains("stateCheckInterval=")
      )
    }
  )
}
