package zio.openfeature

import zio._
import zio.test._
import zio.test.TestAspect.{sequential, withLiveClock}
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Value
}
import java.util.concurrent.CountDownLatch

/** #347: `FlagDef[A]` makes key + type + default a single first-class value, with evaluation overloads that delegate to
  * the existing generic tier. The load-bearing guarantee is that `FlagDef.default` — never `FlagType[A].defaultValue` —
  * is the value passed down as the evaluation default.
  *
  * This spec lives in the shared (cross-compiled) test source dir, so it must compile on 2.13 and 3: braces only, no
  * `given`/`using`, and no explicit implicit-argument application.
  */
object FlagDefSpec extends ZIOSpecDefault {

  /** Echoes back whatever default the evaluation path passed down, with reason DEFAULT — i.e. models "flag not found".
    * This is the discriminator between `FlagDef.default` and `FlagType[Int].defaultValue`: the latter is `0`, so an
    * implementation that wrongly forwards the type-level zero yields 0 where the flag's own 42 is expected.
    */
  private class EchoDefaultProvider extends EventProvider {
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "EchoDefault" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  /** Returns 99 only when the targeting key `"vip"` actually reached the provider; otherwise echoes the default. Lets a
    * test prove the `ctx` argument is threaded through rather than dropped.
    */
  private class TargetingIntProvider extends EventProvider {
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "TargetingInt" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) = {
      val key = if (c == null) null else c.getTargetingKey
      if ("vip" == key) ProviderEvaluations.of[java.lang.Integer](Int.box(99), "TARGETING_MATCH")
      else ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    }
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  /** Never becomes ready, so every evaluation fails with the typed `ProviderNotReady`. */
  private class NotReadyProvider extends EventProvider {
    private val gate                                        = new CountDownLatch(1)
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "NotReady" }
    override def getState: ProviderState                    = ProviderState.NOT_READY
    override def initialize(ctx: OFEvaluationContext): Unit = gate.await()
    override def shutdown(): Unit                           = gate.countDown()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  private def buildReady(p: EventProvider, tag: String): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      p,
      domain = Some(s"flagdef-$tag-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      evaluationTimeout = Some(5.seconds)
    )

