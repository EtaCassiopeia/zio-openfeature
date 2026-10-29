package zio.openfeature

import zio.openfeature.testsupport.PropertyBudget

import zio._
import zio.test._
import zio.test.TestAspect.{sequential, withLiveClock}
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Value
}

/** Spec 4.3.2 as a property: N (generated) concurrent evaluations with generated delays go through a hook registered
  * once, plain or composed. Each writes its own unique token in `before` and must read back exactly that token in
  * `after` and `finallyAfter`, however the evaluations interleave.
  */
object HookDataIsolationPropertySpec extends ZIOSpecDefault {

  private class InstantProvider extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "instant" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(key: String, default: java.lang.Boolean, ctx: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.TRUE, "STATIC")
    override def getStringEvaluation(key: String, default: String, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getIntegerEvaluation(key: String, default: java.lang.Integer, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getDoubleEvaluation(key: String, default: java.lang.Double, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getObjectEvaluation(key: String, default: Value, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
  }

  private val tokenKey = TypedKey[String]("prop.token")

  /** What a probe read back: (token written for this flag, token read in after, token read in finallyAfter). */
  final private case class Read(flag: String, inAfter: Option[String], inFinally: Option[String])

  /** Writes `token-<flag>` in `before`, yields the fiber for `delays(flag)` so evaluations overlap, then records the
    * token seen in `after` / `finallyAfter`.
    */
  private def probe(delays: Map[String, Int], seen: Ref[Map[String, Read]]): FeatureHook = new FeatureHook {
    private def record(ctx: HookContext)(f: (Read, Option[String]) => Read): UIO[Unit] =
      seen.update { m =>
        m.updated(ctx.flagKey, f(m.getOrElse(ctx.flagKey, Read(ctx.flagKey, None, None)), ctx.hookData.get(tokenKey)))
      }

    override def before(ctx: HookContext, hints: HookHints): UIO[Option[EvaluationContext]] =
      ZIO.succeed(ctx.hookData.set(tokenKey, s"token-${ctx.flagKey}")) *>
        ZIO.sleep(delays.getOrElse(ctx.flagKey, 0).millis).as(None)
    override def after[A](ctx: HookContext, details: FlagResolution[A], hints: HookHints): UIO[Unit] =
      ZIO.sleep(delays.getOrElse(ctx.flagKey, 0).millis) *> record(ctx)((r, t) => r.copy(inAfter = t))
    override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
      record(ctx)((r, t) => r.copy(inFinally = t))
  }

  private val shapes: Gen[Any, String] = Gen.elements("plain", "compose", "nested", "duplicated", "twoComposites")

  private def register(ff: FeatureFlags, shape: String, p: FeatureHook): UIO[Unit] = shape match {
    case "plain"         => ff.addHook(p)
    case "compose"       => ff.addHook(FeatureHook.compose(List(p)))
    case "nested"        => ff.addHook(FeatureHook.compose(List(FeatureHook.compose(List(p)))))
    case "duplicated"    => ff.addHook(FeatureHook.compose(List(p, p)))
    case "twoComposites" => ff.addHook(FeatureHook.compose(List(p))) *> ff.addHook(FeatureHook.compose(List(p)))
    case other           => ZIO.die(new IllegalArgumentException(other))
  }

  private val schedules: Gen[Any, (Int, List[Int], String)] =
    for {
      n      <- Gen.int(2, 32)
      delays <- Gen.listOfN(n)(Gen.int(0, 8))
      shape  <- shapes
    } yield (n, delays, shape)

  def spec = suite("HookDataIsolationPropertySpec")(
    test("every concurrent evaluation reads back exactly its own HookData token") {
      check(schedules) { case (n, delays, shape) =>
        val keys = (1 to n).map(i => s"flag-$i").toList
        ZIO.scoped {
          for {
            seen <- Ref.make(Map.empty[String, Read])
            ff   <- FeatureFlags.build(
              new InstantProvider,
              domain = Some(s"hookdata-prop-${java.util.UUID.randomUUID()}"),
              version = None,
              initialHooks = Nil,
              statusRef = None,
              addShutdownFinalizer = true,
              apiOverride = Some(OpenFeatureAPI.createIsolated()),
              evaluationTimeout = Some(10.seconds)
            )
            _ <- register(ff, shape, probe(keys.zip(delays).toMap, seen))
            _ <- ZIO.foreachParDiscard(keys)(k => ff.boolean(k, false))
            m <- seen.get
          } yield {
            val allOwn = keys.forall { k =>
              val own = Some(s"token-$k")
              m.get(k).exists(r => r.inAfter == own && r.inFinally == own)
            }
            assertTrue(m.size == n, allOwn)
          }
        }
      }
    }
  ) @@ sequential @@ withLiveClock @@ PropertyBudget(20) @@ PropertyBudget.timeout(2.minutes)
}
