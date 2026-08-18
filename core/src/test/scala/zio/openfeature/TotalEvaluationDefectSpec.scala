package zio.openfeature

import zio._
import zio.test._
import zio.stream.ZStream

/** #256: the total (never-fails) methods are concrete on the `FeatureFlags` trait, so they must behave correctly for
  * ANY `*Details` implementation — including ones that die or get interrupted. The real provider pipeline funnels every
  * failure through `ZIO.attemptBlocking` into the typed channel, so it can never produce a defect; these branches are
  * reachable only via a custom implementation. `BoomFlags` is a minimal such implementation whose `*Details` fail via a
  * configurable `boom` effect (a defect or an interruption).
  */
object TotalEvaluationDefectSpec extends ZIOSpecDefault {

  abstract private class BoomFlags extends FeatureFlags {
    protected def boom[A]: IO[FeatureFlagError, FlagResolution[A]]

    override def booleanDetails(k: String, d: Boolean, ctx: EvaluationContext, o: EvaluationOptions)      = boom
    override def stringDetails(k: String, d: String, ctx: EvaluationContext, o: EvaluationOptions)        = ???
    override def intDetails(k: String, d: Int, ctx: EvaluationContext, o: EvaluationOptions)              = ???
    override def longDetails(k: String, d: Long, ctx: EvaluationContext, o: EvaluationOptions)            = ???
    override def doubleDetails(k: String, d: Double, ctx: EvaluationContext, o: EvaluationOptions)        = ???
    override def objDetails(k: String, d: Map[String, Any], ctx: EvaluationContext, o: EvaluationOptions) = ???
    override def valueDetails[A: FlagType](k: String, d: A, ctx: EvaluationContext, o: EvaluationOptions) = boom

    override def setGlobalContext(ctx: EvaluationContext): UIO[Unit]                           = ???
    override def globalContext: UIO[EvaluationContext]                                         = ???
    override def setClientContext(ctx: EvaluationContext): UIO[Unit]                           = ???
    override def clientContext: UIO[EvaluationContext]                                         = ???
    override def withContext[R, E, A](ctx: EvaluationContext)(zio: ZIO[R, E, A]): ZIO[R, E, A] = ???
    override def transaction[R, E, A](o: Map[String, Any], c: EvaluationContext, ce: Boolean, n: NestedPolicy)(
      zio: ZIO[R, E, A]
    ): ZIO[R, Compat.OrError[E, FeatureFlagError], TransactionResult[A]] = ???
    override def transactionEither[R, E, A](o: Map[String, Any], c: EvaluationContext, ce: Boolean, n: NestedPolicy)(
      zio: ZIO[R, E, A]
    ): ZIO[R, Either[E, FeatureFlagError], TransactionResult[A]] = ???
    override def inTransaction: UIO[Boolean]                                                             = ???
    override def currentEvaluatedFlags: UIO[Map[String, FlagEvaluation[_]]]                              = ???
    override def events: ZStream[Any, Nothing, ProviderEvent]                                            = ???
    override def providerStatus: UIO[ProviderStatus]                                                     = ???
    override def awaitReady(within: Duration): UIO[ProviderStatus]                                       = ???
    override def providerMetadata: UIO[ProviderMetadata]                                                 = ???
    override def clientMetadata: UIO[ClientMetadata]                                                     = ???
    override def onProviderReady(h: ProviderMetadata => UIO[Unit]): UIO[UIO[Unit]]                       = ???
    override def onProviderError(h: (Throwable, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]]          = ???
    override def onProviderStale(h: (String, ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]]             = ???
    override def onConfigurationChanged(h: (Set[String], ProviderMetadata) => UIO[Unit]): UIO[UIO[Unit]] = ???
    override def on(t: ProviderEventType, h: ProviderEvent => UIO[Unit]): UIO[UIO[Unit]]                 = ???
    override def addHook(hook: FeatureHook): UIO[Unit]                                                   = ???
    override def addHooks(hooks: List[FeatureHook]): UIO[Unit]                                           = ???
    override def clearHooks: UIO[Unit]                                                                   = ???
    override def hooks: UIO[List[FeatureHook]]                                                           = ???
    override def addZioApiHook(hook: FeatureHook): UIO[Unit]                                             = ???
    override def addZioApiHooks(hooks: List[FeatureHook]): UIO[Unit]                                     = ???
    override def clearZioApiHooks: UIO[Unit]                                                             = ???
    override def zioApiHooks: UIO[List[FeatureHook]]                                                     = ???
    override def addApiHook(hook: dev.openfeature.sdk.Hook[_]): UIO[Unit]                                = ???
    override def clearApiHooks: UIO[Unit]                                                                = ???
    override def setProvider(p: dev.openfeature.sdk.FeatureProvider): IO[FeatureFlagError, Unit]         = ???
    override def shutdown: UIO[Unit]                                                                     = ???
    override def track(eventName: String): IO[FeatureFlagError, Unit]                                    = ???
    override def track(eventName: String, context: EvaluationContext): IO[FeatureFlagError, Unit]        = ???
    override def track(eventName: String, details: TrackingEventDetails): IO[FeatureFlagError, Unit]     = ???
    override def track(
      eventName: String,
      context: EvaluationContext,
      details: TrackingEventDetails
    ): IO[FeatureFlagError, Unit] = ???
    override def trackedEvents: UIO[List[(String, EvaluationContext, Option[TrackingEventDetails])]] = ???
  }

  private def dying: BoomFlags = new BoomFlags {
    protected def boom[A]: IO[FeatureFlagError, FlagResolution[A]] = ZIO.die(new RuntimeException("boom"))
  }
  private def interrupting: BoomFlags = new BoomFlags {
    protected def boom[A]: IO[FeatureFlagError, FlagResolution[A]] = ZIO.interrupt
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
