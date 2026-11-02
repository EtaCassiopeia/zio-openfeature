package zio.openfeature.testsupport

import zio._
import zio.openfeature.internal.{Quiescence => Registry}
import zio.test._

/** End-state checks after each test of a lifecycle spec: `@@ Quiescence.aspect`.
  *
  * Around each test it takes a [[LeakCheck]] baseline, runs the test, closes nothing itself, and then fails the test
  * if, within a bound:
  *
  *   1. a root fiber or a non-daemon thread the test started is still alive;
  *   1. an instance the test built still counts an abandoned provider call; or, once closed or shut down, still has its
  *      event bridge on the client, an orphan left, a registered wrapper shut down in its cycle whose event lane still
  *      runs, or, once shut down, a status other than `NotReady`;
  *   1. a `TestFeatureProvider` or `FaultyProvider` the test made had `shutdown()` reach it twice in one initialization
  *      cycle.
  *
  * Two skips, so a spec that shares a layer or ends a test mid-swap is not failed for a state that is legitimate: the
  * bridge and lane checks apply only to an instance that is closed or shut down, and on a live instance the orphan
  * check runs only while no swap or shutdown holds its lock.
  *
  * What is checked is what the test registered on its own fiber and the fibers it joined (see
  * `zio.openfeature.internal.Quiescence`): an instance built in a fiber never joined, or in a layer built outside the
  * test's fiber, is not seen. Resources held by the test's own `Scope` are still open when the checks run, so a test
  * closes what it builds in a `ZIO.scoped` of its own. The aspect makes the spec sequential, since the leak baseline is
  * the whole JVM's.
  */
object Quiescence {

  /** How long the end state may take to settle once the test has returned: shutdowns run on the SDK's executor. */
  val Within: Duration = 5.seconds

  private val checks: TestAspectPoly = new TestAspect.PerTest.Poly {
    def perTest[R, E](
      test: ZIO[R, TestFailure[E], TestSuccess]
    )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] =
      LeakCheck.snapshot.flatMap { baseline =>
        Registry.observed.locally(Chunk.empty) {
          test.flatMap { success =>
            for {
              observed <- Registry.observed.get
              found    <- settle(distinct(observed))
              leaks    <- Live.live(LeakCheck.awaitNoLeaks(baseline, Within))
              problems = found ++ leaks
              result <-
                if (problems.isEmpty) ZIO.succeed(success)
                else ZIO.fail(TestFailure.Assertion(assertTrue(problems.isEmpty) ?? problems.mkString("; ")))
            } yield result
          }
        }
      }
  }

  // After `checks`, which it composes: an object's vals initialize in order.
  val aspect: TestAspectPoly = TestAspect.sequential >>> checks

  // One object can register twice, if it is built twice over the same instance; it is checked once.
  private def distinct(observed: Chunk[Registry.Observed]): Chunk[Registry.Observed] =
    observed.foldLeft(Chunk.empty[Registry.Observed])((seen, o) => if (seen.exists(_ eq o)) seen else seen :+ o)

  // The violations still present once `Within` has passed, or none as soon as there are none. A plain deadline loop,
  // as in LeakCheck: `timeout` forks root fibers the leak check would report.
  private def settle(observed: Chunk[Registry.Observed]): UIO[List[String]] = {
    val current = ZIO.foreach(observed)(o => o.violations.map(_.map(v => s"${o.label}: $v"))).map(_.flatten.toList)
    def loop(deadline: Long): UIO[List[String]] =
      current.flatMap { found =>
        if (found.isEmpty) ZIO.succeed(Nil)
        else Clock.nanoTime.flatMap(now => if (now >= deadline) ZIO.succeed(found) else loop(deadline).delay(50.millis))
      }
    Live.live(Clock.nanoTime).flatMap(now => Live.live(loop(now + Within.toNanos)))
  }
}
