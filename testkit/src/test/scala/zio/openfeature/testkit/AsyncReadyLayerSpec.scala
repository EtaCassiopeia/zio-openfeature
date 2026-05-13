package zio.openfeature.testkit

import zio._
import zio.test._
import zio.openfeature._

object AsyncReadyLayerSpec extends ZIOSpecDefault {

  private def readyLayer(
    flags: Map[String, Any],
    delay: Duration
  ): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    Scope.default >>> TestFeatureProvider.asyncReadyLayer(flags, delay)

  // Bound the polling loop so a real bug surfaces as a test failure instead of a hang.
  private val waitForReady: ZIO[FeatureFlags, Nothing, ProviderStatus] =
    FeatureFlags.providerStatus
      .repeatUntil(_ == ProviderStatus.Ready)
      .timeoutTo(ProviderStatus.NotReady)(identity)(5.seconds)
      .withClock(Clock.ClockLive)

  def spec = suite("asyncReadyLayer")(
    // Under TestClock the forked transition fiber inside asyncReadyLayer suspends on its `ZIO.sleep`
    // until we explicitly adjust the clock. This eliminates the wall-clock race that made the
    // previous `withLiveClock` version flaky on slow CI runners.
    test("starts in NotReady then auto-transitions to Ready") {
      for {
        before <- FeatureFlags.providerStatus
        _      <- TestClock.adjust(60.millis)
        after  <- waitForReady
      } yield assertTrue(before == ProviderStatus.NotReady) && assertTrue(after == ProviderStatus.Ready)
    }.provide(readyLayer(Map.empty, 50.millis)),
    test("evaluations work after auto-transition") {
      for {
        _     <- TestClock.adjust(60.millis)
        _     <- waitForReady
        value <- FeatureFlags.boolean("flag", default = false)
      } yield assertTrue(value == true)
    }.provide(readyLayer(Map("flag" -> true), 50.millis)),
    test("evaluations fail before init delay elapses") {
      // No clock adjustment: the forked transition fiber stays suspended, status remains NotReady.
      for {
        result <- FeatureFlags.boolean("flag", default = false).either
      } yield assertTrue(result.isLeft)
    }.provide(readyLayer(Map("flag" -> true), 10.seconds))
  )
}
