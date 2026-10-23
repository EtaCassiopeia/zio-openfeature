package zio.openfeature

import zio._
import zio.test._
import zio.test.TestAspect.{sequential, withLiveClock}
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  ProviderState,
  Value
}

/** #438 / spec 4.3.2: hook data is created per evaluation and never shared between hooks — including hooks inside a
  * user-built `FeatureHook.compose` that is registered once and reused across (concurrent) evaluations.
  */
object ComposeHookDataSpec extends ZIOSpecDefault {

  private class DelayedProvider(evalDelayMillis: Long) extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "delayed" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(
      key: String,
      default: java.lang.Boolean,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Boolean] = {
      if (key == "boom") throw new dev.openfeature.sdk.exceptions.FlagNotFoundError("no such flag")
      Thread.sleep(evalDelayMillis)
      ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.TRUE, "STATIC")
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

  private def buildFF(evalDelayMillis: Long = 0L): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      new DelayedProvider(evalDelayMillis),
      domain = Some(s"compose-hookdata-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      evaluationTimeout = Some(5.seconds)
    )

  private val tokenKey = TypedKey[String]("probe.token")
  private val listKey  = TypedKey[List[String]]("probe.list")

  /** Writes the flag key as this evaluation's token in `before`; records (written, read-back) in `after`. */
  private def tokenProbe(seen: Ref[List[(String, Option[String])]]): FeatureHook = new FeatureHook {
    override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
      ZIO.succeed(ctx.hookData.set(tokenKey, ctx.flagKey)).as(None)
    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      seen.update((ctx.flagKey, ctx.hookData.get(tokenKey)) :: _)
  }

  /** Appends to a list in its HookData in `before`; records the list length it sees in `after`. */
  private def appendProbe(sizes: Ref[List[Int]]): FeatureHook = new FeatureHook {
    override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
      ZIO.succeed(ctx.hookData.set(listKey, "x" :: ctx.hookData.getOrElse(listKey, Nil))).as(None)
    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      sizes.update(ctx.hookData.getOrElse(listKey, Nil).size :: _)
  }

  private val expectedReads: Set[(String, Option[String])] = Set(("error", Some("boom")), ("finally", Some("boom")))

  private val keys = (1 to 8).map(i => s"flag-$i").toList

  private def allOwnTokens(seen: List[(String, Option[String])]): Boolean =
    seen.size == keys.size && seen.forall { case (key, read) => read.contains(key) }

