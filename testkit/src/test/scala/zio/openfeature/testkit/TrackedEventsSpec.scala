package zio.openfeature.testkit

import zio._
import zio.test._
import zio.openfeature._

// #442: the testkit's own record of what `track` delivered to the provider.
object TrackedEventsSpec extends ZIOSpecDefault {

  def spec = suite("TestFeatureProvider.trackedEvents (#442)")(
    test("records each track call with the merged context and the details") {
      for {
        _ <- FeatureFlags.setGlobalContext(EvaluationContext.empty.withAttribute("region", "eu"))
        _ <- FeatureFlags.track("checkout")
        _ <- FeatureFlags.track(
          "purchase",
          EvaluationContext("user-42"),
          TrackingEventDetails(99.5).withAttribute("currency", "EUR")
        )
        events <- ZIO.serviceWithZIO[TestFeatureProvider](_.trackedEvents)
      } yield assertTrue(
        events.map(_._1) == List("checkout", "purchase"),
        events(1)._2.targetingKey.contains("user-42"),
        events(1)._2.attributes.get("region").contains(AttributeValue.StringValue("eu")),
        events(1)._3.flatMap(_.value).contains(99.5),
        events(1)._3.exists(_.attributes.get("currency").contains("EUR"))
      )
    },
    test("clearTrackedEvents forgets them") {
      for {
        provider <- ZIO.service[TestFeatureProvider]
        _        <- FeatureFlags.track("a")
        _        <- provider.clearTrackedEvents
        events   <- provider.trackedEvents
      } yield assertTrue(events.isEmpty)
    },
    test("keeps only the last 1000 events") {
      for {
        provider <- ZIO.service[TestFeatureProvider]
        _        <- ZIO.foreachDiscard(1 to 1005)(i => FeatureFlags.track(s"e$i"))
        events   <- provider.trackedEvents
      } yield assertTrue(events.size == 1000, events.head._1 == "e6", events.last._1 == "e1005")
    }
  ).provideLayer(TestFeatureProvider.layer) @@ TestAspect.sequential
}
