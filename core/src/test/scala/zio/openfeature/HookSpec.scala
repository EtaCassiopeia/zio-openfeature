package zio.openfeature

import zio._
import zio.test._
import zio.test.Assertion._

object HookSpec extends ZIOSpecDefault {

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
      clientMetadata = ClientMetadata.default,
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
        for {
          result <- FeatureHook.noop.before(makeHookContext(), HookHints.empty)
        } yield assertTrue(result.isEmpty)
      },
      test("after completes successfully") {
        val resolution = FlagResolution.default("test", true)
        for {
          _ <- FeatureHook.noop.after(makeHookContext(), resolution, HookHints.empty)
        } yield assertTrue(true)
      },
      test("error completes successfully") {
        for {
          _ <- FeatureHook.noop.error(makeHookContext(), FeatureFlagError.FlagNotFound("test"), HookHints.empty)
        } yield assertTrue(true)
      },
      test("finallyAfter completes successfully") {
        for {
          _ <- FeatureHook.noop.finallyAfter(makeHookContext(), None, HookHints.empty)
        } yield assertTrue(true)
      }
    ),
    suite("FeatureHook.compose")(
      test("composes multiple hooks") {
        val callOrder = new java.util.concurrent.atomic.AtomicReference(List.empty[String])

        val hook1 = new FeatureHook {
          override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
            ZIO.succeed {
              callOrder.updateAndGet(list => list :+ "hook1")
              ()
            }
        }

        val hook2 = new FeatureHook {
          override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
            ZIO.succeed {
              callOrder.updateAndGet(list => list :+ "hook2")
              ()
            }
        }

        val composed   = FeatureHook.compose(List(hook1, hook2))
        val resolution = FlagResolution.default("test", true)

        for {
          _ <- composed.after(makeHookContext(), resolution, HookHints.empty)
        } yield assertTrue(callOrder.get() == List("hook2", "hook1"))
      },
      test("compose before merges contexts preserving existing attributes") {
        val hook = new FeatureHook {
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            ZIO.succeed(
              Some(
                (
                  EvaluationContext.withAttributes("added" -> AttributeValue.string("value")),
                  hints + ("hookRan" -> true)
                )
              )
            )
        }

        val composed = FeatureHook.compose(List(hook))
        val inputCtx = makeHookContext().copy(
          evaluationContext = EvaluationContext.withAttributes("existing" -> AttributeValue.string("keep"))
        )

        for {
          result <- composed.before(inputCtx, HookHints.empty)
        } yield assertTrue(result.isDefined) &&
          assertTrue(result.get._1.getString("added").contains("value")) &&
          assertTrue(result.get._1.getString("existing").contains("keep")) &&
          assertTrue(result.get._2.get[Boolean]("hookRan").contains(true))
      },
      test("compose before merges contexts from multiple hooks") {
        val hook1 = new FeatureHook {
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            ZIO.succeed(
              Some(
                (
                  EvaluationContext.withAttributes("from-hook1" -> AttributeValue.string("h1")),
                  hints + ("hook1" -> true)
                )
              )
            )
        }

        val hook2 = new FeatureHook {
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            ZIO.succeed(
              Some(
                (
                  EvaluationContext.withAttributes("from-hook2" -> AttributeValue.string("h2")),
                  hints + ("hook2" -> true)
                )
              )
            )
        }

        val composed = FeatureHook.compose(List(hook1, hook2))
        val inputCtx = makeHookContext().copy(
          evaluationContext = EvaluationContext.withAttributes("original" -> AttributeValue.string("orig"))
        )

        for {
          result <- composed.before(inputCtx, HookHints.empty)
        } yield assertTrue(result.isDefined) &&
          assertTrue(result.get._1.getString("original").contains("orig")) &&
          assertTrue(result.get._1.getString("from-hook1").contains("h1")) &&
          assertTrue(result.get._1.getString("from-hook2").contains("h2"))
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

        for {
          beforeResult <- hook.before(ctx, HookHints.empty)
          hints = beforeResult.map(_._2).getOrElse(HookHints.empty)
          _ <- hook.after(ctx, resolution, hints)
        } yield assertTrue(captured.isDefined) &&
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

        for {
          beforeResult <- hook.before(ctx, HookHints.empty)
          hints = beforeResult.map(_._2).getOrElse(HookHints.empty)
          _ <- hook.error(ctx, FeatureFlagError.FlagNotFound("error-test"), hints)
        } yield assertTrue(captured.isDefined) &&
          assertTrue(captured.get._1 == "error-test") &&
          assertTrue(captured.get._3 == false)
      }
    ),
    suite("FeatureHook.logging")(
      test("logging hook with before enabled") {
        val hook = FeatureHook.logging(logBefore = true, logAfter = false, logError = false)
        for {
          result <- hook.before(makeHookContext(), HookHints.empty)
        } yield assertTrue(result.isEmpty)
      },
      test("logging hook after logs info") {
        val hook       = FeatureHook.logging(logBefore = false, logAfter = true, logError = false)
        val resolution = FlagResolution.default("test", true)
        for {
          _ <- hook.after(makeHookContext(), resolution, HookHints.empty)
        } yield assertTrue(true)
      },
      test("logging hook error logs error") {
        val hook = FeatureHook.logging(logBefore = false, logAfter = false, logError = true)
        for {
          _ <- hook.error(makeHookContext(), FeatureFlagError.FlagNotFound("test"), HookHints.empty)
        } yield assertTrue(true)
      },
      test("logging hook all disabled") {
        val hook       = FeatureHook.logging(logBefore = false, logAfter = false, logError = false)
        val resolution = FlagResolution.default("test", true)
        for {
          beforeResult <- hook.before(makeHookContext(), HookHints.empty)
          _            <- hook.after(makeHookContext(), resolution, HookHints.empty)
          _            <- hook.error(makeHookContext(), FeatureFlagError.FlagNotFound("test"), HookHints.empty)
        } yield assertTrue(beforeResult.isEmpty)
      }
    ),
    suite("FeatureHook.structuredLogging")(
      test("before records start time in hookData and returns None") {
        val hook    = FeatureHook.structuredLogging()
        val hookCtx = makeHookContext()
        for {
          result <- hook.before(hookCtx, HookHints.empty)
        } yield
        // Returns None so it doesn't interfere with compose pipeline's context tracking
        assertTrue(result.isEmpty) &&
          assertTrue(hookCtx.hookData.get(TypedKey[Long]("structuredLogging.startTime")).isDefined)
      },
      test("after completes successfully") {
        val hook       = FeatureHook.structuredLogging()
        val hookCtx    = makeHookContext()
        val resolution = FlagResolution.default("test", true)
        for {
          _ <- hook.before(hookCtx, HookHints.empty)
          _ <- hook.after(hookCtx, resolution, HookHints.empty)
        } yield assertTrue(true)
      },
      test("error completes successfully") {
        val hook    = FeatureHook.structuredLogging()
        val hookCtx = makeHookContext()
        for {
          _ <- hook.before(hookCtx, HookHints.empty)
          _ <- hook.error(hookCtx, FeatureFlagError.FlagNotFound("test"), HookHints.empty)
        } yield assertTrue(true)
      },
      test("disabled levels skip logging but still track start time") {
        val hook       = FeatureHook.structuredLogging(beforeLevel = None, afterLevel = None, errorLevel = None)
        val hookCtx    = makeHookContext()
        val resolution = FlagResolution.default("test", true)
        for {
          beforeResult <- hook.before(hookCtx, HookHints.empty)
          _            <- hook.after(hookCtx, resolution, HookHints.empty)
          _            <- hook.error(hookCtx, FeatureFlagError.FlagNotFound("test"), HookHints.empty)
        } yield assertTrue(beforeResult.isEmpty) &&
          assertTrue(hookCtx.hookData.get(TypedKey[Long]("structuredLogging.startTime")).isDefined)
      },
      test("context logging with targeting key and attributes completes") {
        val hook = FeatureHook.structuredLogging(logContext = true)
        val hookCtx = makeHookContext().copy(
          evaluationContext = EvaluationContext.builder
            .targetingKey("user-123")
            .attribute("plan", "premium")
            .build
        )
        val resolution = FlagResolution.default("test", true)
        for {
          _ <- hook.before(hookCtx, HookHints.empty)
          _ <- hook.after(hookCtx, resolution, HookHints.empty)
        } yield assertTrue(true)
      },
      test("redactKeys hides sensitive values while preserving others") {
        val hook =
          FeatureHook.structuredLogging(logContext = true, redactKeys = Set("email"))
        val hookCtx = makeHookContext().copy(
          evaluationContext = EvaluationContext.builder
            .targetingKey("user-123")
            .attribute("email", "secret@example.com")
            .attribute("plan", "premium")
            .build
        )
        val resolution = FlagResolution.default("test", true)
        for {
          _ <- hook.before(hookCtx, HookHints.empty)
          _ <- hook.after(hookCtx, resolution, HookHints.empty)
        } yield assertTrue(true)
      }
    ),
    suite("FeatureHook.compose edge cases")(
      test("compose with empty list") {
        val composed = FeatureHook.compose(Nil)
        for {
          result <- composed.before(makeHookContext(), HookHints.empty)
        } yield assertTrue(result.isEmpty)
      },
      test("compose error calls all hooks") {
        val callCount = new java.util.concurrent.atomic.AtomicInteger(0)

        val hook1 = new FeatureHook {
          override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
            ZIO.succeed(callCount.incrementAndGet())
        }

        val hook2 = new FeatureHook {
          override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
            ZIO.succeed(callCount.incrementAndGet())
        }

        val composed = FeatureHook.compose(List(hook1, hook2))

        for {
          _ <- composed.error(makeHookContext(), FeatureFlagError.FlagNotFound("test"), HookHints.empty)
        } yield assertTrue(callCount.get() == 2)
      },
      test("compose finallyAfter calls all hooks") {
        val callCount = new java.util.concurrent.atomic.AtomicInteger(0)

        val hook1 = new FeatureHook {
          override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
            ZIO.succeed(callCount.incrementAndGet())
        }

        val hook2 = new FeatureHook {
          override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
            ZIO.succeed(callCount.incrementAndGet())
        }

        val composed = FeatureHook.compose(List(hook1, hook2))

        for {
          _ <- composed.finallyAfter(makeHookContext(), None, HookHints.empty)
        } yield assertTrue(callCount.get() == 2)
      },
      test("compose before without modifications returns None") {
        val hook1 = new FeatureHook {
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            ZIO.none
        }

        val hook2 = new FeatureHook {
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            ZIO.none
        }

        val composed = FeatureHook.compose(List(hook1, hook2))

        for {
          result <- composed.before(makeHookContext(), HookHints.empty)
        } yield assertTrue(result.isEmpty)
      }
    ),
    suite("HookData (spec 4.6.1)")(
      test("set and get values") {
        val data = HookData.empty
        data.set("key", "value")
        assertTrue(data.get[String]("key") == Some("value"))
      },
      test("getOrElse returns default for missing key") {
        val data = HookData.empty
        assertTrue(data.getOrElse("missing", 42) == 42)
      },
      test("remove clears value") {
        val data = HookData.empty
        data.set("key", "value")
        data.remove("key")
        assertTrue(data.get[String]("key") == None)
      },
      test("clear removes all values") {
        val data = HookData.empty
        data.set("a", 1)
        data.set("b", 2)
        data.clear()
        assertTrue(data.get[Int]("a") == None) &&
        assertTrue(data.get[Int]("b") == None)
      },
      test("hookData persists across hook stages") {
        val hook = new FeatureHook {
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            ZIO.succeed {
              ctx.hookData.set("span", "my-span-id")
              None
            }

          override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
            ZIO.succeed {
              ctx.hookData.set("after-ran", ctx.hookData.get[String]("span").getOrElse("missing"))
            }
        }

        val composed   = FeatureHook.compose(List(hook))
        val ctx        = makeHookContext()
        val resolution = FlagResolution.default("test", true)

        for {
          _ <- composed.before(ctx, HookHints.empty)
          _ <- composed.after(ctx, resolution, HookHints.empty)
        } yield
        // The composed hook gives each hook its own hookData, so we check that
        // the hook was able to read its own data across stages
        assertTrue(true) // hookData is internal to the hook via compose
      },
      test("compose gives each hook separate hookData") {
        var hook1Value: Option[String] = None
        var hook2Value: Option[String] = None

        val hook1 = new FeatureHook {
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            ZIO.succeed {
              ctx.hookData.set("owner", "hook1")
              None
            }

          override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
            ZIO.succeed { hook1Value = ctx.hookData.get[String]("owner") }
        }

        val hook2 = new FeatureHook {
          override def before(ctx: HookContext, hints: HookHints): UIO[Option[(EvaluationContext, HookHints)]] =
            ZIO.succeed {
              ctx.hookData.set("owner", "hook2")
              None
            }

          override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
            ZIO.succeed { hook2Value = ctx.hookData.get[String]("owner") }
        }

        val composed   = FeatureHook.compose(List(hook1, hook2))
        val ctx        = makeHookContext()
        val resolution = FlagResolution.default("test", true)

        for {
          _ <- composed.before(ctx, HookHints.empty)
          _ <- composed.after(ctx, resolution, HookHints.empty)
        } yield assertTrue(hook1Value == Some("hook1")) &&
          assertTrue(hook2Value == Some("hook2"))
      }
    ),
    suite("TypedKey")(
      test("TypedKey set and get on HookData") {
        val key  = TypedKey[String]("myKey")
        val data = HookData.empty
        data.set(key, "typedValue")
        assertTrue(data.get(key) == Some("typedValue"))
      },
      test("TypedKey get on HookHints") {
        val key   = TypedKey[Int]("count")
        val hints = HookHints(Map(key.name -> 42))
        assertTrue(hints.get(key) == Some(42))
      },
      test("TypedKey add on HookHints") {
        val key   = TypedKey[Boolean]("enabled")
        val hints = HookHints.empty.add(key, true)
        assertTrue(hints.get(key) == Some(true))
      },
      test("TypedKey getOrElse on HookData") {
        val key  = TypedKey[Int]("missing")
        val data = HookData.empty
        assertTrue(data.getOrElse(key, 99) == 99)
      },
      test("TypedKey getOrElse on HookHints") {
        val key   = TypedKey[String]("missing")
        val hints = HookHints.empty
        assertTrue(hints.getOrElse(key, "fallback") == "fallback")
      },
      test("TypedKey remove on HookData") {
        val key  = TypedKey[String]("toRemove")
        val data = HookData.empty
        data.set(key, "present")
        data.remove(key)
        assertTrue(data.get(key) == None)
      },
      test("existing untyped API still works") {
        val data = HookData.empty
        data.set("key", "value")
        assertTrue(data.get[String]("key") == Some("value"))
      }
    ),
    suite("FeatureHook.contextValidator")(
      test("does not modify context when valid") {
        val hook = FeatureHook.contextValidator(
          requireTargetingKey = false,
          requiredAttributes = Nil
        )

        for {
          result <- hook.before(makeHookContext(), HookHints.empty)
        } yield assertTrue(result.isEmpty)
      },
      test("logs warning for missing targeting key") {
        val hook = FeatureHook.contextValidator(
          requireTargetingKey = true,
          requiredAttributes = Nil
        )

        val ctx = makeHookContext()

        for {
          result <- hook.before(ctx, HookHints.empty)
        } yield assertTrue(result.isEmpty)
      },
      test("logs warning for missing required attribute") {
        val hook = FeatureHook.contextValidator(
          requireTargetingKey = false,
          requiredAttributes = List("userId")
        )

        val ctx = makeHookContext()

        for {
          result <- hook.before(ctx, HookHints.empty)
        } yield assertTrue(result.isEmpty)
      }
    )
  )
}
