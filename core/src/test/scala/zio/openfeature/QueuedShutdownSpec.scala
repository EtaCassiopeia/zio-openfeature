package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.SharedApiFixtures._
import zio.openfeature.internal.{LifecycleProbe, ManagedProvider, ProviderRegistration}
import dev.openfeature.sdk.{FeatureProvider, Metadata, OpenFeatureAPI}

/** The SDK queues the shutdown of the provider a swap replaces at the binding, keyed by that provider's state manager,
  * and runs it once the new provider's `initialize()` returns. A provider bound again meanwhile under a new state
  * manager, or swapped off a second time, would be shut down while a slot serves it, or twice. Each interleaving is
  * forced by a provider latch, never by a sleep.
  */
object QueuedShutdownSpec extends ZIOSpecDefault {

  private def named(name: String, failure: Option[String] = None): Counting =
    new Counting(failure) {
      override def getMetadata: Metadata = new Metadata { override def getName: String = name }
    }

  private def holds(api: OpenFeatureAPI, slot: String, provider: FeatureProvider): Boolean = {
    val sdk = ManagedProvider.existing(provider).getOrElse(provider)
    api.getProvider(slot) eq sdk
  }

  private def entered(provider: Counting): UIO[Boolean] =
    Live.live(ZIO.succeed(provider.inits.get()).repeatUntil(_ > 0).timeout(5.seconds).map(_.isDefined))

  // Shutdowns once the SDK's executor has had time to run what it queued.
  private def settled(provider: Gated): UIO[Int] =
    Live.live(ZIO.sleep(300.millis) *> ZIO.succeed(provider.shutdowns.get()))

  // A swap reports the refusal as its typed error.
  private def queued(exit: Exit[Throwable, Any]): Boolean =
    exit.causeOption.flatMap(_.failureOption).exists {
      case FeatureFlagError.ProviderInitializationFailed(_: ProviderRegistration.ShutdownQueued) => true
      case _                                                                                     => false
    }

