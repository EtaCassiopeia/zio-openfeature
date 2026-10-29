package zio.openfeature.testkit

import java.util.concurrent.TimeoutException

import zio.openfeature.testsupport.LeakCheck.{awaitNoLeaks, snapshot}

import zio._
import zio.test._
import zio.openfeature._
import dev.openfeature.sdk.{FeatureProvider, OpenFeatureAPI}

// A shutdown() that arrives while a swap is parked in initialize(): it waits for the swap to settle, then tears down,
// so the final NotReady is never overwritten by the swap's own outcome.
object ShutdownDuringSwapSpec extends ZIOSpecDefault {

  private val testBound = 30.seconds
  private val LeakBound = 5.seconds

  private def buildIsolated(
    provider: FeatureProvider,
    owned: Boolean,
    initTimeout: Duration
  ): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      provider,
      domain = Some(s"shutdown-during-swap-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = owned,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      initTimeout = initTimeout
    )

  private def initFailure(e: FeatureFlagError): Option[Throwable] = e match {
    case FeatureFlagError.ProviderInitializationFailed(t) => Some(t)
    case _                                                => None
  }

  private def bounded[E, A](label: String)(effect: ZIO[Scope, E, A]): ZIO[Any, Any, A] =
    ZIO.scoped(effect).timeoutFail(s"$label did not complete within $testBound")(testBound)

  def spec = suite("ShutdownDuringSwapSpec")(
    test("on a shared instance, a shutdown waits for a parked swap and the status stays NotReady once it settles") {
      bounded("released swap") {
        for {
          previous <- FaultyProvider.scoped(Map("x" -> false), name = "previous")
          ff       <- buildIsolated(previous, owned = false, initTimeout = 10.seconds)
          incoming <- FaultyProvider.scoped(Map("x" -> true), name = "incoming")
          _        <- incoming.script(Op.Initialize, Fault.HangUntilReleased)
          swap     <- ff.setProvider(incoming).either.fork
          _        <- incoming.awaitEntered(Op.Initialize)
          shutdown <- ff.shutdown.fork
          _        <- ZIO.sleep(300.millis)
          early    <- shutdown.poll
          _        <- incoming.release
          _        <- shutdown.join
          after    <- ff.providerStatus
          swapped  <- swap.join
          _        <- ZIO.sleep(300.millis)
          settled  <- ff.providerStatus
        } yield assertTrue(
          early.isEmpty,
          swapped.isRight,
          after == ProviderStatus.NotReady,
          settled == ProviderStatus.NotReady
        )
      }
    } @@ TestAspect.withLiveClock,
    test("on a shared instance, a shutdown during a swap that times out leaves NotReady, not Error") {
      bounded("timed-out swap") {
        for {
          previous <- FaultyProvider.scoped(Map("x" -> false), name = "previous")
          ff       <- buildIsolated(previous, owned = false, initTimeout = 2.seconds)
          incoming <- FaultyProvider.scoped(Map("x" -> true), name = "incoming")
          _        <- incoming.script(Op.Initialize, Fault.HangUntilReleased)
          swap     <- ff.setProvider(incoming).either.fork
          _        <- incoming.awaitEntered(Op.Initialize)
          start    <- Clock.nanoTime
          _        <- ff.shutdown
          end      <- Clock.nanoTime
          after    <- ff.providerStatus
          swapped  <- swap.join
          _        <- ZIO.sleep(300.millis)
          settled  <- ff.providerStatus
        } yield assertTrue(
          swapped.left.toOption.flatMap(initFailure).exists(_.isInstanceOf[TimeoutException]),
          (end - start) / 1000000L < 4500L,
          after == ProviderStatus.NotReady,
          settled == ProviderStatus.NotReady
        )
      }
    } @@ TestAspect.withLiveClock,
    test("interrupting a shutdown that waits for a swap leaves the instance usable, and a later shutdown still runs") {
      bounded("interrupted shutdown") {
        for {
          previous <- FaultyProvider.scoped(Map("x" -> false), name = "previous")
          ff       <- buildIsolated(previous, owned = false, initTimeout = 10.seconds)
          incoming <- FaultyProvider.scoped(Map("x" -> true), name = "incoming")
          _        <- incoming.script(Op.Initialize, Fault.HangUntilReleased)
          swap     <- ff.setProvider(incoming).either.fork
          _        <- incoming.awaitEntered(Op.Initialize)
          waiting  <- ff.shutdown.fork
          _        <- ZIO.sleep(300.millis)
          _        <- waiting.interrupt
          _        <- incoming.release
          swapped  <- swap.join
          served   <- ff.providerStatus
          value    <- ff.boolean("x", false)
          _        <- ff.shutdown
          after    <- ff.providerStatus
        } yield assertTrue(
          swapped.isRight,
          served == ProviderStatus.Ready,
          value,
          after == ProviderStatus.NotReady
        )
      }
    } @@ TestAspect.withLiveClock,
    test("on an owning instance, a shutdown during a long parked swap stays NotReady and shuts each provider once") {
      for {
        baseline <- snapshot
        result   <- ZIO
          .scoped {
            for {
              previous <- FaultyProvider.scoped(Map("x" -> false), name = "previous")
              incoming <- FaultyProvider.scoped(Map("x" -> true), name = "incoming")
              statuses <- ZIO.scoped {
                for {
                  ff       <- buildIsolated(previous, owned = true, initTimeout = 5.seconds)
                  _        <- incoming.script(Op.Initialize, Fault.HangUntilReleased)
                  swap     <- ff.setProvider(incoming).either.fork
                  _        <- incoming.awaitEntered(Op.Initialize)
                  shutdown <- ff.shutdown.fork
                  _        <- ZIO.sleep(300.millis)
                  early    <- shutdown.poll
                  _        <- incoming.release
                  _        <- shutdown.join
                  after    <- ff.providerStatus
                  swapped  <- swap.join
                  _        <- ZIO.sleep(300.millis)
                  settled  <- ff.providerStatus
                } yield (early.isEmpty, swapped.isRight, after, settled)
              }
              previousShutdowns <- previous.calls(Op.Shutdown).map(_.size)
              incomingShutdowns <- incoming.calls(Op.Shutdown).map(_.size)
            } yield (statuses, previousShutdowns, incomingShutdowns)
          }
          .timeoutFail(s"owned swap did not complete within $testBound")(testBound)
        leaked <- awaitNoLeaks(baseline, LeakBound)
        ((waited, swapped, after, settled), previousShutdowns, incomingShutdowns) = result
      } yield assertTrue(
        waited,
        swapped,
        after == ProviderStatus.NotReady,
        settled == ProviderStatus.NotReady,
        previousShutdowns == 1,
        incomingShutdowns == 1,
        leaked.isEmpty
      )
    } @@ TestAspect.withLiveClock
  ) @@ TestAspect.sequential @@ TestAspect.timeout(2.minutes)
}
