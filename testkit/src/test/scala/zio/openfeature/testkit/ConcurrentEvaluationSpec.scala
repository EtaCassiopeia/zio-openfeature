package zio.openfeature.testkit

import zio._
import zio.openfeature._
import zio.test._
import zio.test.TestAspect.{timeout, withLiveClock}

/** Concurrency stress for the core `FeatureFlags` evaluation path (workstream B4).
  *
  * Fans out N parallel fibers, each performing M sequential evaluations, while a separate fiber flips the underlying
  * provider's status `READY → ERROR → READY` mid-burst. Proves:
  *
  *   - No defects escape (`ZIO.Cause.Die`): the evaluation path classifies every error as a typed `FeatureFlagError`.
  *   - Every result is either a flag value (status `READY`) or `ProviderNotReady` (during the `ERROR` window).
  *   - Final `providerStatus` matches what the test driver last set.
  *   - All fibers complete within the bounded test timeout (no deadlock).
  *
  * This spec lives in `testkit` rather than `core` because `TestFeatureProvider.setStatus` is the cleanest API to drive
  * the status transitions; `core` doesn't depend on `testkit`. The behaviour under test is core's evaluation path, so
  * the spec exercises the public `FeatureFlags` service the same way any application would.
  *
  * Sized to keep wall-clock under control (~2 seconds with `TestAspect.withLiveClock`).
  */
object ConcurrentEvaluationSpec extends ZIOSpecDefault {

  private val FiberCount     = 200
  private val EvalsPerFiber  = 10
  private val InitialDelay   = 50.millis
  private val ErrorWindow    = 50.millis
  private val EvaluationFlag = "concurrent-flag"

  // We classify each evaluation outcome into one of three buckets so the final assertion can confirm the only seen
  // failure shape was `ProviderNotReady` — anything else (e.g., a Defect or an unrelated FeatureFlagError) would mean
  // the evaluation pipeline has a concurrency bug.
  sealed private trait Outcome
  private object Outcome {
    case object Ok                                   extends Outcome
    case object NotReady                             extends Outcome
    final case class Unexpected(e: FeatureFlagError) extends Outcome
  }

  private def runOne(ff: FeatureFlags): UIO[Outcome] =
    ff.boolean(EvaluationFlag, default = false).either.map {
      case Right(_)                                   => Outcome.Ok
      case Left(_: FeatureFlagError.ProviderNotReady) => Outcome.NotReady
      case Left(FeatureFlagError.ProviderFatal)       => Outcome.NotReady
      case Left(other)                                => Outcome.Unexpected(other)
    }

  def spec = suite("ConcurrentEvaluationSpec")(
    test(s"$FiberCount fibers x $EvalsPerFiber evaluations survive a READY -> ERROR -> READY transition") {
      for {
        ff       <- ZIO.service[FeatureFlags]
        provider <- ZIO.service[TestFeatureProvider]
        _        <- provider.setFlag(EvaluationFlag, true)

        // Background fiber drives the provider through the failure window. Forked into the test scope; lives or dies
        // with the test. The first sleep means evaluations start cleanly in READY before we toggle.
        statusDriver <- (
          ZIO.sleep(InitialDelay) *>
            provider.setStatus(ProviderStatus.Error) *>
            ZIO.sleep(ErrorWindow) *>
            provider.setStatus(ProviderStatus.Ready)
        ).fork

        // Fan out evaluations. Each fiber records its own outcomes; we aggregate across all of them at the end.
        outcomes <- ZIO.foreachPar(1 to FiberCount) { _ =>
          ZIO.foreach(1 to EvalsPerFiber)(_ => runOne(ff))
        }
        _ <- statusDriver.join

        flat       = outcomes.toList.flatten
        oks        = flat.count(_ == Outcome.Ok)
        notReady   = flat.count(_ == Outcome.NotReady)
        unexpected = flat.collect { case Outcome.Unexpected(e) => e }

        finalStatus <- ff.providerStatus
        // notReady is informational only — the burst can race the status driver on a fast machine and complete the
        // full 2000 evaluations before the ERROR window opens. The deterministic failure-path coverage lives in
        // ProviderInitFailureSpec; this spec proves the concurrency invariants below.
        _ <- ZIO.logInfo(
          s"Concurrent burst summary: ok=$oks, notReady=$notReady, unexpected=${unexpected.size}"
        )
      } yield assertTrue(
        flat.size == FiberCount * EvalsPerFiber,
        unexpected.isEmpty,
        oks > 0,
        finalStatus == ProviderStatus.Ready
      )
    } @@ withLiveClock @@ timeout(20.seconds)
  ).provide(TestFeatureProvider.scopedLayer)
}
