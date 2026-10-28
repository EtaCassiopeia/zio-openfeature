package zio.openfeature.extras

import zio._
import zio.test._
import zio.test.TestAspect.{samples, timeout, withLiveClock}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters._

// The stale-permit invariants as properties. Two views of the same invariants:
//   - a sequential schedule where any permit can be settled late, in any order, against a manual clock (exact check:
//     a stale permit leaves the state untouched), and
//   - real fibers with injected delays against the system ticker (linearisation-independent checks only).
// Invariants: probe slots never exceed halfOpenMaxCalls, a stale permit never closes/re-opens/frees a newer epoch, and
// the circuit never closes while a probe it did not admit is still in flight.
object CircuitBreakerPropertySpec extends ZIOSpecDefault {

  private class ManualTicker extends Ticker {
    private val now                = new AtomicReference[Long](0L)
    def advance(d: Duration): Unit = { now.updateAndGet(_ + d.toNanos); () }
    override def nanos(): Long     = now.get()
  }

  sealed private trait Outcome
  private case object Succeed   extends Outcome
  private case object Fail      extends Outcome
  private case object Reachable extends Outcome

  /** Records the outcome and returns whether it closed (success) or opened (failure) the circuit. */
  private def settle(cb: CircuitBreaker, outcome: Outcome, permit: CircuitBreaker.Permit): (Boolean, Boolean) =
    outcome match {
      case Succeed   => (cb.recordSuccess(permit), false)
      case Fail      => (false, cb.recordFailure(permit))
      case Reachable => cb.recordReachable(permit); (false, false)
    }

  /** Slots in use in a half-open state: banked successes plus the in-flight probe. */
  private def slotsInUse(s: CircuitBreakerState): Int = s.circuit match {
    case CircuitState.HalfOpen(successes, probing, _) => successes + (if (probing) 1 else 0)
    case _                                            => 0
  }

  private val outcomes: Gen[Any, Outcome] = Gen.elements[Outcome](Succeed, Fail, Reachable)

  private val configs: Gen[Any, CircuitBreakerConfig] =
    for {
      threshold <- Gen.int(1, 3)
      maxCalls  <- Gen.int(1, 3)
    } yield CircuitBreakerConfig(
      failureThreshold = threshold,
      resetTimeout = 1.second,
      halfOpenMaxCalls = maxCalls,
      probeTimeout = 2.seconds
    )

  // Sequential schedule

  sealed private trait Step
  private case object Acquire                           extends Step
  final private case class Settle(idx: Int, o: Outcome) extends Step
  final private case class Advance(seconds: Int)        extends Step

  private val steps: Gen[Any, Step] = Gen.oneOf(
    Gen.const(Acquire),
    Gen.const(Acquire),
    for { i <- Gen.int(0, 50); o <- outcomes } yield Settle(i, o),
    for { i <- Gen.int(0, 50); o <- outcomes } yield Settle(i, o),
    Gen.int(0, 3).map(Advance(_))
  )

  private def runSequential(config: CircuitBreakerConfig, schedule: List[Step]): List[String] = {
    val ticker     = new ManualTicker
    val cb         = CircuitBreaker(config, ticker)
    val violations = List.newBuilder[String]
    var permits    = Vector.empty[CircuitBreaker.Permit]

    def check(label: String, ok: Boolean): Unit = if (!ok) violations += label

    schedule.foreach { step =>
      val before = cb.stateRef.get()
      step match {
        case Advance(sec) => ticker.advance(sec.seconds)

        case Acquire =>
          val got = cb.acquire()
          val now = cb.stateRef.get()
          got.foreach { p =>
            permits = permits :+ p
            check(s"permit epoch ${p.epoch} is not the admitting state's epoch ${now.epoch}", p.epoch == now.epoch)
          }
          before.circuit match {
            case CircuitState.HalfOpen(_, true, started) if ticker.nanos() - started < config.probeTimeout.toNanos =>
              check("a second probe was admitted while one was in flight", got.isEmpty && now == before)
            case CircuitState.Closed =>
              check(
                "Closed must always admit under the current epoch",
                got.contains(CircuitBreaker.Permit(before.epoch))
              )
            case _ => ()
          }

        case Settle(idx, o) if permits.nonEmpty =>
          val p                = permits(idx % permits.size)
          val (closed, opened) = settle(cb, o, p)
          val after            = cb.stateRef.get()
          if (p.epoch != before.epoch) {
            check(s"stale permit (epoch ${p.epoch}, now ${before.epoch}) changed the state with $o", after == before)
            check("stale permit reported a transition", !closed && !opened)
          } else if (closed) {
            check(
              "closed without being the admitted probe of a half-open epoch",
              before.circuit.isInstanceOf[CircuitState.HalfOpen] && after.circuit == CircuitState.Closed
            )
          }

        case _: Settle => ()
      }
      val after = cb.stateRef.get()
      check(
        s"slots in use ${slotsInUse(after)} exceed halfOpenMaxCalls ${config.halfOpenMaxCalls}",
        slotsInUse(after) <= config.halfOpenMaxCalls
      )
      check("epoch went backwards", after.epoch >= before.epoch)
    }
    violations.result()
  }

