package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.SharedApiFixtures._
import zio.openfeature.internal.{Bound, LifecycleProbe, ManagedProvider, ProviderEvaluations}
import zio.openfeature.internal.LifecycleProbe.Point
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  ProviderStateAccess
}
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
      // Named apart from `old`: the event bridge attributes an event to its provider by name, so `next`'s READY must not
      // read as A's.
      val next = new Counting(open = true) {
        override def getMetadata: Metadata = new Metadata { override def getName: String = "next" }
      }
      val armed = new AtomicBoolean(false)
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
      val provider = new Gated(failure = Some("boom"), open = true)
      ZIO.scoped {
        for {
          a <- probedBuild(api, first, "a", LifecycleProbe.none, initTimeout = 300.millis)
          // Times out: `a` installs `stuck`, which is still initializing, so there is nothing to roll back to.
          timedOut <- Live.live(a.setProvider(stuck).exit)
          swapped  <- a.setProvider(provider).exit
          kept     <- ZIO.succeed(binds(api, "a", provider))
          count    <- ZIO.succeed(provider.shutdowns.get())
          status   <- a.providerStatus
          _        <- ZIO.succeed(stuck.gate.countDown())
        } yield assertTrue(
          timedOut.isFailure,
          swapped.isFailure,
          kept,
          count == 0,
          status == ProviderStatus.Error
        )
      }
    },
    test("a sibling's build is refused a provider a swap with nothing to roll back to is still initializing") {
      val api      = OpenFeatureAPI.createIsolated()
      val first    = new Gated(open = true)
      val stuck    = new Gated()
      val provider = new Counting(failure = Some("boom"))
      val armed    = new AtomicBoolean(false)
      ZIO.scoped {
        for {
          sdkBind <- Hold(at(Point.SdkBind), armed)
          a       <- probedBuild(api, first, "a", new Holds(List(sdkBind)), initTimeout = 300.millis)
          // Times out: `a` installs `stuck`, which is still initializing, so there is nothing to roll back to.
          timedOut <- Live.live(a.setProvider(stuck).exit)
          _        <- ZIO.succeed(armed.set(true))
          swap     <- a.setProvider(provider).exit.fork
          _        <- sdkBind.reached.await
          sibling  <- ZIO.scoped(asyncBuild(api, provider, "b", 1.hour)).exit
          unbound  <- ZIO.succeed(!binds(api, "b", provider))
          _        <- sdkBind.go
          _        <- ZIO.succeed(provider.gate.countDown())
          swapped  <- swap.join
          kept     <- ZIO.succeed(binds(api, "a", provider))
          counts   <- shutdownsAfterSettling(provider).zip(shutdownsAfterSettling(stuck))
          inits    <- ZIO.succeed(provider.inits.get())
          status   <- a.providerStatus
          _        <- ZIO.succeed(stuck.gate.countDown())
        } yield assertTrue(
          timedOut.isFailure,
          message(sibling).contains("is still initializing from another registration"),
          unbound,
          swapped.isFailure,
          kept,
          counts == (0, 0),
          inits == 1,
          status == ProviderStatus.Error
        )
      }
    },
    test("a sibling's build is refused a provider a swap is still initializing, and the rollback keeps the previous") {
      val api      = OpenFeatureAPI.createIsolated()
      val first    = new Gated(open = true)
      val provider = new Counting(failure = Some("boom"))
      val armed    = new AtomicBoolean(false)
      ZIO.scoped {
        for {
          sdkBind  <- Hold(at(Point.SdkBind), armed)
          a        <- probedBuild(api, first, "a", new Holds(List(sdkBind)))
          _        <- ZIO.succeed(armed.set(true))
          swap     <- a.setProvider(provider).exit.fork
          _        <- sdkBind.reached.await
          sibling  <- ZIO.scoped(syncBuild(api, provider, Some("b"), 5.seconds)).exit
          _        <- sdkBind.go
          _        <- ZIO.succeed(provider.gate.countDown())
          swapped  <- swap.join
          restored <- ZIO.succeed(binds(api, "a", first))
          count    <- shutdownsAfterSettling(first)
          inits    <- ZIO.succeed(provider.inits.get())
        } yield assertTrue(
          message(sibling).contains("is still initializing from another registration"),
          swapped.isFailure,
          restored,
          count == 0,
          inits == 1
        )
      }
    },
    test("a sibling's build shares a provider once the swap that installed it has finished initializing it") {
      val api      = OpenFeatureAPI.createIsolated()
      val first    = new Gated(open = true)
      val provider = new Counting(open = true)
      ZIO.scoped {
        for {
          a       <- syncBuild(api, first, Some("a"), 5.seconds)
          swapped <- a.setProvider(provider).exit
          sibling <- syncBuild(api, provider, Some("b"), 5.seconds).flatMap(_.providerStatus).exit
          shared  <- ZIO.succeed(binds(api, "a", provider) && binds(api, "b", provider))
          inits   <- ZIO.succeed(provider.inits.get())
        } yield assertTrue(
          swapped.isSuccess,
          sibling == Exit.succeed(ProviderStatus.Ready),
          shared,
          inits == 1
        )
      }
    },
    test("a binding replaces a provider only when its own slot holds one the SDK would shut down") {
      val api                               = OpenFeatureAPI.createIsolated()
      val default                           = new Gated(open = true)
      val named                             = new Gated(open = true)
      def replaces(domain: String): Boolean = ProviderStateAccess.replacesProvider(api, domain)
      for {
        // The API's own no-op provider in the default slot, and no named slot bound.
        initially <- ZIO.succeed((replaces(null), replaces("x")))
        _         <- ZIO.attemptBlocking(api.setProviderAndWait(default))
        // An unbound named slot answers with the default slot's manager: binding it replaces nothing.
        withDefault <- ZIO.succeed((replaces(null), replaces("x")))
        _           <- ZIO.attemptBlocking(api.setProviderAndWait("x", named))
        // A named slot sharing the default slot's manager leaves it to the default slot.
        _     <- ZIO.attemptBlocking(api.setProviderAndWait("y", default))
        bound <- ZIO.succeed((replaces("x"), replaces("y")))
        _     <- ZIO.attemptBlocking(api.shutdown())
      } yield assertTrue(
        initially == (false, false),
        withDefault == (true, false),
        bound == (true, false)
      )
    },
    test("a swap is refused a provider another instance's swap has handed the SDK") {
      val api      = OpenFeatureAPI.createIsolated()
      val first    = new Gated(open = true)
      val other    = new Gated(open = true)
      val provider = new Counting()
      val armed    = new AtomicBoolean(false)
      ZIO.scoped {
        for {
          sdkBind  <- Hold(at(Point.SdkBind), armed)
          a        <- probedBuild(api, first, "a", new Holds(List(sdkBind)))
          c        <- syncBuild(api, other, Some("c"), 5.seconds)
          _        <- ZIO.succeed(armed.set(true))
          swap     <- a.setProvider(provider).exit.fork
          _        <- sdkBind.reached.await
          refused  <- c.setProvider(provider).exit
          _        <- sdkBind.go
          _        <- ZIO.succeed(provider.gate.countDown())
          swapped  <- swap.join
          keptC    <- ZIO.succeed(binds(api, "c", other))
          swappedA <- ZIO.succeed(binds(api, "a", provider))
          count    <- shutdownsAfterSettling(other)
          status   <- c.providerStatus
        } yield assertTrue(
          message(refused).contains("is still initializing from an"),
          swapped.isSuccess,
          keptC,
          swappedA,
          count == 0,
          status == ProviderStatus.Ready
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
          nextReady <- Promise.make[Nothing, Unit]
          holds = new Holds(List(reconcile))
          // The status follows the slot's SDK state on any event from the installed provider, so `next`'s own READY
          // must have reached the bridge (and been dropped, the swap's gate being up) before the slot binds `bad`.
          probe = new LifecycleProbe {
            def at(p: Point): UIO[Unit] =
              (p match {
                case Point.DeliverEvent("PROVIDER_READY", "gated") if armed.get() => nextReady.succeed(()).unit
                case _                                                            => ZIO.unit
              }) *> holds.at(p)
          }
          a         <- probedBuild(api, first, "s", probe)
          _         <- ZIO.succeed(armed.set(true))
          swap      <- a.setProvider(next).exit.fork
          _         <- reconcile.reached.await
          delivered <- Live.live(nextReady.await.timeout(5.seconds))
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
          delivered.isDefined,
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
