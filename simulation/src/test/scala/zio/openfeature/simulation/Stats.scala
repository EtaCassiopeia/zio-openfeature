package zio.openfeature.simulation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import scala.jdk.CollectionConverters._

import zio._

/** Coverage counters for a simulation run, so a passing run can show what it actually exercised. */
object Stats {
  private val counters = new ConcurrentHashMap[String, LongAdder]()

  def hit(name: String): Unit = counters.computeIfAbsent(name, _ => new LongAdder).increment()

  def add(name: String, n: Long): Unit = counters.computeIfAbsent(name, _ => new LongAdder).add(n)

  /** Registers counters at 0 without hitting them, so one never reached still shows as `=0`. */
  def declare(names: Iterable[String]): Unit = names.foreach(n => counters.computeIfAbsent(n, _ => new LongAdder): Unit)

  def get(name: String): Long = Option(counters.get(name)).fold(0L)(_.sum)

  def render: String =
    counters.asScala.toList.sortBy(_._1).map { case (k, v) => s"$k=${v.sum}" }.mkString("coverage: ", " ", "")

  /** Set while the regression corpus replays. */
  val replaying: FiberRef[Boolean] = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(false))

  /** Hits a counter that measures the generator: under `replay.` while the corpus replays, so a coverage floor counts
    * generated cases only.
    */
  def generated(name: String): UIO[Unit] = replaying.get.map(r => hit(if (r) s"replay.$name" else name))
}
