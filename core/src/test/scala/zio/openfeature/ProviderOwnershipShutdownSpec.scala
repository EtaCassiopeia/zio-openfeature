package zio.openfeature

import zio._
import zio.stream.SubscriptionRef
import zio.test._
import zio.openfeature.internal.{Bound, LifecycleProbe, ManagedProvider, ProviderRegistration}
import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, FeatureProvider, OpenFeatureAPI, ProviderState}
import dev.openfeature.sdk.exceptions.GeneralError
import java.util.concurrent.{CountDownLatch, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.reflect.ClassTag

/** Who shuts a provider down when a build, an init deadline or a timed-out swap fails on it: the library only for a
  * registration of its own that nothing else on the API is serving from. A provider the user registered with the API
  * stays where it is, and the SDK shuts it down once, when its slot lets go of it.
  */
object ProviderOwnershipShutdownSpec extends ZIOSpecDefault {
  import SharedApiFixtures._

  // The test's own steps on the API, outside the library: recorded for the trace check (RegistryRecording).
  private def bindDirectly(api: OpenFeatureAPI, domain: Option[String], provider: FeatureProvider): UIO[Unit] =
    ZIO.succeed(domain.fold(api.setProvider(provider))(api.setProvider(_, provider))) *>
      RegistryRecording.environment("UserBind", domain, Some(provider))

  private def bindAndWait(api: OpenFeatureAPI, domain: Option[String], provider: FeatureProvider): Task[Unit] =
    ZIO
      .attemptBlocking(domain.fold(api.setProviderAndWait(provider))(api.setProviderAndWait(_, provider)))
      .ensuring(RegistryRecording.environment("UserBind", domain, Some(provider)))

  private def shutDownApi(api: OpenFeatureAPI): Task[Unit] =
    RegistryRecording.environment("UserApiShutdown") *> ZIO.attemptBlocking(api.shutdown())

  private def failedWith[E <: Throwable](exit: Exit[Throwable, Any])(implicit tag: ClassTag[E]): Boolean =
    exit.causeOption.flatMap(_.failureOption).exists(tag.runtimeClass.isInstance)

  private def awaitFatal(ff: FeatureFlagsLive): UIO[Option[ProviderStatus]] =
    Live.live(ff.providerStatus.repeatUntil(_ == ProviderStatus.Fatal).timeout(5.seconds))

  private def orphansLeft(ff: FeatureFlagsLive): UIO[Boolean] =
    ff.endState.violations.map(_.exists(_.startsWith("orphans left")))

  private def orphansSettled(ff: FeatureFlagsLive): UIO[Boolean] =
    Live.live(orphansLeft(ff).repeatUntil(!_).timeout(10.seconds)).map(_.isDefined)

  private val userRegistered = suite("a provider the user registered with the API")(
    test("a build whose wait times out fails and leaves it in its slot") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      for {
        _     <- bindDirectly(api, None, provider)
        exit  <- ZIO.scoped(syncBuild(api, provider, Some("d"), 300.millis)).exit
        count <- ZIO.succeed(provider.shutdowns.get())
        held  <- ZIO.succeed(api.getProvider() eq provider)
        _     <- ZIO.succeed(provider.gate.countDown())
      } yield assertTrue(failedWith[TimeoutException](exit), count == 0, held)
    },
    test("a build whose wait times out leaves its status Error, and a READY after the failure does not reach it") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      val readies  = new AtomicInteger(0)
      for {
        status <- SubscriptionRef.make[ProviderStatus](ProviderStatus.NotReady)
        _      <- bindDirectly(api, None, provider)
        _      <- ZIO.succeed(api.onProviderReady { _ => readies.incrementAndGet(); () })
        out    <- ZIO.scoped {
          // Read inside the build's scope: closing it removes the bridge's handlers anyway.
          syncBuild(api, provider, Some("d"), 300.millis, statusRef = Some(status)).exit.flatMap { exit =>
            for {
              failed <- status.get
              // initialize() returns, so the SDK sends the provider's READY.
              _     <- ZIO.succeed(provider.gate.countDown())
              ready <- Live.live(ZIO.succeed(readies.get).repeatUntil(_ > 0).timeout(5.seconds))
              moved <- Live.live(status.get.repeatUntil(_ != ProviderStatus.Error).timeout(500.millis))
            } yield assertTrue(
              failedWith[TimeoutException](exit),
              failed == ProviderStatus.Error,
              ready.isDefined,
              moved.isEmpty
            )
          }
        }
      } yield out
    },
    test("the same when the user registered it in the build's own slot") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      for {
        _     <- bindDirectly(api, Some("d"), provider)
        exit  <- ZIO.scoped(syncBuild(api, provider, Some("d"), 300.millis)).exit
        count <- ZIO.succeed(provider.shutdowns.get())
        held  <- ZIO.succeed(api.getProvider("d") eq provider)
        _     <- ZIO.succeed(provider.gate.countDown())
      } yield assertTrue(failedWith[TimeoutException](exit), count == 0, held)
    },
    test("a build fails with the provider's error when its initialize() throws during the wait, and leaves it") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated(failure = Some("boom"))
      for {
        _     <- bindDirectly(api, None, provider)
        fiber <- ZIO.scoped(syncBuild(api, provider, Some("d"), 10.seconds)).exit.fork
        // Nothing shows the build has reached its wait: an unbound domain reads the default slot, which holds it.
        _     <- Live.live(ZIO.sleep(300.millis))
        _     <- ZIO.succeed(provider.gate.countDown())
        exit  <- fiber.join
        count <- ZIO.succeed(provider.shutdowns.get())
        state <- ZIO.succeed(api.getClient("d").getProviderState)
        held  <- ZIO.succeed(api.getProvider() eq provider)
      } yield assertTrue(failedWith[GeneralError](exit), count == 0, state == ProviderState.ERROR, held)
    },
    test("a build on one that had already failed fails without shutting it down") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated(failure = Some("boom"), open = true)
      for {
        // Fails by design: this is the state the build then finds.
        _     <- bindAndWait(api, None, provider).ignore
        exit  <- ZIO.scoped(syncBuild(api, provider, Some("d"), 5.seconds)).exit
        count <- ZIO.succeed(provider.shutdowns.get())
      } yield assertTrue(failedWith[GeneralError](exit), count == 0)
    },
    test("an init deadline makes the instance Fatal, publishes the error, releases the latch, and leaves it") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      val latch    = new CountDownLatch(1)
      for {
        _   <- bindDirectly(api, None, provider)
        out <- ZIO.scoped {
          asyncBuild(api, provider, "d", 1.second, Some(latch)).flatMap { ff =>
            for {
              errors <- Ref.make(0)
              _      <- ff.on(ProviderEventType.Error, _ => errors.update(_ + 1))
              _      <- TestClock.adjust(2.seconds)
              status <- awaitFatal(ff)
              seen   <- Live.live(errors.get.repeatUntil(_ > 0).timeout(5.seconds))
              count  <- shutdownsAfterSettling(provider)
            } yield assertTrue(
              status.contains(ProviderStatus.Fatal),
              seen.isDefined,
              latch.getCount == 0L,
              count == 0,
              api.getProvider() eq provider
            )
          }
        }
        _ <- ZIO.succeed(provider.gate.countDown())
      } yield out
    },
    test("an async build on one still initializing in its own slot fires no READY until initialize() returns") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      val latch    = new CountDownLatch(1)
      val readies  = new AtomicInteger(0)
      for {
        _   <- ZIO.succeed(api.onProviderReady { _ => readies.incrementAndGet(); () })
        _   <- bindDirectly(api, Some("d"), provider)
        out <- ZIO.scoped {
          asyncBuild(api, provider, "d", 1.hour, Some(latch)).flatMap { ff =>
            for {
              _     <- Live.live(ZIO.sleep(300.millis))
              early <- ff.providerStatus
              fired = readies.get()
              held  = latch.getCount
              _      <- ZIO.succeed(provider.gate.countDown())
              status <- Live.live(ff.providerStatus.repeatUntil(_ == ProviderStatus.Ready).timeout(5.seconds))
            } yield assertTrue(
              fired == 0,
              early == ProviderStatus.NotReady,
              held == 1L,
              status.contains(ProviderStatus.Ready)
            )
          }
        }
      } yield out
    },
    test("after a failed build the SDK shuts it down exactly once, with the API") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      for {
        _    <- bindDirectly(api, None, provider)
        exit <- ZIO.scoped(syncBuild(api, provider, Some("d"), 300.millis)).exit
        _    <- ZIO.succeed(provider.gate.countDown())
        _    <- shutDownApi(api)
      } yield assertTrue(exit.isFailure, provider.shutdowns.get() == 1)
    },
    test("a swap off it that times out and then fails leaves it while the default slot holds it") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated(open = true)
      val incoming = new Gated(failure = Some("boom"))
      ZIO.scoped {
        for {
          _       <- bindAndWait(api, None, provider)
          ff      <- syncBuild(api, provider, Some("d"), 300.millis, owns = true)
          swap    <- ff.setProvider(incoming).exit
          pending <- orphansLeft(ff)
          _       <- ZIO.succeed(incoming.gate.countDown())
          settled <- orphansSettled(ff)
          count   <- ZIO.succeed(provider.shutdowns.get())
        } yield assertTrue(swap.isFailure, pending, settled, count == 0)
      }
    },
    test("the same when no slot holds it any more shuts it down once: nothing else would") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated(open = true)
      val incoming = new Gated(failure = Some("boom"))
      ZIO.scoped {
        for {
          _       <- bindAndWait(api, Some("d"), provider)
          ff      <- syncBuild(api, provider, Some("d"), 300.millis, owns = true)
          swap    <- ff.setProvider(incoming).exit
          pending <- orphansLeft(ff)
          _       <- ZIO.succeed(incoming.gate.countDown())
          settled <- orphansSettled(ff)
          count   <- ZIO.succeed(provider.shutdowns.get())
        } yield assertTrue(swap.isFailure, pending, settled, count == 1)
      }
    },
    test("one the user registers in a slot again before the swap's init settles is left: that slot serves it now") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated(open = true)
      val incoming = new Gated(failure = Some("boom"))
      ZIO.scoped {
        for {
          _       <- bindAndWait(api, Some("d"), provider)
          ff      <- syncBuild(api, provider, Some("d"), 300.millis, owns = true)
          swap    <- ff.setProvider(incoming).exit
          pending <- orphansLeft(ff)
          _       <- bindAndWait(api, None, provider)
          _       <- ZIO.succeed(incoming.gate.countDown())
          settled <- orphansSettled(ff)
          count   <- ZIO.succeed(provider.shutdowns.get())
        } yield assertTrue(swap.isFailure, pending, settled, count == 0)
      }
    },
    // Pins the wait through the watcher's interrupt, which waits out its uninterruptible settle; resolveAll's own
    // await of the entry adds nothing while every caller holds the swap lock.
    test("shutdown waits for a shutdown of a timed-out swap's previous provider that its watcher has started") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new SlowShutdown
      val incoming = new Gated(failure = Some("boom"))
      ZIO.scoped {
        for {
          ff      <- syncBuild(api, provider, Some("d"), 300.millis, owns = true)
          swap    <- ff.setProvider(incoming).exit
          pending <- orphansLeft(ff)
          _       <- ZIO.succeed(incoming.gate.countDown())
          started <- ZIO.attemptBlocking(provider.started.await(5, TimeUnit.SECONDS))
          closing <- ff.shutdown.fork
          _       <- Live.live(ZIO.sleep(200.millis))
          early   <- closing.poll
          _       <- ZIO.succeed(provider.release.countDown())
          _       <- closing.join
          done    <- ZIO.succeed(provider.finished.get())
        } yield assertTrue(swap.isFailure, pending, started, early.isEmpty, done)
      }
    }
  )

  // Its shutdown() blocks until released, so a test can see who waits for it.
  private class SlowShutdown extends Gated(open = true) {
    val started                   = new CountDownLatch(1)
    val release                   = new CountDownLatch(1)
    val finished                  = new AtomicBoolean(false)
    override def shutdown(): Unit = {
      started.countDown()
      release.await(5, TimeUnit.SECONDS): Unit
      finished.set(true)
      super.shutdown()
    }
  }

  private val sharedWrapper = suite("the library's wrapper of a provider a sibling domain is initializing")(
    test("a build whose wait times out fails and leaves the sibling to finish") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      for {
        sibling <- siblingBuilding(api, provider, "a")
        exit    <- ZIO.scoped(syncBuild(api, provider, Some("b"), 300.millis)).exit
        count   <- ZIO.succeed(provider.shutdowns.get())
        _       <- ZIO.succeed(provider.gate.countDown())
        status  <- sibling.join
      } yield assertTrue(failedWith[TimeoutException](exit), count == 0, status == ProviderStatus.Ready)
    },
    test("an init deadline leaves the sibling to finish") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      for {
        sibling <- siblingBuilding(api, provider, "a")
        out     <- ZIO.scoped {
          asyncBuild(api, provider, "b", 1.second).flatMap { ff =>
            for {
              _      <- TestClock.adjust(2.seconds)
              status <- awaitFatal(ff)
              count  <- shutdownsAfterSettling(provider)
            } yield assertTrue(status.contains(ProviderStatus.Fatal), count == 0)
          }
        }
        _      <- ZIO.succeed(provider.gate.countDown())
        status <- sibling.join
      } yield out && assertTrue(status == ProviderStatus.Ready)
    }
  )

  // `a`, open on `old`, has bound `failing` when a sibling's build on `b` joins it: both share the call that throws. A swap
  // reaching its binding after the sibling's call has started is refused instead.
  private def swapAndBuildFailTogether(api: OpenFeatureAPI, old: Gated, failing: Gated) =
    for {
      reached <- Promise.make[Nothing, Unit]
      release <- Promise.make[Nothing, Unit]
      a       <- probedBuild(api, old, "a", holdAt(LifecycleProbe.Point.SdkBind, reached, release))
      swap    <- a.setProvider(failing).exit.fork
      _       <- reached.await
      build   <- ZIO.scoped(syncBuild(api, failing, Some("b"), 10.seconds)).exit.fork
      _       <- wrapperIn(api, failing, "b")
      _       <- release.succeed(())
      _       <- wrapperIn(api, failing, "a")
      _       <- ZIO.succeed(failing.gate.countDown())
      swapped <- swap.join
      built   <- build.join
    } yield (a, swapped, built)

  private val failedSwap = suite("a swap that fails on a wrapper a sibling's build has joined")(
    test("shuts it down once between them when the rollback succeeds, and goes back to the previous provider") {
      val api     = OpenFeatureAPI.createIsolated()
      val old     = new Gated(open = true)
      val failing = new Gated(failure = Some("boom"))
      ZIO.scoped {
        for {
          out <- swapAndBuildFailTogether(api, old, failing)
          (a, swapped, built) = out
          count  <- shutdownsAfterSettling(failing)
          status <- a.providerStatus
          kept = ManagedProvider.existing(old).exists(w => api.getProvider("a") eq w)
        } yield assertTrue(
          swapped.isFailure,
          failedWith[GeneralError](built),
          count == 1,
          kept,
          status == ProviderStatus.Ready
        )
      }
    },
    test("shuts it down once between them when the rollback fails too") {
      val api     = OpenFeatureAPI.createIsolated()
      val old     = new FailsWhenReinitialized
      val failing = new Gated(failure = Some("boom"))
      ZIO.scoped {
        for {
          out <- swapAndBuildFailTogether(api, old, failing)
          (a, swapped, built) = out
          count  <- shutdownsAfterSettling(failing)
          status <- a.providerStatus
        } yield assertTrue(
          swapped.isFailure,
          failedWith[GeneralError](built),
          count == 1,
          status == ProviderStatus.Error
        )
      }
    },
    test("shuts it down when a sibling failed first and deferred to it, once it has moved off") {
      val api     = OpenFeatureAPI.createIsolated()
      val old     = new Gated(open = true)
      val failing = new Gated(failure = Some("boom"))
      ZIO.scoped {
        for {
          reached  <- Promise.make[Nothing, Unit]
          release  <- Promise.make[Nothing, Unit]
          a        <- probedBuild(api, old, "a", holdAt(LifecycleProbe.Point.SdkBind, reached, release))
          swap     <- a.setProvider(failing).exit.fork
          _        <- reached.await
          sibling  <- asyncBuild(api, failing, "b", 1.hour)
          _        <- wrapperIn(api, failing, "b")
          _        <- release.succeed(())
          _        <- wrapperIn(api, failing, "a")
          wrapper  <- ZIO.getOrFail(ManagedProvider.existing(failing))
          shutDown <- sibling.shutDownFailedRegistration(Bound.Managed(wrapper), "sibling")
          deferred <- ZIO.succeed(failing.shutdowns.get())
          _        <- ZIO.succeed(failing.gate.countDown())
          swapped  <- swap.join
          count    <- shutdownsAfterSettling(failing)
        } yield assertTrue(!shutDown, deferred == 0, swapped.isFailure, count == 1)
      }
    },
    test("leaves it when the slot that kept it is retrying its initialization") {
      val api      = OpenFeatureAPI.createIsolated()
      val old      = new Gated(open = true)
      val retrying = new CountDownLatch(1)
      val inits    = new AtomicInteger(0)
      val failing  = new Gated() {
        // The first call throws once released; the retry waits for the test.
        override def initialize(ctx: OFEvaluationContext): Unit =
          if (inits.incrementAndGet() == 1) { gate.await(); throw new GeneralError("boom") }
          else retrying.await()
      }
      ZIO.scoped {
        for {
          atBind      <- Promise.make[Nothing, Unit]
          goBind      <- Promise.make[Nothing, Unit]
          atReconcile <- Promise.make[Nothing, Unit]
          goReconcile <- Promise.make[Nothing, Unit]
          probe = new LifecycleProbe {
            def at(p: LifecycleProbe.Point): UIO[Unit] = p match {
              case LifecycleProbe.Point.SdkBind       => atBind.succeed(()) *> goBind.await
              case LifecycleProbe.Point.SwapReconcile => atReconcile.succeed(()) *> goReconcile.await
              case _                                  => ZIO.unit
            }
          }
          a      <- probedBuild(api, old, "a", probe)
          swap   <- a.setProvider(failing).exit.fork
          _      <- atBind.await
          build  <- ZIO.scoped(syncBuild(api, failing, Some("b"), 10.seconds)).exit.fork
          _      <- wrapperIn(api, failing, "b")
          _      <- goBind.succeed(())
          _      <- wrapperIn(api, failing, "a")
          _      <- ZIO.succeed(failing.gate.countDown())
          _      <- atReconcile.await
          _      <- build.join
          before <- ZIO.succeed(failing.shutdowns.get())
          _      <- RegistryRecording.environment("UserRetryInit", Some("b"), Some(failing))
          retry  <- ProviderRegistration.retryFailedInitialization(api, Some("b"), failing, 10.seconds).exit.fork
          _      <- Live.live(ZIO.succeed(inits.get()).repeatUntil(_ == 2).timeout(5.seconds))
          _      <- goReconcile.succeed(())
          _      <- swap.join
          during <- ZIO.succeed(failing.shutdowns.get())
          _      <- ZIO.succeed(retrying.countDown())
          _      <- retry.join
        } yield assertTrue(during == before)
      }
    },
    test("alone, with the rollback failing too, shuts it down once and releases it from the API") {
      val api     = OpenFeatureAPI.createIsolated()
      val old     = new FailsWhenReinitialized
      val failing = new Gated(failure = Some("boom"), open = true)
      ZIO.scoped {
        for {
          a       <- syncBuild(api, old, Some("a"), 5.seconds)
          swapped <- a.setProvider(failing).exit
          count   <- shutdownsAfterSettling(failing)
          wrapper = ManagedProvider.existing(failing)
          // The SDK refuses a provider still registered with another API, naming it so.
          bound <- ZIO.attempt(wrapper.foreach(w => OpenFeatureAPI.createIsolated().setProvider(w))).exit
          refusal = bound.causeOption.flatMap(_.failureOption).map(_.getMessage).getOrElse("")
        } yield assertTrue(swapped.isFailure, count == 1, wrapper.isDefined, !refusal.contains("another API"))
      }
    },
    test("no longer counts as using it, from the moment it has failed") {
      val api     = OpenFeatureAPI.createIsolated()
      val old     = new Gated(open = true)
      val failing = new Gated(failure = Some("boom"), open = true)
      ZIO.scoped {
        for {
          reached <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          a       <- probedBuild(api, old, "a", holdAt(LifecycleProbe.Point.SwapRollback, reached, release))
          swap    <- a.setProvider(failing).exit.fork
          _       <- reached.await
          serving = ManagedProvider.existing(failing).exists(a.serves(_))
          bound   = ManagedProvider.existing(failing).exists(w => api.getProvider("a") eq w)
          _ <- release.succeed(())
          _ <- swap.join
        } yield assertTrue(bound, !serving)
      }
    }
  )

  private val swapBehindSibling = suite("a swap reaching its binding while a sibling initializes the provider")(
    test("is refused, and its previous provider is never touched") {
      val api     = OpenFeatureAPI.createIsolated()
      val old     = new Counting(open = true)
      val failing = new Counting(failure = Some("boom"))
      ZIO.scoped {
        for {
          reached <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          a       <- probedBuild(api, old, "a", holdAt(LifecycleProbe.Point.SwapBind, reached, release))
          swap    <- a.setProvider(failing).exit.fork
          _       <- reached.await
          build   <- ZIO.scoped(syncBuild(api, failing, Some("b"), 10.seconds)).exit.fork
          _       <- Live.live(ZIO.succeed(failing.inits.get()).repeatUntil(_ == 1).timeout(5.seconds))
          _       <- release.succeed(())
          swapped <- swap.join
          // Past where the SDK's task would have run the early shutdown.
          oldShut <- Live.live(
            ZIO.succeed(old.shutdowns.get()).repeatUntil(_ > 0).timeout(500.millis).map(_.getOrElse(0))
          )
          oldRuns = old.inits.get()
          kept    = ManagedProvider.existing(old).exists(w => api.getProvider("a") eq w)
          status     <- a.providerStatus
          violations <- a.endState.violations
          message = swapped.causeOption.flatMap(_.failureOption).map(_.toString).getOrElse("")
          _     <- ZIO.succeed(failing.gate.countDown())
          built <- build.join
          count <- shutdownsAfterSettling(failing)
        } yield assertTrue(
          swapped.isFailure,
          message.contains("still initializing"),
          oldShut == 0,
          oldRuns == 1,
          kept,
          status == ProviderStatus.Ready,
          violations.isEmpty,
          failedWith[GeneralError](built),
          count == 1
        )
      }
    },
    test("succeeds when tried again once that call has returned") {
      val api      = OpenFeatureAPI.createIsolated()
      val old      = new Counting(open = true)
      val incoming = new Counting()
      ZIO.scoped {
        for {
          reached <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          a       <- probedBuild(api, old, "a", holdAt(LifecycleProbe.Point.SwapBind, reached, release))
          swap    <- a.setProvider(incoming).exit.fork
          _       <- reached.await
          build   <- syncBuild(api, incoming, Some("b"), 10.seconds).fork
          _       <- Live.live(ZIO.succeed(incoming.inits.get()).repeatUntil(_ == 1).timeout(5.seconds))
          _       <- release.succeed(())
          refused <- swap.join
          _       <- ZIO.succeed(incoming.gate.countDown())
          _       <- build.join
          retried <- a.setProvider(incoming).exit
          status  <- a.providerStatus
          inSlot = ManagedProvider.existing(incoming).exists(w => api.getProvider("a") eq w)
          oldShut <- Live.live(ZIO.succeed(old.shutdowns.get()).repeatUntil(_ == 1).timeout(5.seconds))
          runs = (old.inits.get(), incoming.inits.get())
        } yield assertTrue(
          refused.isFailure,
          retried.isSuccess,
          status == ProviderStatus.Ready,
          inSlot,
          oldShut.contains(1),
          runs == ((1, 1))
        )
      }
    },
    test("shares the call when another instance on its own slot has just installed the provider") {
      val api      = OpenFeatureAPI.createIsolated()
      val old      = new Counting(open = true)
      val incoming = new Counting()
      ZIO.scoped {
        for {
          reached <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          a       <- probedBuild(api, old, "d", holdAt(LifecycleProbe.Point.SwapBind, reached, release))
          sibling <- syncBuild(api, old, Some("d"), 10.seconds)
          swap    <- a.setProvider(incoming).exit.fork
          _       <- reached.await
          other   <- sibling.setProvider(incoming).exit.fork
          _       <- Live.live(ZIO.succeed(incoming.inits.get()).repeatUntil(_ == 1).timeout(5.seconds))
          _       <- release.succeed(())
          // The swap waits on the sibling's call rather than being refused and rolling back over it.
          early  <- swap.poll.delay(300.millis).withClock(Clock.ClockLive)
          _      <- ZIO.succeed(incoming.gate.countDown())
          first  <- swap.join
          second <- other.join
          inSlot = ManagedProvider.existing(incoming).exists(w => api.getProvider("d") eq w)
          oldShut <- Live.live(ZIO.succeed(old.shutdowns.get()).repeatUntil(_ == 1).timeout(5.seconds))
          runs = (old.inits.get(), incoming.inits.get(), incoming.shutdowns.get())
        } yield assertTrue(
          early.isEmpty,
          first.isSuccess,
          second.isSuccess,
          inSlot,
          oldShut.contains(1),
          runs == ((1, 1, 0))
        )
      }
    }
  )

  private val otherDomain = suite("a provider the user registered under a domain neither the build's nor the default")(
    test("when an instance was built on that domain, a build elsewhere reuses it and a failure leaves it") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Counting
      ZIO.scoped {
        for {
          _     <- bindDirectly(api, Some("x"), provider)
          _     <- asyncBuild(api, provider, "x", 1.hour)
          bound <- ZIO.attempt(Bound.of(api, Some("y"), provider))
          exit  <- ZIO.scoped(syncBuild(api, provider, Some("y"), 300.millis)).exit
          count <- ZIO.succeed(provider.shutdowns.get())
          held  = api.getProvider("x") eq provider
          inits = provider.inits.get()
          _ <- ZIO.succeed(provider.gate.countDown())
        } yield assertTrue(
          bound == Bound.Foreign(provider),
          failedWith[TimeoutException](exit),
          count == 0,
          held,
          inits == 1
        )
      }
    },
    test("the same once that instance has closed, since its slot keeps the registration") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Counting
      for {
        _     <- bindDirectly(api, Some("x"), provider)
        built <- ZIO.scoped(asyncBuild(api, provider, "x", 1.hour).map(new java.lang.ref.WeakReference(_)))
        // Past the instance itself, when the collector takes it in time: its slot still holds the provider.
        _ <- Live.live(
          ZIO.succeed { java.lang.System.gc(); built.get() == null }.repeatUntil(identity).timeout(2.seconds)
        )
        bound <- ZIO.attempt(Bound.of(api, Some("y"), provider))
        exit  <- ZIO.scoped(syncBuild(api, provider, Some("y"), 300.millis)).exit
        count <- ZIO.succeed(provider.shutdowns.get())
        inits = provider.inits.get()
        _ <- ZIO.succeed(provider.gate.countDown())
      } yield assertTrue(bound == Bound.Foreign(provider), failedWith[TimeoutException](exit), count == 0, inits == 1)
    },
    test("a swap off it that times out and then fails leaves it while an instance's slot holds it") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated(open = true)
      val incoming = new Gated(failure = Some("boom"))
      ZIO.scoped {
        for {
          _       <- bindAndWait(api, Some("x"), provider)
          _       <- syncBuild(api, provider, Some("x"), 5.seconds)
          ff      <- syncBuild(api, provider, Some("d"), 300.millis, owns = true)
          swap    <- ff.setProvider(incoming).exit
          pending <- orphansLeft(ff)
          _       <- ZIO.succeed(incoming.gate.countDown())
          settled <- orphansSettled(ff)
          count   <- ZIO.succeed(provider.shutdowns.get())
        } yield assertTrue(swap.isFailure, pending, settled, count == 0)
      }
    },
    test("a swap to it on a slot that holds its wrapper changes nothing and shuts nothing down") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated(open = true)
      ZIO.scoped {
        for {
          _   <- bindAndWait(api, Some("x"), provider)
          onY <- syncBuild(api, provider, Some("y"), 5.seconds)
          _   <- syncBuild(api, provider, Some("x"), 5.seconds)
          inY = ManagedProvider.existing(provider).exists(w => api.getProvider("y") eq w)
          swap <- onY.setProvider(provider).exit
          still = ManagedProvider.existing(provider).exists(w => api.getProvider("y") eq w)
          count  <- shutdownsAfterSettling(provider)
          status <- onY.providerStatus
        } yield assertTrue(inY, swap.isSuccess, still, count == 0, status == ProviderStatus.Ready)
      }
    },
    test("an owner closing while a swap off it hangs leaves its shutdown to the API, once") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated(open = true)
      // Stays hung through the API's shutdown: a return would run the SDK's own shutDownOld of the provider it replaced.
      val incoming = new Gated() {
        override def shutdown(): Unit = { shutdowns.incrementAndGet(); () }
      }
      for {
        _ <- ZIO.scoped {
          for {
            _  <- bindAndWait(api, Some("x"), provider)
            _  <- syncBuild(api, provider, Some("x"), 5.seconds)
            ff <- syncBuild(api, provider, Some("d"), 300.millis, owns = true)
            _  <- ff.setProvider(incoming).exit
          } yield ()
        }
        count <- ZIO.succeed(provider.shutdowns.get())
        _     <- ZIO.succeed(incoming.gate.countDown())
      } yield assertTrue(count == 1)
    },
    test("a wrapper only the default slot holds does not hide it from a build on a new domain") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated(open = true)
      ZIO.scoped {
        for {
          _     <- syncBuild(api, provider, None, 5.seconds)
          _     <- bindAndWait(api, Some("x"), provider)
          _     <- syncBuild(api, provider, Some("x"), 5.seconds)
          bound <- ZIO.attempt(Bound.of(api, Some("y"), provider))
        } yield assertTrue(bound == Bound.Foreign(provider))
      }
    },
    test("a domain-scoped one in a domain no instance was built on is wrapped, as any other is") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated(open = true) {
        override def isDomainScoped: Boolean = true
      }
      ZIO.scoped {
        for {
          _     <- bindAndWait(api, Some("x"), provider)
          bound <- ZIO.attempt(Bound.of(api, Some("y"), provider))
          exit  <- syncBuild(api, provider, Some("y"), 5.seconds).exit
        } yield assertTrue(bound.managed.isDefined, exit.isSuccess)
      }
    },
    test("a domain-scoped one cannot be bound twice: the build fails and leaves it") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated(open = true) {
        override def isDomainScoped: Boolean = true
      }
      ZIO.scoped {
        for {
          _     <- bindAndWait(api, Some("x"), provider)
          _     <- syncBuild(api, provider, Some("x"), 5.seconds)
          exit  <- ZIO.scoped(syncBuild(api, provider, Some("y"), 5.seconds)).exit
          count <- ZIO.succeed(provider.shutdowns.get())
        } yield assertTrue(failedWith[IllegalArgumentException](exit), count == 0)
      }
    },
    test(
      "when no instance was built on that domain, the library cannot see it: it wraps the provider and initializes it again"
    ) {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Counting
      for {
        _     <- bindDirectly(api, Some("x"), provider)
        _     <- Live.live(ZIO.succeed(provider.inits.get()).repeatUntil(_ == 1).timeout(5.seconds))
        bound <- ZIO.attempt(Bound.of(api, Some("y"), provider))
        _     <- ZIO.succeed(provider.gate.countDown())
        exit  <- ZIO.scoped(syncBuild(api, provider, Some("y"), 5.seconds)).exit
        inits = provider.inits.get()
      } yield assertTrue(bound.managed.isDefined, exit.isSuccess, inits == 2)
    }
  )

  private val ownRegistration = suite("the library's own registration, which nothing else serves from")(
    test("a build whose wait times out shuts it down once") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      for {
        exit <- ZIO.scoped(syncBuild(api, provider, Some("d"), 300.millis)).exit
      } yield assertTrue(failedWith[TimeoutException](exit), provider.shutdowns.get() == 1)
    },
    test("a build whose provider threw shuts it down once") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated(failure = Some("boom"), open = true)
      for {
        exit <- ZIO.scoped(syncBuild(api, provider, Some("d"), 5.seconds)).exit
      } yield assertTrue(failedWith[GeneralError](exit), provider.shutdowns.get() == 1)
    },
    test("an init deadline shuts it down once") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      ZIO.scoped {
        asyncBuild(api, provider, "d", 1.second).flatMap { ff =>
          for {
            _      <- TestClock.adjust(2.seconds)
            status <- awaitFatal(ff)
            count  <- shutdownsAfterSettling(provider)
          } yield assertTrue(status.contains(ProviderStatus.Fatal), count == 1)
        }
      }
    },
    test("a sibling whose scope has closed no longer counts: a build that times out shuts it down once") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      for {
        _      <- ZIO.scoped(asyncBuild(api, provider, "a", 1.hour))
        before <- ZIO.succeed(provider.shutdowns.get())
        exit   <- ZIO.scoped(syncBuild(api, provider, Some("b"), 300.millis)).exit
      } yield assertTrue(before == 0, failedWith[TimeoutException](exit), provider.shutdowns.get() == 1)
    },
    test("two siblings that fail together on an API with no owner shut it down once between them") {
      val api                   = OpenFeatureAPI.createIsolated()
      val provider              = new Gated(failure = Some("boom"))
      def build(domain: String) = ZIO.scoped(syncBuild(api, provider, Some(domain), 10.seconds)).exit.fork
      for {
        a <- build("a")
        b <- build("b")
        _ <- Live.live(
          ZIO
            .succeed(
              ManagedProvider.existing(provider).exists(w => (api.getProvider("a") eq w) && (api.getProvider("b") eq w))
            )
            .repeatUntil(identity)
            .timeout(5.seconds)
        )
        _     <- ZIO.succeed(provider.gate.countDown())
        exitA <- a.join
        exitB <- b.join
      } yield assertTrue(exitA.isFailure, exitB.isFailure, provider.shutdowns.get() == 1)
    },
    test("two siblings that both reach their deadline shut it down once, and the API's shutdown adds nothing") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated()
      for {
        count <- ZIO.scoped {
          for {
            a     <- asyncBuild(api, provider, "a", 1.second)
            b     <- asyncBuild(api, provider, "b", 1.second)
            _     <- TestClock.adjust(2.seconds)
            _     <- awaitFatal(a)
            _     <- awaitFatal(b)
            count <- shutdownsAfterSettling(provider)
          } yield count
        }
        _ <- ZIO.succeed(provider.gate.countDown())
        _ <- shutDownApi(api)
      } yield assertTrue(count == 1, provider.shutdowns.get() == 1)
    }
  )

  private val sharedApi = suite("an instance that shares its API")(
    test("shut down on its own, leaves the API and the provider a sibling is bound to") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated(open = true)
      ZIO.scoped {
        for {
          a      <- syncBuild(api, provider, Some("a"), 5.seconds)
          b      <- syncBuild(api, provider, Some("b"), 5.seconds)
          _      <- a.shutdown
          count  <- shutdownsAfterSettling(provider)
          status <- b.providerStatus
          held = ManagedProvider.existing(provider).exists(w => api.getProvider("b") eq w)
        } yield assertTrue(count == 0, status == ProviderStatus.Ready, held)
      }
    },
    test("the owner's shutdown retires it and shuts the provider they share down once") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Gated(open = true)
      ZIO.scoped {
        for {
          owner   <- syncBuild(api, provider, None, 5.seconds, owns = true)
          sibling <- syncBuild(api, provider, Some("a"), 5.seconds)
          _       <- owner.shutdown
          status  <- sibling.providerStatus
          first   <- Live.live(ZIO.succeed(provider.shutdowns.get()).repeatUntil(_ > 0).timeout(5.seconds))
          // A second shutdown would land on the SDK's executor too: give it the same window before counting.
          _     <- Live.live(ZIO.sleep(300.millis))
          count <- ZIO.succeed(provider.shutdowns.get())
        } yield assertTrue(first.isDefined, count == 1, status == ProviderStatus.NotReady)
      }
    }
  )

  // The owner on `ownerDomain` and a sibling on `siblingDomain` both bind `provider`'s wrapper; the owner swaps to
  // `incoming`, whose initialize() outlasts the swap's wait and is then released.
  private def ownerSwapsOffShared(
    siblingDomain: Option[String],
    ownerDomain: Option[String],
    incoming: Gated
  ) = {
    val api          = OpenFeatureAPI.createIsolated()
    val provider     = new Gated(open = true)
    def siblingHolds = ManagedProvider
      .existing(provider)
      .exists(w => siblingDomain.fold(api.getProvider())(api.getProvider(_)) eq w)
    ZIO.scoped {
      for {
        sibling <- syncBuild(api, provider, siblingDomain, 5.seconds)
        owner   <- syncBuild(api, provider, ownerDomain, 300.millis, owns = true)
        shared = siblingHolds
        swap    <- owner.setProvider(incoming).exit
        _       <- ZIO.succeed(incoming.gate.countDown())
        settled <- orphansSettled(owner)
        count   <- shutdownsAfterSettling(provider)
        status  <- sibling.providerStatus
        held = siblingHolds
      } yield assertTrue(shared, swap.isFailure, settled, count == 0, held, status == ProviderStatus.Ready)
    }
  }

  // The same swap with no sibling: nothing else holds the wrapper, so it is still shut down, once.
  private def ownerSwapsOffUnshared(incoming: Gated) = {
    val api      = OpenFeatureAPI.createIsolated()
    val provider = new Gated(open = true)
    ZIO.scoped {
      for {
        owner   <- syncBuild(api, provider, Some("d"), 300.millis, owns = true)
        swap    <- owner.setProvider(incoming).exit
        _       <- ZIO.succeed(incoming.gate.countDown())
        settled <- orphansSettled(owner)
        count   <- shutdownsAfterSettling(provider)
      } yield assertTrue(swap.isFailure, settled, count == 1)
    }
  }

  private val ownerSwapOffShared = suite("an owner's timed-out swap off the wrapper it was built with")(
    test("with no sibling, shuts it down once when the new provider then fails") {
      ownerSwapsOffUnshared(new Gated(failure = Some("boom")))
    },
    test("with no sibling, shuts it down once when the new provider then initializes") {
      ownerSwapsOffUnshared(new Gated())
    },
    test("leaves it when the new provider then fails, the sibling on the default slot") {
      ownerSwapsOffShared(None, Some("d"), new Gated(failure = Some("boom")))
    },
    test("leaves it when the new provider then initializes, the sibling on the default slot") {
      ownerSwapsOffShared(None, Some("d"), new Gated())
    },
    test("leaves it when the new provider then fails, the owner on the default slot") {
      ownerSwapsOffShared(Some("a"), None, new Gated(failure = Some("boom")))
    },
    test("leaves it when the new provider then initializes, the owner on the default slot") {
      ownerSwapsOffShared(Some("a"), None, new Gated())
    }
  )

  def spec =
    suite("ProviderOwnershipShutdownSpec")(
      ownerSwapOffShared,
      sharedApi,
      userRegistered,
      otherDomain,
      sharedWrapper,
      failedSwap,
      swapBehindSibling,
      ownRegistration
    ) @@
      TestAspect.sequential @@ TestAspect.timeout(120.seconds) @@ RegistryRecording.eachTest("ownership")
}
