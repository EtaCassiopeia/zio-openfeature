package snippets

import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*
import zio.test.*

object TrackingSpec extends ZIOSpecDefault:

  def spec = suite("Tracking")(
    test("both events are recorded with their context and details") {
      for
        _      <- Tracking.purchase
        events <- ZIO.serviceWithZIO[TestFeatureProvider](_.trackedEvents)
        names  <- Tracking.received
      yield assertTrue(
        names == List("checkout-completed", "purchase"),
        events(1)._2.targetingKey.contains("user-42"),
        events(1)._3.flatMap(_.value).contains(99.99)
      )
    },
    test("an unrepresentable attribute fails with InvalidContext before anything is sent") {
      for
        res   <- FeatureFlags.track("bad", TrackingEventDetails().withAttribute("when", new Object)).either
        names <- Tracking.received
      yield assertTrue(res.left.exists(_.isInstanceOf[FeatureFlagError.InvalidContext]), names.isEmpty)
    }
  ).provide(TestFeatureProvider.layer)
