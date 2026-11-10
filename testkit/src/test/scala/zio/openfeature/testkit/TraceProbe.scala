package zio.openfeature.testkit

import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider}
import zio._
import zio.openfeature.internal.{LifecycleProbe, ManagedProvider, ProviderNames}
import zio.openfeature.internal.LifecycleProbe.Point

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters._

/** Records the lifecycle probe points a run passes, one JSON line each, for `formal/lifecycle/validate.py`, which
  * checks the run is a behaviour of the TLA+ lifecycle model. Lines carry a sequence number taken when the point is
  * reached, since several threads record; `begin` marks the moment the instance is built and the run starts.
  */
final class TraceProbe private () extends LifecycleProbe {
  private val seq     = new AtomicLong(0)
  private val records = new ConcurrentLinkedQueue[(Long, String)]()

  def at(point: Point): UIO[Unit] =
    ZIO.succeed(record(point.name, TraceProbe.argument(point), TraceProbe.identityFields(point)))

  /** The instance is built on `initial`: what follows is the run. */
  def begin(initial: OFFeatureProvider): UIO[Unit] =
    ZIO.succeed {
      val who = TraceProbe.Who.of(initial)
      record("Begin", "", List("provider" -> who.name, "providerInstance" -> who.instance))
    }

  /** Write the run to `<dir>/<name>.ndjson`. A failure to write is reported, never thrown. */
  def write(name: String): UIO[Unit] = write(name, TraceProbe.directory)

  private[testkit] def write(name: String, dir: Path): UIO[Unit] =
    ZIO
      .attemptBlocking {
        Files.createDirectories(dir)
        val lines = records.asScala.toList.sortBy(_._1).map(_._2)
        Files.write(dir.resolve(s"$name.ndjson"), lines.asJava, StandardCharsets.UTF_8)
        ()
      }
      .catchAllCause(c => ZIO.logWarningCause(s"could not write the lifecycle trace '$name'", c))

  private def record(point: String, arg: String, extra: List[(String, String)]): Unit = {
    val n      = seq.getAndIncrement()
    val fields = extra.map { case (k, v) => s""","$k":"${TraceProbe.escape(v)}"""" }.mkString
    records.add(n -> s"""{"seq":$n,"point":"$point","arg":"${TraceProbe.escape(arg)}"$fields}""")
    ()
  }
}

object TraceProbe {

  /** Whether runs are recorded: `ZOF_TRACE=1`. */
  val enabled: Boolean = sys.env.get("ZOF_TRACE").contains("1")

  /** Where runs are written: `ZOF_TRACE_DIR`, else `target/traces` under the test's working directory. */
  def directory: Path = Paths.get(sys.env.getOrElse("ZOF_TRACE_DIR", "target/traces"))

  /** A recorder whatever `ZOF_TRACE` says, for testing the recorder itself; `make` is the gated one. */
  private[testkit] def recording: TraceProbe = new TraceProbe()

  /** A recorder when tracing is enabled. */
  def make: UIO[Option[TraceProbe]] = ZIO.succeed(if (enabled) Some(new TraceProbe()) else None)

  /** `probe`, also recording into `trace` when there is one. */
  def alongside(probe: LifecycleProbe, trace: Option[TraceProbe]): LifecycleProbe = trace match {
    case None    => probe
    case Some(t) =>
      new LifecycleProbe {
        def at(point: Point): UIO[Unit] = t.at(point) *> probe.at(point)
      }
  }

  /** A provider a point names: `instance` tells apart providers that share a name, which the SDK's events cannot. */
  final case class Who(name: String, instance: String)

  object Who {
    def of(provider: OFFeatureProvider): Who =
      Who(
        ProviderNames.of(provider),
        Integer.toHexString(java.lang.System.identityHashCode(ManagedProvider.unwrap(provider)))
      )
  }

  private def named(nameKey: String, instanceKey: String, p: OFFeatureProvider): List[(String, String)] = {
    val who = Who.of(p)
    List(nameKey -> who.name, instanceKey -> who.instance)
  }

  private def identityFields(point: Point): List[(String, String)] = point match {
    case Point.SwapStart(p)                    => named("provider", "providerInstance", p)
    case Point.OrphanResolved(previous, watch) =>
      named("provider", "providerInstance", previous) ++ named("watch", "watchInstance", watch)
    case Point.DeliverEvent(_, provider)      => List("provider" -> provider)
    case Point.ProviderShutDown(_, p, caller) => named("provider", "providerInstance", p) :+ ("caller" -> caller)
    case _                                    => Nil
  }

  private def argument(point: Point): String = point match {
    case Point.ApplySignal(signal)          => signal.toString
    case Point.DeliverEvent(event, _)       => event
    case Point.ProviderShutDown(call, _, _) => call.toString
    case Point.ShutdownReturned(call, ok)   => s"$call $ok"
    case _                                  => ""
  }

  private def escape(s: String): String = s.flatMap {
    case '"'          => "\\\""
    case '\\'         => "\\\\"
    case c if c < ' ' => f"\\u${c.toInt}%04x"
    case c            => c.toString
  }
}
