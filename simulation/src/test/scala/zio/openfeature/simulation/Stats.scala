package zio.openfeature.simulation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import scala.jdk.CollectionConverters._

/** Coverage counters for a simulation run, so a passing run can show what it actually exercised. */
object Stats {
  private val counters = new ConcurrentHashMap[String, LongAdder]()

  def hit(name: String): Unit = counters.computeIfAbsent(name, _ => new LongAdder).increment()

  /** Registers counters at 0 without hitting them, so one never reached still shows as `=0`. */
  def declare(names: Iterable[String]): Unit = names.foreach(n => counters.computeIfAbsent(n, _ => new LongAdder): Unit)

  def render: String =
    counters.asScala.toList.sortBy(_._1).map { case (k, v) => s"$k=${v.sum}" }.mkString("coverage: ", " ", "")
}
