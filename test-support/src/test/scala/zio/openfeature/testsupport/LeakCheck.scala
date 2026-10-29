package zio.openfeature.testsupport

import scala.jdk.CollectionConverters._

import zio._

/** Leaks a test leaves behind: root fibers and live non-daemon threads that were not there when it took its baseline.
  */
object LeakCheck {

  final case class Baseline(fibers: Set[FiberId], threads: Set[Thread])

  private def liveNonDaemonThreads: UIO[Set[Thread]] =
    ZIO.succeed(Thread.getAllStackTraces.keySet.asScala.filter(t => t.isAlive && !t.isDaemon).toSet)

  private def liveRoots: UIO[Chunk[Fiber.Runtime[_, _]]] =
    Fiber.roots.flatMap(ZIO.filter(_)(_.status.map(_ != Fiber.Status.Done)))

  def snapshot: UIO[Baseline] =
    for {
      roots   <- liveRoots
      threads <- liveNonDaemonThreads
    } yield Baseline(roots.map(_.id).toSet, threads)

  private def leaks(baseline: Baseline): UIO[List[String]] =
    for {
      roots   <- liveRoots
      threads <- liveNonDaemonThreads
    } yield roots.filterNot(f => baseline.fibers(f.id)).map(f => s"leaked fiber ${f.id.threadName}").toList ++
      (threads -- baseline.threads).map(t => s"leaked non-daemon thread '${t.getName}'").toList

  /** The leaks still present once `within` has passed, or none as soon as there are none. */
  def awaitNoLeaks(baseline: Baseline, within: Duration): UIO[List[String]] = {
    // A plain deadline loop: `timeout` races in forked root fibers, which the check itself would report.
    def loop(deadline: Long): UIO[List[String]] =
      leaks(baseline).flatMap { found =>
        if (found.isEmpty) ZIO.succeed(Nil)
        else Clock.nanoTime.flatMap(now => if (now >= deadline) ZIO.succeed(found) else loop(deadline).delay(50.millis))
      }
    Clock.nanoTime.flatMap(now => loop(now + within.toNanos))
  }
}
