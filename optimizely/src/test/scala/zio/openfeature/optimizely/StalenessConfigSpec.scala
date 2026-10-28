package zio.openfeature.optimizely

import zio.test._

// The staleness watchdog derivation, now from named constants — values unchanged.
object StalenessConfigSpec extends ZIOSpecDefault {

  private def derive(poll: java.time.Duration, staleAfter: Option[java.time.Duration] = None) =
    OptimizelyProvider.stalenessConfig(
      OptimizelyProviderConfig(sdkKey = "staleness-key", pollingInterval = Some(poll), staleAfter = staleAfter)
    )

  def spec = suite("StalenessConfigSpec")(
    test("without staleAfter: stale after 3 polling intervals, checked every polling interval") {
      val c = derive(java.time.Duration.ofSeconds(10))
      assertTrue(
        c.map(_.staleAfter).contains(java.time.Duration.ofSeconds(30)),
        c.map(_.checkInterval).contains(java.time.Duration.ofSeconds(10))
      )
    },
    test("a short staleAfter is checked every half of it") {
      val c = derive(java.time.Duration.ofSeconds(10), Some(java.time.Duration.ofSeconds(4)))
      assertTrue(c.map(_.checkInterval).contains(java.time.Duration.ofSeconds(2)))
    },
    test("the check interval is floored at 1 ms") {
      val c = derive(java.time.Duration.ofNanos(1000))
      assertTrue(c.map(_.checkInterval).contains(java.time.Duration.ofMillis(1)))
    },
    test("polling off: no watchdog") {
      assertTrue(OptimizelyProvider.stalenessConfig(OptimizelyProviderConfig(sdkKey = "staleness-key")).isEmpty)
    }
  )
}
