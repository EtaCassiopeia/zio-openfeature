package zio.openfeature
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
import zio._
import zio.test._
import zio.test.TestAspect.{withLiveClock, sequential}

/** Documents and verifies the hook-defect contract.
  *
  * Per the FeatureHook scaladoc (Hook.scala §Error semantics): "Defects (`die`) in a hook will still propagate and fail
  * the evaluation fiber, so wrap genuinely untrusted code in `ZIO.attempt(...).ignoreLogged` or similar."
  *
  * These tests confirm that contract: a hook throwing a RuntimeException surfaces as a ZIO defect at the evaluation
  * call site, callers can absorb it via `.sandbox`/`.catchAllCause`, and a safe hook in the same chain still runs to
  * completion.
  */
object HookDefectSpec extends ZIOSpecDefault {

  private class SimpleProvider extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Simple" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()

    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](true, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  // A hook stage that throws synchronously (not in a ZIO effect), which ZIO converts to a Die defect.
  private def throwingHook(stageName: String, calledRef: Ref[List[String]]): FeatureHook = new FeatureHook {
    override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
      calledRef.update(_ :+ s"$stageName.before") *>
        ZIO.die(new RuntimeException(s"defect in $stageName.before"))

    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      calledRef.update(_ :+ s"$stageName.after") *>
        ZIO.die(new RuntimeException(s"defect in $stageName.after"))

    override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
      calledRef.update(_ :+ s"$stageName.error") *>
        ZIO.die(new RuntimeException(s"defect in $stageName.error"))

    override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
      calledRef.update(_ :+ s"$stageName.finallyAfter") *>
        ZIO.die(new RuntimeException(s"defect in $stageName.finallyAfter"))
  }

  private def safeHook(calledRef: Ref[List[String]]): FeatureHook = new FeatureHook {
    override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
      calledRef.update(_ :+ "safe.before").as(None)

    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      calledRef.update(_ :+ "safe.after")

    override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
      calledRef.update(_ :+ "safe.finallyAfter")
  }

  private def buildFF(hooks: List[FeatureHook]): ZIO[Scope, Throwable, FeatureFlags] = {
    val api    = OpenFeatureAPI.createIsolated()
    val domain = s"hook-defect-${java.util.UUID.randomUUID()}"
    FeatureFlags.build(
      new SimpleProvider,
      domain = Some(domain),
      version = None,
      initialHooks = hooks,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(api),
      evaluationTimeout = Some(5.seconds)
    )
  }

  def spec = suite("HookDefectSpec")(
    test("a hook that dies in `before` surfaces as a ZIO defect — caller absorbs via .sandbox") {
      ZIO.scoped {
        for {
          log <- Ref.make[List[String]](Nil)
          hook = throwingHook("bad", log)
          ff <- buildFF(List(hook))
          // .sandbox converts the Die into a typed Failure(Cause.die(...)) — no fiber is killed
          result <- ff.boolean("flag", default = false).sandbox.either
          calls  <- log.get
        } yield assertTrue(
          result.isLeft,
          result.left.exists(_.isDie),
          calls.contains("bad.before")
        )
      }
    },
    test("a hook that dies does NOT prevent a preceding safe hook from running before") {
      ZIO.scoped {
        for {
          log <- Ref.make[List[String]](Nil)
          safe = safeHook(log)
          bad  = throwingHook("bad", log)
          // safe hook is first in the chain; the die in bad.before happens after safe.before
          ff    <- buildFF(List(safe, bad))
          _     <- ff.boolean("flag", default = false).sandbox.either
          calls <- log.get
        } yield assertTrue(calls.contains("safe.before"))
      }
    },
    test("a defect from a hook stage does not kill sibling fibers") {
      ZIO.scoped {
        for {
          log <- Ref.make[List[String]](Nil)
          hook = throwingHook("bad", log)
          ff <- buildFF(List(hook))
          // Run two fibers concurrently; each absorbs defects individually
          r1 <- ff.boolean("flag", default = false).sandbox.either.fork
          r2 <- ff.boolean("flag", default = false).sandbox.either.fork
          e1 <- r1.join
          e2 <- r2.join
        } yield assertTrue(
          e1.isLeft && e1.left.exists(_.isDie),
          e2.isLeft && e2.left.exists(_.isDie)
        )
      }
    },
    test("a safe hook wrapped in ZIO.attempt.ignoreLogged does not propagate defects") {
      ZIO.scoped {
        val called = new java.util.concurrent.atomic.AtomicBoolean(false)
        // This is the RECOMMENDED pattern: wrap untrusted hook code
        val safeWrapped = new FeatureHook {
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
            ZIO
              .attempt {
                called.set(true)
                throw new RuntimeException("boom from third-party hook code")
              }
              .ignoreLogged
              .as(None)
        }
        for {
          ff <- buildFF(List(safeWrapped))
          // Evaluation must succeed — the defect was absorbed inside the hook
          result <- ff.boolean("flag", default = false)
        } yield assertTrue(result == true, called.get())
      }
    }
  ) @@ sequential @@ withLiveClock
}
