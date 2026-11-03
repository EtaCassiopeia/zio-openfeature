package zio.openfeature

import zio._
import zio.test._
import zio.stream.ZStream

/** The total (never-fails) methods are concrete on the `FeatureFlags` trait, so they must behave correctly for ANY
  * `*Details` implementation — including ones that die or get interrupted. The real provider pipeline funnels every
  * failure through `ZIO.attemptBlocking` into the typed channel, so it can never produce a defect; these branches are
  * reachable only via a custom implementation. `BoomFlags` is a minimal such implementation whose `valueDetails` fails
  * via a configurable `boom` effect (a defect or an interruption).
  */
object TotalEvaluationDefectSpec extends ZIOSpecDefault {

  abstract private class BoomFlags extends FeatureFlags {
    protected def boom[A]: IO[FeatureFlagError.EvaluationError, FlagResolution[A]]

    override def valueDetails[A: FlagType](k: String, d: A, ctx: EvaluationContext, o: EvaluationOptions) = boom

    override def setGlobalContext(ctx: EvaluationContext): UIO[Unit]                           = ???
    override def globalContext: UIO[EvaluationContext]                                         = ???
    override def setClientContext(ctx: EvaluationContext): UIO[Unit]                           = ???
    override def clientContext: UIO[EvaluationContext]                                         = ???
    override def withContext[R, E, A](ctx: EvaluationContext)(zio: ZIO[R, E, A]): ZIO[R, E, A] = ???
    override def transactionEither[R, E, A](o: Map[String, Any], c: EvaluationContext, ce: Boolean, n: NestedPolicy)(
      zio: ZIO[R, E, A]
    ): ZIO[R, Either[E, FeatureFlagError.TransactionError], TransactionResult[A]]                                 = ???
    override def inTransaction: UIO[Boolean]                                                                      = ???
    override def currentEvaluatedFlags: UIO[Map[String, FlagEvaluation[_]]]                                       = ???
    override def events: ZStream[Any, Nothing, ProviderEvent]                                                     = ???
    override def providerStatus: UIO[ProviderStatus]                                                              = ???
    override def awaitReady(within: Duration): UIO[ProviderStatus]                                                = ???
    override def providerMetadata: UIO[ProviderMetadata]                                                          = ???
    override def clientMetadata: UIO[ClientMetadata]                                                              = ???
    override def addHook(hook: FeatureHook): UIO[Unit]                                                            = ???
    override def addHooks(hooks: List[FeatureHook]): UIO[Unit]                                                    = ???
    override def clearHooks: UIO[Unit]                                                                            = ???
    override def hooks: UIO[List[FeatureHook]]                                                                    = ???
    override def track(eventName: String): IO[FeatureFlagError.EvaluationError, Unit]                             = ???
    override def track(eventName: String, context: EvaluationContext): IO[FeatureFlagError.EvaluationError, Unit] = ???
    override def track(eventName: String, details: TrackingEventDetails): IO[FeatureFlagError.EvaluationError, Unit] =
      ???
    override def track(
      eventName: String,
      context: EvaluationContext,
      details: TrackingEventDetails
    ): IO[FeatureFlagError.EvaluationError, Unit] = ???
  }

  private def dying: BoomFlags = new BoomFlags {
    protected def boom[A]: IO[FeatureFlagError.EvaluationError, FlagResolution[A]] =
      ZIO.die(new RuntimeException("boom"))
  }
  private def interrupting: BoomFlags = new BoomFlags {
    protected def boom[A]: IO[FeatureFlagError.EvaluationError, FlagResolution[A]] = ZIO.interrupt
  }

  def spec = suite("TotalEvaluationDefectSpec")(
    test("booleanOrDefault absorbs a defect from *Details and serves the default") {
      dying.booleanOrDefault("k", default = true).map(v => assertTrue(v))
    },
    test("resolveOrDefault maps a defect to reason=Error with errorCode General") {
      dying.resolveOrDefault[Boolean]("k", default = true).map { res =>
        assertTrue(res.value, res.reason == ResolutionReason.Error, res.errorCode.contains(ErrorCode.General))
      }
    },
    test("booleanOrDefault propagates interruption instead of absorbing it into the default") {
      interrupting.booleanOrDefault("k", default = true).exit.map(ex => assertTrue(ex.isInterrupted))
    }
  )
}
