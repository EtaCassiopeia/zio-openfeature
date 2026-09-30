package zio.openfeature.extras

import zio._
import zio.test._
import zio.test.TestAspect.withLiveClock
import zio.openfeature.{FeatureFlags, FeatureFlagsConfig}
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

/** A wrapper forwards a `MultiProvider`'s `getProviderHooks`, and since Java SDK 1.23.0 those hooks carry the caller's
  * client metadata to the chain members' own hooks through a thread-local (#423). The members' hooks run once either
  * way; whether they see the caller's domain depends on whether the wrapper evaluates on the calling thread (#427).
  * `DeferredProvider` and `CachingProvider` always do. `CircuitBreakerProvider` does only when `evaluationTimeout` is
  * not finite: a finite timeout needs the delegate on another thread, so the members' hooks see the SDK's fallback
  * domain, "multiprovider".
  *
  * Shared test source dir → compiles on 2.13 and 3: braces only, no `given`/`using`, no `enum`.
  */
object MultiProviderWrapperHookSpec extends ZIOSpecDefault {

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

  /** Evaluates a flag twice through `wrap(MultiProvider(child))` in domain "checkout"; returns (values, stages,
    * domains). Twice, so a `CachingProvider` hit and a reused blocking-pool thread are both exercised.
    */
  private def evaluateThrough(
    wrap: FeatureProvider => FeatureProvider
  ): ZIO[Scope, Throwable, (List[Boolean], List[String], List[String])] = {
    val stages  = new ConcurrentLinkedQueue[String]()
    val domains = new ConcurrentLinkedQueue[String]()
    val chain   = FeatureFlags.multiProvider(List(new HookedChild(stages, domains)))
    for {
      ff <- FeatureFlags
        .fromProvider(
          wrap(chain),
          FeatureFlagsConfig(domain = Some("checkout")),
          statusRef = None,
          apiOverride = Some(OpenFeatureAPI.createIsolated())
        )
        .build
        .map(_.get[FeatureFlags])
      vs <- ZIO.replicateZIO(2)(ff.boolean("flag", default = false).orDieWith(e => new RuntimeException(e.message)))
    } yield (vs.toList, stages.asScala.toList, domains.asScala.toList)
  }

  def spec = suite("MultiProviderWrapperHookSpec")(
    test("through DeferredProvider, chain members' hooks run once and see the caller's domain") {
      ZIO.scoped {
        evaluateThrough(chain => DeferredProvider("deferred")(() => chain)).map { case (vs, stages, domains) =>
          assertTrue(
            vs == List(true, true),
            stages == List("before", "finally", "before", "finally"),
            domains == List("checkout", "checkout")
          )
        }
      }
    },
    test("through CachingProvider, chain members' hooks see the caller's domain, and a cache hit runs none") {
      ZIO.scoped {
        evaluateThrough(chain => CachingProvider(chain)).map { case (vs, stages, domains) =>
          assertTrue(vs == List(true, true), stages == List("before", "finally"), domains == List("checkout"))
        }
      }
    },
    test("through CircuitBreakerProvider with infinite timeout, chain members' hooks see the caller's domain") {
      ZIO.scoped {
        val config = CircuitBreakerProviderConfig(evaluationTimeout = Duration.Infinity)
        evaluateThrough(chain => CircuitBreakerProvider(chain, config)).map { case (vs, stages, domains) =>
          assertTrue(
            vs == List(true, true),
            stages == List("before", "finally", "before", "finally"),
            domains == List("checkout", "checkout")
          )
        }
      }
    },
    test("through CircuitBreakerProvider, chain members' hooks run once per evaluation but see the fallback domain") {
      ZIO.scoped {
        evaluateThrough(chain => CircuitBreakerProvider(chain)).map { case (vs, stages, domains) =>
          // The default finite timeout evaluates on another thread, so the SDK's thread-local context is out of reach.
          assertTrue(
            vs == List(true, true),
            stages == List("before", "finally", "before", "finally"),
            domains == List("multiprovider", "multiprovider")
          )
        }
      }
    }
  ) @@ withLiveClock
}
