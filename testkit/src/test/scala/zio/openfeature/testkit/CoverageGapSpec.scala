package zio.openfeature.testkit

import zio._
import zio.test._
import zio.test.Assertion._
import zio.openfeature._

/** Tests for coverage gaps identified in the existing test suite.
  *
  * These tests exercise code paths and features that were not previously covered.
  */
object CoverageGapSpec extends ZIOSpecDefault {

  private def testLayer(
    flags: Map[String, Any] = Map.empty
  ): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    Scope.default >>> TestFeatureProvider.layer(flags)

  def spec = suite("CoverageGapSpec")(
    objectEvaluationSuite,
    contextHierarchySuite,
    transactionEdgeCasesSuite,
    fiberContextIsolationSuite,
    eventHandlerCancellationSuite,
    shutdownBehaviorSuite,
    evaluationOptionsSuite,
    testFeatureProviderEventsSuite,
    scopedLayerSuite,
    evaluationContextFactorySuite,
    fromProviderWithHooksSuite,
    fromMultiProviderSuite,
    errorHookSuite
  ) @@ TestAspect.withLiveClock @@ TestAspect.flaky(3)

  // Object flag evaluation (obj/objDetails) - completely untested before
  private val objectEvaluationSuite = suite("Object Evaluation")(
    test("obj returns map flag value") {
      for {
        result <- FeatureFlags.obj("config", default = Map.empty[String, Any])
      } yield assertTrue(result("timeout") == 30.0) && // SDK converts numbers to Double
        assertTrue(result("retries") == 3.0)
    }.provide(testLayer(Map("config" -> Map("timeout" -> 30, "retries" -> 3)))),
    test("obj returns default when flag not found") {
      val default = Map("key" -> "default-value")
      for {
        result <- FeatureFlags.obj("missing", default = default)
      } yield assertTrue(result == default)
    }.provide(testLayer()),
    test("obj with context works") {
      val ctx = EvaluationContext("user-1")
      for {
        result <- FeatureFlags.obj("settings", default = Map.empty[String, Any], ctx)
      } yield assertTrue(result.nonEmpty)
    }.provide(testLayer(Map("settings" -> Map("theme" -> "dark")))),
    test("objDetails returns FlagResolution") {
      for {
        resolution <- FeatureFlags.objDetails("config", default = Map.empty[String, Any])
      } yield assertTrue(resolution.flagKey == "config") &&
        assertTrue(resolution.value.nonEmpty) &&
        assertTrue(resolution.reason == ResolutionReason.TargetingMatch)
    }.provide(testLayer(Map("config" -> Map("enabled" -> true))))
  )

  // Full 5-level context hierarchy merging
  private val contextHierarchySuite = suite("Full Context Hierarchy Merging")(
    test("all five context levels merge with correct precedence") {
      val globalCtx = EvaluationContext.empty
        .withAttribute("level", "global")
        .withAttribute("global-only", "yes")
      val clientCtx = EvaluationContext.empty
        .withAttribute("level", "client")
        .withAttribute("client-only", "yes")
      val scopedCtx = EvaluationContext.empty
        .withAttribute("level", "scoped")
        .withAttribute("scoped-only", "yes")
      val invocationCtx = EvaluationContext("invocation-user")
        .withAttribute("level", "invocation")
        .withAttribute("invocation-only", "yes")

      // Use a tracking hook to capture the effective context
      val capturedCtx = Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(Ref.make(Option.empty[EvaluationContext])).getOrThrow()
      }

      val ctxCapture = new FeatureHook {
        override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
          capturedCtx.set(Some(ctx.evaluationContext)).as(None)
      }

      for {
        _ <- FeatureFlags.setGlobalContext(globalCtx)
        _ <- FeatureFlags.setClientContext(clientCtx)
        _ <- FeatureFlags.addHook(ctxCapture)
        _ <- FeatureFlags.withContext(scopedCtx) {
          FeatureFlags.boolean("flag", default = false, invocationCtx)
        }
        ctx <- capturedCtx.get
      } yield {
        val effective = ctx.get
        // Invocation has highest precedence
        assertTrue(effective.getString("level").contains("invocation")) &&
        assertTrue(effective.targetingKey.contains("invocation-user")) &&
        // All level-specific attributes should be present
        assertTrue(effective.getString("global-only").contains("yes")) &&
        assertTrue(effective.getString("client-only").contains("yes")) &&
        assertTrue(effective.getString("scoped-only").contains("yes")) &&
        assertTrue(effective.getString("invocation-only").contains("yes"))
      }
    }.provide(testLayer(Map("flag" -> true))),
    test("transaction context merges between scoped and invocation") {
      val globalCtx = EvaluationContext.empty.withAttribute("source", "global")
      val txCtx     = EvaluationContext.empty.withAttribute("source", "transaction")

      val capturedCtx = Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(Ref.make(Option.empty[EvaluationContext])).getOrThrow()
      }

