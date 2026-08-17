package zio.openfeature

import zio._
import zio.test._
import zio.test.TestAspect.{sequential, withLiveClock}
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  ErrorCode => OFErrorCode,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  ProviderState,
  Value
}

/** The object-backed custom-type evaluation path (`FeatureFlagsLive`, the `case None` fall-through) had two defects,
  * both surfaced by the pre-implementation audit on #348:
  *
  *   1. the caller's default never reached the provider — an empty `Value()` was sent instead of `encode(default)`, so
  *      a provider could not serve the caller's default on a miss; 2. anything that failed to extract was relabelled
  *      `TYPE_MISMATCH`, so a provider-reported `FLAG_NOT_FOUND` was reported as a type error — unlike the scalar path,
  *      which returns the default with the provider's error code.
  *
  * Shared (cross-compiled) test dir → must compile on 2.13 and 3: braces only, no `given`/`using`, no `enum`.
  */
object CustomTypeObjectPathSpec extends ZIOSpecDefault {

  final case class Cfg(tier: String, pct: Int)

  /** An object-backed custom type: `wireType` stays the domain name, so evaluation takes the object path. Numbers come
    * back from the `Value` bridge as `Double`, which is why `pct` decodes through `FlagType[Int]` rather than casting.
    */
  implicit val cfgFlagType: FlagType[Cfg] = FlagType.from[Cfg](
    "Cfg",
    Cfg("free", 0),
    {
      case m: Map[_, _] =>
        val sm = m.asInstanceOf[Map[String, Any]]
        for {
          t <- sm.get("tier").map(_.toString).toRight("missing tier")
          p <- sm.get("pct").toRight("missing pct").flatMap(v => FlagType[Int].decode(v))
        } yield Cfg(t, p)
      case other => Left("not a Cfg: " + other)
    },
    c => Map[String, Any]("tier" -> c.tier, "pct" -> c.pct)
  )

  private class ObjProvider(reply: Value => ProviderEvaluation[Value]) extends EventProvider {
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Obj" }
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
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = reply(d)
  }

  private def build(p: EventProvider, tag: String): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      p,
      domain = Some(s"objpath-$tag-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      evaluationTimeout = Some(5.seconds)
    )

  private val echo: Value => ProviderEvaluation[Value] =
    d => ProviderEvaluations.of[Value](d, "TARGETING_MATCH")

  private val notFound: Value => ProviderEvaluation[Value] =
    _ => ProviderEvaluations.error[Value](new Value(), OFErrorCode.FLAG_NOT_FOUND, "flag not found")

  private val garbage: Value => ProviderEvaluation[Value] =
    _ => ProviderEvaluations.of[Value](new Value("not-an-object"), "TARGETING_MATCH")

  def spec = suite("CustomTypeObjectPathSpec")(
    test("the caller's default reaches the provider on the object path") {
      ZIO.scoped {
        // The provider echoes whatever default it was handed. Before the fix an empty `Value()` was sent, so the
        // echo carried nothing, extraction yielded None, and this failed as a TYPE_MISMATCH.
        build(new ObjProvider(echo), "echo").flatMap { ff =>
          ff.value[Cfg]("cfg.flag", Cfg("gold", 42)).map(c => assertTrue(c == Cfg("gold", 42)))
        }
      }
    },
    test("a provider-reported FLAG_NOT_FOUND serves the default and keeps the provider's error code") {
      ZIO.scoped {
        build(new ObjProvider(notFound), "notfound").flatMap { ff =>
          ff.valueDetails[Cfg]("cfg.flag", Cfg("gold", 42)).map { res =>
            assertTrue(
              // Not relabelled a type error, and the caller's default is served — matching the scalar path.
              res.errorCode.contains(ErrorCode.FlagNotFound),
              res.value == Cfg("gold", 42),
              res.reason == ResolutionReason.Error
            )
          }
        }
      }
    },
    test("FLAG_NOT_FOUND on the object path does not fail the partial tier") {
      ZIO.scoped {
        build(new ObjProvider(notFound), "notfound2").flatMap { ff =>
          // The scalar path returns the default here rather than failing; the object path must agree.
          ff.value[Cfg]("cfg.flag", Cfg("gold", 42)).either.map { r =>
            assertTrue(r == Right(Cfg("gold", 42)))
          }
        }
      }
    },
    test("a payload that genuinely cannot decode still fails with TypeMismatch") {
      ZIO.scoped {
        // Regression guard: the fix must not swallow real decode failures into the default.
        build(new ObjProvider(garbage), "garbage").flatMap { ff =>
          ff.value[Cfg]("cfg.flag", Cfg("gold", 42)).either.map { r =>
            assertTrue(r.isLeft, r.left.toOption.exists(_.isInstanceOf[FeatureFlagError.TypeMismatch]))
          }
        }
      }
    },
    test("the total tier absorbs a not-found on the object path into the caller's default") {
      ZIO.scoped {
        build(new ObjProvider(notFound), "total").flatMap { ff =>
          ff.valueOrDefault[Cfg]("cfg.flag", Cfg("gold", 42)).map(c => assertTrue(c == Cfg("gold", 42)))
        }
      }
    }
  ) @@ sequential @@ withLiveClock
}
