package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.SharedApiFixtures._
import zio.openfeature.internal.{Bound, ManagedProvider, ProviderEvaluations}
import zio.openfeature.internal.LifecycleProbe.Point
import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, Metadata, OpenFeatureAPI, ProviderState}
import dev.openfeature.sdk.exceptions.GeneralError
import java.util.concurrent.atomic.AtomicBoolean

/** Instances on one API racing each other's swaps, rollbacks and retirement. Each interleaving is forced by a probe
  * hold or a provider latch, never by a sleep.
  */
object SharedApiSwapRaceSpec extends ZIOSpecDefault {

  private def wrapperOf(provider: Gated): Option[ManagedProvider] = ManagedProvider.existing(provider)

  private def binds(api: OpenFeatureAPI, slot: String, provider: Gated): Boolean =
    wrapperOf(provider).exists(api.getProvider(slot) eq _)

  private def at(point: Point): Point => Boolean = _ == point

  private def message(exit: Exit[Throwable, Any]): String =
    exit.causeOption.flatMap(_.failureOption).map(_.getMessage).getOrElse("")

  def spec = suite("SharedApiSwapRaceSpec")(
    test("a sibling's retirement leaves the shared API and its owner serving") {
      val api   = OpenFeatureAPI.createIsolated()
      val owned = new Gated(open = true) {
        override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
          ProviderEvaluations.of("owned", "STATIC")
      }
      val sibling = new Gated(open = true)
      ZIO.scoped {
        for {
          owner   <- syncBuild(api, owned, Some("a"), 5.seconds, owns = true)
          retired <- syncBuild(api, sibling, Some("b"), 5.seconds)
          _       <- retired.retire
          gone    <- retired.providerStatus
          status  <- owner.providerStatus
          served  <- owner.string("flag", "fallback").either
          // Read now: assertTrue reads its operands once the scope has closed, and the owner's close shuts the API down.
          kept   <- ZIO.succeed(binds(api, "a", owned))
          counts <- ZIO.succeed((owned.shutdowns.get(), sibling.shutdowns.get()))
        } yield assertTrue(
          gone == ProviderStatus.NotReady,
          kept,
          status == ProviderStatus.Ready,
          served == Right("owned"),
          counts == (0, 0)
        )
      }
    },
    test("a rollback leaves a slot that a sibling swap rebound meanwhile") {
      val api     = OpenFeatureAPI.createIsolated()
      val old     = new Counting(open = true)
      val failing = new Gated(failure = Some("boom"), open = true)
      val next    = new Counting(open = true)
      val armed   = new AtomicBoolean(false)
      ZIO.scoped {
        for {
          rollback <- Hold(at(Point.SwapRollback), armed)
          a        <- probedBuild(api, old, "s", new Holds(List(rollback)))
          b        <- syncBuild(api, old, Some("s"), 5.seconds)
          _        <- ZIO.succeed(armed.set(true))
          swap     <- a.setProvider(failing).exit.fork
          _        <- rollback.reached.await
          rebound  <- b.setProvider(next).exit
          _        <- rollback.go
          swapped  <- swap.join
          kept     <- ZIO.succeed(binds(api, "s", next))
          inits    <- ZIO.succeed(old.inits.get())
          statusA  <- a.providerStatus
          statusB  <- b.providerStatus
          count    <- shutdownsAfterSettling(next)
        } yield assertTrue(
          rebound.isSuccess,
          swapped.isFailure,
          kept,
          inits == 1,
          statusA == ProviderStatus.Error,
          statusB == ProviderStatus.Ready,
          count == 0
        )
      }
    },
    test("a swap refused before it binds leaves the provider to the slot that holds it") {
      val api      = OpenFeatureAPI.createIsolated()
      val old      = new Gated(open = true)
      val provider = new Counting()
      val armed    = new AtomicBoolean(false)
      ZIO.scoped {
        for {
          bind      <- Hold(at(Point.SwapBind), armed)
          reconcile <- Hold(at(Point.SwapReconcile), armed)
          a         <- probedBuild(api, old, "a", new Holds(List(bind, reconcile)))
          _         <- ZIO.succeed(armed.set(true))
          swap      <- a.setProvider(provider).exit.fork
          _         <- bind.reached.await
          sibling   <- siblingBuilding(api, provider, "b")
          entered   <- Live.live(ZIO.succeed(provider.inits.get()).repeatUntil(_ == 1).timeout(5.seconds))
          _         <- bind.go
          _         <- reconcile.reached.await
          _         <- ZIO.succeed(provider.gate.countDown())
          built     <- sibling.join
          _         <- reconcile.go
          swapped   <- swap.join
          count     <- ZIO.succeed(provider.shutdowns.get())
          kept      <- ZIO.succeed(binds(api, "b", provider))
        } yield assertTrue(
          entered.isDefined,
          built == ProviderStatus.Ready,
          message(swapped).contains("is still initializing from another registration"),
          count == 0,
          kept
        )
      }
    },
    test("a failed swap with nothing to roll back to leaves its provider in its own slot") {
      val api      = OpenFeatureAPI.createIsolated()
      val first    = new Gated(open = true)
      val stuck    = new Gated()
      val provider = new Gated(failure = Some("boom"))
      val armed    = new AtomicBoolean(false)
      ZIO.scoped {
        for {
          sdkBind   <- Hold(at(Point.SdkBind), armed)
          reconcile <- Hold(at(Point.SwapReconcile), armed)
          a         <- probedBuild(api, first, "a", new Holds(List(sdkBind, reconcile)), initTimeout = 300.millis)
          // Times out: `a` installs `stuck`, which is still initializing, so there is nothing to roll back to.
          timedOut <- Live.live(a.setProvider(stuck).exit)
          _        <- ZIO.succeed(armed.set(true))
          swap     <- a.setProvider(provider).exit.fork
          _        <- sdkBind.reached.await
          _        <- ZIO.scoped {
            for {
              _ <- asyncBuild(api, provider, "b", 1.hour)
              _ <- wrapperIn(api, provider, "b")
              _ <- sdkBind.go
              _ <- ZIO.succeed(provider.gate.countDown())
              _ <- reconcile.reached.await
            } yield ()
          }
          _       <- reconcile.go
          swapped <- swap.join
          kept    <- ZIO.succeed(binds(api, "a", provider))
          count   <- ZIO.succeed(provider.shutdowns.get())
          status  <- a.providerStatus
          _       <- ZIO.succeed(stuck.gate.countDown())
        } yield assertTrue(
          timedOut.isFailure,
          swapped.isFailure,
          kept,
          count == 0,
          status == ProviderStatus.Error
        )
      }
    },
    test("reconciling after a swap ignores the state of a provider the slot no longer binds") {
      val api   = OpenFeatureAPI.createIsolated()
      val first = new Gated(open = true)
      val next  = new Gated(open = true)
      // Named apart from `next`: the event bridge attributes an event to its provider by name.
      val bad = new Gated(open = true) {
        override def getMetadata: Metadata                      = new Metadata { override def getName: String = "bad" }
        override def initialize(ctx: OFEvaluationContext): Unit = throw new GeneralError("bad boom")
      }
      val armed = new AtomicBoolean(false)
      ZIO.scoped {
        for {
          reconcile <- Hold(at(Point.SwapReconcile), armed)
          a         <- probedBuild(api, first, "s", new Holds(List(reconcile)))
          _         <- ZIO.succeed(armed.set(true))
          swap      <- a.setProvider(next).exit.fork
          _         <- reconcile.reached.await
          _         <- ZIO.succeed(api.setProvider("s", bad))
          _         <- RegistryRecording.environment("UserBind", Some("s"), Some(bad))
          errored   <- Live.live(
            ZIO.succeed(api.getClient("s").getProviderState).repeatUntil(_ == ProviderState.ERROR).timeout(5.seconds)
          )
          before  <- a.providerStatus
          _       <- reconcile.go
          swapped <- swap.join
          after   <- a.providerStatus
        } yield assertTrue(
          errored.isDefined,
          before == ProviderStatus.Ready,
          swapped.isSuccess,
          after == ProviderStatus.Ready
        )
      }
    },
    test("a failed registration of a provider the user registered is not the library's to shut down") {
      val api  = OpenFeatureAPI.createIsolated()
      val user = new Gated(open = true)
      ZIO.scoped {
        for {
          _        <- ZIO.attemptBlocking(api.setProviderAndWait("a", user))
          _        <- RegistryRecording.environment("UserBind", Some("a"), Some(user))
          a        <- syncBuild(api, user, Some("a"), 5.seconds)
          shutDown <- a.shutDownFailedRegistration(Bound.Foreign(user), "test")
          count    <- ZIO.succeed(user.shutdowns.get())
          kept     <- ZIO.succeed(api.getProvider("a") eq user)
        } yield assertTrue(!shutDown, count == 0, kept)
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds) @@ RegistryRecording.eachTest("shared-api-race")
}