  private def buildNotReady: ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.buildAsync(
      new NotReadyProvider,
      domain = Some(s"flagdef-nr-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      initTimeout = 1.hour
    )

  private def recordingHook(fired: Ref[Boolean]): FeatureHook = new FeatureHook {
    override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
      fired.set(true)
  }

  /** `FlagType[Int].defaultValue` is 0, so 42 here is deliberately different from it. */
  private val intFlag = FlagDef("flagdef.int", 42, "an int flag")

  def spec = suite("FlagDefSpec")(
    // --- the value type itself (no provider needed) ---
    test("carries key, default, description and the summoned FlagType instance") {
      assertTrue(
        intFlag.key == "flagdef.int",
        intFlag.default == 42,
        intFlag.description == "an int flag",
        intFlag.flagType.typeName == "Int"
      )
    },
    test("description defaults to the empty string when omitted") {
      assertTrue(FlagDef("k", true).description == "")
    },
    test("equality is structural over key/default/description and excludes the FlagType instance") {
      val a = FlagDef("k", 1)
      val b = FlagDef("k", 1)
      val c = FlagDef("k", 2)
      assertTrue(
        a == b,
        a.hashCode == b.hashCode,
        // Same key, different default => NOT equal. Derived structural equality is kept deliberately
        // (see the scaladoc); this pins that documented consequence.
        a != c,
        // productArity 3 proves `flagType` is not part of the case-class product: it lives in the
        // second parameter list, which is what keeps it out of equals/hashCode without an override.
        a.productArity == 3
      )
    },
    test("sameKey compares only the key, across differing type parameters and defaults") {
      val a = FlagDef("k", 1)
      assertTrue(
        a.sameKey(FlagDef("k", 2)),
        a.sameKey(FlagDef("k", "a string")),
        !a.sameKey(FlagDef("other", 1))
      )
    },
    // --- evaluation: FlagDef.default is what reaches the provider ---
    test("valueOrDefault(flag) serves FlagDef.default (42), not FlagType[Int].defaultValue (0)") {
      ZIO.scoped {
        buildReady(new EchoDefaultProvider, "echo").flatMap { ff =>
          ff.valueOrDefault(intFlag).map(v => assertTrue(v == 42))
        }
      }
    },
    test("value(flag) serves FlagDef.default on a not-found flag") {
      ZIO.scoped {
        buildReady(new EchoDefaultProvider, "echo2").flatMap { ff =>
          ff.value(intFlag).map(v => assertTrue(v == 42))
        }
      }
    },
    test("valueDetails(flag) returns the full resolution carrying FlagDef.default") {
      ZIO.scoped {
        buildReady(new EchoDefaultProvider, "echo3").flatMap { ff =>
          ff.valueDetails(intFlag).map { res =>
            assertTrue(res.value == 42, res.flagKey == "flagdef.int")
          }
        }
      }
    },
    // --- evaluation: ctx and options are threaded, not dropped ---
    test("value(flag, ctx) threads the context through to the provider") {
      ZIO.scoped {
        buildReady(new TargetingIntProvider, "target").flatMap { ff =>
          for {
            matched <- ff.value(intFlag, EvaluationContext("vip"))
            // The arity-1 overload must use EvaluationContext.empty, so no targeting key reaches the
            // provider and it echoes the flag's own default instead of 99.
            empty <- ff.value(intFlag)
          } yield assertTrue(matched == 99, empty == 42)
        }
      }
    },
    test("valueDetails(flag, ctx, options) threads the invocation options through (hook fires)") {
      ZIO.scoped {
        buildReady(new EchoDefaultProvider, "opts").flatMap { ff =>
          for {
            fired <- Ref.make(false)
            _     <- ff.valueDetails(intFlag, EvaluationContext.empty, EvaluationOptions(recordingHook(fired)))
            saw   <- fired.get
          } yield assertTrue(saw)
        }
      }
    },
    test("resolveOrDefault(flag, ctx, options) threads the invocation options through (hook fires)") {
      ZIO.scoped {
        buildReady(new EchoDefaultProvider, "opts2").flatMap { ff =>
          for {
            fired <- Ref.make(false)
            _     <- ff.resolveOrDefault(intFlag, EvaluationContext.empty, EvaluationOptions(recordingHook(fired)))
            saw   <- fired.get
          } yield assertTrue(saw)
        }
      }
    },
    test("valueOrDefault(flag, ctx) threads the context through to the provider") {
      ZIO.scoped {
        buildReady(new TargetingIntProvider, "target2").flatMap { ff =>
          ff.valueOrDefault(intFlag, EvaluationContext("vip")).map(v => assertTrue(v == 99))
        }
      }
    },
    // The two arity-2 overloads below have no other test that proves they forward `ctx`: the arity-3
    // forms are covered by the hook-fires tests, but those prove `options` threading, not `ctx`. A
    // dropped `ctx` here would compile and pass every other test in this spec.
    test("resolveOrDefault(flag, ctx) threads the context through to the provider") {
      ZIO.scoped {
        buildReady(new TargetingIntProvider, "target3").flatMap { ff =>
          ff.resolveOrDefault(intFlag, EvaluationContext("vip")).map(res => assertTrue(res.value == 99))
        }
      }
    },
    test("valueDetails(flag, ctx) threads the context through to the provider") {
      ZIO.scoped {
        buildReady(new TargetingIntProvider, "target4").flatMap { ff =>
          ff.valueDetails(intFlag, EvaluationContext("vip")).map(res => assertTrue(res.value == 99))
        }
      }
    },
    test("the overloads are generic over A — a String flag evaluates through the same path") {
      ZIO.scoped {
        buildReady(new EchoDefaultProvider, "string").flatMap { ff =>
          // `FlagType[String].defaultValue` is "", so "fallback" discriminates here too.
          val strFlag = FlagDef("flagdef.str", "fallback", "a string flag")
          for {
            v <- ff.valueOrDefault(strFlag)
            d <- ff.valueDetails(strFlag)
          } yield assertTrue(v == "fallback", d.value == "fallback", strFlag.flagType.typeName == "String")
        }
      }
    },
    // --- evaluation: error paths keep the tier's contract ---
    test("value(flag) still FAILS on a typed provider error (the partial tier stays partial)") {
      ZIO.scoped {
        buildNotReady.flatMap { ff =>
          ff.value(intFlag).either.map { r =>
            assertTrue(r.isLeft, r.left.toOption.exists(_.isInstanceOf[FeatureFlagError.ProviderNotReady]))
          }
        }
      }
    },
    test("valueOrDefault(flag) absorbs a typed error into FlagDef.default") {
      ZIO.scoped {
        buildNotReady.flatMap { ff =>
          ff.valueOrDefault(intFlag).map(v => assertTrue(v == 42))
        }
      }
    },
    test("resolveOrDefault(flag) reports reason=Error with errorCode and value=FlagDef.default") {
      ZIO.scoped {
        buildNotReady.flatMap { ff =>
          ff.resolveOrDefault(intFlag).map { res =>
            assertTrue(
              res.value == 42,
              res.reason == ResolutionReason.Error,
              res.errorCode.contains(ErrorCode.ProviderNotReady),
              res.errorMessage.isDefined
            )
          }
        }
      }
    },
    // --- companion accessors ---
    test("companion accessors resolve FlagDef overloads through the environment") {
      ZIO.scoped {
        buildReady(new EchoDefaultProvider, "accessor").flatMap { ff =>
          val env = ZEnvironment[FeatureFlags](ff)
          for {
            v     <- FeatureFlags.valueOrDefault(intFlag).provideEnvironment(env)
            d     <- FeatureFlags.valueDetails(intFlag).provideEnvironment(env)
            r     <- FeatureFlags.resolveOrDefault(intFlag).provideEnvironment(env)
            plain <- FeatureFlags.value(intFlag).provideEnvironment(env)
          } yield assertTrue(v == 42, d.value == 42, r.value == 42, plain == 42)
        }
      }
    },
    // Each accessor is a one-line `ZIO.serviceWithZIO(_.m(flag, ctx, …))`, so a wrong target (delegating
    // to the arity-1 trait overload and dropping `ctx`) type-checks either way — only a test catches it.
    // Asserting 99 rather than 42 is what makes these prove forwarding instead of mere compilation.
    test("companion accessors forward ctx and options for every remaining arity") {
      ZIO.scoped {
        buildReady(new TargetingIntProvider, "accessor2").flatMap { ff =>
          val env = ZEnvironment[FeatureFlags](ff)
          val vip = EvaluationContext("vip")
          for {
            firedR <- Ref.make(false)
            firedD <- Ref.make(false)
            v      <- FeatureFlags.value(intFlag, vip).provideEnvironment(env)
            vod    <- FeatureFlags.valueOrDefault(intFlag, vip).provideEnvironment(env)
            rod    <- FeatureFlags.resolveOrDefault(intFlag, vip).provideEnvironment(env)
            rodO <- FeatureFlags
              .resolveOrDefault(intFlag, vip, EvaluationOptions(recordingHook(firedR)))
              .provideEnvironment(env)
            vd <- FeatureFlags.valueDetails(intFlag, vip).provideEnvironment(env)
            vdO <- FeatureFlags
              .valueDetails(intFlag, vip, EvaluationOptions(recordingHook(firedD)))
              .provideEnvironment(env)
            sawR <- firedR.get
            sawD <- firedD.get
          } yield assertTrue(
            v == 99,
            vod == 99,
            rod.value == 99,
            rodO.value == 99,
            vd.value == 99,
            vdO.value == 99,
            sawR,
            sawD
          )
        }
      }
    }
  ) @@ sequential @@ withLiveClock
}
