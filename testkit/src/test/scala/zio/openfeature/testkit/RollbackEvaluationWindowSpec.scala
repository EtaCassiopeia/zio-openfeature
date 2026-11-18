package zio.openfeature.testkit

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import zio._
import zio.test._
import zio.openfeature._
import dev.openfeature.sdk.{
  EvaluationContext => OFContext,
  FeatureProvider,
  ImmutableMetadata,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  Value
}

// An evaluation that starts during a failed swap's rollback must not be answered by the failed provider.
//
// Provider A answers "A". Provider B's initialize() waits on a latch and throws (control: returns); B answers "B".
// The swap to B fails and is rolled back to A. No evaluation may return "B" when B failed to initialize: CHANGELOG
// says one started during the failed swap's rollback fails with ProviderNotReady(NotReady), and
// docs/providers.md says evaluations that start during the swap fail with ProviderNotReady.
//
// The window: after B's initialize() threw, the swap rolls its refs back and applies SwapFailed (status Error, which a
// status check alone lets through) while the SDK slot still holds B (the SDK short-circuits only NOT_READY and FATAL;
// the slot's state is ERROR). An evaluation that passed its check there would reach B, and its re-check
// (`Evaluator.gated`) refuses only when the generation moved or the gate is still up; the rollback does not move the
// generation and the gate drops at the swap's end, so B's answer returning after that would be served.
object RollbackEvaluationWindowSpec extends ZIOSpecDefault {

  private val Hold = 2L // seconds; every latch wait is bounded

  /** A provider answering `answer` to every string read; `onInit(n)` runs on its n-th initialize(), `onEval` on each
    * string read, `onMeta` on each getMetadata.
    */
  final class Stub(name: String, answer: String, onInit: Int => Unit, onEval: () => Unit, onMeta: () => Unit)
      extends FeatureProvider {
    private val inits                              = new AtomicInteger(0)
    override def getMetadata: Metadata             = { onMeta(); new Metadata { override def getName: String = name } }
    override def initialize(ctx: OFContext): Unit  = onInit(inits.incrementAndGet())
    private def ev[T](v: T): ProviderEvaluation[T] =
      new ProviderEvaluation[T](v, null, "STATIC", null, null, ImmutableMetadata.builder().build())
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFContext) = ev(d)
    override def getStringEvaluation(k: String, d: String, c: OFContext)             = { onEval(); ev(answer) }
    override def getIntegerEvaluation(k: String, d: Integer, c: OFContext)           = ev(d)
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFContext)   = ev(d)
    override def getObjectEvaluation(k: String, d: Value, c: OFContext)              = ev(d)
  }

  private def await(l: CountDownLatch): Unit = { l.await(Hold, TimeUnit.SECONDS); () }

  private def build(p: FeatureProvider): ZIO[Scope, Throwable, FeatureFlagsLive] =
    FeatureFlags.build(
      p,
      domain = Some(s"rollback-window-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      evaluationTimeout = Some(10.seconds),
      initTimeout = 5.seconds
    )

  /** What one swap saw: counts only, so a failed assertion prints a short line. */
  final case class Run(swapFailed: Boolean, held: Boolean, after: String, answeredB: Int, notReady: Int, answeredA: Int)

  sealed trait Pin
  object Pin {
    // Nothing held.
    case object Free extends Pin
    // The first evaluation reaching B is held inside B until the swap has returned.
    case object Answer extends Pin
    // As Answer, and A.getMetadata waits (bounded) for that evaluation once B's initialize() threw: the rollback's
    // re-registration of A reads it before the SDK rebinds the slot.
    case object Entry extends Pin
  }

  def once(bFails: Boolean, pin: Pin, reuseName: Boolean): ZIO[Scope, Throwable, Run] = {
    val bInit    = new CountDownLatch(1)
    val entered  = new CountDownLatch(1)
    val release  = new CountDownLatch(1)
    val enteredB = new AtomicBoolean(false)
    val bThrew   = new AtomicBoolean(false)
    // A's metadata holds once per run: enough to open the window, and only one bounded wait when nothing enters B.
    val heldA = new AtomicBoolean(false)
    val a     = new Stub(
      "A",
      "A",
      _ => (),
      () => (),
      () => if (pin == Pin.Entry && bThrew.get() && !enteredB.get() && heldA.compareAndSet(false, true)) await(entered)
    )
    val b = new Stub(
      if (reuseName) "A" else "B",
      "B",
      _ => {
        await(bInit)
        if (bFails) { bThrew.set(true); throw new RuntimeException("B: initialize fails") }
      },
      () =>
        if (pin != Pin.Free && enteredB.compareAndSet(false, true)) { entered.countDown(); await(release) }
        else (),
      () => ()
    )
    for {
      ff   <- build(a)
      stop <- Ref.make(false)
      out  <- Ref.make(Map.empty[String, Int])
      swap <- ff.setProvider(b).either.fork
      loop <- (ff.string("k", "default").either.flatMap { r =>
        val k = r.fold(e => s"err:${e.getClass.getSimpleName}", identity)
        out.update(m => m.updated(k, m.getOrElse(k, 0) + 1))
      } *> ZIO.yieldNow).repeatUntilZIO(_ => stop.get).fork
      _     <- ZIO.sleep(20.millis)
      _     <- ZIO.succeed(bInit.countDown())
      res   <- swap.join
      _     <- ZIO.succeed(release.countDown())
      _     <- stop.set(true)
      _     <- loop.join
      evals <- out.get
      after <- ff.string("k", "default").either
    } yield Run(
      res.isLeft,
      enteredB.get(),
      after.fold(e => s"err:${e.getClass.getSimpleName}", identity),
      evals.getOrElse("B", 0),
      evals.getOrElse("err:ProviderNotReady", 0),
      evals.getOrElse("A", 0)
    )
  }

  def spec = suite("RollbackEvaluationWindowSpec")(
    test("pinned: no evaluation is answered by a provider whose initialize() threw") {
      // No `held`: an evaluation that starts while the swap runs fails before it reaches B, so none is held there.
      ZIO.scoped(once(bFails = true, Pin.Entry, reuseName = false)).map { r =>
        val failed = r.swapFailed; val after = r.after; val b = r.answeredB
        assertTrue(failed, after == "A", b == 0)
      }
    } @@ TestAspect.nonFlaky(20),
    test("control: B initializes, the held evaluation and later ones are B's") {
      ZIO.scoped(once(bFails = false, Pin.Entry, reuseName = false)).map { r =>
        val held = r.held; val failed = r.swapFailed; val after = r.after; val b = r.answeredB
        assertTrue(!failed, held, after == "B", b >= 1)
      }
    } @@ TestAspect.nonFlaky(50)
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(20.minutes)
}
