package zio.openfeature.testkit

import zio._
import zio.test._
import zio.test.Assertion._
import zio.openfeature._

object SpecComplianceSpec extends ZIOSpecDefault {

  private def testLayer(
    flags: Map[String, Any] = Map.empty
  ): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    Scope.default >>> TestFeatureProvider.layer(flags)

  def spec = suite("SpecComplianceSpec")(
    suite("Event Propagation")(
      test("onConfigurationChanged receives changed flag keys (spec 5.2.5)") {
        for {
          tp       <- ZIO.service[TestFeatureProvider]
          received <- Ref.make(Option.empty[Set[String]])
          _        <- FeatureFlags.onConfigurationChanged((flags, _) => received.set(Some(flags)))
          _        <- ZIO.sleep(200.millis)
          _        <- tp.emitEvent(ProviderEvent.ConfigurationChanged(Set("flag-a", "flag-b"), tp.metadata))
          result   <- received.get.repeatUntil(_.isDefined).timeout(5.seconds)
        } yield assertTrue(result.flatten.contains(Set("flag-a", "flag-b")))
      }.provide(testLayer()),
      test("events stream receives published events (spec 5.1.1)") {
        for {
          tp    <- ZIO.service[TestFeatureProvider]
          queue <- Queue.unbounded[ProviderEvent]
          fiber <- FeatureFlags.events.foreach(e => queue.offer(e)).fork
          _     <- ZIO.sleep(200.millis)
          _     <- tp.emitEvent(ProviderEvent.ConfigurationChanged(Set("x"), tp.metadata))
          event <- queue.take.timeout(5.seconds)
          _     <- fiber.interrupt
        } yield assertTrue(event.exists {
          case ProviderEvent.ConfigurationChanged(flags, _) => flags == Set("x")
          case _                                            => false
        })
      }.provide(testLayer()),
      test("error in one handler doesn't prevent others (spec 5.2.6)") {
        for {
          tp       <- ZIO.service[TestFeatureProvider]
          received <- Ref.make(false)
          // First handler dies
          _ <- FeatureFlags.onConfigurationChanged((_, _) => ZIO.die(new RuntimeException("boom")))
          // Second handler records that it fired
          _       <- FeatureFlags.onConfigurationChanged((_, _) => received.set(true))
          _       <- ZIO.sleep(200.millis)
          _       <- tp.emitEvent(ProviderEvent.ConfigurationChanged(Set("flag-1"), tp.metadata))
          didFire <- received.get.repeatUntil(identity).timeout(5.seconds)
        } yield assertTrue(didFire.contains(true))
      }.provide(testLayer())
    )
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds) @@ TestAspect.flaky(3)
}
