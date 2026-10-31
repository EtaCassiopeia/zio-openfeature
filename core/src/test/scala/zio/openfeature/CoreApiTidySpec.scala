package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  ErrorCode => JErrorCode,
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Value
}

// Small API fixes — lazily read provider name in `on`, stackless provider-reported causes,
// EvaluationOptions on the total tier and `value`, a shared stopwatch for the timing hooks, Long/Instant builder
// overloads.
object CoreApiTidySpec extends ZIOSpecDefault {

  /** `slow` sleeps past any short per-call timeout; `parse` reports PARSE_ERROR; everything else is `true`. */
  private class NamedProvider(name: String) extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = name }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(key: String, default: java.lang.Boolean, ctx: OFEvaluationContext) =
      key match {
        case "slow"  => Thread.sleep(1000); ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.TRUE, "STATIC")
        case "parse" => ProviderEvaluations.error[java.lang.Boolean](default, JErrorCode.PARSE_ERROR, "bad json at 3:7")
        case _       => ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.TRUE, "STATIC")
      }
    override def getStringEvaluation(key: String, default: String, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getIntegerEvaluation(key: String, default: java.lang.Integer, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getDoubleEvaluation(key: String, default: java.lang.Double, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getObjectEvaluation(key: String, default: Value, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
  }

  private def buildFF(provider: FeatureProvider): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      provider,
      domain = Some(s"tidy-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated())
    )

  private val fast = EvaluationOptions.empty.withTimeout(50.millis)

  def spec = suite("CoreApiTidySpec")(
    test("an on(...) registration built before a swap reports the provider current when it runs") {
      ZIO.scoped {
        for {
          ff   <- buildFF(new NamedProvider("first"))
          seen <- Promise.make[Nothing, String]
          registration = ff.on(
            ProviderEventType.Ready,
            {
              case ProviderEvent.Ready(meta, _) => seen.succeed(meta.name).unit
              case _                            => ZIO.unit
            }
          )
          _    <- ff.setProvider(new NamedProvider("second"))
          _    <- registration
          name <- seen.await
        } yield assertTrue(name == "second")
      }
    },
    test("a provider-reported PARSE_ERROR carries the provider's message in a stackless cause") {
      ZIO.scoped {
        for {
          ff     <- buildFF(new NamedProvider("p"))
          result <- ff.boolean("parse", false).either
        } yield assertTrue(result.left.toOption.exists {
          case FeatureFlagError.ParseError("parse", cause) =>
            cause.getMessage == "bad json at 3:7" && cause.getStackTrace.isEmpty
          case _ => false
        })
      }
    },
    test("the *OrDefault overloads honour per-call options: a short timeout serves the default") {
      ZIO.scoped {
        for {
          ff <- buildFF(new NamedProvider("p"))
          b  <- ff.booleanOrDefault("slow", false, EvaluationContext.empty, fast)
          v  <- ff.valueOrDefault[Boolean]("slow", false, EvaluationContext.empty, fast)
          r  <- ff.resolveOrDefault[Boolean]("slow", false, EvaluationContext.empty, fast)
        } yield assertTrue(!b, !v, r.errorCode.contains(ErrorCode.General))
      }
    } @@ TestAspect.withLiveClock,
    test("value with options fails with the timeout, and FlagDef overloads take options too") {
      val slowFlag = FlagDef[Boolean]("slow", false)
      ZIO.scoped {
        for {
          ff        <- buildFF(new NamedProvider("p"))
          byKey     <- ff.value[Boolean]("slow", false, EvaluationContext.empty, fast).either
          byFlag    <- ff.value(slowFlag, EvaluationContext.empty, fast).either
          orDefault <- ff.valueOrDefault(slowFlag, EvaluationContext.empty, fast)
        } yield assertTrue(
          byKey.left.toOption.exists(_.cause.exists(_.isInstanceOf[java.util.concurrent.TimeoutException])),
          byFlag.isLeft,
          !orDefault
        )
      }
    } @@ TestAspect.withLiveClock,
    test("the per-type getters take options after the context, on the instance and through the service") {
      ZIO.scoped {
        for {
          ff    <- buildFF(new NamedProvider("p"))
          calls <- Ref.make(0)
          hook = new FeatureHook {
            override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
              calls.update(_ + 1).as(None)
          }
          opts = EvaluationOptions(hook)
          ctx  = EvaluationContext.empty
          b          <- ff.boolean("f", true, ctx, opts)
          s          <- ff.string("f", "s", ctx, opts)
          i          <- ff.int("f", 1, ctx, opts)
          l          <- ff.long("f", 2L, ctx, opts)
          d          <- ff.double("f", 3.0, ctx, opts)
          o          <- ff.obj("f", Map[String, Any]("k" -> "v"), ctx, opts)
          onInstance <- calls.get
          viaService <- (FeatureFlags.boolean("f", true, ctx, opts) *> FeatureFlags.string("f", "s", ctx, opts) *>
            FeatureFlags.int("f", 1, ctx, opts) *> FeatureFlags.long("f", 2L, ctx, opts) *>
            FeatureFlags.double("f", 3.0, ctx, opts) *> FeatureFlags.obj("f", Map[String, Any](), ctx, opts))
            .provideSomeLayer[Any](ZLayer.succeed(ff)) *> calls.get
        } yield assertTrue(
          b,
          s == "s",
          i == 1,
          l == 2L,
          d == 3.0,
          o == Map[String, Any]("k" -> "v"),
          onInstance == 6,
          viaService == 12
        )
      }
    },
    test("invocation hooks passed in options run on the total tier") {
      ZIO.scoped {
        for {
          ff     <- buildFF(new NamedProvider("p"))
          stages <- Ref.make(List.empty[String])
          hook = new FeatureHook {
            override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
              stages.update("before" :: _).as(None)
            override def finallyAfter(c: HookContext, d: Option[FlagResolution[_]], h: HookHints): UIO[Unit] =
              stages.update("finally" :: _)
          }
          _ <- ff.booleanOrDefault("f", false, EvaluationContext.empty, EvaluationOptions(hook))
          s <- stages.get
        } yield assertTrue(s.reverse == List("before", "finally"))
      }
    },
    test("the metrics hook reports the elapsed clock time between its stages") {
      val hook = FeatureHook.metrics((_, _, _) => ZIO.unit)
      val ctx  = HookContext(
        "timed",
        FlagValueType.Boolean,
        false,
        EvaluationContext.empty,
        ClientMetadata(None),
        ProviderMetadata("p"),
        HookData.empty
      )
      for {
        reported <- Ref.make(Option.empty[Duration])
        probe = FeatureHook.metrics((_, d, _) => reported.set(Some(d)))
        _ <- hook.before(ctx.copy(hookData = HookData.empty), HookHints.empty) // a separate hook, separate data
        _ <- probe.before(ctx, HookHints.empty)
        _ <- TestClock.adjust(250.millis)
        _ <- probe.after(ctx, FlagResolution.cached("timed", false), HookHints.empty)
        d <- reported.get
      } yield assertTrue(d.contains(250.millis))
    },
    test("the context builder takes Long and Instant attributes, and an Int literal stays an Int") {
      val now = java.time.Instant.parse("2026-10-23T00:00:00Z")
      val ctx = EvaluationContext.builder.attribute("n", 5L).attribute("t", now).attribute("i", 5).build
      assertTrue(
        ctx.attributes.get("n").contains(AttributeValue.LongValue(5L)),
        ctx.attributes.get("t").contains(AttributeValue.InstantValue(now)),
        ctx.attributes.get("i").contains(AttributeValue.IntValue(5))
      )
    }
  )
}
