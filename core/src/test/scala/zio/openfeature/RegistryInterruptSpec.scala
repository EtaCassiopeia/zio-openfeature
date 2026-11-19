package zio.openfeature

import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, NoOpProvider, OpenFeatureAPI}
import zio._
import zio.test._

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

object RegistryInterruptSpec extends ZIOSpecDefault {

  // A busy wait, so the interrupt lands at a different point of each call; a sleep would park the fiber instead.
  private def spin(nanos: Long): UIO[Unit] = ZIO.succeed {
    val end = java.lang.System.nanoTime() + nanos
    while (java.lang.System.nanoTime() < end) ()
  }

  private def registry: ZIO[Scope, Throwable, FeatureFlagRegistry] =
    FeatureFlagRegistry.fromProvider(new NoOpProvider).build.map(_.get[FeatureFlagRegistry])

  // The delay before the interrupt of attempt `i`: 0 to 50 microseconds in steps of 250 nanoseconds, which covers the
  // start of a first call, where the lookup, the insert and the fork happen.
  private def delay(i: Int): Long = (i % 200).toLong * 250L

  // Interrupts `first` on a fresh registry after a different delay each time, then runs `next` on it: the number of
  // registries on which `next` did not end within a bound far above a healthy call's milliseconds.
  private def wedgedAfterInterrupt(
    first: FeatureFlagRegistry => IO[FeatureFlagError, Any],
    next: FeatureFlagRegistry => IO[FeatureFlagError, Any]
  ): UIO[Int] =
    ZIO
      .foreach((0 until 400).toList) { i =>
        ZIO.scoped {
          for {
            r      <- registry.orDie
            _      <- first(r).fork.flatMap(f => spin(delay(i)) *> f.interrupt)
            result <- next(r).either.timeout(5.seconds)
          } yield result.isEmpty
        }
      }
      .map(_.count(identity))

  // Blocks initialize() until released, and counts the calls.
  final private class GatedProvider extends NoOpProvider {
    val started                                             = new CountDownLatch(1)
    val gate                                                = new CountDownLatch(1)
    val inits                                               = new AtomicInteger(0)
    override def initialize(ctx: OFEvaluationContext): Unit = {
      inits.incrementAndGet()
      started.countDown()
      gate.await(30, TimeUnit.SECONDS)
      ()
    }
  }

  // A registry closes while a getClient retry with another provider is initializing; that one then starts, or fails.
  // Returns how often the first, failed, provider was shut down, and whether another API can then build it. Not in a
  // suite the registry's trace check records: a run that builds on a second API is outside that model.
  private def firstAfterCloseDuringRetry(secondFails: Boolean, settleFirst: Boolean = false): UIO[(Int, Boolean)] = {
    val firstInits = new AtomicInteger(0)
    val entered    = new CountDownLatch(1)
    val release    = new CountDownLatch(1)
    // Fails its first initialize() only, so another API can build it afterwards.
    val first = new SharedApiFixtures.Gated(open = true) {
      override def initialize(ctx: OFEvaluationContext): Unit =
        if (firstInits.incrementAndGet() == 1) throw new RuntimeException("first failed to start")
    }
    // Waits until the registry has closed, or the SDK interrupts it at the API's shutdown.
    val second = new SharedApiFixtures.Gated(open = true) {
      override def initialize(ctx: OFEvaluationContext): Unit = {
        entered.countDown()
        try release.await()
        catch { case _: InterruptedException => () }
        if (secondFails) throw new RuntimeException("second failed to start")
      }
    }
    val reuse = ZIO.scoped(
      FeatureFlags.build(
        first,
        domain = None,
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = true,
        apiOverride = Some(OpenFeatureAPI.createIsolated()),
        initTimeout = 5.seconds
      )
    )
    for {
      _ <- ZIO.scoped {
        for {
          r     <- registry
          _     <- r.setProvider("d", first)
          _     <- r.getClient("d").either
          _     <- r.setProvider("d", second)
          retry <- r.getClient("d").exit.fork
          _     <- ZIO.attemptBlocking(entered.await())
          // Or let the retry settle while the registry is open: its close then has nothing left to release.
          _ <- ZIO.when(settleFirst)(ZIO.succeed(release.countDown()) *> retry.join)
        } yield ()
      }.orDie
      _      <- ZIO.succeed(release.countDown())
      _      <- ZIO.succeed(first.shutdowns.get()).repeatUntil(_ >= 1).timeout(5.seconds)
      _      <- ZIO.sleep(200.millis) // room for a second shutdown, were one coming
      shut   <- ZIO.succeed(first.shutdowns.get())
      reused <- reuse.exit
    } yield (shut, reused.isSuccess)
  }

  def spec = suite("interrupting a registry caller")(
    test("a getClient interrupted at any point leaves its domain usable") {
      wedgedAfterInterrupt(_.getClient("d"), _.getClient("d")).map(wedged => assertTrue(wedged == 0))
    },
    test("a defaultClient interrupted at any point leaves the default client usable") {
      wedgedAfterInterrupt(_.defaultClient, _.defaultClient).map(wedged => assertTrue(wedged == 0))
    },
    test("setProvider completes on a domain whose first getClient was interrupted") {
      wedgedAfterInterrupt(_.getClient("d"), _.setProvider("d", new NoOpProvider)).map(wedged =>
        assertTrue(wedged == 0)
      )
    },
    test("interrupting the caller that started a build leaves the build to the callers still waiting") {
      val provider = new GatedProvider
      ZIO.scoped {
        for {
          r      <- registry
          _      <- r.setProvider("gated", provider)
          first  <- r.getClient("gated").fork
          _      <- ZIO.attemptBlocking(provider.started.await(10, TimeUnit.SECONDS)).orDie
          _      <- first.interrupt
          second <- r.getClient("gated").fork
          _      <- ZIO.succeed(provider.gate.countDown())
          client <- second.join.timeout(10.seconds)
          status <- ZIO.foreach(client)(_.providerStatus)
        } yield assertTrue(client.isDefined, status.contains(ProviderStatus.Ready), provider.inits.get() == 1)
      }
    },
    test("closing the registry interrupts a build in flight and its waiter") {
      val provider = new GatedProvider
      for {
        waiter <- Promise.make[Nothing, Fiber[FeatureFlagError, FeatureFlags]]
        closed <- ZIO
          .scoped {
            for {
              r <- registry
              _ <- r.setProvider("gated", provider)
              f <- r.getClient("gated").forkDaemon
              _ <- waiter.succeed(f)
              _ <- ZIO.attemptBlocking(provider.started.await(10, TimeUnit.SECONDS)).orDie
            } yield ()
          }
          .timeout(5.seconds)
        _    <- ZIO.succeed(provider.gate.countDown())
        f    <- waiter.await
        exit <- f.await.timeout(5.seconds)
      } yield assertTrue(closed.isDefined, exit.exists(!_.isSuccess))
    },
    test("a close during a retry with another provider shuts the provider it replaced down once, when that one fails") {
      firstAfterCloseDuringRetry(secondFails = true).map { case (shut, reused) => assertTrue(shut == 1, reused) }
    },
    test("a close during a retry with another provider shuts the provider it replaced down once, when that one starts") {
      firstAfterCloseDuringRetry(secondFails = false).map { case (shut, reused) => assertTrue(shut == 1, reused) }
    },
    test("a close after a retry with another provider started shuts the provider it replaced down once") {
      firstAfterCloseDuringRetry(secondFails = false, settleFirst = true).map { case (shut, reused) =>
        assertTrue(shut == 1, reused)
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(10.minutes)
}
