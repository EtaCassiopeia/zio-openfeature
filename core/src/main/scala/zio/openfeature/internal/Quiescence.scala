package zio.openfeature.internal

import zio._

/** What a test built, for an end-state check after it.
  *
  * A test aspect opens a collector for the test with [[collecting]]; instances and test providers made while it is open
  * register in it. The collector is inherited by every fiber the test forks, joined or not, so a registration made on
  * the fiber ZIO Test runs the body on (a daemon fiber whose `FiberRef` changes never reach the aspect) is still seen.
  * An instance built in a layer built outside the test's fiber is not seen. In production no collector is open and a
  * registration is one `FiberRef` read.
  */
private[openfeature] object Quiescence {

  /** Something built during a test whose end state can be checked. */
  trait Observed {

    /** Names it in a failure. */
    def label: String

    /** What is wrong with its end state now; empty when nothing is. */
    def violations: UIO[Chunk[String]]
  }

  // The open collector, if any. A forked fiber gets its parent's, so registrations from any fiber of the test land in
  // the one the aspect reads.
  private val collector: FiberRef[Option[Ref[Chunk[Observed]]]] =
    Unsafe.unsafe(implicit u => FiberRef.unsafe.make(Option.empty[Ref[Chunk[Observed]]]))

  def register(o: Observed): UIO[Unit] =
    collector.get.flatMap(ZIO.foreachDiscard(_)(_.update(_ :+ o)))

  /** Runs `effect` with a fresh collector open, and passes what was registered while it ran to `after`. */
  def collecting[R, E, A, B](effect: ZIO[R, E, A])(after: (A, Chunk[Observed]) => ZIO[R, E, B]): ZIO[R, E, B] =
    Ref.make(Chunk.empty[Observed]).flatMap { ref =>
      collector.locally(Some(ref))(effect).flatMap(a => ref.get.flatMap(after(a, _)))
    }
}