  // A build fails with the refusal itself.
  private def buildQueued(exit: Exit[Throwable, Any]): Boolean =
    exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[ProviderRegistration.ShutdownQueued])

  // The user binds `raw` under two domains; an instance on each. The first one's swap off it times out, so the SDK shuts
  // `raw` down once that swap's provider returns, and the second one's swap off it must wait for that.
  private def rawLeftWaiting(timedOutOwns: Boolean) = {
    val api  = OpenFeatureAPI.createIsolated()
    val raw  = named("raw")
    val slow = named("slow")
    val next = named("next")
    val own  = named("own")
    ZIO.scoped {
      for {
        _ <- ZIO.succeed { raw.gate.countDown(); next.gate.countDown(); own.gate.countDown() }
        _ <- ZIO.attemptBlocking { api.setProviderAndWait("a", raw); api.setProviderAndWait("b", raw) }
        _ <- ZIO.unless(timedOutOwns)(syncBuild(api, own, None, 10.seconds, owns = true))
        a <- syncBuild(api, raw, Some("a"), 300.millis, owns = timedOutOwns)
        b <- syncBuild(api, raw, Some("b"), 10.seconds)
        // Times out with slow's initialize() still running: the SDK shuts raw down if that call returns.
        timedOut <- a.setProvider(slow).exit
        refused  <- b.setProvider(next).exit
        _        <- ZIO.succeed(slow.gate.countDown())
        early    <- settled(raw)
        retried  <- b.setProvider(next).exit
        count    <- settled(raw)
      } yield assertTrue(timedOut.isFailure, queued(refused), early == 0, retried.isSuccess, count == 1)
    }
  }

  def spec = suite("QueuedShutdownSpec")(
    test(
      "a swap off a provider a sibling's in-flight swap is replacing is refused, and the sibling's rollback keeps it"
    ) {
      val api     = OpenFeatureAPI.createIsolated()
      val shared  = named("shared", None)
      val failing = named("failing", Some("boom"))
      val next    = named("next")
      ZIO.scoped {
        for {
          _       <- ZIO.succeed(shared.gate.countDown())
          a       <- syncBuild(api, shared, Some("a"), 10.seconds, owns = true)
          b       <- syncBuild(api, shared, Some("b"), 10.seconds)
          rolling <- b.setProvider(failing).exit.fork
          _       <- entered(failing)
          second  <- a.setProvider(next).exit
          _       <- ZIO.succeed(failing.gate.countDown())
          _       <- rolling.join
          _       <- ZIO.succeed(next.gate.countDown())
          count   <- settled(shared)
          keptA   <- ZIO.succeed(holds(api, "a", shared))
          keptB   <- ZIO.succeed(holds(api, "b", shared))
          nexts   <- ZIO.succeed(next.inits.get())
        } yield assertTrue(queued(second), count == 0, keptA, keptB, nexts == 0)
      }
    },
    test("the second of two concurrent swaps off one shared provider succeeds when retried after the first settles") {
      val api    = OpenFeatureAPI.createIsolated()
      val shared = named("shared")
      val first  = named("first")
      val next   = named("next")
      ZIO.scoped {
        for {
          _       <- ZIO.succeed { shared.gate.countDown(); next.gate.countDown() }
          a       <- syncBuild(api, shared, Some("a"), 10.seconds, owns = true)
          b       <- syncBuild(api, shared, Some("b"), 10.seconds)
          swap    <- b.setProvider(first).exit.fork
          _       <- entered(first)
          refused <- a.setProvider(next).exit
          _       <- ZIO.succeed(first.gate.countDown())
          swapped <- swap.join
          retried <- a.setProvider(next).exit
          count   <- settled(shared)
          serving <- ZIO.succeed(holds(api, "a", next) && holds(api, "b", first))
        } yield assertTrue(queued(refused), swapped.isSuccess, retried.isSuccess, count == 1, serving)
      }
    },
    test("a swap off a raw provider a timed-out swap left waiting is refused, and the provider is shut down once") {
      rawLeftWaiting(timedOutOwns = true)
    },
    test("the same when the timed-out swap was made by an instance that does not own the API") {
      rawLeftWaiting(timedOutOwns = false)
    },
    test("a build on a provider whose shutdown is queued, held by no slot, is refused and starts no initialize()") {
      val api  = OpenFeatureAPI.createIsolated()
      val old  = named("old")
      val next = named("next")
      ZIO.scoped {
        for {
          _     <- ZIO.succeed(old.gate.countDown())
          a     <- syncBuild(api, old, Some("a"), 10.seconds, owns = true)
          swap  <- a.setProvider(next).exit.fork
          _     <- entered(next)
          built <- ZIO.scoped(syncBuild(api, old, Some("c"), 10.seconds)).exit
          _     <- ZIO.succeed(next.gate.countDown())
          _     <- swap.join
          inits <- ZIO.succeed(old.inits.get())
          count <- settled(old)
        } yield assertTrue(buildQueued(built), inits == 1, count == 1)
      }
    },
    test("a swap to a provider a sibling's in-flight swap is replacing, held by no slot, is refused") {
      val api   = OpenFeatureAPI.createIsolated()
      val old   = named("old")
      val next  = named("next")
      val other = named("other")
      ZIO.scoped {
        for {
          _       <- ZIO.succeed { old.gate.countDown(); other.gate.countDown() }
          a       <- syncBuild(api, old, Some("a"), 10.seconds, owns = true)
          b       <- syncBuild(api, other, Some("b"), 10.seconds)
          swap    <- a.setProvider(next).exit.fork
          _       <- entered(next)
          refused <- b.setProvider(old).exit
          _       <- ZIO.succeed(next.gate.countDown())
          _       <- swap.join
          inits   <- ZIO.succeed(old.inits.get())
          count   <- settled(old)
          kept    <- ZIO.succeed(holds(api, "b", other))
        } yield assertTrue(queued(refused), inits == 1, count == 1, kept)
      }
    },
    test("a swap that passed its early check is refused as it binds once another swap has moved the last slot off") {
      val api   = OpenFeatureAPI.createIsolated()
      val old   = named("old")
      val next  = named("next")
      val other = named("other")
      ZIO.scoped {
        for {
          _       <- ZIO.succeed { old.gate.countDown(); other.gate.countDown() }
          reached <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          a       <- syncBuild(api, old, Some("a"), 10.seconds, owns = true)
          b       <- probedBuild(api, other, "b", holdAt(LifecycleProbe.Point.SwapBind, reached, release))
          // Slot a still holds old: b's swap to it is let through, and waits at its binding.
          toOld   <- b.setProvider(old).exit.fork
          _       <- reached.await
          swap    <- a.setProvider(next).exit.fork
          _       <- entered(next)
          _       <- release.succeed(())
          refused <- toOld.join
          kept    <- ZIO.succeed(holds(api, "b", other))
          _       <- ZIO.succeed(next.gate.countDown())
          _       <- swap.join
          inits   <- ZIO.succeed(old.inits.get())
          count   <- settled(old)
        } yield assertTrue(queued(refused), kept, inits == 1, count == 1)
      }
    },
    test("a swap off the default slot's provider claims it as a named slot's does") {
      val api     = OpenFeatureAPI.createIsolated()
      val shared  = named("shared")
      val failing = named("failing", Some("boom"))
      val next    = named("next")
      ZIO.scoped {
        for {
          _       <- ZIO.succeed { shared.gate.countDown(); next.gate.countDown() }
          a       <- syncBuild(api, shared, None, 10.seconds, owns = true)
          b       <- syncBuild(api, shared, Some("b"), 10.seconds)
          rolling <- a.setProvider(failing).exit.fork
          _       <- entered(failing)
          refused <- b.setProvider(next).exit
          _       <- ZIO.succeed(failing.gate.countDown())
          rolled  <- rolling.join
          count   <- settled(shared)
          wrapper = ManagedProvider.existing(shared).orNull
          kept <- ZIO.succeed((api.getProvider() eq wrapper) && holds(api, "b", shared))
        } yield assertTrue(queued(refused), rolled.isFailure, count == 0, kept)
      }
    },
    test("a failed swap's rollback binds the provider its own swap moved off, and leaves no claim behind") {
      val api     = OpenFeatureAPI.createIsolated()
      val old     = named("old")
      val failing = named("failing", Some("boom"))
      val next    = named("next")
      ZIO.scoped {
        for {
          _      <- ZIO.succeed { old.gate.countDown(); failing.gate.countDown(); next.gate.countDown() }
          a      <- syncBuild(api, old, Some("a"), 10.seconds, owns = true)
          failed <- a.setProvider(failing).exit
          back   <- ZIO.succeed(holds(api, "a", old))
          b      <- syncBuild(api, old, Some("b"), 10.seconds)
          // Swapping off old from another slot finds no claim of a's left.
          moved <- b.setProvider(next).exit
          count <- settled(old)
        } yield assertTrue(failed.isFailure, back, moved.isSuccess, count == 0)
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
}
