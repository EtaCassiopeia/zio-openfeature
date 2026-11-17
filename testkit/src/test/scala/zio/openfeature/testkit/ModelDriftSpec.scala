package zio.openfeature.testkit

import zio._
import zio.test._
import zio.openfeature._
import dev.openfeature.sdk.{FeatureProvider, OpenFeatureAPI, ProviderEvent => OFProviderEvent, ProviderEventDetails}

// Paths the TLA+ models abstract away (formal/*/README.md "outside the model"), exercised against the code.
object ModelDriftSpec extends ZIOSpecDefault {

  private val testBound = 40.seconds

  private def buildOn(
    api: OpenFeatureAPI,
    provider: FeatureProvider,
    owned: Boolean,
    initTimeout: Duration = 2.seconds,
    domain: String = s"drift-${java.util.UUID.randomUUID()}"
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    FeatureFlags.build(
      provider,
      domain = Some(domain),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = owned,
      apiOverride = Some(api),
      initTimeout = initTimeout
    )

  private def bounded[E, A](label: String)(effect: ZIO[Scope, E, A]): ZIO[Any, Any, A] =
    ZIO.scoped(effect).timeoutFail(s"$label did not complete within $testBound")(testBound)

  private val boom = new RuntimeException("boom")

  def spec = suite("ModelDriftSpec")(
    // formal/lifecycle README:382-387: a provider that reports state from inside initialize() and then fails.
    test("READY emitted inside initialize() then a throw: the swap fails and rolls back to the previous provider") {
      bounded("ready-then-fail") {
        for {
          api      <- ZIO.succeed(OpenFeatureAPI.createIsolated())
          previous <- FaultyProvider.scoped(Map("x" -> false), name = "previous")
          ff       <- buildOn(api, previous, owned = true)
          incoming <- FaultyProvider.scoped(Map("x" -> true), name = "incoming")
          _        <- incoming.script(
            Op.Initialize,
            Fault.Emit(OFProviderEvent.PROVIDER_READY, ProviderEventDetails.builder().build(), Fault.Throw(boom))
          )
          result    <- ff.setProvider(incoming).either
          _         <- ZIO.sleep(1.second)
          status    <- ff.providerStatus
          meta      <- ff.providerMetadata
          value     <- ff.boolean("x", true).either
          prevShuts <- previous.calls(Op.Shutdown)
          incShuts  <- incoming.calls(Op.Shutdown)
          incInits  <- incoming.calls(Op.Initialize)
        } yield assertTrue(
          result.isLeft,
          status == ProviderStatus.Ready,
          meta.name == "previous",
          value == Right(false),
          prevShuts.isEmpty,
          incInits.size == 1,
          incShuts.size <= 1
        )
      }
    } @@ TestAspect.withLiveClock,
    test("STALE emitted inside initialize() then a throw: the swap fails and rolls back to the previous provider") {
      bounded("stale-then-fail") {
        for {
          api      <- ZIO.succeed(OpenFeatureAPI.createIsolated())
          previous <- FaultyProvider.scoped(Map("x" -> false), name = "previous")
          ff       <- buildOn(api, previous, owned = true)
          incoming <- FaultyProvider.scoped(Map("x" -> true), name = "incoming")
          _        <- incoming.script(
            Op.Initialize,
            Fault.Emit(OFProviderEvent.PROVIDER_STALE, ProviderEventDetails.builder().build(), Fault.Throw(boom))
          )
          result    <- ff.setProvider(incoming).either
          _         <- ZIO.sleep(1.second)
          status    <- ff.providerStatus
          meta      <- ff.providerMetadata
          value     <- ff.boolean("x", true).either
          prevShuts <- previous.calls(Op.Shutdown)
          incShuts  <- incoming.calls(Op.Shutdown)
        } yield assertTrue(
          result.isLeft,
          status == ProviderStatus.Ready,
          meta.name == "previous",
          value == Right(false),
          prevShuts.isEmpty,
          incShuts.size <= 1
        )
      }
    } @@ TestAspect.withLiveClock,
    // formal/registry README:24-30, lifecycle README:393-397: an owner tears the shared API down while a sibling's swap
    // is parked in initialize().
    test(
      "an owner closing a shared API while a sibling's swap is parked leaves the sibling NotReady, each provider shut once"
    ) {
      bounded("owner-close-during-sibling-swap") {
        for {
          api        <- ZIO.succeed(OpenFeatureAPI.createIsolated())
          ownerP     <- FaultyProvider.scoped(Map("x" -> false), name = "owner")
          sibP       <- FaultyProvider.scoped(Map("x" -> false), name = "sibling")
          incoming   <- FaultyProvider.scoped(Map("x" -> true), name = "incoming")
          _          <- incoming.script(Op.Initialize, Fault.HangUntilReleased)
          ownerScope <- Scope.make
          _          <- buildOn(api, ownerP, owned = true).provideEnvironment(ZEnvironment(ownerScope))
          sibling    <- buildOn(api, sibP, owned = false, initTimeout = 10.seconds)
          swap       <- sibling.setProvider(incoming).either.fork
          _          <- incoming.awaitEntered(Op.Initialize)
          closed     <- ownerScope.close(Exit.unit).timeout(20.seconds)
          _          <- incoming.release
          swapped    <- swap.join
          _          <- ZIO.sleep(1.second)
          status     <- sibling.providerStatus
          value      <- sibling.boolean("x", false).either
          incShuts   <- incoming.calls(Op.Shutdown)
          sibShuts   <- sibP.calls(Op.Shutdown)
          ownShuts   <- ownerP.calls(Op.Shutdown)
        } yield assertTrue(
          closed.isDefined,
          status == ProviderStatus.NotReady,
          value.isLeft,
          incShuts.size <= 1,
          sibShuts.size <= 1,
          ownShuts.size == 1
        )
      }
    } @@ TestAspect.withLiveClock,
    // formal/lifecycle README:236: an interrupt while waiting for the swap lock has no action in the model.
    test("a setProvider interrupted while queued behind a parked swap never touches its provider") {
      bounded("interrupt-queued-swap") {
        for {
          api      <- ZIO.succeed(OpenFeatureAPI.createIsolated())
          previous <- FaultyProvider.scoped(Map("x" -> false), name = "previous")
          ff       <- buildOn(api, previous, owned = true, initTimeout = 10.seconds)
          first    <- FaultyProvider.scoped(Map("x" -> true), name = "first")
          _        <- first.script(Op.Initialize, Fault.HangUntilReleased)
          second   <- FaultyProvider.scoped(Map("x" -> true), name = "second")
          f1       <- ff.setProvider(first).either.fork
          _        <- first.awaitEntered(Op.Initialize)
          f2       <- ff.setProvider(second).either.fork
          _        <- ZIO.sleep(300.millis)
          _        <- f2.interrupt
          _        <- first.release
          r1       <- f1.join
          _        <- ZIO.sleep(500.millis)
          meta     <- ff.providerMetadata
          status   <- ff.providerStatus
          secInit  <- second.calls(Op.Initialize)
        } yield assertTrue(r1.isRight, meta.name == "first", status == ProviderStatus.Ready, secInit.isEmpty)
      }
    } @@ TestAspect.withLiveClock,
    // formal/lifecycle README:398-399: the async build's watchdog is outside the model. Scope close while the async
    // build's init is still parked, before the watchdog fires.
    test("closing the scope of an async build parked in initialize() shuts the provider down once and does not hang") {
      for {
        api      <- ZIO.succeed(OpenFeatureAPI.createIsolated())
        provider <- FaultyProvider.make(Map("x" -> true), name = "async")
        _        <- provider.script(Op.Initialize, Fault.HangUntilShutdown)
        took     <- ZIO
          .scoped(
            FeatureFlags
              .buildAsync(
                provider,
                domain = Some(s"drift-async-${java.util.UUID.randomUUID()}"),
                version = None,
                initialHooks = Nil,
                statusRef = None,
                addShutdownFinalizer = true,
                apiOverride = Some(api),
                initTimeout = 10.seconds
              )
              .flatMap(_ => provider.awaitEntered(Op.Initialize))
          )
          .timed
          .timeout(25.seconds)
        _     <- ZIO.sleep(500.millis)
        shuts <- provider.calls(Op.Shutdown)
        _     <- provider.release
      } yield assertTrue(took.isDefined, shuts.size == 1)
    } @@ TestAspect.withLiveClock
  ) @@ TestAspect.sequential @@ TestAspect.timeout(3.minutes)
}
