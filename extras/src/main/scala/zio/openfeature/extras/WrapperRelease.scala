package zio.openfeature.extras

import zio._

/** The scope finalizer shared by the wrappers' `scoped` constructors (#451), mirroring OFREP's `releaseOfrep`. */
private[extras] object WrapperRelease {

  def release(name: String, timeout: Duration)(shutdown: => Unit): UIO[Unit] =
    // `.disconnect` because finalizers run uninterruptibly and the timeout must still fire.
    ZIO
      .attemptBlocking(shutdown)
      .disconnect
      .timeout(timeout)
      .foldCauseZIO(
        cause => ZIO.logWarningCause(s"$name shutdown failed", cause),
        {
          case Some(_) => ZIO.unit
          case None    => ZIO.logWarning(s"$name shutdown did not finish within ${timeout.toMillis}ms")
        }
      )
}
