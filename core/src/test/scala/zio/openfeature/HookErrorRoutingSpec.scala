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

/** Verifies the hook-stage routing required by spec §4.3.6/§4.4.6, §4.3.8/§4.4.7 and §4.4.5/§4.4.8:
  *   - an error-code resolution (FLAG_NOT_FOUND, ...) runs the `error` stage, NOT `after`;
  *   - a clean resolution runs `after`, NOT `error`;
  *   - a defect in a `before` hook still runs `error` and `finallyAfter` (never skipped);
  *   - a defect in an `after` hook runs `error`, and `finallyAfter` receives the default-valued ERROR details.
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

  /** after() records then dies (optionally so does error()); records every stage and what `finallyAfter` received. */
  private def afterDiesHook(
    log: Ref[List[String]],
    finallyDetails: Ref[Option[Option[FlagResolution[_]]]],
    errorAlsoDies: Boolean = false
  ): FeatureHook = new FeatureHook {
    override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
      log.update(_ :+ "before").as(None)
    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      log.update(_ :+ "after") *> ZIO.die(new RuntimeException("defect in after"))
    override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
      log.update(_ :+ "error") *> ZIO.when(errorAlsoDies)(ZIO.die(new RuntimeException("defect in error"))).unit
    override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
      log.update(_ :+ "finallyAfter") *> finallyDetails.set(Some(details))
  }

  private def afterRecorder(log: Ref[List[String]], name: String): FeatureHook = new FeatureHook {
    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      log.update(_ :+ name)
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
    test("a defect in an after hook runs `error` and `finallyAfter`, and still dies on the typed tier (spec §4.4.5)") {
      ZIO.scoped {
        for {
          log    <- Ref.make[List[String]](Nil)
          fin    <- Ref.make[Option[Option[FlagResolution[_]]]](None)
          ff     <- buildFF(List(afterDiesHook(log, fin)))
          result <- ff.booleanDetails("ok", default = false).sandbox.either
          calls  <- log.get
        } yield assertTrue(
          result.left.exists(_.dieOption.map(_.getMessage).contains("defect in after")),
          calls == List("before", "after", "error", "finallyAfter")
        )
      }
    },
    test("after an after-hook defect, `finallyAfter` receives the default value with ERROR/GENERAL (spec §4.4.8)") {
      ZIO.scoped {
        for {
          log <- Ref.make[List[String]](Nil)
          fin <- Ref.make[Option[Option[FlagResolution[_]]]](None)
          ff  <- buildFF(List(afterDiesHook(log, fin)))
          _   <- ff.booleanDetails("ok", default = false).sandbox.either
          got <- fin.get
        } yield assertTrue(
          got.flatten.exists(d =>
            d.flagKey == "ok" && d.value == false && d.variant.isEmpty && d.reason == ResolutionReason.Error &&
              d.errorCode.contains(zio.openfeature.ErrorCode.General) && d.errorMessage.exists(
                _.contains("defect in after")
              )
          )
        )
      }
    },
    test("the details `finallyAfter` receives match what the total tier serves for the same after-hook defect") {
      ZIO.scoped {
        for {
          log    <- Ref.make[List[String]](Nil)
          fin    <- Ref.make[Option[Option[FlagResolution[_]]]](None)
          ff     <- buildFF(List(afterDiesHook(log, fin)))
          served <- ff.resolveOrDefault[Boolean]("ok", true)
          got    <- fin.get
        } yield assertTrue(
          served.value,
          served.errorCode.contains(zio.openfeature.ErrorCode.General),
          got.flatten.exists(d =>
            d.value == served.value && d.variant == served.variant && d.reason == served.reason &&
              d.errorCode == served.errorCode && d.errorMessage == served.errorMessage && d.flagKey == served.flagKey
          )
        )
      }
    },
    test("once an after hook dies, the remaining after hooks do not run (spec §4.4.6)") {
      ZIO.scoped {
        for {
          log <- Ref.make[List[String]](Nil)
          fin <- Ref.make[Option[Option[FlagResolution[_]]]](None)
          // `after` runs in reverse registration order, so the dying hook (registered last) runs first.
          ff    <- buildFF(List(afterRecorder(log, "remaining-after"), afterDiesHook(log, fin)))
          _     <- ff.booleanDetails("ok", default = false).sandbox.either
          calls <- log.get
        } yield assertTrue(calls.contains("after"), !calls.contains("remaining-after"), calls.contains("error"))
      }
    },
    test("a defect in the error stage is combined with the after-hook defect, never replaces it") {
      ZIO.scoped {
        for {
          log    <- Ref.make[List[String]](Nil)
          fin    <- Ref.make[Option[Option[FlagResolution[_]]]](None)
          ff     <- buildFF(List(afterDiesHook(log, fin, errorAlsoDies = true)))
          result <- ff.booleanDetails("ok", default = false).sandbox.either
          calls  <- log.get
          got    <- fin.get
        } yield assertTrue(
          result.left.exists(_.defects.map(_.getMessage).toSet == Set("defect in after", "defect in error")),
          calls.contains("finallyAfter"),
          got.flatten.exists(d => d.value == false && d.reason == ResolutionReason.Error)
        )
      }
    },
    test("an interruption during after does not run `error`, but `finallyAfter` still runs") {
      ZIO.scoped {
        for {
          log  <- Ref.make[List[String]](Nil)
          fin  <- Ref.make[Option[Option[FlagResolution[_]]]](None)
          gate <- Promise.make[Nothing, Unit]
          blockingHook = new FeatureHook {
            override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
              log.update(_ :+ "after") *> gate.await
            override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
              log.update(_ :+ "error")
            override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints)
              : UIO[Unit] =
              log.update(_ :+ "finallyAfter") *> fin.set(Some(details))
          }
          ff    <- buildFF(List(blockingHook))
          fiber <- ff.booleanDetails("ok", default = false).fork
          _     <- log.get.repeatUntil(_.contains("after"))
          _     <- fiber.interrupt
          calls <- log.get
          got   <- fin.get
        } yield assertTrue(!calls.contains("error"), calls.contains("finallyAfter"), got.contains(None))
      }
    },
    test("a defect in a before hook still hands `finallyAfter` no details (settled in #19)") {
      ZIO.scoped {
        for {
          log <- Ref.make[List[String]](Nil)
          fin <- Ref.make[Option[Option[FlagResolution[_]]]](None)
          hook = new FeatureHook {
            override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
              ZIO.die(new RuntimeException("defect in before"))
            override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints)
              : UIO[Unit] =
              fin.set(Some(details))
          }
          ff  <- buildFF(List(hook))
          _   <- ff.booleanDetails("ok", default = false).sandbox.either
          got <- fin.get
        } yield assertTrue(got.contains(None))
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
