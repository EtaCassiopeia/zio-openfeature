package zio.openfeature.extras

import zio._
import zio.openfeature.internal.{ProviderCalls, Timeouts}

/** The scope finalizer shared by the wrappers' `scoped` constructors, mirroring OFREP's `releaseOfrep`. */
private[extras] object WrapperRelease {

  def release(name: String, timeout: Duration)(shutdown: => Unit): UIO[Unit] =
    Timeouts
      .boundedFinalizer(
        ProviderCalls.blocking(shutdown).tapErrorCause(cause => ZIO.logWarningCause(s"$name shutdown failed", cause)),
        timeout
      )(ZIO.logWarning(s"$name shutdown did not finish within ${timeout.toMillis}ms"))
      .unit
}
