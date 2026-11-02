package zio.openfeature.testkit

import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

import dev.openfeature.sdk.OpenFeatureAPI
import zio._
import zio.openfeature._
import zio.openfeature.internal.{EvaluationProbe, FeatureFlagsFactories}
import zio.openfeature.internal.EvaluationProbe.Point
import zio.openfeature.testsupport.LeakCheck
import zio.test._

/** Interrupts one evaluation at every point of its pipeline, and lets the evaluation timeout elapse where the timeout
  * races the call, then checks what must hold whatever the stage: the caller's fiber ends interrupted, never dies;
  * `finallyAfter` runs once if the hook stage was entered and never otherwise; the provider is entered once if the call
  * was started and never otherwise; no abandoned call stays counted once the provider returns; the transaction's cache
  * gains the evaluation only if it completed; no fiber or thread is left behind.
  *
  * Each row is one evaluation of a string flag inside a transaction that already caches another flag, on a provider
  * that parks each call until released, with a recording hook. The probe holds the evaluation at the row's point; the
  * competitor then acts: `Interrupt` interrupts the caller, `Timeout` lets the evaluation timeout elapse (only where
  * the race exists, at `CallForked`), and `Release` is the control row. The `ErrorHooks` rows use a provider that
  * throws, since only a failure reaches that stage.
  */
object EvaluationInterruptionSpec extends ZIOSpecDefault {

  sealed trait Competitor extends Product with Serializable
  object Competitor {
    case object Interrupt extends Competitor
    case object Timeout   extends Competitor
    case object Release   extends Competitor
  }

  private val Key   = "evaluated"
  private val Other = "cached"

  private val ShortTimeout = 300.millis
  private val LongTimeout  = 30.seconds

  /** Holds the first evaluation that reaches `target` once armed, and records every point reached once armed. */
  final private class HoldingProbe(target: Point, held: Promise[Nothing, Unit], release: Promise[Nothing, Unit])
      extends EvaluationProbe {
    val armed           = new AtomicBoolean(false)
    val reached         = new java.util.concurrent.ConcurrentLinkedQueue[Point]()
    private val holding = new AtomicBoolean(false)

    def at(point: Point): UIO[Unit] =
      ZIO.suspendSucceed {
        if (!armed.get()) ZIO.unit
        else {
          reached.add(point)
          if (point == target && holding.compareAndSet(false, true)) held.succeed(()) *> release.await
          else ZIO.unit
        }
      }

    def wasReached(point: Point): Boolean = reached.contains(point)
  }

  final private case class Observed(
    exit: Exit[FeatureFlagError, String],
    finallies: Int,
    entered: Int,
    abandonedAtOnce: Int,
    abandonedAfter: Int,
    cachedBefore: Set[String],
    cachedAfter: Set[String],
    reachedBefore: Boolean,
    reachedCall: Boolean,
    leaks: List[String]
  )

  private def runRow(point: Point, competitor: Competitor): ZIO[Any, Nothing, Observed] =
    for {
      baseline <- LeakCheck.snapshot
      observed <- ZIO.scoped(observe(point, competitor))
      leaks    <- LeakCheck.awaitNoLeaks(baseline, 5.seconds)
    } yield observed.copy(leaks = leaks)

