package snippets

import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*
import zio.test.*

object LifecycleSpec extends ZIOSpecDefault:

  private val flag = FeatureFlags.boolean("new-checkout", default = false)

  private def evaluateIn(status: ProviderStatus) =
    ZIO.serviceWithZIO[TestFeatureProvider](_.setStatus(status)) *> flag.either

  private val layer = TestFeatureProvider.scopedLayer(Map("new-checkout" -> true))

  def spec = suite("Lifecycle")(
    test("readiness is true once the provider is Ready") {
      assertZIO(Lifecycle.readiness)(Assertion.isTrue)
    },
    test("Ready, Stale and Error evaluate; NotReady and Fatal fail fast") {
      for
        ready    <- evaluateIn(ProviderStatus.Ready)
        stale    <- evaluateIn(ProviderStatus.Stale)
        error    <- evaluateIn(ProviderStatus.Error)
        notReady <- evaluateIn(ProviderStatus.NotReady)
        fatal    <- evaluateIn(ProviderStatus.Fatal)
      yield assertTrue(
        ready == Right(true),
        stale == Right(true),
        error == Right(true),
        notReady == Left(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady)),
        fatal == Left(FeatureFlagError.ProviderFatal)
      )
    },
    test("not-ready: the snippet serves the default while the provider starts") {
      for
        _      <- ZIO.serviceWithZIO[TestFeatureProvider](_.setStatus(ProviderStatus.NotReady))
        result <- Lifecycle.checkout
      yield assertTrue(!result)
    },
    test("a Stale event moves Ready to Stale and reaches onProviderStale") {
      for
        seen     <- Promise.make[Nothing, String]
        cancel   <- FeatureFlags.onProviderStale((reason, _) => seen.succeed(reason).unit)
        provider <- ZIO.service[TestFeatureProvider]
        _        <- provider.emitEvent(ProviderEvent.Stale("datafile is old", ProviderMetadata("test")))
        reason   <- seen.await
        _        <- cancel
        status   <- FeatureFlags.providerStatus
      yield assertTrue(reason == "datafile is old", status == ProviderStatus.Stale)
    },
    test("handlers: all five register and unsubscribe") {
      Lifecycle.watchProvider.flatten.as(assertCompletes)
    },
    test("events: a configuration change reaches the stream") {
      for
        received <- Promise.make[Nothing, Set[String]]
        _ <- FeatureFlags.events
          .collect { case ProviderEvent.ConfigurationChanged(keys, _, _) => keys }
          .foreach(received.succeed(_))
          .forkScoped
        provider <- ZIO.service[TestFeatureProvider]
        // Re-emit until the forked subscriber has seen one: it may not be subscribed yet on the first
        _ <- (provider.emitEvent(ProviderEvent.ConfigurationChanged(Set("banner"), ProviderMetadata("test"))) *>
          ZIO.sleep(20.millis)).repeatUntilZIO(_ => received.isDone)
        keys <- received.await
      yield assertTrue(keys == Set("banner"))
    } @@ TestAspect.withLiveClock,
    test("the events snippet runs") {
      Lifecycle.logEvents.as(assertCompletes)
    },
    test("tracking: both events are recorded with their context and details") {
      for
        _      <- Lifecycle.purchase
        events <- FeatureFlags.trackedEvents
      yield assertTrue(
        events.map(_._1) == List("checkout-completed", "purchase"),
        events(1)._2.targetingKey.contains("user-42"),
        events(1)._3.flatMap(_.value).contains(99.99)
      )
    },
    test("shutdown ends at NotReady and evaluations then fail") {
      for
        _      <- Lifecycle.stop
        status <- FeatureFlags.providerStatus
        result <- flag.either
      yield assertTrue(
        status == ProviderStatus.NotReady,
        result == Left(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady))
      )
    }
  ).provide(layer, Scope.default) @@ TestAspect.sequential