  // Concurrent fibers

  /** One round: acquire, hold the permit for `holdMillis`, then settle it (or abandon it when `outcome` is None). */
  final private case class Round(holdMillis: Int, outcome: Option[Outcome])

  private val rounds: Gen[Any, Round] =
    for {
      hold <- Gen.int(0, 4)
      o    <- Gen.option(outcomes)
    } yield Round(hold, o)

  private val fiberScripts: Gen[Any, List[List[Round]]] =
    for {
      fibers  <- Gen.int(2, 8)
      scripts <- Gen.listOfN(fibers)(Gen.listOfBounded(1, 10)(rounds))
    } yield scripts

  private def runConcurrent(config: CircuitBreakerConfig, scripts: List[List[Round]]): UIO[List[String]] =
    ZIO
      .succeed {
        val cb         = CircuitBreaker(config, Ticker.system)
        val violations = new ConcurrentLinkedQueue[String]
        // epoch -> number of probe permits admitted under it (only epochs seen as HalfOpen count)
        val probesPerEpoch = new java.util.concurrent.ConcurrentHashMap[Long, Integer]
        (cb, violations, probesPerEpoch)
      }
      .flatMap { case (cb, violations, probesPerEpoch) =>
        def check(label: String, ok: Boolean): UIO[Unit] = ZIO.succeed { if (!ok) violations.add(label); () }

        def snapshot(label: String): UIO[CircuitBreakerState] =
          ZIO.succeed(cb.stateRef.get()).tap { s =>
            check(
              s"$label: slots in use ${slotsInUse(s)} exceed ${config.halfOpenMaxCalls}",
              slotsInUse(s) <= config.halfOpenMaxCalls
            )
          }

        def round(r: Round): UIO[Unit] =
          for {
            _   <- snapshot("before acquire")
            got <- ZIO.succeed(cb.acquire())
            // `Open`/`HalfOpen`/`Closed` is fixed per epoch, so a post-read that still shows p's epoch classifies it.
            _ <- ZIO.foreachDiscard(got) { p =>
              ZIO.succeed(cb.stateRef.get()).flatMap { s =>
                if (s.epoch == p.epoch && s.circuit.isInstanceOf[CircuitState.HalfOpen]) {
                  val n = probesPerEpoch.merge(p.epoch, 1, (a, b) => a + b)
                  check(s"epoch ${p.epoch} admitted $n probes", n == 1)
                } else ZIO.unit
              }
            }
            _ <- ZIO.foreachDiscard(got) { p =>
              for {
                _      <- ZIO.sleep(r.holdMillis.millis)
                before <- snapshot("before settle")
                _ <- ZIO.foreachDiscard(r.outcome) { o =>
                  ZIO.succeed(settle(cb, o, p)).flatMap { case (closed, opened) =>
                    check(
                      s"stale permit (epoch ${p.epoch} < ${before.epoch}) caused a close/open",
                      before.epoch <= p.epoch || (!closed && !opened)
                    )
                  }
                }
                _ <- snapshot("after settle")
              } yield ()
            }
          } yield ()

        ZIO
          .foreachParDiscard(scripts)(script => ZIO.foreachDiscard(script)(round))
          .as(violations.asScala.toList)
      }

  def spec = suite("CircuitBreakerPropertySpec")(
    test("sequential schedules: stale permits are no-ops, slots stay bounded, only the probe closes") {
      check(configs, Gen.listOfBounded(5, 80)(steps)) { (config, schedule) =>
        val violations = runSequential(config, schedule)
        assertTrue(violations.isEmpty)
      }
    } @@ samples(200),
    test("concurrent fibers with injected delays keep the same invariants") {
      check(configs.map(_.copy(resetTimeout = 3.millis, probeTimeout = 3.millis)), fiberScripts) { (config, scripts) =>
        runConcurrent(config, scripts).map(violations => assertTrue(violations.isEmpty))
      }
    } @@ samples(30)
  ) @@ withLiveClock @@ timeout(2.minutes)
}