  private def observe(point: Point, competitor: Competitor): ZIO[Scope, Nothing, Observed] =
    for {
      provider <- FaultyProvider.scoped(Map(Key -> "served", Other -> "other"), name = "parked")
      // The cached flag's call comes first and is served; the evaluated one throws on the ErrorHooks rows.
      second = if (point == Point.ErrorHooks) Fault.Throw(new RuntimeException("boom")) else Fault.HangUntilReleased
      _ <- provider.script(Op.EvaluateString, Fault.HangUntilReleased, second)
      // Every call the provider receives is released as soon as it has entered: the row's hold is the probe's.
      _ <- ZIO
        .iterate(1)(_ => true)(n => provider.awaitEntered(Op.EvaluateString, n) *> provider.release.as(n + 1))
        .forkScoped
      hook    <- RecordingHook.make()
      held    <- Promise.make[Nothing, Unit]
      release <- Promise.make[Nothing, Unit]
      probe   = new HoldingProbe(point, held, release)
      timeout = if (competitor == Competitor.Timeout) ShortTimeout else LongTimeout
      ff <- FeatureFlagsFactories
        .buildWithTracking(
          provider,
          domain = Some(s"interruption-${java.util.UUID.randomUUID()}"),
          version = None,
          initialHooks = List(hook),
          statusRef = None,
          addShutdownFinalizer = true,
          apiOverride = Some(OpenFeatureAPI.createIsolated()),
          evaluationTimeout = Some(timeout),
          evaluationProbe = probe
        )
        .orDie
      result <- ff
        .transactionEither() {
          for {
            // The other flag is cached first, so the evaluated one still reaches the provider.
            _            <- ff.string(Other, "none").orDieWith(e => new RuntimeException(e.toString))
            cachedBefore <- ff.transactionEvaluations.map(_.fold(Set.empty[String])(_.keySet))
            _            <- hook.clear
            entered0     <- provider.entered(Op.EvaluateString)
            _            <- ZIO.succeed(probe.armed.set(true))
            caller       <- ff.string(Key, "default").fork
            _            <- held.await
            _            <- competitor match {
              case Competitor.Interrupt => caller.interruptFork
              case Competitor.Timeout   => ZIO.sleep(ShortTimeout * 2)
              case Competitor.Release   => ZIO.unit
            }
            _          <- release.succeed(())
            exit       <- caller.await
            abandoned0 <- ZIO.succeed(ff.abandonedCallCount)
            // The released call returns at once; its finalizer then clears the abandoned count.
            abandoned1 <- ZIO
              .succeed(ff.abandonedCallCount)
              .repeatUntil(_ == 0)
              .timeout(5.seconds)
              .map(_.getOrElse(ff.abandonedCallCount))
            entered1    <- provider.entered(Op.EvaluateString)
            calls       <- hook.calls
            cachedAfter <- ff.transactionEvaluations.map(_.fold(Set.empty[String])(_.keySet))
          } yield Observed(
            exit,
            calls.count(_.isInstanceOf[HookCall.Finally]),
            entered1 - entered0,
            abandoned0,
            abandoned1,
            cachedBefore,
            cachedAfter,
            probe.wasReached(Point.BeforeHooks),
            probe.wasReached(Point.CallForked),
            Nil
          )
        }
        .orDieWith(e => new RuntimeException(e.toString))
    } yield result.result

  private def rows: List[(Point, Competitor)] =
    Point.all.flatMap(p => List(p -> Competitor.Interrupt, p -> Competitor.Release)) :+
      (Point.CallForked -> Competitor.Timeout)

  private def check(point: Point, competitor: Competitor, o: Observed): TestResult = {
    val ended = competitor match {
      case Competitor.Interrupt => assertTrue(o.exit.isInterrupted, o.exit.causeOption.forall(_.dieOption.isEmpty))
      case Competitor.Timeout   =>
        assertTrue(o.exit match {
          case Exit.Failure(cause) =>
            cause.failureOption.exists {
              case FeatureFlagError.ProviderError(_: TimeoutException) => true
              case _                                                   => false
            }
          case _ => false
        })
      case Competitor.Release =>
        if (point == Point.ErrorHooks) assertTrue(o.exit.causeOption.exists(_.failureOption.isDefined))
        else assertTrue(o.exit == Exit.succeed("served"))
    }
    // An interruption before the hook stage or before the call starts leaves them unreached; every other row passes
    // through both.
    val stageEntered =
      competitor != Competitor.Interrupt || Point.all.indexOf(point) >= Point.all.indexOf(Point.BeforeHooks)
    val callStarted =
      competitor != Competitor.Interrupt || Point.all.indexOf(point) >= Point.all.indexOf(Point.CallForked)
    // The transaction records an evaluation once it has its answer, an error-coded one included, before the hooks
    // after it: an interruption from there on keeps the entry, one before it leaves the cache as it was.
    val completed = Point.all.indexOf(point) > Point.all.indexOf(Point.GateRechecked)
    val recorded  = competitor == Competitor.Release || completed
    ended &&
    assertTrue(
      o.reachedBefore == stageEntered,
      o.finallies == (if (stageEntered) 1 else 0),
      o.reachedCall == callStarted,
      o.entered == (if (callStarted) 1 else 0),
      o.abandonedAfter == 0,
      callStarted || o.abandonedAtOnce == 0,
      o.cachedBefore == Set(Other),
      o.cachedAfter == (if (recorded) Set(Other, Key) else Set(Other)),
      o.leaks.isEmpty
    )
  }

  def spec = suite("EvaluationInterruptionSpec")(
    rows.map { case (point, competitor) =>
      test(s"${point.name} x $competitor") {
        runRow(point, competitor).map(o => check(point, competitor, o))
      }
    }: _*
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(5.minutes)
}
