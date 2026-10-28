package snippets

import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.TestFeatureProvider

/** The code on the Tracking page (docs/tracking.md). */
object Tracking:

  // --8<-- [start:tracking]
  val purchase: ZIO[FeatureFlags, FeatureFlagError, Unit] =
    val user    = EvaluationContext("user-42")
    val details = TrackingEventDetails(99.99).withAttribute("currency", "EUR")
    FeatureFlags.track("checkout-completed") *>     // event name only
      FeatureFlags.track("purchase", user, details) // with targeting context and details
  // --8<-- [end:tracking]

  // --8<-- [start:assert]
  // In a test: what the provider received, oldest first, with the context the SDK merged
  val received: ZIO[TestFeatureProvider, Nothing, List[String]] =
    ZIO.serviceWithZIO[TestFeatureProvider](_.trackedEvents).map(_.map { case (name, _, _) => name })
  // --8<-- [end:assert]
