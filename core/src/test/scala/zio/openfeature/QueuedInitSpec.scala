package zio.openfeature

import dev.openfeature.sdk.OpenFeatureAPI
import zio._
import zio.openfeature.internal.{FeatureFlagsFactories, LifecycleProbe, ManagedProvider}
import zio.openfeature.testkit.ScriptedInitDeadline
import zio.test._

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

/** The SDK binds a provider before its executor calls `initialize()`. A call it has been handed but has not started is
  * still one that will run: nothing may start a second call on the provider before it, as a rollback or a binding
  * would. Each call is held at the wrapper's door (`InitEntering`), before the wrapper counts it.
  */
object QueuedInitSpec extends ZIOSpecDefault {
  import EvictedInitBuildSpec.Gated

  /** Holds the first `initialize()` the SDK calls on the wrapper, before the wrapper counts it. */
  final class Door {
    val reached               = new CountDownLatch(1)
    val release               = new CountDownLatch(1)
    private val first         = new AtomicBoolean(true)
    val probe: LifecycleProbe = new LifecycleProbe {
      def at(point: LifecycleProbe.Point): UIO[Unit] = point match {
        case LifecycleProbe.Point.InitEntering if first.compareAndSet(true, false) =>
          ZIO.succeed { reached.countDown(); release.await(30, TimeUnit.SECONDS) }.unit
        case _ => ZIO.unit
      }
    }
    def awaitReached: Task[Boolean] = ZIO.attemptBlocking(reached.await(30, TimeUnit.SECONDS))
    def open: UIO[Unit]             = ZIO.succeed(release.countDown())
  }

  private def owner(api: OpenFeatureAPI, dl: ScriptedInitDeadline) = {
    val o = new Gated("o", fails = false); o.gate.countDown()
    FeatureFlagsFactories.build(
      o,
      domain = Some("d1"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(api),
      initTimeout = 60.seconds,
      initDeadline = dl
    )
  }

  // Times the swap to `p` out while its initialize() waits at the door, unstarted.
  private def timedOutUnstarted(ff: FeatureFlagsLive, p: Gated, door: Door, dl: ScriptedInitDeadline) =
    for {
      swap  <- ff.setProvider(p).either.fork
      _     <- door.awaitReached
      _     <- ZIO.succeed(dl.armed.contains(p)).repeatUntil(identity).timeout(30.seconds)
      _     <- ZIO.succeed(dl.fire(p))
      timed <- swap.join
    } yield timed

  def spec = suite("QueuedInit")(
    test("a failed swap does not roll back onto a provider whose timed-out swap's initialize() has not started") {
      val api  = OpenFeatureAPI.createIsolated()
      val p    = new Gated("p", fails = false); p.gate.countDown()
      val q    = new Gated("q", fails = true); q.gate.countDown()
      val door = new Door
      // Held strongly: the wrapper cache is weak, and a wrapper made later would have no door.
      val wrapper = ManagedProvider(p, door.probe)
      ZIO.scoped {
        for {
          dl     <- ZIO.succeed(new ScriptedInitDeadline)
          ff     <- owner(api, dl)
          timed  <- timedOutUnstarted(ff, p, door, dl)
          failed <- ff.setProvider(q).either
          _      <- door.open
          // Once p's one call has run and returned, the provider q replaced it with is shut down as its orphan.
          shut  <- ZIO.succeed(p.shutdowns.get()).repeatUntil(_ > 0).timeout(10.seconds)
          inits <- ZIO.succeed(p.inits.get())
        } yield assertTrue(
          ManagedProvider.existing(p).contains(wrapper),
          timed.isLeft,
          failed.isLeft,
          inits == 1,
          p.overlap.get() <= 1,
          shut.contains(1)
        )
      }
    },
    test("a queued call that starts takes over the in-flight count, and one that ended leaves nothing unfinished") {
      val api     = OpenFeatureAPI.createIsolated()
      val p       = new Gated("p", fails = false); p.gate.countDown()
      val door    = new Door
      val wrapper = ManagedProvider(p, door.probe)
      ZIO.scoped {
        for {
          dl      <- ZIO.succeed(new ScriptedInitDeadline)
          ff      <- owner(api, dl)
          swap    <- ff.setProvider(p).either.fork
          _       <- door.awaitReached
          queued  <- ZIO.succeed((wrapper.initQueued, wrapper.initUnfinished, wrapper.initsInFlightAnywhere))
          _       <- door.open
          swapped <- swap.join
          after   <- ZIO.succeed((wrapper.initQueued, wrapper.initUnfinished))
        } yield assertTrue(queued == ((true, true, 0)), swapped.isRight, after == ((false, false)))
      }
    },
    test("a binding that reuses a registration already initialized marks no call queued") {
      val api     = OpenFeatureAPI.createIsolated()
      val p       = new Gated("p", fails = false); p.gate.countDown()
      val wrapper = ManagedProvider(p)
      ZIO.scoped {
        for {
          dl <- ZIO.succeed(new ScriptedInitDeadline)
          ff <- owner(api, dl)
          _  <- ff.setProvider(p)
          // A second domain of the API reuses p's READY registration: the SDK starts no call.
          _      <- ZIO.scoped(SharedApiFixtures.syncBuild(api, p, Some("d2"), 10.seconds).flatMap(_.providerStatus))
          marked <- ZIO.succeed((wrapper.initQueued, wrapper.initUnfinished, p.inits.get()))
        } yield assertTrue(marked == ((false, false, 1)))
      }
    },
    test("a swap to a provider whose timed-out swap's initialize() has not started is refused") {
      val api     = OpenFeatureAPI.createIsolated()
      val p       = new Gated("p", fails = false); p.gate.countDown()
      val r       = new Gated("r", fails = false); r.gate.countDown()
      val door    = new Door
      val wrapper = ManagedProvider(p, door.probe)
      ZIO.scoped {
        for {
          dl      <- ZIO.succeed(new ScriptedInitDeadline)
          ff      <- owner(api, dl)
          timed   <- timedOutUnstarted(ff, p, door, dl)
          moved   <- ff.setProvider(r).either
          back    <- ff.setProvider(p).either
          _       <- door.open
          settled <- ZIO.succeed(wrapper.initUnfinished).repeatUntil(!_).timeout(10.seconds)
          inits   <- ZIO.succeed(p.inits.get())
        } yield assertTrue(
          timed.isLeft,
          moved.isRight,
          back.left.exists(_.toString.contains("still initializing")),
          settled.isDefined,
          inits == 1
        )
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
}
