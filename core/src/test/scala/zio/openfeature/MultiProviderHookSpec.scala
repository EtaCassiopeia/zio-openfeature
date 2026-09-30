package zio.openfeature

import zio._
import zio.test._
import zio.test.TestAspect.withLiveClock
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  FlagEvaluationDetails,
  Hook,
  HookContext => OFHookContext,
  Metadata,
  OpenFeatureAPI,
  Value
}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

/** Since Java SDK 1.23.0 (java-sdk #2005) a `MultiProvider` runs each child provider's own hooks (#423). The SDK hands
  * those hooks the caller's client metadata through a thread-local that the MultiProvider's `before` hook sets, so the
  * child evaluation must run on the thread that called the SDK. `FeatureFlags` calls the SDK client once,
  * synchronously, inside a single blocking effect, which is what these tests pin.
  *
  * Shared test source dir → compiles on 2.13 and 3: braces only, no `given`/`using`, no `enum`.
  */
object MultiProviderHookSpec extends ZIOSpecDefault {

  /** A chain member with its own provider hook, recording each stage it sees and the client domain it was handed. */
  final private class HookedChild(stages: ConcurrentLinkedQueue[String], domains: ConcurrentLinkedQueue[String])
      extends FeatureProvider {
    private val hook: Hook[Object] = new Hook[Object] {
      override def before(
        ctx: OFHookContext[Object],
        hints: java.util.Map[String, Object]
      ): java.util.Optional[OFEvaluationContext] = {
        stages.add("before")
        domains.add(String.valueOf(ctx.getClientMetadata.getDomain))
        java.util.Optional.empty[OFEvaluationContext]()
      }
      override def after(
        ctx: OFHookContext[Object],
        details: FlagEvaluationDetails[Object],
        hints: java.util.Map[String, Object]
      ): Unit = { stages.add("after"); () }
      override def finallyAfter(
        ctx: OFHookContext[Object],
        details: FlagEvaluationDetails[Object],
        hints: java.util.Map[String, Object]
      ): Unit = { stages.add("finally"); () }
    }

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                     = new Metadata { def getName: String = "hooked-child" }
    override def getProviderHooks: java.util.List[Hook[_]] = java.util.Collections.singletonList[Hook[_]](hook)
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](true, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  private def chainIn(domain: String, child: FeatureProvider): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags
      .fromProvider(
        FeatureFlags.multiProvider(List(child)),
        FeatureFlagsConfig(domain = Some(domain)),
        statusRef = None,
        apiOverride = Some(OpenFeatureAPI.createIsolated())
      )
      .build
      .map(_.get[FeatureFlags])

  def spec = suite("MultiProviderHookSpec")(
    test("a chain member's own provider hooks run exactly once per evaluation") {
      // Once, not twice: the ZIO hook pipeline never calls `getProviderHooks`, so the SDK is the only thing running
      // them. Up to SDK 1.22.x a MultiProvider did not run them at all and this list was empty.
      val stages  = new ConcurrentLinkedQueue[String]()
      val domains = new ConcurrentLinkedQueue[String]()
      ZIO.scoped {
        for {
          ff <- chainIn("checkout", new HookedChild(stages, domains))
          v1 <- ff.boolean("flag", default = false)
          v2 <- ff.boolean("flag", default = false)
        } yield assertTrue(
          v1,
          v2,
          stages.asScala.toList == List("before", "after", "finally", "before", "after", "finally")
        )
      }
    },
    test("a chain member's provider hook sees the caller's client domain, not the MultiProvider's fallback") {
      // The SDK falls back to a client whose domain is "multiprovider" when its thread-local is empty, i.e. when the
      // child evaluation runs on a different thread from the MultiProvider's `before` hook.
      val stages  = new ConcurrentLinkedQueue[String]()
      val domains = new ConcurrentLinkedQueue[String]()
      ZIO.scoped {
        for {
          ff <- chainIn("checkout", new HookedChild(stages, domains))
          _  <- ff.boolean("flag", default = false)
        } yield assertTrue(domains.asScala.toList == List("checkout"))
      }
    }
  ) @@ withLiveClock
}
