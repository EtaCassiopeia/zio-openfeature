package snippets

import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*
import zio.test.*

object HooksSpec extends ZIOSpecDefault:

  private def recorder(name: String, log: Ref[List[String]]): FeatureHook = new FeatureHook:
    private def note(stage: String)                                             = log.update(_ :+ s"$stage:$name")
    override def before(ctx: HookContext, hints: HookHints)                     = note("before").as(None)
    override def after[A](ctx: HookContext, d: FlagResolution[A], h: HookHints) = note("after")
    override def error(ctx: HookContext, e: FeatureFlagError, h: HookHints)     = note("error")
    override def finallyAfter(ctx: HookContext, d: Option[FlagResolution[_]], h: HookHints) = note("finally")

  private def withHooks(log: Ref[List[String]]) =
    for
      _ <- FeatureFlags.addZioApiHook(recorder("api", log))
      _ <- FeatureFlags.addHooks(List(recorder("client1", log), recorder("client2", log)))
    yield EvaluationOptions(recorder("invocation", log))

  def spec = suite("Hooks")(
    test("before runs api, client, invocation; the later stages run in reverse") {
      for
        log  <- Ref.make(List.empty[String])
        opts <- withHooks(log)
        _    <- FeatureFlags.booleanDetails("on", false, EvaluationContext.empty, opts)
        out  <- log.get
      yield assertTrue(
        out == List(
          "before:api",
          "before:client1",
          "before:client2",
          "before:invocation",
          "after:invocation",
          "after:client2",
          "after:client1",
          "after:api",
          "finally:invocation",
          "finally:client2",
          "finally:client1",
          "finally:api"
        )
      )
    },
    test("a missing flag runs error, not after, and the typed tier fails") {
      for
        log  <- Ref.make(List.empty[String])
        opts <- withHooks(log)
        res  <- FeatureFlags.booleanDetails("missing", false, EvaluationContext.empty, opts).either
        out  <- log.get
      yield assertTrue(
        res.left.exists(_.isInstanceOf[FeatureFlagError.FlagNotFound]),
        out.filter(_.startsWith("error")) == List("error:invocation", "error:client2", "error:client1", "error:api"),
        !out.exists(_.startsWith("after")),
        out.count(_.startsWith("finally")) == 4
      )
    },
    test("the total tier also runs error for a missing flag") {
      for
        log <- Ref.make(List.empty[String])
        _   <- FeatureFlags.addHook(recorder("c", log))
        v   <- FeatureFlags.booleanOrDefault("missing", true)
        out <- log.get
      yield assertTrue(v, out == List("before:c", "error:c", "finally:c"))
    },
    test("hooks run before the status gate") {
      for
        log <- Ref.make(List.empty[String])
        _   <- FeatureFlags.addHook(recorder("c", log))
        _   <- ZIO.serviceWithZIO[TestFeatureProvider](_.setStatus(ProviderStatus.NotReady))
        res <- FeatureFlags.boolean("on", false).either
        out <- log.get
      yield assertTrue(
        res.left.exists(_.isInstanceOf[FeatureFlagError.ProviderNotReady]),
        out == List("before:c", "error:c", "finally:c")
      )
    },
    test("supportedFlagTypes filters evaluations") {
      for
        log <- Ref.make(List.empty[String])
        _   <- FeatureFlags.addHook(new FeatureHook:
          override def supportedFlagTypes                         = Hooks.stringsOnly.supportedFlagTypes
          override def before(ctx: HookContext, hints: HookHints) = log.update(_ :+ ctx.flagKey).as(None))
        _   <- FeatureFlags.boolean("on", false)
        _   <- FeatureFlags.string("color", "red")
        out <- log.get
      yield assertTrue(out == List("color"))
    },
    test("hints from EvaluationOptions reach the hooks") {
      for
        seen <- Ref.make(Option.empty[String])
        hook = new FeatureHook:
          override def after[A](ctx: HookContext, d: FlagResolution[A], hints: HookHints) =
            seen.set(hints.get[String]("requestId"))
        _ <- FeatureFlags.booleanDetails(
          "on",
          false,
          EvaluationContext.empty,
          EvaluationOptions(hook).withHint("requestId", "r-17")
        )
        got <- seen.get
      yield assertTrue(got.contains("r-17"))
    },
    test("hook data carries a value from before to finallyAfter") {
      for
        seen <- Ref.make(Option.empty[Long])
        key  = TypedKey[Long]("k")
        hook = new FeatureHook:
          override def before(ctx: HookContext, hints: HookHints) = ZIO.succeed(ctx.hookData.set(key, 7L)).as(None)
          override def finallyAfter(ctx: HookContext, d: Option[FlagResolution[_]], hints: HookHints) =
            seen.set(ctx.hookData.get(key))
        _   <- FeatureFlags.addHook(hook)
        _   <- FeatureFlags.addHook(Hooks.timer)
        _   <- FeatureFlags.boolean("on", false)
        got <- seen.get
      yield assertTrue(got.contains(7L))
    },
    test("hooks run on transaction overrides and cache hits") {
      for
        log <- Ref.make(List.empty[String])
        _   <- FeatureFlags.addHook(recorder("c", log))
        _   <- FeatureFlags.transaction(Map("x" -> true))(
          FeatureFlags.boolean("x", false) *> FeatureFlags.boolean("on", false) *> FeatureFlags.boolean("on", false)
        )
        out <- log.get
      yield assertTrue(out.count(_ == "after:c") == 3)
    },
    test("snippets: registration, built-ins, manage") {
      for
        _ <- Hooks.register
        _ <- Hooks.once
        _ <- ZIO.foreachDiscard(
          List(
            Hooks.logging,
            Hooks.structured,
            Hooks.metrics,
            Hooks.detailedMetrics,
            Hooks.validator,
            Hooks.requestIdLogger,
            Hooks.safe(_ => ZIO.fail(new RuntimeException("down")))
          )
        )(FeatureFlags.addHook)
        v      <- FeatureFlags.boolean("on", false)
        _      <- Hooks.registerJava
        v2     <- FeatureFlags.boolean("on", false)
        before <- Hooks.manage
        after  <- FeatureFlags.hooks
      yield assertTrue(v, v2, before.size == 10, after.isEmpty)
    },
    test("finallyAfter gets the error-coded answer, and None when there was no answer") {
      for
        seen <- Ref.make(List.empty[Option[FlagResolution[_]]])
        _    <- FeatureFlags.addHook(
          new FeatureHook:
            override def finallyAfter(ctx: HookContext, d: Option[FlagResolution[_]], hints: HookHints) =
              seen.update(_ :+ d)
        )
        _   <- FeatureFlags.boolean("missing", false).ignore
        _   <- ZIO.serviceWithZIO[TestFeatureProvider](_.setStatus(ProviderStatus.NotReady))
        _   <- FeatureFlags.boolean("on", false).ignore
        out <- seen.get
      yield assertTrue(out.head.exists(_.errorCode.isDefined), out(1).isEmpty)
    }
  ).provide(
    TestFeatureProvider.layer(Map("on" -> true, "color" -> "blue", "new-ui" -> true))
  ) @@ TestAspect.withLiveClock
