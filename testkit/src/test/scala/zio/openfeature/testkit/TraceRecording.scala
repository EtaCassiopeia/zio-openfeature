package zio.openfeature.testkit

import zio._
import zio.openfeature.internal.LifecycleProbe

/** Records one run of a lifecycle scenario for trace validation (`formal/lifecycle/validate.py`) when `ZOF_TRACE=1`.
  *
  * The body builds one instance with `probe = TraceRecording.probe(trace)` (or `TraceProbe.alongside` with its own
  * probe), calls `begin` on the trace once that instance is built, and must not build a second instance on the same
  * API: the checker follows one instance. With tracing off the body gets `None`, and this is `ZIO.scoped`.
  */
private[testkit] object TraceRecording {

  /** Run `body` in its own scope, close the scope, then write the run as `<name>-<id>`. A run whose body fails is not
    * written: it never reached its teardown.
    */
  def recorded[E, A](name: String)(body: Option[TraceProbe] => ZIO[Scope, E, A]): IO[E, A] =
    for {
      trace <- TraceProbe.make
      a     <- ZIO.scoped(body(trace))
      _     <- ZIO.foreachDiscard(trace)(_.write(s"$name-${java.util.UUID.randomUUID().toString.take(8)}"))
    } yield a

  /** The probe for a build that only records. */
  def probe(trace: Option[TraceProbe]): LifecycleProbe = TraceProbe.alongside(LifecycleProbe.none, trace)
}
