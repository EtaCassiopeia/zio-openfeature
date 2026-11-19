package zio.openfeature.simulation

import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import java.util.concurrent.atomic.AtomicLong

import scala.jdk.CollectionConverters._

import zio._
import zio.openfeature.internal.{EvaluationProbe, LifecycleProbe}

/** A seeded schedule perturber for the concurrent simulation. At every lifecycle and evaluation probe point it passes,
  * yields a few times, or sleeps a little live time.
  *
  * A decision is a pure function of the seed, the point's name and how many times that point was reached before, so a
  * point's k-th visit gets the same decision under the same seed whatever order the threads arrive in. Each run takes a
  * pass rate from the seed, so some runs stay nearly unperturbed and others are aggressive. Sleeps use the live clock
  * (some points run on SDK threads, outside the test's runtime) and share a per-run budget; once it is spent a sleep
  * becomes a single yield.
  *
  * Every non-pass decision is logged, with the harness's own events (`note`) on the same tick counter, so a failure
  * shows what the schedule did next to what the harness did.
  */
final class SchedulePerturber(seed: Long, onLifecycle: LifecycleProbe.Point => UIO[Unit]) {
  import SchedulePerturber._

  private val hits  = new ConcurrentHashMap[String, AtomicLong]()
  private val tick  = new AtomicLong(0L)
  private val slept = new AtomicLong(0L)
  private val log   = new ConcurrentLinkedQueue[String]()

  /** The percentage of probe visits that pass unperturbed in this run. */
  val passRate: Int = PassRates(((mix(seed) >>> 1) % PassRates.size).toInt)

  private def decide(name: String): Decision = {
    val n = hits.computeIfAbsent(name, _ => new AtomicLong).getAndIncrement()
    val h = mix(seed ^ (name.hashCode.toLong * 0x9e3779b97f4a7c15L) ^ ((n + 1) * 0xbf58476d1ce4e5b9L))
    val r = ((h >>> 1) % 100).toInt
    if (r < passRate) Decision.Pass
    else if (r < passRate + (100 - passRate) / 2) Decision.Yield(1 + ((h >>> 12) % MaxYields).toInt)
    else {
      val us = MinSleepMicros + (h >>> 20) % (MaxSleepMicros - MinSleepMicros)
      if (slept.addAndGet(us) > SleepBudgetMicros) Decision.Yield(1) else Decision.Sleep(us)
    }
  }

  private def record(entry: String): Unit = log.add(s"t${tick.incrementAndGet()} $entry"): Unit

  private def perturb(name: String): UIO[Unit] =
    ZIO.suspendSucceed {
      Stats.hit(s"probe.$name")
      decide(name) match {
        case Decision.Pass     => ZIO.unit
        case Decision.Yield(k) =>
          record(s"$name -> yield $k")
          ZIO.yieldNow.repeatN(k - 1)
        case Decision.Sleep(us) =>
          record(s"$name -> sleep ${us}us")
          ZIO.sleep(Duration.fromNanos(us * 1000)).withClock(Clock.ClockLive)
      }
    }

  /** Stamps a harness event into the decision log. */
  def note(event: String): UIO[Unit] = ZIO.succeed(record(event))

  /** The seed, the pass rate and the last `last` log entries, for a failure message. */
  def render(last: Int): String = {
    val all = log.asScala.toVector
    s"schedule seed $seed (pass $passRate%), ${all.size} decision log entries, last ${all.size min last}:\n" +
      all.takeRight(last).mkString("\n")
  }

  val lifecycle: LifecycleProbe = new LifecycleProbe {
    def at(point: LifecycleProbe.Point): UIO[Unit] = onLifecycle(point) *> perturb(point.name)
  }

  val evaluation: EvaluationProbe = new EvaluationProbe {
    def at(point: EvaluationProbe.Point): UIO[Unit] = perturb(point.name)
  }
}

object SchedulePerturber {

  private val PassRates         = Vector(95, 80, 60, 40)
  private val MaxYields         = 8L
  private val MinSleepMicros    = 50L
  private val MaxSleepMicros    = 2000L
  private val SleepBudgetMicros = 300000L

  /** The lifecycle points a run whose leaves always initialize reaches; with every evaluation point, they are declared
    * to `Stats`, so one the simulation stops reaching shows as `=0`.
    */
  val reachedLifecyclePoints: List[String] = List(
    "SwapStart",
    "SwapBind",
    "SdkBind",
    "SwapReconcile",
    "GateDown",
    "SwapDone",
    "DeliverEvent",
    "ApplySignal",
    "StatusWritten",
    "InitEntered",
    "InitReturned",
    "InitOutcome",
    "ProviderShutDown",
    "ShutdownReturned",
    "BuildBind",
    "RetireSiblings",
    "ScopeClose"
  )

  /** Every probe counter name the coverage line should show. */
  val declaredCounters: List[String] =
    (reachedLifecyclePoints ++ EvaluationProbe.Point.all.map(_.name)).map(n => s"probe.$n")

  private enum Decision {
    case Pass
    case Yield(times: Int)
    case Sleep(micros: Long)
  }

  // splitmix64's finalizer.
  private def mix(z0: Long): Long = {
    val z1 = (z0 ^ (z0 >>> 30)) * 0xbf58476d1ce4e5b9L
    val z2 = (z1 ^ (z1 >>> 27)) * 0x94d049bb133111ebL
    z2 ^ (z2 >>> 31)
  }
}