  def spec = suite("ComposeHookDataSpec (#438)")(
    test("a composite registered once isolates HookData across concurrent evaluations") {
      ZIO.scoped {
        for {
          seen <- Ref.make(List.empty[(String, Option[String])])
          ff   <- buildFF(evalDelayMillis = 100L)
          _    <- ff.addHook(FeatureHook.compose(List(tokenProbe(seen))))
          _    <- ZIO.foreachParDiscard(keys)(k => ff.boolean(k, false))
          s    <- seen.get
        } yield assertTrue(allOwnTokens(s))
      }
    },
    test("each evaluation through a reused composite starts with empty HookData") {
      ZIO.scoped {
        for {
          firstSeen <- Ref.make(Option.empty[String])
          probe = new FeatureHook {
            override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
              firstSeen.update(_.orElse(ctx.hookData.get(tokenKey)).orElse(Some("<none>"))) *>
                ZIO.succeed(ctx.hookData.set(tokenKey, ctx.flagKey)).as(None)
          }
          ff   <- buildFF()
          _    <- ff.addHook(FeatureHook.compose(List(probe)))
          _    <- ff.boolean("first", false)
          _    <- firstSeen.set(None)
          _    <- ff.boolean("second", false)
          seen <- firstSeen.get
        } yield assertTrue(seen.contains("<none>"))
      }
    },
    test("the same hook instance twice in one composite gets two separate HookData") {
      ZIO.scoped {
        for {
          sizes <- Ref.make(List.empty[Int])
          probe = appendProbe(sizes)
          ff <- buildFF()
          _  <- ff.addHook(FeatureHook.compose(List(probe, probe)))
          _  <- ff.boolean("dup", false)
          s  <- sizes.get
        } yield assertTrue(s == List(1, 1))
      }
    },
    test("the same hook instance registered twice via addHook gets two separate HookData") {
      ZIO.scoped {
        for {
          sizes <- Ref.make(List.empty[Int])
          probe = appendProbe(sizes)
          ff <- buildFF()
          _  <- ff.addHook(probe)
          _  <- ff.addHook(probe)
          _  <- ff.boolean("dup", false)
          s  <- sizes.get
        } yield assertTrue(s == List(1, 1))
      }
    },
    test("a nested composite keeps each evaluation's HookData isolated") {
      ZIO.scoped {
        for {
          seen <- Ref.make(List.empty[(String, Option[String])])
          ff   <- buildFF(evalDelayMillis = 100L)
          _    <- ff.addHook(FeatureHook.compose(List(FeatureHook.compose(List(tokenProbe(seen))))))
          _    <- ZIO.foreachParDiscard(keys)(k => ff.boolean(k, false))
          s    <- seen.get
        } yield assertTrue(allOwnTokens(s))
      }
    },
    test("error and finallyAfter read back the HookData written in before") {
      ZIO.scoped {
        for {
          reads <- Ref.make(List.empty[(String, Option[String])])
          probe = new FeatureHook {
            override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
              ZIO.succeed(ctx.hookData.set(tokenKey, ctx.flagKey)).as(None)
            override def error(ctx: HookContext, err: FeatureFlagError, hints: HookHints): UIO[Unit] =
              reads.update(("error", ctx.hookData.get(tokenKey)) :: _)
            override def finallyAfter(
              ctx: HookContext,
              details: Option[FlagResolution[_]],
              hints: HookHints
            ): UIO[Unit] =
              reads.update(("finally", ctx.hookData.get(tokenKey)) :: _)
          }
          ff <- buildFF()
          _  <- ff.addHook(FeatureHook.compose(List(probe)))
          _  <- ff.boolean("boom", false).either
          r  <- reads.get
        } yield assertTrue(r.toSet == expectedReads)
      }
    },
    test("hooks filtered out by flag type keep the remaining hooks' slots apart") {
      ZIO.scoped {
        for {
          sizes <- Ref.make(List.empty[Int])
          probe = appendProbe(sizes)
          stringOnly = new FeatureHook {
            override def supportedFlagTypes: Set[FlagValueType] = Set(FlagValueType.String)
          }
          ff <- buildFF()
          _  <- ff.addHook(FeatureHook.compose(List(stringOnly, probe, probe)))
          _  <- ff.boolean("filtered", false)
          s  <- sizes.get
        } yield assertTrue(s == List(1, 1))
      }
    },
    test("two composites handed the same HookData keep separate slots") {
      for {
        sizes <- Ref.make(List.empty[Int])
        probe = appendProbe(sizes)
        a     = FeatureHook.compose(List(probe))
        b     = FeatureHook.compose(List(probe, probe))
        ctx = HookContext(
          "manual",
          FlagValueType.Boolean,
          false,
          EvaluationContext.empty,
          ClientMetadata(None),
          ProviderMetadata("manual"),
          HookData.empty
        )
        resolution = FlagResolution.cached("manual", false)
        _ <- a.before(ctx, HookHints.empty) *> b.before(ctx, HookHints.empty)
        _ <- a.after(ctx, resolution, HookHints.empty) *> b.after(ctx, resolution, HookHints.empty)
        s <- sizes.get
      } yield assertTrue(s == List(1, 1, 1))
    },
    test("two metrics hooks inside one user composite each measure their own duration") {
      ZIO.scoped {
        for {
          cap1 <- Ref.make[Option[Duration]](None)
          cap2 <- Ref.make[Option[Duration]](None)
          h1 = FeatureHook.metrics((_, dur, _) => cap1.set(Some(dur)))
          spacer = new FeatureHook {
            override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
              ZIO.sleep(120.millis).as(None)
          }
          h2 = FeatureHook.metrics((_, dur, _) => cap2.set(Some(dur)))
          ff <- buildFF()
          _  <- ff.addHook(FeatureHook.compose(List(h1, spacer, h2)))
          _  <- ff.boolean("timed", false)
          d1 <- cap1.get
          d2 <- cap2.get
        } yield assertTrue(
          d1.isDefined,
          d2.isDefined,
          d1.get.toMillis - d2.get.toMillis >= 80L
        )
      }
    }
  ) @@ sequential @@ withLiveClock
}
