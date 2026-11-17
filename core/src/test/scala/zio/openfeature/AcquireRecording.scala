package zio.openfeature

import dev.openfeature.sdk.FeatureProvider
import zio._
import zio.openfeature.internal.{FeatureFlagsFactories, LifecycleProbe}
import zio.openfeature.testkit.{TraceProbe, TraceRecording}

/** Records one `fromAcquireAsync` run for `formal/acquire/validate.py` when `ZOF_TRACE=1`, under `acquire/` in the
  * trace directory. The body builds one layer with `run.fromAcquireAsync`, which records the run's start, and calls
  * `run.end` once with what it observed after the layer's release. With tracing off nothing is recorded.
  */
private[openfeature] object AcquireRecording {

  final class Run private[AcquireRecording] (trace: Option[TraceProbe], userShuts: Boolean) {
    private val probe: LifecycleProbe = TraceRecording.probe(trace)

    def fromAcquireAsync(
      acquire: RIO[Scope, FeatureProvider],
      fallback: URIO[Scope, FeatureProvider],
      config: FeatureFlagsConfig = FeatureFlagsConfig.default,
      acquireConfig: AcquireConfig = AcquireConfig.default
    ): ULayer[FeatureFlags with FeatureFlagsAdmin with AcquireStatus] =
      ZLayer.fromZIO(
        ZIO.foreachDiscard(trace)(_.beginAcquire(FeatureFlagsFactories.ownsApiOf(config), userShuts))
      ) >>> FeatureFlagsFactories.fromAcquireAsync(acquire, fallback, config, acquireConfig, probe)

    /** The run is over: `shuts` are the `shutdown()` counts of the providers it counted, `stack` that of the composed
      * stack, and `trail` the order of the shutdowns and of the close of a resource `acquire` registered (or `closed`,
      * whether that resource closed).
      */
    def end(
      shuts: List[(FeatureProvider, Int)] = Nil,
      stack: Option[Int] = None,
      trail: Option[List[String]] = None,
      closed: Option[Boolean] = None
    ): UIO[Unit] = {
      val closedAt = trail.map(_.indexOf("resource.closed"))
      ZIO.foreachDiscard(trace)(
        _.endAcquire(
          shuts,
          stack,
          closedAt.map(_ >= 0).orElse(closed),
          trail.zip(closedAt).map { case (t, at) => at >= 0 && t.drop(at + 1).exists(_.endsWith(".shutdown")) }
        )
      )
    }
  }

  /** Run `body`, then write the run as `<name>-<id>`. A run whose body fails is not written. */
  def recorded[E, A](name: String, userShuts: Boolean = false)(body: Run => IO[E, A]): IO[E, A] =
    for {
      trace <- TraceProbe.make
      a     <- body(new Run(trace, userShuts))
      id = java.util.UUID.randomUUID().toString.take(8)
      _ <- ZIO.foreachDiscard(trace)(_.write(s"$name-$id", TraceProbe.directory.resolve("acquire")))
    } yield a
}
