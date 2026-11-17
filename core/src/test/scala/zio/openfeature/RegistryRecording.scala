package zio.openfeature

import dev.openfeature.sdk.FeatureProvider
import zio._
import zio.openfeature.internal.LifecycleProbe
import zio.openfeature.testkit.TraceProbe
import zio.test.{TestAspect, TestFailure, TestSuccess}

import java.util.concurrent.atomic.AtomicInteger

/** Records the runs of several instances on one API for `formal/registry/validate.py` when `ZOF_TRACE=1`, under
  * `registry/` in the trace directory: each test of a spec under [[eachTest]] is one run. A build in the test takes its
  * probe from [[instanceProbe]] (or a registry from [[registryProbe]]), which tags its points with the instance, and a
  * step the test takes on the API itself, outside the library, is recorded with [[environment]]. With tracing off the
  * probes are `LifecycleProbe.none` and nothing is recorded.
  */
private[openfeature] object RegistryRecording {

  final private class Run(val trace: TraceProbe) {
    private val built        = new AtomicInteger(0)
    def next: LifecycleProbe = trace.forInstance(s"b${built.incrementAndGet()}")
  }

  private val current: FiberRef[Option[Run]] = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(Option.empty[Run]))

  /** Record each test as `<name>-<id>`, written once it ends, passed or not, after the SDK's queued shutdowns land; a
    * test that recorded nothing (one that builds no instance) is not written.
    */
  def eachTest(name: String): TestAspect[Nothing, Any, Nothing, Any] =
    new TestAspect.PerTest[Nothing, Any, Nothing, Any] {
      def perTest[R, E](test: ZIO[R, TestFailure[E], TestSuccess])(implicit
        trace: Trace
      ): ZIO[R, TestFailure[E], TestSuccess] =
        TraceProbe.make.flatMap {
          case None    => test
          case Some(t) =>
            current
              .locally(Some(new Run(t)))(test)
              .ensuring(
                settled(t) *> ZIO.when(t.size > 0)(
                  t.write(s"$name-${java.util.UUID.randomUUID().toString.take(8)}", directory)
                )
              )
        }
    }

  /** The probe of the next instance the test builds, alongside `probe`. */
  def instanceProbe(probe: LifecycleProbe = LifecycleProbe.none): UIO[LifecycleProbe] =
    current.get.map {
      case None      => probe
      case Some(run) =>
        val tagged = run.next
        new LifecycleProbe {
          def at(point: LifecycleProbe.Point): UIO[Unit] = tagged.at(point) *> probe.at(point)
        }
    }

  /** A registry's probes: one instance per domain, tagged with the domain, the default client's `default`. */
  def registryProbe: UIO[Option[String] => LifecycleProbe] =
    current.get.map {
      case None      => _ => LifecycleProbe.none
      case Some(run) => domain => run.trace.forInstance(domain.fold("default")("domain:" + _))
    }

  /** A step the test took on the API itself (see [[TraceProbe.environment]]). */
  def environment(step: String, domain: Option[String] = None, provider: Option[FeatureProvider] = None): UIO[Unit] =
    current.get.flatMap(ZIO.foreachDiscard(_)(_.trace.environment(step, domain, provider)))

  private def directory = TraceProbe.directory.resolve("registry")

  // The SDK shuts a replaced provider, and an API's providers, down on its own executor: wait until the run records
  // nothing new for a while, bounded.
  private def settled(trace: TraceProbe): UIO[Unit] = {
    def quiet(seen: Int, still: Int, left: Int): UIO[Unit] =
      if (still >= 4 || left <= 0) ZIO.unit
      else
        ZIO.sleep(50.millis) *> ZIO.suspendSucceed {
          val now = trace.size
          quiet(now, if (now == seen) still + 1 else 0, left - 1)
        }
    quiet(trace.size, 0, 60).withClock(Clock.ClockLive)
  }
}
