package zio.openfeature.extras

import zio._
import zio.test._
import zio.openfeature.testkit.{DifferentialLaws, TestFeatureProvider}

// The two wrappers answer FLAG_NOT_FOUND, as their delegate does, for every adversarial key it does not hold.
object WrapperAbsentKeysSpec extends ZIOSpecDefault {

  def spec = suite("WrapperAbsentKeysSpec")(
    DifferentialLaws.absentKeysAreNotFound("caching")(fs => TestFeatureProvider.make(fs.flags).map(CachingProvider(_))),
    DifferentialLaws.absentKeysAreNotFound("circuit-breaker")(fs =>
      TestFeatureProvider.make(fs.flags).map(CircuitBreakerProvider(_))
    )
  )
}
