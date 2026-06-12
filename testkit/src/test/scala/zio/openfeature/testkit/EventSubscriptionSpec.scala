package zio.openfeature.testkit

import zio._
import zio.test._
import zio.openfeature._

/** Pins the registration guarantee fixed in #177: the hub subscription is established before the `on*` registration
  * effect returns, so an event published immediately afterwards is always delivered (previously the forked stream could
  * subscribe after the event, silently losing it).
  */
object EventSubscriptionSpec extends ZIOSpecDefault {

  private def testLayer: ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    Scope.default >>> TestFeatureProvider.layer(Map.empty)

  private def awaitCount(ref: Ref[Int], atLeast: Int): UIO[Int] =
    ref.get.repeatUntil(_ >= atLeast)

  def spec = suite("Event handler subscription guarantee (#177)")(
    test("onConfigurationChanged delivers an event emitted right after registration") {
      for {
        ref          <- Ref.make(0)
        testProvider <- ZIO.service[TestFeatureProvider]
        _            <- FeatureFlags.onConfigurationChanged((_, _) => ref.update(_ + 1))
        _            <- testProvider.emitEvent(ProviderEvent.ConfigurationChanged(Set("f"), testProvider.metadata))
        n            <- awaitCount(ref, 1).timeoutFail(new RuntimeException("event never delivered"))(10.seconds)
      } yield assertTrue(n >= 1)
    },
    test("on(Stale) delivers an event emitted right after registration") {
      for {
        ref          <- Ref.make(0)
        testProvider <- ZIO.service[TestFeatureProvider]
        _            <- FeatureFlags.on(ProviderEventType.ConfigurationChanged, _ => ref.update(_ + 1))
        _            <- testProvider.emitEvent(ProviderEvent.ConfigurationChanged(Set("g"), testProvider.metadata))
        n            <- awaitCount(ref, 1).timeoutFail(new RuntimeException("event never delivered"))(10.seconds)
      } yield assertTrue(n >= 1)
    },
    test("repeated register-then-emit cycles never lose the event") {
      // Exercises the former race window repeatedly; with the subscription handshake every cycle must deliver.
      ZIO
        .foreach(1 to 20) { i =>
          for {
            ref          <- Ref.make(0)
            testProvider <- ZIO.service[TestFeatureProvider]
            cancel       <- FeatureFlags.onConfigurationChanged((_, _) => ref.update(_ + 1))
            _ <- testProvider.emitEvent(ProviderEvent.ConfigurationChanged(Set(s"flag-$i"), testProvider.metadata))
            n <- awaitCount(ref, 1).timeoutFail(new RuntimeException(s"cycle $i lost the event"))(10.seconds)
            _ <- cancel
          } yield n
        }
        .map(counts => assertTrue(counts.forall(_ >= 1)))
    },
    test("cancellation stops delivery") {
      for {
        ref          <- Ref.make(0)
        testProvider <- ZIO.service[TestFeatureProvider]
        cancel       <- FeatureFlags.onConfigurationChanged((_, _) => ref.update(_ + 1))
        _            <- testProvider.emitEvent(ProviderEvent.ConfigurationChanged(Set("a"), testProvider.metadata))
        _            <- awaitCount(ref, 1).timeoutFail(new RuntimeException("first event never delivered"))(10.seconds)
        _            <- cancel
        _            <- testProvider.emitEvent(ProviderEvent.ConfigurationChanged(Set("b"), testProvider.metadata))
        _            <- ZIO.sleep(200.millis)
        n            <- ref.get
      } yield assertTrue(n == 1)
    }
  ).provide(testLayer) @@ TestAspect.withLiveClock @@ TestAspect.sequential
}
