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

  def spec = suite("asyncReadyLayer")(
    test("starts in NotReady then auto-transitions to Ready") {
      for {
        before <- FeatureFlags.providerStatus
        _      <- ZIO.sleep(200.millis)
        after  <- FeatureFlags.providerStatus
      } yield assertTrue(before == ProviderStatus.NotReady) && assertTrue(after == ProviderStatus.Ready)
    }.provide(readyLayer(Map.empty, 50.millis)),
    test("evaluations work after auto-transition") {
      for {
        _     <- ZIO.sleep(200.millis)
        value <- FeatureFlags.boolean("flag", default = false)
      } yield assertTrue(value == true)
    }.provide(readyLayer(Map("flag" -> true), 50.millis)),
    test("evaluations fail before init delay elapses") {
      for {
        result <- FeatureFlags.boolean("flag", default = false).either
      } yield assertTrue(result.isLeft)
    }.provide(readyLayer(Map("flag" -> true), 10.seconds))
  ) @@ TestAspect.withLiveClock
}
