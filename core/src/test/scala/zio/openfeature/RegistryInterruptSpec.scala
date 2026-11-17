package zio.openfeature

import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, NoOpProvider}
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
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(10.minutes)
}
