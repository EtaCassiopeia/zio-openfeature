package zio.openfeature.internal

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

import zio._
import zio.openfeature.testsupport.LeakCheck
import zio.test._

object TimeoutsSpec extends ZIOSpecDefault {

  private val Bound = 100.millis

  private def elapsedOf[R, E, A](effect: ZIO[R, E, A]): ZIO[R, E, (A, Duration)] =
    for {
      start <- Clock.nanoTime
      a     <- effect
      end   <- Clock.nanoTime
    } yield (a, Duration.fromNanos(end - start))

  // A scope whose finalizer runs `effect` through `boundedFinalizer`, closed at once: the time the close took.
  private def closeTime[A](effect: Task[A], bound: Duration, onTimeout: UIO[Any] = ZIO.unit): UIO[Duration] =
    elapsedOf(ZIO.scoped(ZIO.addFinalizer(Timeouts.boundedFinalizer(effect, bound)(onTimeout)))).map(_._2)

  def spec = suite("TimeoutsSpec")(
    suite("boundedFinalizer")(
      test("a shutdown that never returns does not hang the scope close past the bound, and leaves nothing behind") {
        val latch = new CountDownLatch(1)
        for {
          baseline <- LeakCheck.snapshot
          timedOut <- Promise.make[Nothing, Unit]
          took     <- closeTime(ZIO.attemptBlocking(latch.await()), Bound, timedOut.succeed(()))
          signal   <- timedOut.isDone
          _        <- ZIO.succeed(latch.countDown())
          leaks    <- LeakCheck.awaitNoLeaks(baseline, 5.seconds)
        } yield assertTrue(took < 2.seconds, signal, leaks.isEmpty)
      },
      test("a fast shutdown does not wait for the bound") {
        val ran = new AtomicBoolean(false)
        for {
          took <- closeTime(ZIO.attemptBlocking(ran.set(true)), 10.seconds)
        } yield assertTrue(took < 5.seconds, ran.get)
      },
      test("its value is returned, a failure is None, and a timeout is None after the callback") {
        val latch = new CountDownLatch(1)
        for {
          value    <- Timeouts.boundedFinalizer(ZIO.succeed(42), Bound)(ZIO.unit).uninterruptible
          failed   <- Timeouts.boundedFinalizer(ZIO.fail(new RuntimeException("boom")), Bound)(ZIO.unit).uninterruptible
          timedOut <- Ref.make(false)
          expired  <- Timeouts
            .boundedFinalizer(ZIO.attemptBlocking(latch.await(10, TimeUnit.SECONDS)), Bound)(timedOut.set(true))
            .uninterruptible
          signal <- timedOut.get
          _      <- ZIO.succeed(latch.countDown())
        } yield assertTrue(value.contains(42), failed.isEmpty, expired.isEmpty, signal)
      },
      test("it runs even from a finalizer whose fiber was interrupted") {
        val ran = new AtomicBoolean(false)
        for {
          started <- Promise.make[Nothing, Unit]
          fiber   <- ZIO
            .scoped(
              ZIO.addFinalizer(Timeouts.boundedFinalizer(ZIO.attemptBlocking(ran.set(true)), 5.seconds)(ZIO.unit)) *>
                started.succeed(()) *> ZIO.never
            )
            .fork
          _ <- started.await
          _ <- fiber.interrupt
        } yield assertTrue(ran.get)
      }
    ),
    suite("failAfter")(
      test("interrupted mid-wait, it leaves no timer fiber behind") {
        for {
          baseline <- LeakCheck.snapshot
          started  <- Promise.make[Nothing, Unit]
          fiber    <- Timeouts
            .failAfter(started.succeed(()) *> ZIO.never, 30.seconds, new RuntimeException("timed out"))
            .fork
          _     <- started.await
          _     <- fiber.interrupt
          leaks <- LeakCheck.awaitNoLeaks(baseline, 5.seconds)
        } yield assertTrue(leaks.isEmpty)
      }
    )
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds) @@ TestAspect.sequential
}
