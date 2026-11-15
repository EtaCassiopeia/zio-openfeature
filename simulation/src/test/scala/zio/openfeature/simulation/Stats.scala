package zio.openfeature.simulation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import scala.jdk.CollectionConverters._

/** Coverage counters for a simulation run, so a passing run can show what it actually exercised. */
object Stats {
  private val counters = new ConcurrentHashMap[String, LongAdder]()

  def hit(name: String): Unit = counters.computeIfAbsent(name, _ => new LongAdder).increment()

  def render: String =
    counters.asScala.toList.sortBy(_._1).map { case (k, v) => s"$k=${v.sum}" }.mkString("coverage: ", " ", "")
}
