package zio.openfeature.testkit

import zio._
import zio.openfeature.internal.LifecycleProbe
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

  def at(point: Point): UIO[Unit] = ZIO.succeed(record(point.name, TraceProbe.argument(point)))

  /** The instance is built: what follows is the run. */
  def begin: UIO[Unit] = ZIO.succeed(record("Begin", ""))

  /** Write the run to `<dir>/<name>.ndjson`. A failure to write is reported, never thrown. */
  def write(name: String): UIO[Unit] =
    ZIO
      .attemptBlocking {
        val dir = TraceProbe.directory
        Files.createDirectories(dir)
        val lines = records.asScala.toList.sortBy(_._1).map(_._2)
        Files.write(dir.resolve(s"$name.ndjson"), lines.asJava, StandardCharsets.UTF_8)
        ()
      }
      .catchAllCause(c => ZIO.logWarningCause(s"could not write the lifecycle trace '$name'", c))

  private def record(point: String, arg: String): Unit = {
    val n = seq.getAndIncrement()
    records.add(n -> s"""{"seq":$n,"point":"$point","arg":"${TraceProbe.escape(arg)}"}""")
    ()
  }
}

object TraceProbe {

  /** Whether runs are recorded: `ZOF_TRACE=1`. */
  val enabled: Boolean = sys.env.get("ZOF_TRACE").contains("1")

  /** Where runs are written: `ZOF_TRACE_DIR`, else `target/traces` under the test's working directory. */
  def directory: Path = Paths.get(sys.env.getOrElse("ZOF_TRACE_DIR", "target/traces"))

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

  private def argument(point: Point): String = point match {
    case Point.ApplySignal(signal) => signal.toString
    case Point.DeliverEvent(event) => event
    case _                         => ""
  }

  private def escape(s: String): String = s.flatMap {
    case '"'          => "\\\""
    case '\\'         => "\\\\"
    case c if c < ' ' => f"\\u${c.toInt}%04x"
    case c            => c.toString
  }
}