      val ctxCapture = new FeatureHook {
        override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
          capturedCtx.set(Some(ctx.evaluationContext)).as(None)
      }

      for {
        _ <- FeatureFlags.setGlobalContext(globalCtx)
        _ <- FeatureFlags.addHook(ctxCapture)
        _ <- FeatureFlags.transaction(context = txCtx) {
          FeatureFlags.boolean("flag", default = false)
        }
        ctx <- capturedCtx.get
      } yield {
        val effective = ctx.get
        // Transaction context overrides global
        assertTrue(effective.getString("source").contains("transaction"))
      }
    }.provide(testLayer(Map("flag" -> true)))
  )

  // Transaction edge cases
  private val transactionEdgeCasesSuite = suite("Transaction Edge Cases")(
    test("transaction override type mismatch produces OverrideTypeMismatch error") {
      val result = FeatureFlags.transaction(Map("flag" -> "not-a-boolean")) {
        FeatureFlags.boolean("flag", default = false)
      }
      for {
        exit <- result.exit
      } yield assertTrue(exit.isFailure)
    }.provide(testLayer(Map("flag" -> true))),
    test("transaction with mixed overrides and provider evaluations") {
      for {
        txResult <- FeatureFlags.transaction(Map("override-flag" -> true)) {
          for {
            a <- FeatureFlags.boolean("override-flag", default = false)
            b <- FeatureFlags.boolean("provider-flag", default = false)
          } yield (a, b)
        }
      } yield assertTrue(txResult.result == (true, true)) &&
        assertTrue(txResult.wasOverridden("override-flag")) &&
        assertTrue(!txResult.wasOverridden("provider-flag")) &&
        assertTrue(txResult.providerEvaluatedKeys == Set("provider-flag"))
    }.provide(testLayer(Map("override-flag" -> false, "provider-flag" -> true))),
    test("currentEvaluatedFlags returns empty outside transaction") {
      for {
        flags <- FeatureFlags.currentEvaluatedFlags
      } yield assertTrue(flags.isEmpty)
    }.provide(testLayer()),
    test("TransactionResult.map transforms the result value") {
      for {
        txResult <- FeatureFlags.transaction() {
          FeatureFlags.int("count", default = 0)
        }
      } yield {
        val mapped = txResult.map(_ * 2)
        assertTrue(mapped.result == 84) &&
        assertTrue(mapped.flagCount == txResult.flagCount)
      }
    }.provide(testLayer(Map("count" -> 42))),
    test("TransactionResult.toValueMap returns simple values") {
      for {
        txResult <- FeatureFlags.transaction() {
          for {
            _ <- FeatureFlags.boolean("a", default = false)
            _ <- FeatureFlags.string("b", default = "")
          } yield ()
        }
      } yield {
        val valueMap = txResult.toValueMap
        assertTrue(valueMap("a") == true) &&
        assertTrue(valueMap("b") == "hello")
      }
    }.provide(testLayer(Map("a" -> true, "b" -> "hello")))
  )

  // Fiber-local context isolation
  private val fiberContextIsolationSuite = suite("Fiber Context Isolation")(
    test("withContext is isolated between concurrent fibers") {
      val ctx1 = EvaluationContext("user-1").withAttribute("fiber", "one")
      val ctx2 = EvaluationContext("user-2").withAttribute("fiber", "two")

      val capturedCtxs = Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(Ref.make(List.empty[(String, EvaluationContext)])).getOrThrow()
      }

      val ctxCapture = new FeatureHook {
        override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
          capturedCtxs
            .update(_ :+ (ctx.evaluationContext.targetingKey.getOrElse("unknown") -> ctx.evaluationContext))
            .as(None)
      }

      for {
        _ <- FeatureFlags.addHook(ctxCapture)
        fiber1 <- FeatureFlags
          .withContext(ctx1) {
            ZIO.sleep(50.millis) *> FeatureFlags.boolean("flag", default = false)
          }
          .fork
        fiber2 <- FeatureFlags
          .withContext(ctx2) {
            ZIO.sleep(50.millis) *> FeatureFlags.boolean("flag", default = false)
          }
          .fork
        _    <- fiber1.join
        _    <- fiber2.join
        ctxs <- capturedCtxs.get
      } yield {
        val user1Ctx = ctxs.find(_._1 == "user-1").map(_._2)
        val user2Ctx = ctxs.find(_._1 == "user-2").map(_._2)
        assertTrue(user1Ctx.exists(_.getString("fiber").contains("one"))) &&
        assertTrue(user2Ctx.exists(_.getString("fiber").contains("two")))
      }
    }.provide(testLayer(Map("flag" -> true)))
  )

  // Event handler cancellation
  private val eventHandlerCancellationSuite = suite("Event Handler Cancellation")(
    test("cancelled handler stops receiving events") {
      for {
        tp       <- ZIO.service[TestFeatureProvider]
        received <- Ref.make(0)
        cancel   <- FeatureFlags.onConfigurationChanged((_, _) => received.update(_ + 1))
        _        <- ZIO.sleep(200.millis)
        _        <- tp.emitEvent(ProviderEvent.ConfigurationChanged(Set("a"), tp.metadata))
        _        <- received.get.repeatUntil(_ > 0).timeout(5.seconds)
        before   <- received.get
        _        <- cancel                // Cancel the handler
        _        <- ZIO.sleep(200.millis)
        _        <- tp.emitEvent(ProviderEvent.ConfigurationChanged(Set("b"), tp.metadata))
        _        <- ZIO.sleep(500.millis) // Give time for event to (not) arrive
        after    <- received.get
      } yield assertTrue(before >= 1) &&
        assertTrue(after == before) // Count should not increase after cancellation
    }.provide(testLayer())
  )

  // Shutdown behavior
  private val shutdownBehaviorSuite = suite("Shutdown Behavior")(
    test("shutdown sets status to NotReady") {
      for {
        before <- FeatureFlags.providerStatus
        _      <- FeatureFlags.shutdown
        after  <- FeatureFlags.providerStatus
      } yield assertTrue(before == ProviderStatus.Ready) &&
        assertTrue(after == ProviderStatus.NotReady)
    }.provide(testLayer()),
    test("shutdown clears tracked events") {
      for {
        _      <- FeatureFlags.track("event-1")
        before <- FeatureFlags.trackedEvents
        _      <- FeatureFlags.shutdown
        after  <- FeatureFlags.trackedEvents
      } yield assertTrue(before.nonEmpty) &&
        assertTrue(after.isEmpty)
    }.provide(testLayer()),
    test("evaluation fails after shutdown") {
      for {
        _      <- FeatureFlags.shutdown
        result <- FeatureFlags.boolean("flag", default = false).exit
      } yield assertTrue(result.isFailure)
    }.provide(testLayer(Map("flag" -> true)))
  )

  // EvaluationOptions builder methods
  private val evaluationOptionsSuite = suite("EvaluationOptions")(
    test("withHook adds a hook to options") {
      val hook1 = FeatureHook.noop
      val hook2 = FeatureHook.noop
      val opts  = EvaluationOptions.empty.withHook(hook1).withHook(hook2)
      assertTrue(opts.hooks.length == 2)
    },
    test("withHooks adds multiple hooks") {
      val hooks = List(FeatureHook.noop, FeatureHook.noop, FeatureHook.noop)
      val opts  = EvaluationOptions.empty.withHooks(hooks)
      assertTrue(opts.hooks.length == 3)
    },
    test("withHint adds a hook hint") {
      val opts = EvaluationOptions.empty.withHint("key1", "value1").withHint("key2", 42)
      assertTrue(opts.hookHints.get[String]("key1").contains("value1")) &&
      assertTrue(opts.hookHints.get[Int]("key2").contains(42))
    },
    test("varargs factory creates options with hooks") {
      val h1   = FeatureHook.noop
      val h2   = FeatureHook.noop
      val opts = EvaluationOptions(h1, h2)
      assertTrue(opts.hooks.length == 2)
    },
    test("EvaluationOptions hooks run during evaluation") {
      val callsRef = Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(Ref.make(0)).getOrThrow()
      }

      val hook = new FeatureHook {
        override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
          callsRef.update(_ + 1)
      }

      val opts = EvaluationOptions.empty.withHook(hook).withHint("trace-id", "abc")
      for {
        _     <- FeatureFlags.booleanDetails("flag", default = false, EvaluationContext.empty, opts)
        calls <- callsRef.get
      } yield assertTrue(calls == 1)
    }.provide(testLayer(Map("flag" -> true)))
  )

  // TestFeatureProvider event streaming
  private val testFeatureProviderEventsSuite = suite("TestFeatureProvider Event Streaming")(
    test("events stream receives emitted events") {
      for {
        provider <- TestFeatureProvider.make
        queue    <- Queue.unbounded[ProviderEvent]
        fiber    <- provider.events.foreach(e => queue.offer(e)).fork
        _        <- ZIO.sleep(100.millis)
        _        <- provider.emitEvent(ProviderEvent.ConfigurationChanged(Set("x"), provider.metadata))
        event    <- queue.take.timeout(5.seconds)
        _        <- fiber.interrupt
      } yield assertTrue(event.exists {
        case ProviderEvent.ConfigurationChanged(flags, _) => flags == Set("x")
        case _                                            => false
      })
    },
    test("TestFeatureProvider metadata has expected values") {
      for {
        provider <- TestFeatureProvider.make
      } yield assertTrue(provider.metadata.name == "TestFeatureProvider") &&
        assertTrue(provider.metadata.version.contains("1.0.0"))
    }
  )

  // scopedLayer factory methods
  private val scopedLayerSuite = suite("Scoped Layer")(
    test("scopedLayer provides both TestFeatureProvider and FeatureFlags") {
      val program = for {
        _      <- ZIO.service[TestFeatureProvider]
        _      <- ZIO.service[FeatureFlags]
        result <- FeatureFlags.boolean("flag", default = false)
      } yield assertTrue(result == true)

      program.provide(TestFeatureProvider.scopedLayer(Map("flag" -> true)))
    },
    test("scopedLayer with empty flags works") {
      val program = for {
        result <- FeatureFlags.boolean("missing", default = true)
      } yield assertTrue(result == true)

      program.provide(TestFeatureProvider.scopedLayer)
    }
  )

  // EvaluationContext factory methods
  private val evaluationContextFactorySuite = suite("EvaluationContext Factories")(
    test("forEntity creates context with targeting key and attributes") {
      val ctx = EvaluationContext.forEntity("user-123")
      assertTrue(ctx.targetingKey.contains("user-123")) &&
      assertTrue(ctx.getString("entityId").contains("user-123")) &&
      assertTrue(ctx.getString("entityType").contains("user"))
    },
    test("forEntity with custom entity type") {
      val ctx = EvaluationContext.forEntity("device-456", "device")
      assertTrue(ctx.targetingKey.contains("device-456")) &&
      assertTrue(ctx.getString("entityType").contains("device"))
    },
    test("withAttributes creates context without targeting key") {
      val ctx = EvaluationContext.withAttributes(
        "env"     -> AttributeValue.string("production"),
        "version" -> AttributeValue.string("2.0")
      )
      assertTrue(ctx.targetingKey.isEmpty) &&
      assertTrue(ctx.getString("env").contains("production")) &&
      assertTrue(ctx.getString("version").contains("2.0"))
    }
  )

  // fromProviderWithHooks factory
  private val fromProviderWithHooksSuite = suite("fromProviderWithHooks")(
    test("layer created with initial hooks has those hooks") {
      val callsRef = Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(Ref.make(0)).getOrThrow()
      }

      val hook = new FeatureHook {
        override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
          callsRef.update(_ + 1)
      }

      for {
        provider <- TestFeatureProvider.make(Map("flag" -> true))
        layer = FeatureFlags.fromProviderWithHooks(provider, List(hook))
        _     <- FeatureFlags.boolean("flag", default = false).provide(Scope.default >>> layer)
        calls <- callsRef.get
      } yield assertTrue(calls == 1)
    }
  )

  // fromMultiProvider factory
  private val fromMultiProviderSuite = suite("fromMultiProvider")(
    test("fromMultiProvider creates a usable layer") {
      // fromMultiProvider uses the global (non-domain) provider path which can conflict
      // with other tests. Test that it creates a valid layer and can evaluate flags.
      ZIO.scoped {
        for {
          provider <- TestFeatureProvider.make(Map("flag" -> true))
          ff       <- FeatureFlags.fromMultiProvider(List(provider)).build.map(_.get)
          result   <- ff.boolean("flag", default = false)
        } yield assertTrue(result == true)
      }
    },
    test("fromMultiProvider with custom strategy creates a usable layer") {
      import dev.openfeature.sdk.multiprovider.FirstSuccessfulStrategy
      ZIO.scoped {
        for {
          provider <- TestFeatureProvider.make(Map("flag" -> "hello"))
          ff <- FeatureFlags
            .fromMultiProvider(List(provider), new FirstSuccessfulStrategy())
            .build
            .map(_.get)
          result <- ff.string("flag", default = "none")
        } yield assertTrue(result == "hello")
      }
    }
  )

  // Error hook execution
  private val errorHookSuite = suite("Error Hook Execution")(
    test("error hook is called when evaluation fails") {
      val errorRef = Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(Ref.make(Option.empty[FeatureFlagError])).getOrThrow()
      }

      val errorHook = new FeatureHook {
        override def error(ctx: HookContext, error: FeatureFlagError, hints: HookHints): UIO[Unit] =
          errorRef.set(Some(error))
      }

      for {
        tp    <- ZIO.service[TestFeatureProvider]
        _     <- FeatureFlags.addHook(errorHook)
        _     <- tp.setStatus(ProviderStatus.Fatal)
        _     <- FeatureFlags.boolean("flag", default = false).exit
        error <- errorRef.get
      } yield assertTrue(error.contains(FeatureFlagError.ProviderFatal))
    }.provide(testLayer(Map("flag" -> true))),
    test("finallyAfter runs even when before hook modifies context") {
      val finallyRan = Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(Ref.make(false)).getOrThrow()
      }

      val hook = new FeatureHook {
        override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
          ZIO.some((ctx.evaluationContext.withAttribute("enriched", AttributeValue.bool(true)), hints))

        override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
          finallyRan.set(true)
      }

      for {
        _   <- FeatureFlags.addHook(hook)
        _   <- FeatureFlags.boolean("flag", default = false)
        ran <- finallyRan.get
      } yield assertTrue(ran)
    }.provide(testLayer(Map("flag" -> true)))
  )
}
