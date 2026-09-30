package zio.openfeature

import zio._
import zio.test._
import zio.test.TestAspect.{sequential, withLiveClock}
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  ErrorCode,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  ProviderState,
  Value
}

/** Verifies the hook-stage routing required by spec §4.3.6/§4.4.6 and §4.3.8/§4.4.7:
  *   - an error-code resolution (FLAG_NOT_FOUND, ...) runs the `error` stage, NOT `after`;
  *   - a clean resolution runs `after`, NOT `error`;
  *   - a defect in a `before` hook still runs `error` and `finallyAfter` (never skipped);
  *   - a defect in an `after` hook runs `error` and hands `finallyAfter` default-valued ERROR details (§4.4.5/§4.4.8).
  */
object HookErrorRoutingSpec extends ZIOSpecDefault {

  /** "missing" → an error-code resolution (FLAG_NOT_FOUND); anything else → a clean STATIC true. */
  private class RoutingProvider extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Routing" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      if (k == "missing") ProviderEvaluations.error[java.lang.Boolean](d, ErrorCode.FLAG_NOT_FOUND, "not found")
      else ProviderEvaluations.of[java.lang.Boolean](true, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  private def recordingHook(log: Ref[List[String]]): FeatureHook = new FeatureHook {
    override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
      log.update(_ :+ "before").as(None)
    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      log.update(_ :+ "after")
    override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
      log.update(_ :+ "error")
    override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
      log.update(_ :+ "finallyAfter")
  }

  /** before() records then dies; the other stages record safely — so we can assert error/finally run after a
    * before-hook defect.
    */
  private def beforeDiesHook(log: Ref[List[String]]): FeatureHook = new FeatureHook {
    override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
      log.update(_ :+ "before") *> ZIO.die(new RuntimeException("defect in before"))
    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      log.update(_ :+ "after")
    override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
      log.update(_ :+ "error")
    override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
      log.update(_ :+ "finallyAfter")
  }

  /** after() records then dies; the other stages record safely, and `finallyAfter` captures the details it is handed.
    */
  private def afterDiesHook(
    log: Ref[List[String]],
    finallyDetails: Ref[Option[Option[FlagResolution[_]]]],
    errorDies: Boolean = false
  ): FeatureHook = new FeatureHook {
    override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
      log.update(_ :+ "before").as(None)
    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      log.update(_ :+ "after") *> ZIO.die(new RuntimeException("defect in after"))
    override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
      log.update(_ :+ "error") *> ZIO.when(errorDies)(ZIO.die(new RuntimeException("defect in error"))).unit
    override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
      log.update(_ :+ "finallyAfter") *> finallyDetails.set(Some(details))
  }

  private def buildFF(hooks: List[FeatureHook]): ZIO[Scope, Throwable, FeatureFlags] = {
    val api = OpenFeatureAPI.createIsolated()
    FeatureFlags.build(
      new RoutingProvider,
      domain = Some(s"hook-routing-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = hooks,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(api),
      evaluationTimeout = Some(5.seconds)
    )
  }

  def spec = suite("HookErrorRoutingSpec")(
    test("an error-code resolution runs `error`, not `after` (spec §4.3.6/§4.4.6)") {
      ZIO.scoped {
        for {
          log   <- Ref.make[List[String]](Nil)
          ff    <- buildFF(List(recordingHook(log)))
          v     <- ff.boolean("missing", default = false).either // the typed tier now fails on the code (#388)
          calls <- log.get
        } yield assertTrue(
          v == Left(FeatureFlagError.FlagNotFound("missing")),
          calls.contains("before"),
          calls.contains("error"),
          !calls.contains("after"),
          calls.contains("finallyAfter")
        )
      }
    },
    test("a clean resolution runs `after`, not `error`") {
      ZIO.scoped {
        for {
          log   <- Ref.make[List[String]](Nil)
          ff    <- buildFF(List(recordingHook(log)))
          v     <- ff.boolean("ok", default = false)
          calls <- log.get
        } yield assertTrue(
          v,
          calls.contains("before"),
          calls.contains("after"),
          !calls.contains("error"),
          calls.contains("finallyAfter")
        )
      }
    },
    test("a defect in a before hook still runs `error` and `finallyAfter` (spec §4.3.8/§4.4.7)") {
      ZIO.scoped {
        for {
          log    <- Ref.make[List[String]](Nil)
          ff     <- buildFF(List(beforeDiesHook(log)))
          result <- ff.boolean("ok", default = false).sandbox.either
          calls  <- log.get
        } yield assertTrue(
          result.isLeft,
          result.left.exists(_.isDie),
          calls.contains("before"),
          calls.contains("error"),
          !calls.contains("after"), // a before-defect never reaches the success/after branch
          calls.contains("finallyAfter")
        )
      }
    },
    test("a defect in an after hook runs `error` and `finallyAfter`, and the typed tier still dies (spec §4.4.5)") {
      ZIO.scoped {
        for {
          log     <- Ref.make[List[String]](Nil)
          details <- Ref.make(Option.empty[Option[FlagResolution[_]]])
          ff      <- buildFF(List(afterDiesHook(log, details)))
          result  <- ff.booleanDetails("ok", default = false).sandbox.either
          calls   <- log.get
        } yield assertTrue(
          result.left.exists(_.isDie),
          calls == List("before", "after", "error", "finallyAfter")
        )
      }
    },
    test("after an after-hook defect, `finallyAfter` receives default-valued ERROR/GENERAL details (spec §4.4.8)") {
      ZIO.scoped {
        for {
          log     <- Ref.make[List[String]](Nil)
          details <- Ref.make(Option.empty[Option[FlagResolution[_]]])
          ff      <- buildFF(List(afterDiesHook(log, details)))
          _       <- ff.booleanDetails("ok", default = false).sandbox.either
          seen    <- details.get
          total   <- ff.resolveOrDefault("ok", false)
        } yield {
          val d = seen.flatten
          assertTrue(
            d.exists(_.value == false),
            d.exists(_.variant.isEmpty),
            d.exists(_.reason == ResolutionReason.Error),
            d.exists(_.errorCode.contains(zio.openfeature.ErrorCode.General)),
            d.exists(_.flagKey == "ok"),
            // identical to what the total tier serves for the same evaluation
            d.exists(r =>
              r.value == total.value && r.variant == total.variant && r.reason == total.reason &&
                r.errorCode == total.errorCode && r.errorMessage == total.errorMessage
            )
          )
        }
      }
    },
    test("once an after hook dies, the remaining after hooks do not run (spec §4.4.6)") {
      ZIO.scoped {
        for {
          log     <- Ref.make[List[String]](Nil)
          second  <- Ref.make[List[String]](Nil)
          details <- Ref.make(Option.empty[Option[FlagResolution[_]]])
          // `after` runs in reverse registration order (spec 4.4.2), so the dying hook is registered last to run first.
          ff     <- buildFF(List(recordingHook(second), afterDiesHook(log, details)))
          _      <- ff.boolean("ok", default = false).sandbox.either
          calls2 <- second.get
        } yield assertTrue(
          !calls2.contains("after"),
          calls2.contains("error"),
          calls2.contains("finallyAfter")
        )
      }
    },
    test("an interruption during after does not run `error`, but `finallyAfter` still runs") {
      ZIO.scoped {
        for {
          log  <- Ref.make[List[String]](Nil)
          gate <- Promise.make[Nothing, Unit]
          blockingHook = new FeatureHook {
            override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
              log.update(_ :+ "after") *> gate.await
            override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
              log.update(_ :+ "error")
            override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints)
              : UIO[Unit] =
              log.update(_ :+ "finallyAfter")
          }
          ff    <- buildFF(List(blockingHook))
          fiber <- ff.boolean("ok", default = false).fork
          _     <- log.get.repeatUntil(_.contains("after"))
          _     <- fiber.interrupt
          calls <- log.get
        } yield assertTrue(
          calls.contains("after"),
          !calls.contains("error"),
          calls.contains("finallyAfter")
        )
      }
    },
    test("a defect in the error stage after an after-hook defect is combined with, never replaces, the after cause") {
      ZIO.scoped {
        for {
          log     <- Ref.make[List[String]](Nil)
          details <- Ref.make(Option.empty[Option[FlagResolution[_]]])
          ff      <- buildFF(List(afterDiesHook(log, details, errorDies = true)))
          result  <- ff.boolean("ok", default = false).sandbox.either
          calls   <- log.get
        } yield {
          val messages = result.left.toOption.toList.flatMap(_.defects).map(_.getMessage)
          assertTrue(
            messages.contains("defect in after"),
            messages.contains("defect in error"),
            calls.contains("finallyAfter")
          )
        }
      }
    },
    test("an interruption during before does not run `error`, but `finallyAfter` still runs") {
      ZIO.scoped {
        for {
          log  <- Ref.make[List[String]](Nil)
          gate <- Promise.make[Nothing, Unit]
          blockingHook = new FeatureHook {
            override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
              log.update(_ :+ "before") *> gate.await.as(None)
            override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
              log.update(_ :+ "after")
            override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
              log.update(_ :+ "error")
            override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints)
              : UIO[Unit] =
              log.update(_ :+ "finallyAfter")
          }
          ff    <- buildFF(List(blockingHook))
          fiber <- ff.boolean("ok", default = false).fork
          _     <- log.get.repeatUntil(_.contains("before")) // wait until before is running (and blocked on the gate)
          _     <- fiber.interrupt                           // interrupt returns only after finalizers have run
          calls <- log.get
        } yield assertTrue(
          calls.contains("before"),
          !calls.contains("error"), // cancellation is not a hook failure
          calls.contains("finallyAfter")
        )
      }
    },
    test("the built-in metrics hook counts a FLAG_NOT_FOUND resolution once (failure), not twice") {
      ZIO.scoped {
        for {
          counts <- Ref.make[List[Boolean]](Nil)
          hook = FeatureHook.metrics((_, _, success) => counts.update(_ :+ success))
          ff <- buildFF(List(hook))
          _  <- ff.boolean("missing", default = false).either
          cs <- counts.get
        } yield assertTrue(cs == List(false)) // exactly one call, marked failure — not success+failure
      }
    }
  ) @@ sequential @@ withLiveClock
}
