package zio.openfeature.extras

import zio._
import zio.openfeature.FeatureFlagError

/** Bounds checks for the wrapper configs (#451). Each violation names the config class, the field, the rule and the
  * offending value, and every violation of a config is reported together.
  *
  * The checks live here rather than on the config companions: an explicit companion of a case class drops the
  * `AbstractFunctionN` parent Scala 2.13 gives the synthetic one, which breaks binary compatibility.
  */
private[extras] object ConfigValidation {

  type Validated[A] = Either[FeatureFlagError.InvalidConfiguration, A]

  /** `failureThreshold >= 1`, `halfOpenMaxCalls >= 1`, `resetTimeout > 0`, `probeTimeout > 0`. */
  def circuitBreaker(config: CircuitBreakerConfig): Validated[CircuitBreakerConfig] =
    result(
      config,
      breaker("CircuitBreakerConfig", config.failureThreshold, config.resetTimeout, config.halfOpenMaxCalls) ++
        positive("CircuitBreakerConfig", "probeTimeout", config.probeTimeout)
    )

  /** The breaker bounds, plus `evaluationTimeout > 0` and `stateCheckInterval >= 0`. */
  def circuitBreakerProvider(config: CircuitBreakerProviderConfig): Validated[CircuitBreakerProviderConfig] = {
    val owner = "CircuitBreakerProviderConfig"
    result(
      config,
      breaker(owner, config.failureThreshold, config.resetTimeout, config.halfOpenMaxCalls) ++
        positive(owner, "evaluationTimeout", config.evaluationTimeout) ++
        nonNegative(owner, "stateCheckInterval", config.stateCheckInterval)
    )
  }

  /** `maxEntries >= 1`, `ttl > 0`. `contextKeys` is not checked: `Some(Set.empty)` deliberately ignores the context. */
  def caching(config: CachingConfig): Validated[CachingConfig] =
    result(
      config,
      atLeastOne("CachingConfig", "maxEntries", config.maxEntries) ++
        positive("CachingConfig", "ttl", config.ttl)
    )

  /** For the unsafe `apply` constructors, which follow the `require` convention. */
  def orThrow[A](validated: Validated[A]): A =
    validated match {
      case Right(a) => a
      case Left(e)  => throw new IllegalArgumentException(e.reason)
    }

  // The fields both breaker configs share, checked under the caller's class name so the message names the config the
  // user actually wrote.
  private def breaker(owner: String, failureThreshold: Int, resetTimeout: Duration, halfOpenMaxCalls: Int) =
    atLeastOne(owner, "failureThreshold", failureThreshold) ++
      positive(owner, "resetTimeout", resetTimeout) ++
      atLeastOne(owner, "halfOpenMaxCalls", halfOpenMaxCalls)

  private def atLeastOne(owner: String, field: String, value: Int): List[String] =
    if (value >= 1) Nil else List(s"$owner.$field must be >= 1, got $value")

  // `Duration.Infinity` is positive, so it passes; it is a meaningful setting for every duration checked here.
  private def positive(owner: String, field: String, value: Duration): List[String] =
    if (value.compareTo(Duration.Zero) > 0) Nil else List(s"$owner.$field must be > 0, got $value")

  private def nonNegative(owner: String, field: String, value: Duration): List[String] =
    if (value.compareTo(Duration.Zero) >= 0) Nil else List(s"$owner.$field must be >= 0, got $value")

  private def result[A](config: A, violations: List[String]): Validated[A] =
    if (violations.isEmpty) Right(config) else Left(FeatureFlagError.InvalidConfiguration(violations.mkString("; ")))
}
