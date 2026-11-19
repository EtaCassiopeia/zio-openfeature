package zio

/** Runs effects outside the test's fiber supervision.
  *
  * `TestClock.adjust` first waits until every fiber the test supervises is suspended. A fiber blocked in a provider
  * call (a leaf the simulation stalls on purpose) is running, not suspended, so adjusting the clock to time that call
  * out would wait for it forever. Fibers forked under [[unsupervised]] are not tracked, so the clock can move while
  * they block. They still see the test's clock and every other fiber-local value.
  */
object SimulationSupervision {

  def unsupervised[R, E, A](effect: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    FiberRef.currentSupervisor.locally(Supervisor.none)(effect)
}
