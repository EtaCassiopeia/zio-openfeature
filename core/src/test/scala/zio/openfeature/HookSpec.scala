package zio.openfeature

import zio.*
import zio.test.*
import zio.test.Assertion.*

object HookSpec extends ZIOSpecDefault:

  val testMetadata = ProviderMetadata("TestProvider", "1.0")

  def makeHookContext(
    flagKey: String = "test-flag",
    flagType: FlagValueType = FlagValueType.Boolean,
    defaultValue: Any = false
  ): HookContext =
    HookContext(
      flagKey = flagKey,
      flagType = flagType,
      defaultValue = defaultValue,
      evaluationContext = EvaluationContext.empty,
      providerMetadata = testMetadata
    )

  def spec = suite("HookSpec")(
    suite("HookHints")(
      test("empty hints have no values") {
        val hints = HookHints.empty
        assertTrue(hints.get[String]("key").isEmpty)
      },
      test("get returns stored value") {
        val hints = HookHints("key" -> "value")
        assertTrue(hints.get[String]("key").contains("value"))
      },
      test("getOrElse returns default for missing key") {
        val hints = HookHints.empty
        assertTrue(hints.getOrElse("missing", "default") == "default")
      },
      test("+ adds entry") {
        val hints = HookHints.empty + ("key" -> 42)
        assertTrue(hints.get[Int]("key").contains(42))
      },
      test("++ combines hints") {
        val hints1   = HookHints("a" -> 1)
        val hints2   = HookHints("b" -> 2)
        val combined = hints1 ++ hints2
        assertTrue(hints1.get[Int]("a").contains(1)) &&
        assertTrue(combined.get[Int]("b").contains(2))
      }
    ),
    suite("FlagValueType")(
      test("fromFlagType returns correct type for Boolean") {
        val fvt = FlagValueType.fromFlagType[Boolean]
        assertTrue(fvt == FlagValueType.Boolean)
      },
      test("fromFlagType returns correct type for String") {
        val fvt = FlagValueType.fromFlagType[String]
        assertTrue(fvt == FlagValueType.String)
      },
      test("fromFlagType returns correct type for Int") {
        val fvt = FlagValueType.fromFlagType[Int]
        assertTrue(fvt == FlagValueType.Int)
      },
      test("fromFlagType returns correct type for Double") {
        val fvt = FlagValueType.fromFlagType[Double]
        assertTrue(fvt == FlagValueType.Double)
      },
      test("fromFlagType returns Object for Map") {
        val fvt = FlagValueType.fromFlagType[Map[String, Any]]
        assertTrue(fvt == FlagValueType.Object)
      },
      test("name returns correct name for Boolean") {
        assertTrue(FlagValueType.Boolean.name == "Boolean")
      },
      test("name returns correct name for String") {
        assertTrue(FlagValueType.String.name == "String")
      },
      test("name returns correct name for Int") {
        assertTrue(FlagValueType.Int.name == "Int")
      },
      test("name returns correct name for Double") {
        assertTrue(FlagValueType.Double.name == "Double")
      },
      test("name returns correct name for Object") {
        assertTrue(FlagValueType.Object.name == "Object")
      }
    ),
    suite("FeatureHook.noop")(
      test("before returns None") {
        for result <- FeatureHook.noop.before(makeHookContext(), HookHints.empty)
        yield assertTrue(result.isEmpty)
      },
      test("after completes successfully") {
        val resolution = FlagResolution.default("test", true)
        for _ <- FeatureHook.noop.after(makeHookContext(), resolution, HookHints.empty)
        yield assertTrue(true)
      },
      test("error completes successfully") {
        for _ <- FeatureHook.noop.error(makeHookContext(), FeatureFlagError.FlagNotFound("test"), HookHints.empty)
        yield assertTrue(true)
      },
      test("finallyAfter completes successfully") {
        for _ <- FeatureHook.noop.finallyAfter(makeHookContext(), HookHints.empty)
        yield assertTrue(true)
      }
    ),
    suite("FeatureHook.compose")(
      test("composes multiple hooks") {
        val callOrder = new java.util.concurrent.atomic.AtomicReference(List.empty[String])

        val hook1 = new FeatureHook:
          override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
            ZIO.succeed {
              callOrder.updateAndGet(list => list :+ "hook1")
              ()
            }

        val hook2 = new FeatureHook:
          override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
            ZIO.succeed {
              callOrder.updateAndGet(list => list :+ "hook2")
              ()
            }

        val composed   = FeatureHook.compose(List(hook1, hook2))
        val resolution = FlagResolution.default("test", true)

        for _ <- composed.after(makeHookContext(), resolution, HookHints.empty)
        yield assertTrue(callOrder.get() == List("hook1", "hook2"))
      },
      test("compose before merges contexts") {
        val hook = new FeatureHook:
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            ZIO.succeed(
              Some(
                (
                  ctx.evaluationContext.withAttribute("added", AttributeValue.string("value")),
                  hints + ("hookRan" -> true)
                )
              )
            )

        val composed = FeatureHook.compose(List(hook))

        for result <- composed.before(makeHookContext(), HookHints.empty)
        yield assertTrue(result.isDefined) &&
          assertTrue(result.get._1.getString("added").contains("value")) &&
          assertTrue(result.get._2.get[Boolean]("hookRan").contains(true))
      }
    ),
    suite("FeatureHook.metrics")(
      test("tracks evaluation duration") {
        var captured: Option[(String, Duration, Boolean)] = None

        val hook = FeatureHook.metrics { (key, duration, success) =>
          ZIO.succeed {
            captured = Some((key, duration, success))
          }
        }

        val ctx        = makeHookContext("metrics-test")
        val resolution = FlagResolution.default("metrics-test", true)

        for
          beforeResult <- hook.before(ctx, HookHints.empty)
          hints = beforeResult.map(_._2).getOrElse(HookHints.empty)
          _ <- hook.after(ctx, resolution, hints)
        yield assertTrue(captured.isDefined) &&
          assertTrue(captured.get._1 == "metrics-test") &&
          assertTrue(captured.get._3 == true)
      },
      test("tracks error duration") {
        var captured: Option[(String, Duration, Boolean)] = None

        val hook = FeatureHook.metrics { (key, duration, success) =>
          ZIO.succeed {
            captured = Some((key, duration, success))
          }
        }

        val ctx = makeHookContext("error-test")

        for
          beforeResult <- hook.before(ctx, HookHints.empty)
          hints = beforeResult.map(_._2).getOrElse(HookHints.empty)
          _ <- hook.error(ctx, FeatureFlagError.FlagNotFound("error-test"), hints)
        yield assertTrue(captured.isDefined) &&
          assertTrue(captured.get._1 == "error-test") &&
          assertTrue(captured.get._3 == false)
      }
    ),
    suite("FeatureHook.logging")(
      test("logging hook with before enabled") {
        val hook = FeatureHook.logging(logBefore = true, logAfter = false, logError = false)
        for result <- hook.before(makeHookContext(), HookHints.empty)
        yield assertTrue(result.isEmpty)
      },
      test("logging hook after logs info") {
        val hook       = FeatureHook.logging(logBefore = false, logAfter = true, logError = false)
        val resolution = FlagResolution.default("test", true)
        for _ <- hook.after(makeHookContext(), resolution, HookHints.empty)
        yield assertTrue(true)
      },
      test("logging hook error logs error") {
        val hook = FeatureHook.logging(logBefore = false, logAfter = false, logError = true)
        for _ <- hook.error(makeHookContext(), FeatureFlagError.FlagNotFound("test"), HookHints.empty)
        yield assertTrue(true)
      },
      test("logging hook all disabled") {
        val hook       = FeatureHook.logging(logBefore = false, logAfter = false, logError = false)
        val resolution = FlagResolution.default("test", true)
        for
          beforeResult <- hook.before(makeHookContext(), HookHints.empty)
          _            <- hook.after(makeHookContext(), resolution, HookHints.empty)
          _            <- hook.error(makeHookContext(), FeatureFlagError.FlagNotFound("test"), HookHints.empty)
        yield assertTrue(beforeResult.isEmpty)
      }
    ),
    suite("FeatureHook.compose edge cases")(
      test("compose with empty list") {
        val composed = FeatureHook.compose(Nil)
        for result <- composed.before(makeHookContext(), HookHints.empty)
        yield assertTrue(result.isEmpty)
      },
      test("compose error calls all hooks") {
        val callCount = new java.util.concurrent.atomic.AtomicInteger(0)

        val hook1 = new FeatureHook:
          override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
            ZIO.succeed(callCount.incrementAndGet())

        val hook2 = new FeatureHook:
          override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
            ZIO.succeed(callCount.incrementAndGet())

        val composed = FeatureHook.compose(List(hook1, hook2))

        for _ <- composed.error(makeHookContext(), FeatureFlagError.FlagNotFound("test"), HookHints.empty)
        yield assertTrue(callCount.get() == 2)
      },
      test("compose finallyAfter calls all hooks") {
        val callCount = new java.util.concurrent.atomic.AtomicInteger(0)

        val hook1 = new FeatureHook:
          override def finallyAfter(ctx: HookContext, hints: HookHints): UIO[Unit] =
            ZIO.succeed(callCount.incrementAndGet())

        val hook2 = new FeatureHook:
          override def finallyAfter(ctx: HookContext, hints: HookHints): UIO[Unit] =
            ZIO.succeed(callCount.incrementAndGet())

        val composed = FeatureHook.compose(List(hook1, hook2))

        for _ <- composed.finallyAfter(makeHookContext(), HookHints.empty)
        yield assertTrue(callCount.get() == 2)
      },
      test("compose before without modifications returns None") {
        val hook1 = new FeatureHook:
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            ZIO.none

        val hook2 = new FeatureHook:
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            ZIO.none

        val composed = FeatureHook.compose(List(hook1, hook2))

        for result <- composed.before(makeHookContext(), HookHints.empty)
        yield assertTrue(result.isEmpty)
      }
    ),
    suite("FeatureHook.contextValidator")(
      test("does not modify context when valid") {
        val hook = FeatureHook.contextValidator(
          requireTargetingKey = false,
          requiredAttributes = Nil
        )

        for result <- hook.before(makeHookContext(), HookHints.empty)
        yield assertTrue(result.isEmpty)
      },
      test("logs warning for missing targeting key") {
        val hook = FeatureHook.contextValidator(
          requireTargetingKey = true,
          requiredAttributes = Nil
        )

        val ctx = makeHookContext()

        for result <- hook.before(ctx, HookHints.empty)
        yield assertTrue(result.isEmpty)
      },
      test("logs warning for missing required attribute") {
        val hook = FeatureHook.contextValidator(
          requireTargetingKey = false,
          requiredAttributes = List("userId")
        )

        val ctx = makeHookContext()

        for result <- hook.before(ctx, HookHints.empty)
        yield assertTrue(result.isEmpty)
      }
    )
  )
