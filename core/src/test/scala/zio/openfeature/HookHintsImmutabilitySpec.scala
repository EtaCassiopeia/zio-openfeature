package zio.openfeature

import zio._
import zio.test._
import zio.test.TestAspect.{sequential, withLiveClock}
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  ProviderState,
  Value
}

/** Verifies spec §4.5.3/§4.2.2.1: hook hints are immutable through the pipeline. Since `before` now returns
  * `Option[EvaluationContext]` (no hints), a hook cannot alter the hints seen by later hooks/stages; per-hook state
  * flows through `HookData` (the built-in `metrics` hook uses it for its start time).
  */
object HookHintsImmutabilitySpec extends ZIOSpecDefault {

  private class SimpleProvider(evalDelayMillis: Long = 0L) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Simple" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = {
      if (evalDelayMillis > 0) Thread.sleep(evalDelayMillis) // so the metrics hook measures a non-zero duration
      ProviderEvaluations.of[java.lang.Boolean](true, "STATIC")
    }
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  private def buildFF(hooks: List[FeatureHook], evalDelayMillis: Long = 0L): ZIO[Scope, Throwable, FeatureFlags] = {
    val api = OpenFeatureAPI.createIsolated()
    FeatureFlags.build(
      new SimpleProvider(evalDelayMillis),
      domain = Some(s"hook-hints-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = hooks,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(api),
      evaluationTimeout = Some(5.seconds)
    )
  }

  def spec = suite("HookHintsImmutabilitySpec")(
    test("before can modify the evaluation context and later stages observe it (Option[EvaluationContext])") {
      ZIO.scoped {
        for {
          sawMarker <- Ref.make(false)
          hook = new FeatureHook {
            override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
              ZIO.succeed(Some(ctx.evaluationContext.withAttribute("marker", AttributeValue.StringValue("x"))))
            override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
              sawMarker.set(ctx.evaluationContext.getString("marker").contains("x"))
          }
          ff  <- buildFF(List(hook))
          _   <- ff.boolean("flag", default = false)
          saw <- sawMarker.get
        } yield assertTrue(saw)
      }
    },
    test("a before hook cannot alter the hints seen by later stages") {
      ZIO.scoped {
        for {
          beforeHints <- Ref.make("")
          afterHints  <- Ref.make("")
          hook = new FeatureHook {
            override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
              beforeHints.set(hints.toString).as(None)
            override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
              afterHints.set(hints.toString)
          }
          ff <- buildFF(List(hook))
          _  <- ff.boolean("flag", default = false)
          b  <- beforeHints.get
          a  <- afterHints.get
        } yield assertTrue(b == a)
      }
    },
    test("the built-in metrics hook reports a real (non-zero) duration via HookData, not hints") {
      ZIO.scoped {
        for {
          captured <- Ref.make[Option[Duration]](None)
          hook = FeatureHook.metrics((_, dur, _) => captured.set(Some(dur)))
          // 60ms provider delay between the metrics hook's before and after: a duration >= 40ms proves the start time
          // was genuinely captured in HookData (a broken capture falls back to `end`, giving a zero duration).
          ff <- buildFF(List(hook), evalDelayMillis = 60L)
          _  <- ff.boolean("flag", default = false)
          c  <- captured.get
        } yield assertTrue(c.exists(_ >= 40.millis))
      }
    },
    test("two composed metrics hooks each measure their own duration (per-hook HookData scoping)") {
      ZIO.scoped {
        for {
          cap1 <- Ref.make[Option[Duration]](None)
          cap2 <- Ref.make[Option[Duration]](None)
          h1 = FeatureHook.metrics((_, dur, _) => cap1.set(Some(dur)))
          // A hook between the two metrics hooks whose `before` sleeps, so h1.before and h2.before capture start times
          // ~120ms apart. If the two metrics hooks shared one HookData (scoping bug), h2.before would clobber h1's
          // start time and both durations would be ~equal; per-hook scoping keeps h1's duration the larger by ~120ms.
          spacer = new FeatureHook {
            override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
              ZIO.sleep(120.millis).as(None)
          }
          h2 = FeatureHook.metrics((_, dur, _) => cap2.set(Some(dur)))
          ff <- buildFF(List(h1, spacer, h2))
          _  <- ff.boolean("flag", default = false)
          d1 <- cap1.get
          d2 <- cap2.get
        } yield assertTrue(
          d1.isDefined,
          d2.isDefined,
          d1.exists(a => d2.exists(b => a >= b + 80.millis)) // h1 started ~120ms before h2 → independent start times
        )
      }
    }
  ) @@ sequential @@ withLiveClock
}
