package zio.openfeature.extras

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Hook,
  ImmutableContext,
  Metadata,
  MutableTrackingEventDetails,
  ProviderEvaluation,
  ProviderState,
  TrackingEventDetails,
  Value
}
import zio.openfeature.internal.ProviderEvaluations
import zio._
import zio.test._
import java.util.concurrent.CopyOnWriteArrayList

/** #261: the caching / circuit-breaker decorators must forward the delegate's `getProviderHooks` and `track` — the Java
  * SDK's `FeatureProvider` defines both as no-op defaults, so a decorator that doesn't override them silently drops the
  * delegate's provider hooks (telemetry/validation) and discards tracking events.
  */
object ProviderDelegationSpec extends ZIOSpecDefault {

  private class HookedTrackingProvider extends EventProvider {
    // Type the hook as `Hook[_]` so `singletonList` infers `List[Hook[_]]` (not `List[Hook[Object]]`, which the
    // invariant `java.util.List` would reject against the annotated return type). This mirrors the cross-version-safe
    // pattern in core's ProviderHookDuplicationSpec.
    private val providerHook: Hook[_] = new Hook[java.lang.Object] {}
    val tracked                       = new CopyOnWriteArrayList[String]()

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                       = new Metadata { override def getName: String = "Hooked" }
    override def getState: ProviderState                     = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit  = ()
    override def shutdown(): Unit                            = ()
    override def getProviderHooks(): java.util.List[Hook[_]] = java.util.Collections.singletonList(providerHook)
    override def track(eventName: String, context: OFEvaluationContext, details: TrackingEventDetails): Unit = {
      tracked.add(eventName); ()
    }
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "STATIC")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "STATIC")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "STATIC")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "STATIC")
  }

  private val ctx     = new ImmutableContext()
  private val details = new MutableTrackingEventDetails()

  def spec = suite("provider decorator delegation (#261)")(
    test("CachingProvider forwards getProviderHooks and track to the delegate") {
      val delegate = new HookedTrackingProvider
      val cached   = CachingProvider(delegate)
      cached.track("purchase", ctx, details)
      assertTrue(
        cached.getProviderHooks.size == 1,    // the delegate's hooks, not the SDK's empty default
        delegate.tracked.contains("purchase") // track reached the delegate, not the no-op default
      )
    },
    test("CircuitBreakerProvider forwards getProviderHooks and track to the delegate") {
      val delegate = new HookedTrackingProvider
      for {
        cb <- CircuitBreakerProvider.make(delegate)
        _  <- ZIO.succeed(cb.track("purchase", ctx, details))
      } yield assertTrue(
        cb.getProviderHooks.size == 1,
        delegate.tracked.contains("purchase")
      )
    }
  )
}
