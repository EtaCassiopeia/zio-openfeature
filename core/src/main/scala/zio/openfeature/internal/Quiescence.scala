package zio.openfeature.internal

import zio._

/** What a test built, for an end-state check after it.
  *
  * Instances and test providers register here when they are made; a test aspect reads the registrations on the test's
  * own fiber once the body has run and asks each for its violations. Registrations follow the fiber tree, so a test
  * sees only what it and the fibers it joined built: an instance built in a fiber that is never joined, or in a layer
  * built outside the test's fiber, is not seen. In production nothing reads them; a registration is one `FiberRef`
  * update per instance built.
  */
private[openfeature] object Quiescence {

  /** Something built during a test whose end state can be checked. */
  trait Observed {

    /** Names it in a failure. */
    def label: String

    /** What is wrong with its end state now; empty when nothing is. */
    def violations: UIO[Chunk[String]]
  }

  /** Everything registered by this fiber and the fibers it joined. A forked fiber starts empty, so a join adds only
    * what that fiber and its own joined children registered, never the parent's entries a second time.
    */
  val observed: FiberRef[Chunk[Observed]] =
    Unsafe.unsafe(implicit u =>
      FiberRef.unsafe.make(Chunk.empty[Observed], _ => Chunk.empty, (parent, child) => parent ++ child)
    )

  def register(o: Observed): UIO[Unit] = observed.update(_ :+ o)
}
