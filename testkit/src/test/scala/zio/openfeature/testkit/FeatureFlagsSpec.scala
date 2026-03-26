package zio.openfeature.testkit

import zio._
import zio.test._
import zio.test.Assertion._
import zio.openfeature._

object FeatureFlagsSpec extends ZIOSpecDefault {

  private def testLayer(
    flags: Map[String, Any] = Map.empty
  ): ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags] =
    Scope.default >>> TestFeatureProvider.layer(flags)

  def spec = suite("FeatureFlagsSpec")(
    suite("Simple Evaluation")(
      test("boolean returns flag value") {
        for {
          result <- FeatureFlags.boolean("dark-mode", default = false)
        } yield assertTrue(result == true)
      }.provide(testLayer(Map("dark-mode" -> true))),
      test("boolean returns default when flag not found") {
        for {
          result <- FeatureFlags.boolean("missing-flag", default = false)
        } yield assertTrue(result == false)
      }.provide(testLayer()),
      test("string returns flag value") {
        for {
          result <- FeatureFlags.string("welcome-message", default = "Hello")
        } yield assertTrue(result == "Welcome!")
      }.provide(testLayer(Map("welcome-message" -> "Welcome!"))),
      test("int returns flag value") {
        for {
          result <- FeatureFlags.int("max-items", default = 10)
        } yield assertTrue(result == 50)
      }.provide(testLayer(Map("max-items" -> 50))),
      test("double returns flag value") {
        for {
          result <- FeatureFlags.double("rate-limit", default = 1.0)
        } yield assertTrue(result == 2.5)
      }.provide(testLayer(Map("rate-limit" -> 2.5))),
      test("long returns flag value via int conversion") {
        for {
          result <- FeatureFlags.long("max-bytes", default = 1000L)
        } yield assertTrue(result == 999999L)
      }.provide(testLayer(Map("max-bytes" -> 999999)))
    ),
    suite("Detailed Evaluation")(
      test("booleanDetails returns FlagResolution") {
        for {
          resolution <- FeatureFlags.booleanDetails("feature-x", default = false)
        } yield assertTrue(resolution.value == true) &&
          assertTrue(resolution.flagKey == "feature-x") &&
          assertTrue(resolution.reason == ResolutionReason.TargetingMatch)
      }.provide(testLayer(Map("feature-x" -> true))),
      test("stringDetails returns default reason when not found") {
        for {
          resolution <- FeatureFlags.stringDetails("missing", default = "default-value")
        } yield assertTrue(resolution.value == "default-value") &&
          assertTrue(resolution.reason == ResolutionReason.Default)
      }.provide(testLayer()),
      test("intDetails includes variant") {
        for {
          resolution <- FeatureFlags.intDetails("variant-flag", default = 0)
        } yield assertTrue(resolution.value == 42)
      }.provide(testLayer(Map("variant-flag" -> 42)))
    ),
    suite("Context Management")(
      test("setGlobalContext and globalContext work") {
        val ctx = EvaluationContext("user-123")
        for {
          _      <- FeatureFlags.setGlobalContext(ctx)
          result <- FeatureFlags.globalContext
        } yield assertTrue(result.targetingKey.contains("user-123"))
      }.provide(testLayer()),
      test("setClientContext and clientContext work") {
        val ctx = EvaluationContext("client-user")
        for {
          _      <- FeatureFlags.setClientContext(ctx)
          result <- FeatureFlags.clientContext
        } yield assertTrue(result.targetingKey.contains("client-user"))
      }.provide(testLayer()),
      test("client context is separate from global context") {
        val globalCtx = EvaluationContext("global-user")
        val clientCtx = EvaluationContext("client-user")
        for {
          _      <- FeatureFlags.setGlobalContext(globalCtx)
          _      <- FeatureFlags.setClientContext(clientCtx)
          global <- FeatureFlags.globalContext
          client <- FeatureFlags.clientContext
        } yield assertTrue(global.targetingKey.contains("global-user")) &&
          assertTrue(client.targetingKey.contains("client-user"))
      }.provide(testLayer()),
      test("withContext scopes context to block") {
        val globalCtx = EvaluationContext("global-user")
        val localCtx  = EvaluationContext("local-user")
        for {
          _         <- FeatureFlags.setGlobalContext(globalCtx)
          globalKey <- FeatureFlags.globalContext.map(_.targetingKey)
          localResult <- FeatureFlags.withContext(localCtx) {
            FeatureFlags.globalContext
          }
        } yield assertTrue(globalKey.contains("global-user")) &&
          assertTrue(localResult.targetingKey.contains("global-user"))
      }.provide(testLayer())
    ),
    suite("Transaction")(
      test("transaction returns result and evaluations") {
        for {
          txResult <- FeatureFlags.transaction() {
            for {
              a <- FeatureFlags.boolean("flag-a", default = false)
              b <- FeatureFlags.int("flag-b", default = 0)
            } yield (a, b)
          }
        } yield assertTrue(txResult.result == (true, 42)) &&
          assertTrue(txResult.flagCount == 2) &&
          assertTrue(txResult.allFlagKeys == Set("flag-a", "flag-b"))
      }.provide(testLayer(Map("flag-a" -> true, "flag-b" -> 42))),
      test("transaction with overrides returns override values") {
        for {
          txResult <- FeatureFlags.transaction(
            overrides = Map("flag-a" -> false, "flag-b" -> 100)
          ) {
            for {
              a <- FeatureFlags.boolean("flag-a", default = true)
              b <- FeatureFlags.int("flag-b", default = 0)
            } yield (a, b)
          }
        } yield assertTrue(txResult.result == (false, 100)) &&
          assertTrue(txResult.overrideCount == 2) &&
          assertTrue(txResult.wasOverridden("flag-a")) &&
          assertTrue(txResult.wasOverridden("flag-b"))
      }.provide(testLayer(Map("flag-a" -> true, "flag-b" -> 42))),
      test("transaction with context applies context") {
        val txCtx = EvaluationContext("tx-user")
        for {
          txResult <- FeatureFlags.transaction(context = txCtx) {
            FeatureFlags.boolean("flag", default = false)
          }
        } yield assertTrue(txResult.result == true)
      }.provide(testLayer(Map("flag" -> true))),
      test("nested transaction fails") {
        val effect = FeatureFlags.transaction() {
          FeatureFlags.transaction() {
            ZIO.unit
          }
        }
        for {
          result <- effect.exit
        } yield assertTrue(result.isFailure)
      }.provide(testLayer()),
      test("inTransaction returns true inside transaction") {
        for {
          outside <- FeatureFlags.inTransaction
          inside <- FeatureFlags.transaction() {
            FeatureFlags.inTransaction
          }
        } yield assertTrue(!outside) &&
          assertTrue(inside.result == true)
      }.provide(testLayer()),
      test("currentEvaluatedFlags returns flags inside transaction") {
        for {
          outsideFlags <- FeatureFlags.currentEvaluatedFlags
          insideResult <- FeatureFlags.transaction() {
            for {
              _     <- FeatureFlags.boolean("flag-x", default = false)
              flags <- FeatureFlags.currentEvaluatedFlags
            } yield flags
          }
        } yield assertTrue(outsideFlags.isEmpty) &&
          assertTrue(insideResult.result.contains("flag-x"))
      }.provide(testLayer(Map("flag-x" -> true))),
      test("transaction caches evaluated flags for subsequent calls") {
        for {
          txResult <- FeatureFlags.transaction() {
            for {
              first  <- FeatureFlags.boolean("cached-flag", default = false)
              second <- FeatureFlags.boolean("cached-flag", default = false)
              third  <- FeatureFlags.boolean("cached-flag", default = false)
            } yield (first, second, third)
          }
        } yield
        // All three evaluations should return the same value
        assertTrue(txResult.result == (true, true, true)) &&
          // Only one evaluation should be recorded (the first one, subsequent ones are cached)
          assertTrue(txResult.flagCount == 1) &&
          // The flag should not be marked as overridden (it was evaluated from provider, then cached)
          assertTrue(!txResult.wasOverridden("cached-flag"))
      }.provide(testLayer(Map("cached-flag" -> true))),
      test("transaction caching returns cached reason for subsequent evaluations") {
        for {
          txResult <- FeatureFlags.transaction() {
            for {
              first  <- FeatureFlags.booleanDetails("detail-flag", default = false)
              second <- FeatureFlags.booleanDetails("detail-flag", default = false)
            } yield (first, second)
          }
        } yield {
          val (firstRes, secondRes) = txResult.result
          // First evaluation should be from provider (TargetingMatch)
          assertTrue(firstRes.reason == ResolutionReason.TargetingMatch) &&
          // Second evaluation should be cached
          assertTrue(secondRes.reason == ResolutionReason.Cached) &&
          // Both should have the same value
          assertTrue(firstRes.value == secondRes.value)
        }
      }.provide(testLayer(Map("detail-flag" -> true))),
      test("transaction with cacheEvaluations=false does not cache") {
        for {
          txResult <- FeatureFlags.transaction(cacheEvaluations = false) {
            for {
              first  <- FeatureFlags.booleanDetails("no-cache-flag", default = false)
              second <- FeatureFlags.booleanDetails("no-cache-flag", default = false)
            } yield (first, second)
          }
        } yield {
          val (firstRes, secondRes) = txResult.result
          // Both evaluations should be from provider (TargetingMatch), not cached
          assertTrue(firstRes.reason == ResolutionReason.TargetingMatch) &&
          assertTrue(secondRes.reason == ResolutionReason.TargetingMatch) &&
          // Both should have the same value
          assertTrue(firstRes.value == secondRes.value) &&
          // With caching disabled, each evaluation is recorded separately
          // but since they have the same key, the map will have only 1 entry (last one wins)
          assertTrue(txResult.flagCount == 1)
        }
      }.provide(testLayer(Map("no-cache-flag" -> true)))
    ),
    suite("Hooks")(
      test("addHook and hooks work") {
        val hook = FeatureHook.noop
        for {
          initial <- FeatureFlags.hooks
          _       <- FeatureFlags.addHook(hook)
          after   <- FeatureFlags.hooks
        } yield assertTrue(initial.isEmpty) &&
          assertTrue(after.length == 1)
      }.provide(testLayer()),
      test("addHooks adds multiple hooks atomically") {
        val hooks = List(FeatureHook.noop, FeatureHook.noop, FeatureHook.noop)
        for {
          _     <- FeatureFlags.addHooks(hooks)
          after <- FeatureFlags.hooks
        } yield assertTrue(after.length == 3)
      }.provide(testLayer()),
      test("clearHooks removes all hooks") {
        val hook = FeatureHook.noop
        for {
          _      <- FeatureFlags.addHook(hook)
          _      <- FeatureFlags.addHook(hook)
          before <- FeatureFlags.hooks
          _      <- FeatureFlags.clearHooks
          after  <- FeatureFlags.hooks
        } yield assertTrue(before.length == 2) &&
          assertTrue(after.isEmpty)
      }.provide(testLayer()),
      test("hooks are called during evaluation") {
        val callsRef = Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.run(Ref.make(List.empty[String])).getOrThrow()
        }

        val trackingHook = new FeatureHook {
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            callsRef.update(_ :+ s"before:${ctx.flagKey}").as(None)

          override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
            callsRef.update(_ :+ s"after:${ctx.flagKey}")
        }

        for {
          _     <- FeatureFlags.addHook(trackingHook)
          _     <- FeatureFlags.boolean("test-flag", default = false)
          calls <- callsRef.get
        } yield assertTrue(calls == List("before:test-flag", "after:test-flag"))
      }.provide(testLayer(Map("test-flag" -> true))),
      test("invocation-level hooks are called") {
        val callsRef = Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.run(Ref.make(List.empty[String])).getOrThrow()
        }

        val invocationHook = new FeatureHook {
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            callsRef.update(_ :+ s"invocation-before:${ctx.flagKey}").as(None)

          override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
            callsRef.update(_ :+ s"invocation-after:${ctx.flagKey}")
        }

        val options = EvaluationOptions(invocationHook)
        for {
          _     <- FeatureFlags.booleanDetails("hook-test", default = false, EvaluationContext.empty, options)
          calls <- callsRef.get
        } yield assertTrue(calls == List("invocation-before:hook-test", "invocation-after:hook-test"))
      }.provide(testLayer(Map("hook-test" -> true))),
      test("client hooks run before invocation hooks") {
        val callsRef = Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.run(Ref.make(List.empty[String])).getOrThrow()
        }

        val clientHook = new FeatureHook {
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            callsRef.update(_ :+ "client-before").as(None)
        }

        val invocationHook = new FeatureHook {
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            callsRef.update(_ :+ "invocation-before").as(None)
        }

        val options = EvaluationOptions(invocationHook)
        for {
          _     <- FeatureFlags.addHook(clientHook)
          _     <- FeatureFlags.booleanDetails("order-test", default = false, EvaluationContext.empty, options)
          calls <- callsRef.get
        } yield assertTrue(calls.take(2) == List("client-before", "invocation-before"))
      }.provide(testLayer(Map("order-test" -> true))),
      test("after hooks run in reverse order (spec 4.4.6)") {
        val callsRef = Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.run(Ref.make(List.empty[String])).getOrThrow()
        }

        val hook1 = new FeatureHook {
          override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
            callsRef.update(_ :+ "first")
        }

        val hook2 = new FeatureHook {
          override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
            callsRef.update(_ :+ "second")
        }

        val hook3 = new FeatureHook {
          override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
            callsRef.update(_ :+ "third")
        }

        for {
          _     <- FeatureFlags.addHook(hook1)
          _     <- FeatureFlags.addHook(hook2)
          _     <- FeatureFlags.addHook(hook3)
          _     <- FeatureFlags.boolean("test-flag", default = false)
          calls <- callsRef.get
        } yield assertTrue(calls == List("third", "second", "first"))
      }.provide(testLayer(Map("test-flag" -> true))),
      test("hook hints are passed to invocation hooks") {
        val receivedHints = Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.run(Ref.make(Option.empty[String])).getOrThrow()
        }

        val hintCheckHook = new FeatureHook {
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            receivedHints.set(hints.get[String]("test-hint")).as(None)
        }

        val options = EvaluationOptions(List(hintCheckHook), HookHints("test-hint" -> "hint-value"))
        for {
          _    <- FeatureFlags.booleanDetails("hint-test", default = false, EvaluationContext.empty, options)
          hint <- receivedHints.get
        } yield assertTrue(hint.contains("hint-value"))
      }.provide(testLayer(Map("hint-test" -> true))),
      test("hooks with unsupported flag type are skipped (spec 4.4.2.1)") {
        val callsRef = Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.run(Ref.make(List.empty[String])).getOrThrow()
        }

        // Hook that only supports String flags
        val stringOnlyHook = new FeatureHook {
          override def supportedFlagTypes: Set[FlagValueType] = Set(FlagValueType.String)

          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            callsRef.update(_ :+ s"before:${ctx.flagKey}").as(None)

          override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
            callsRef.update(_ :+ s"after:${ctx.flagKey}")
        }

        for {
          _         <- FeatureFlags.addHook(stringOnlyHook)
          _         <- FeatureFlags.boolean("bool-flag", default = false)
          afterBool <- callsRef.get
          _         <- FeatureFlags.string("str-flag", default = "default")
          afterStr  <- callsRef.get
        } yield assertTrue(afterBool.isEmpty) &&
          assertTrue(afterStr == List("before:str-flag", "after:str-flag"))
      }.provide(testLayer(Map("bool-flag" -> true, "str-flag" -> "hello")))
    ),
    suite("Provider Status")(
      test("providerStatus returns current status") {
        for {
          status <- FeatureFlags.providerStatus
        } yield assertTrue(status == ProviderStatus.Ready)
      }.provide(testLayer()),
      test("providerMetadata returns provider info") {
        for {
          metadata <- FeatureFlags.providerMetadata
        } yield assertTrue(metadata.name == "TestFeatureProvider")
      }.provide(testLayer()),
      test("clientMetadata returns client info with domain") {
        for {
          metadata <- FeatureFlags.clientMetadata
        } yield assertTrue(metadata.hasDomain) &&
          assertTrue(metadata.domain.exists(_.startsWith("test-")))
      }.provide(testLayer()),
      test("clientMetadata version is None by default") {
        for {
          metadata <- FeatureFlags.clientMetadata
        } yield assertTrue(metadata.version.isEmpty)
      }.provide(testLayer())
    ),
    suite("Evaluation with Context")(
      test("boolean with context passes context to provider") {
        val ctx = EvaluationContext("ctx-user")
        for {
          result <- FeatureFlags.boolean("flag", default = false, ctx)
        } yield assertTrue(result == true)
      }.provide(testLayer(Map("flag" -> true))),
      test("string with context works") {
        val ctx = EvaluationContext.empty
        for {
          result <- FeatureFlags.string("msg", default = "default", ctx)
        } yield assertTrue(result == "hello")
      }.provide(testLayer(Map("msg" -> "hello"))),
      test("int with context works") {
        val ctx = EvaluationContext.empty
        for {
          result <- FeatureFlags.int("num", default = 0, ctx)
        } yield assertTrue(result == 123)
      }.provide(testLayer(Map("num" -> 123))),
      test("double with context works") {
        val ctx = EvaluationContext.empty
        for {
          result <- FeatureFlags.double("rate", default = 0.0, ctx)
        } yield assertTrue(result == 3.14)
      }.provide(testLayer(Map("rate" -> 3.14)))
    ),
    suite("Generic Value Evaluation")(
      test("value with FlagType works") {
        for {
          result <- FeatureFlags.value[Boolean]("bool-flag", default = false)
        } yield assertTrue(result == true)
      }.provide(testLayer(Map("bool-flag" -> true))),
      test("valueDetails with FlagType works") {
        for {
          resolution <- FeatureFlags.valueDetails[Int]("int-flag", default = 0)
        } yield assertTrue(resolution.value == 99) &&
          assertTrue(resolution.flagKey == "int-flag")
      }.provide(testLayer(Map("int-flag" -> 99))),
      test("value with context works") {
        val ctx = EvaluationContext.empty
        for {
          result <- FeatureFlags.value[String]("str-flag", default = "none", ctx)
        } yield assertTrue(result == "found")
      }.provide(testLayer(Map("str-flag" -> "found")))
    ),
    suite("Event Handlers")(
      test("onProviderReady registers handler and returns cancellation") {
        for {
          cancel <- FeatureFlags.onProviderReady(_ => ZIO.unit)
        } yield assertTrue(cancel != null)
      }.provide(testLayer()),
      test("onProviderError registers handler and returns cancellation") {
        for {
          cancel <- FeatureFlags.onProviderError((_, _) => ZIO.unit)
        } yield assertTrue(cancel != null)
      }.provide(testLayer()),
      test("onProviderStale registers handler and returns cancellation") {
        for {
          cancel <- FeatureFlags.onProviderStale((_, _) => ZIO.unit)
        } yield assertTrue(cancel != null)
      }.provide(testLayer()),
      test("onConfigurationChanged registers handler and returns cancellation") {
        for {
          cancel <- FeatureFlags.onConfigurationChanged((_, _) => ZIO.unit)
        } yield assertTrue(cancel != null)
      }.provide(testLayer()),
      test("multiple handlers can be registered") {
        for {
          cancel1 <- FeatureFlags.onProviderReady(_ => ZIO.unit)
          cancel2 <- FeatureFlags.onProviderReady(_ => ZIO.unit)
          cancel3 <- FeatureFlags.onProviderError((_, _) => ZIO.unit)
        } yield assertTrue(cancel1 != null && cancel2 != null && cancel3 != null)
      }.provide(testLayer()),
      test("handler can be cancelled") {
        for {
          cancel <- FeatureFlags.onProviderReady(_ => ZIO.unit)
          _      <- cancel
        } yield assertTrue(true)
      }.provide(testLayer()),
      test("onProviderReady handler runs immediately when provider is already ready (spec 5.3.3)") {
        val callsRef = Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.run(Ref.make(0)).getOrThrow()
        }
        for {
          _     <- FeatureFlags.onProviderReady(_ => callsRef.update(_ + 1))
          calls <- callsRef.get
        } yield assertTrue(calls == 1)
      }.provide(testLayer()),
      test("onProviderStale handler runs immediately when provider is already stale") {
        val callsRef = Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.run(Ref.make(0)).getOrThrow()
        }
        for {
          testProvider <- ZIO.service[TestFeatureProvider]
          _            <- testProvider.setStatus(ProviderStatus.Stale)
          _            <- FeatureFlags.onProviderStale((_, _) => callsRef.update(_ + 1))
          calls        <- callsRef.get
        } yield assertTrue(calls == 1)
      }.provide(testLayer()),
      test("generic on method registers handler for event type") {
        val callsRef = Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.run(Ref.make(0)).getOrThrow()
        }
        for {
          cancel <- FeatureFlags.on(ProviderEventType.Ready, _ => callsRef.update(_ + 1))
          calls  <- callsRef.get
        } yield assertTrue(calls == 1) &&
          assertTrue(cancel != null)
      }.provide(testLayer()),
      test("generic on method works for stale events") {
        val callsRef = Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.run(Ref.make(0)).getOrThrow()
        }
        for {
          testProvider <- ZIO.service[TestFeatureProvider]
          _            <- testProvider.setStatus(ProviderStatus.Stale)
          _            <- FeatureFlags.on(ProviderEventType.Stale, _ => callsRef.update(_ + 1))
          calls        <- callsRef.get
        } yield assertTrue(calls == 1)
      }.provide(testLayer())
    ),
    suite("Tracking API")(
      test("track with event name only succeeds") {
        for {
          _ <- FeatureFlags.track("button-clicked")
        } yield assertTrue(true)
      }.provide(testLayer()),
      test("track with context succeeds") {
        val ctx = EvaluationContext("user-123")
        for {
          _ <- FeatureFlags.track("purchase-completed", ctx)
        } yield assertTrue(true)
      }.provide(testLayer()),
      test("track with details succeeds") {
        val details = TrackingEventDetails(value = Some(99.99))
        for {
          _ <- FeatureFlags.track("checkout", details)
        } yield assertTrue(true)
      }.provide(testLayer()),
      test("track with context and details succeeds") {
        val ctx = EvaluationContext("user-456")
        val details = TrackingEventDetails(
          value = Some(149.99),
          attributes = Map("currency" -> "USD", "quantity" -> 2)
        )
        for {
          _ <- FeatureFlags.track("order-placed", ctx, details)
        } yield assertTrue(true)
      }.provide(testLayer()),
      test("track with empty details succeeds") {
        for {
          _ <- FeatureFlags.track("page-view", TrackingEventDetails.empty)
        } yield assertTrue(true)
      }.provide(testLayer()),
      test("track merges global and client context") {
        val globalCtx = EvaluationContext.withAttributes("env" -> AttributeValue.string("prod"))
        val clientCtx = EvaluationContext.withAttributes("app" -> AttributeValue.string("web"))
        for {
          _ <- FeatureFlags.setGlobalContext(globalCtx)
          _ <- FeatureFlags.setClientContext(clientCtx)
          _ <- FeatureFlags.track("merged-event")
          _ <- FeatureFlags.track("merged-event-ctx", EvaluationContext("user-1"))
          _ <- FeatureFlags.track("merged-event-details", TrackingEventDetails(value = Some(1.0)))
          _ <- FeatureFlags.track(
            "merged-event-both",
            EvaluationContext("user-2"),
            TrackingEventDetails(value = Some(2.0))
          )
          events <- FeatureFlags.trackedEvents
        } yield
        // All merged contexts should contain both global and client attributes
        assertTrue(events.size == 4) &&
          assertTrue(events.forall { case (_, ctx, _) =>
            ctx.getString("env").contains("prod") && ctx.getString("app").contains("web")
          }) &&
          // Verify targeting keys from invocation context
          assertTrue(events(1)._2.targetingKey.contains("user-1")) &&
          assertTrue(events(3)._2.targetingKey.contains("user-2")) &&
          // Verify tracking details are passed through
          assertTrue(events(0)._3.isEmpty) &&
          assertTrue(events(1)._3.isEmpty) &&
          assertTrue(events(2)._3.exists(_.value.contains(1.0))) &&
          assertTrue(events(3)._3.exists(_.value.contains(2.0)))
      }.provide(testLayer()),
      test("TrackingEventDetails builder methods work") {
        val details = TrackingEventDetails.empty
          .withValue(50.0)
          .withAttribute("item", "product-123")
          .withAttribute("category", "electronics")
        assertTrue(details.value.contains(50.0)) &&
        assertTrue(details.attributes("item") == "product-123") &&
        assertTrue(details.attributes("category") == "electronics")
      }
    ),
    suite("Shutdown")(
      test("shutdown clears hooks") {
        val hook = FeatureHook.noop
        for {
          _      <- FeatureFlags.addHook(hook)
          _      <- FeatureFlags.addHook(hook)
          before <- FeatureFlags.hooks
          _      <- FeatureFlags.shutdown
          after  <- FeatureFlags.hooks
        } yield assertTrue(before.length == 2) &&
          assertTrue(after.isEmpty)
      }.provide(testLayer()),
      test("shutdown resets global context") {
        val ctx = EvaluationContext("user-123")
        for {
          _      <- FeatureFlags.setGlobalContext(ctx)
          before <- FeatureFlags.globalContext
          _      <- FeatureFlags.shutdown
          after  <- FeatureFlags.globalContext
        } yield assertTrue(before.targetingKey.contains("user-123")) &&
          assertTrue(after.isEmpty)
      }.provide(testLayer()),
      test("shutdown resets client context") {
        val ctx = EvaluationContext("client-user")
        for {
          _      <- FeatureFlags.setClientContext(ctx)
          before <- FeatureFlags.clientContext
          _      <- FeatureFlags.shutdown
          after  <- FeatureFlags.clientContext
        } yield assertTrue(before.targetingKey.contains("client-user")) &&
          assertTrue(after.isEmpty)
      }.provide(testLayer())
    ),
    suite("Provider Fatal Guard")(
      test("evaluation fails with ProviderFatal when provider status is Fatal") {
        for {
          testProvider <- ZIO.service[TestFeatureProvider]
          _            <- testProvider.setStatus(ProviderStatus.Fatal)
          result       <- FeatureFlags.boolean("test-flag", default = false).exit
        } yield assertTrue(result == Exit.fail(FeatureFlagError.ProviderFatal))
      }.provide(testLayer(Map("test-flag" -> true))),
      test("evaluation fails with ProviderFatal for detailed evaluation too") {
        for {
          testProvider <- ZIO.service[TestFeatureProvider]
          _            <- testProvider.setStatus(ProviderStatus.Fatal)
          result       <- FeatureFlags.booleanDetails("test-flag", default = false).exit
        } yield assertTrue(result == Exit.fail(FeatureFlagError.ProviderFatal))
      }.provide(testLayer(Map("test-flag" -> true)))
    ),
    suite("finallyAfter receives evaluation details (spec 4.3.8)")(
      test("finallyAfter receives details on success") {
        val capturedDetails = Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.run(Ref.make(Option.empty[FlagResolution[_]])).getOrThrow()
        }

        val hook = new FeatureHook {
          override def finallyAfter(
            ctx: HookContext,
            details: Option[FlagResolution[_]],
            hints: HookHints
          ): UIO[Unit] =
            capturedDetails.set(details)
        }

        for {
          _       <- FeatureFlags.addHook(hook)
          _       <- FeatureFlags.boolean("test-flag", default = false)
          details <- capturedDetails.get
        } yield {
          val d = details.get
          assertTrue(details.isDefined) &&
          assertTrue(d.value == (true: Any)) &&
          assertTrue(d.flagKey == "test-flag")
        }
      }.provide(testLayer(Map("test-flag" -> true))),
      test("finallyAfter receives None on error") {
        val capturedDetails = Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.run(Ref.make(Option.empty[Option[FlagResolution[_]]])).getOrThrow()
        }

        val hook = new FeatureHook {
          override def finallyAfter(
            ctx: HookContext,
            details: Option[FlagResolution[_]],
            hints: HookHints
          ): UIO[Unit] =
            capturedDetails.set(Some(details))
        }

        for {
          _       <- FeatureFlags.addHook(hook)
          tp      <- ZIO.service[TestFeatureProvider]
          _       <- tp.setStatus(ProviderStatus.Fatal)
          _       <- FeatureFlags.boolean("test-flag", default = false).exit
          details <- capturedDetails.get
        } yield assertTrue(details.contains(None))
      }.provide(testLayer(Map("test-flag" -> true)))
    ),
    suite("Provider NotReady Guard (spec 1.7.6)")(
      test("evaluation fails with ProviderNotReady when provider status is NotReady") {
        for {
          testProvider <- ZIO.service[TestFeatureProvider]
          _            <- testProvider.setStatus(ProviderStatus.NotReady)
          result       <- FeatureFlags.boolean("test-flag", default = false).exit
        } yield assertTrue(
          result == Exit.fail(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady))
        )
      }.provide(testLayer(Map("test-flag" -> true))),
      test("evaluation succeeds when provider is Ready") {
        for {
          result <- FeatureFlags.boolean("test-flag", default = false)
        } yield assertTrue(result == true)
      }.provide(testLayer(Map("test-flag" -> true))),
      test("evaluation succeeds when provider is Stale") {
        for {
          testProvider <- ZIO.service[TestFeatureProvider]
          _            <- testProvider.setStatus(ProviderStatus.Stale)
          result       <- FeatureFlags.boolean("test-flag", default = false)
        } yield assertTrue(result == true)
      }.provide(testLayer(Map("test-flag" -> true))),
      test("evaluation fails with ProviderNotReady when provider is in Error status (spec 1.7.3)") {
        for {
          testProvider <- ZIO.service[TestFeatureProvider]
          _            <- testProvider.setStatus(ProviderStatus.Error)
          result       <- FeatureFlags.boolean("test-flag", default = false).exit
        } yield assertTrue(
          result == Exit.fail(FeatureFlagError.ProviderNotReady(ProviderStatus.Error))
        )
      }.provide(testLayer(Map("test-flag" -> true)))
    ),
    suite("Hook Context Metadata")(
      test("hooks receive clientMetadata during evaluation") {
        val capturedMeta = Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.run(Ref.make(Option.empty[ClientMetadata])).getOrThrow()
        }

        val metaHook = new FeatureHook {
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            capturedMeta.set(Some(ctx.clientMetadata)).as(None)
        }

        for {
          _    <- FeatureFlags.addHook(metaHook)
          _    <- FeatureFlags.boolean("test-flag", default = false)
          meta <- capturedMeta.get
        } yield assertTrue(meta.isDefined) &&
          assertTrue(meta.get.hasDomain)
      }.provide(testLayer(Map("test-flag" -> true))),
      test("hooks receive providerMetadata during evaluation") {
        val capturedMeta = Unsafe.unsafe { implicit u =>
          Runtime.default.unsafe.run(Ref.make(Option.empty[ProviderMetadata])).getOrThrow()
        }

        val metaHook = new FeatureHook {
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            capturedMeta.set(Some(ctx.providerMetadata)).as(None)
        }

        for {
          _    <- FeatureFlags.addHook(metaHook)
          _    <- FeatureFlags.boolean("test-flag", default = false)
          meta <- capturedMeta.get
        } yield assertTrue(meta.isDefined) &&
          assertTrue(meta.get.name == "TestFeatureProvider")
      }.provide(testLayer(Map("test-flag" -> true)))
    ),
    suite("Object Evaluation")(
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
    ),
    suite("Full Context Hierarchy Merging")(
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
          assertTrue(effective.getString("level").contains("invocation")) &&
          assertTrue(effective.targetingKey.contains("invocation-user")) &&
          assertTrue(effective.getString("global-only").contains("yes")) &&
          assertTrue(effective.getString("client-only").contains("yes")) &&
          assertTrue(effective.getString("scoped-only").contains("yes")) &&
          assertTrue(effective.getString("invocation-only").contains("yes"))
        }
      }.provide(testLayer(Map("flag" -> true))),
      test(
        "transaction context merges between global and client per spec (API -> Transaction -> Client -> Invocation)"
      ) {
        val globalCtx = EvaluationContext.empty.withAttribute("source", "global").withAttribute("global-only", "yes")
        val txCtx     = EvaluationContext.empty.withAttribute("source", "transaction").withAttribute("tx-only", "yes")
        val clientCtx = EvaluationContext.empty.withAttribute("source", "client").withAttribute("client-only", "yes")

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
          _ <- FeatureFlags.transaction(context = txCtx) {
            FeatureFlags.boolean("flag", default = false)
          }
          ctx <- capturedCtx.get
        } yield {
          val effective = ctx.get
          // Client overrides Transaction (spec: API -> Transaction -> Client -> Invocation)
          assertTrue(effective.getString("source").contains("client")) &&
          // All unique attributes from each level are present
          assertTrue(effective.getString("global-only").contains("yes")) &&
          assertTrue(effective.getString("tx-only").contains("yes")) &&
          assertTrue(effective.getString("client-only").contains("yes"))
        }
      }.provide(testLayer(Map("flag" -> true)))
    ),
    suite("Transaction Edge Cases")(
      test("transaction override type mismatch produces error") {
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
    ),
    suite("Fiber Context Isolation")(
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
    ),
    suite("Event Handler Cancellation")(
      test("cancelled handler stops receiving events") {
        for {
          tp       <- ZIO.service[TestFeatureProvider]
          received <- Ref.make(0)
          cancel   <- FeatureFlags.onConfigurationChanged((_, _) => received.update(_ + 1))
          _        <- ZIO.sleep(200.millis)
          _        <- tp.emitEvent(ProviderEvent.ConfigurationChanged(Set("a"), tp.metadata))
          _        <- received.get.repeatUntil(_ > 0).timeout(5.seconds)
          before   <- received.get
          _        <- cancel
          _        <- ZIO.sleep(200.millis)
          _        <- tp.emitEvent(ProviderEvent.ConfigurationChanged(Set("b"), tp.metadata))
          _        <- ZIO.sleep(500.millis)
          after    <- received.get
        } yield assertTrue(before >= 1) &&
          assertTrue(after == before)
      }.provide(testLayer())
    ),
    suite("Shutdown Behavior")(
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
    ),
    suite("EvaluationOptions")(
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
    ),
    suite("Error Hook Execution")(
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
    ),
    suite("Event Metadata Propagation")(
      test("event metadata from ConfigurationChanged is propagated through the event bridge") {
        for {
          tp    <- ZIO.service[TestFeatureProvider]
          queue <- Queue.unbounded[ProviderEvent]
          _     <- FeatureFlags.events.foreach(e => queue.offer(e)).fork
          _     <- ZIO.sleep(100.millis)
          meta = FlagMetadata.fromStrings("source" -> "webhook", "region" -> "us-east")
          _     <- tp.emitEvent(ProviderEvent.ConfigurationChanged(Set("flag-1"), tp.metadata, meta))
          event <- queue.take.timeout(5.seconds)
        } yield {
          val em = event.get.eventMeta
          assertTrue(em.getString("source").contains("webhook")) &&
          assertTrue(em.getString("region").contains("us-east"))
        }
      }.provide(testLayer())
    )
  ) @@ TestAspect.withLiveClock
}
