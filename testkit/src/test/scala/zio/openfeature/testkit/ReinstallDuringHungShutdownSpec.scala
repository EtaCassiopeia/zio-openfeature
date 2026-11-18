package zio.openfeature.testkit

import zio._
import zio.test._
import zio.openfeature._
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  ProviderEventDetails,
  Value
}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

// A provider replaced by a swap is shut down by the SDK on its executor. Re-installing it waits for that shutdown for
// at most OrphanedProviders.SdkShutDownWait (3s). If the shutdown() is still running then, the re-install is refused
// rather than installing a provider that call would close once it returns.
object ReinstallDuringHungShutdownSpec extends ZIOSpecDefault {

  // A delegate whose shutdown() really closes it: evaluations throw until the next initialize(). This is the shape of
  // a provider that owns a connection or a polling thread.
  final class ClosableProvider extends FeatureProvider {
    val closed                                              = new AtomicBoolean(false)
    val inits                                               = new AtomicInteger(0)
    val shutdowns                                           = new AtomicInteger(0)
    def getMetadata: Metadata                               = () => "closable"
    override def initialize(ctx: OFEvaluationContext): Unit = { inits.incrementAndGet(); closed.set(false) }
    override def shutdown(): Unit                           = { shutdowns.incrementAndGet(); closed.set(true) }
    private def answer[T](v: T): ProviderEvaluation[T]      = {
      if (closed.get()) throw new IllegalStateException("provider is shut down")
      // Statement by statement and cast, as FaultyProvider does: Scala 2.13 infers the chained builder's type badly.
      val builder = ProviderEvaluation.builder[T]()
      builder.value(v)
      builder.build().asInstanceOf[ProviderEvaluation[T]]
    }
    def getBooleanEvaluation(
      k: String,
      d: java.lang.Boolean,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Boolean] =
      answer(java.lang.Boolean.TRUE)
    def getStringEvaluation(k: String, d: String, c: OFEvaluationContext): ProviderEvaluation[String]    = answer(d)
    def getIntegerEvaluation(k: String, d: Integer, c: OFEvaluationContext): ProviderEvaluation[Integer] = answer(d)
    def getDoubleEvaluation(
      k: String,
      d: java.lang.Double,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Double] =
      answer(d)
    def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext): ProviderEvaluation[Value] = answer(d)
  }

  private def build(api: OpenFeatureAPI, provider: FeatureProvider): ZIO[Scope, Throwable, FeatureFlagsLive] =
    FeatureFlags.build(
      provider,
      domain = Some(s"reinstall-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(api),
      initTimeout = 2.seconds
    )

  private def lifecycleOps(p: FaultyProvider[_]): UIO[Chunk[Op]] =
    p.calls.map(_.map(_.op).filter(op => op == Op.Initialize || op == Op.Shutdown))

  private def pollUntil[A](read: UIO[A])(done: A => Boolean): UIO[A] =
    read.flatMap(a => if (done(a)) ZIO.succeed(a) else ZIO.sleep(5.millis) *> pollUntil(read)(done))

  // A latch, not a sleep: wait until `n` shutdown() calls have completed (recorded calls are completed calls).
  private def awaitShutdownsReturned(p: FaultyProvider[_], n: Int): IO[String, Unit] =
    pollUntil(p.calls(Op.Shutdown))(_.size >= n).unit
      .timeoutFail(s"shutdown() #$n did not return")(10.seconds)

  final case class Outcome(
    reinstall: Either[FeatureFlagError, Unit],
    statusAfterLateShutdown: ProviderStatus,
    valueAfterLateShutdown: Either[FeatureFlagError, Boolean],
    delegateClosed: Boolean,
    statusAfterErrorEvent: ProviderStatus,
    ops: Chunk[Op]
  )

  // Build on a, swap to b, put a back while a's first shutdown() is parked, release it after the re-install returned.
  private def releasedAfterReinstall(shutdown: Fault): ZIO[Any, Any, Outcome] =
    ZIO.scoped {
      for {
        api       <- ZIO.succeed(OpenFeatureAPI.createIsolated())
        delegate  <- ZIO.succeed(new ClosableProvider)
        a         <- FaultyProvider.wrapScoped(delegate, name = "a")
        _         <- a.script(Op.Shutdown, shutdown, Fault.Succeed)
        ff        <- build(api, a)
        b         <- FaultyProvider.scoped(Map("x" -> false), name = "b")
        _         <- ff.setProvider(b)
        _         <- a.awaitEntered(Op.Shutdown)
        reinstall <- ff.setProvider(a).either
        _         <- a.release
        _         <- awaitShutdownsReturned(a, 1)
        status    <- ff.providerStatus
        value     <- ff.boolean("x", false).either
        closed    <- ZIO.succeed(delegate.closed.get())
        // Is a's event bridge still wired to this instance after the late shutdown()?
        _        <- ZIO.succeed(a.emitProviderError(ProviderEventDetails.builder().message("late").build()))
        afterErr <- pollUntil(ff.providerStatus)(_ == ProviderStatus.Error)
          .timeout(2.seconds)
          .flatMap(s => s.fold(ff.providerStatus)(ZIO.succeed(_)))
        ops <- lifecycleOps(a)
      } yield Outcome(reinstall, status, value, closed, afterErr, ops)
    }

  def spec = suite("ReinstallDuringHungShutdownSpec")(
    test("control: a shutdown() that ends within the wait finishes before the re-install's initialize()") {
      releasedAfterReinstall(Fault.Delay(1.second)).map { o =>
        assertTrue(
          o.reinstall.isRight,
          o.ops == Chunk(Op.Initialize, Op.Shutdown, Op.Initialize),
          o.statusAfterLateShutdown == ProviderStatus.Ready,
          o.valueAfterLateShutdown == Right(true),
          !o.delegateClosed,
          o.statusAfterErrorEvent == ProviderStatus.Error
        )
      }
    },
    test("a shutdown() still running after the wait: the re-install is refused, not installed and then closed") {
      releasedAfterReinstall(Fault.HangUntilReleased).map { o =>
        val refusal = o.reinstall match {
          case Left(FeatureFlagError.ProviderInitializationFailed(e)) => Option(e.getMessage).getOrElse("")
          case _                                                      => ""
        }
        assertTrue(refusal.contains("still shutting down"), o.ops == Chunk(Op.Initialize, Op.Shutdown))
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.nonFlaky(5) @@ TestAspect.timeout(5.minutes)
}
