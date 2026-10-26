package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Value
}
import java.util.concurrent.atomic.AtomicInteger

// #443: the factories build with ZLayer.scoped, which owns the layer's scope, so they need no Scope from the caller.
object ScopeFreeLayerSpec extends ZIOSpecDefault {

  private class FlagProvider(name: String) extends FeatureProvider {
    val shutdowns = new AtomicInteger(0)
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = name }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = { shutdowns.incrementAndGet(); () }
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.TRUE, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
  }

  private def isolated(config: FeatureFlagsConfig = FeatureFlagsConfig()) = {
    val provider                                    = new FlagProvider("scope-free")
    val layer: ZLayer[Any, Throwable, FeatureFlags] =
      FeatureFlags.fromProvider(provider, config, statusRef = None, apiOverride = Some(OpenFeatureAPI.createIsolated()))
    (provider, layer)
  }

  def spec = suite("ScopeFreeLayerSpec (#443)")(
    test("fromProvider can be provided with no Scope") {
      val (_, layer) = isolated()
      FeatureFlags.boolean("f", false).provide(layer).map(v => assertTrue(v))
    },
    test("an async-init fromProvider can be provided with no Scope") {
      val (_, layer) = isolated(FeatureFlagsConfig(initMode = InitMode.Async))
      (FeatureFlags.awaitReady(5.seconds) *> FeatureFlags.boolean("f", false))
        .provide(layer)
        .map(v => assertTrue(v))
    } @@ TestAspect.withLiveClock,
    test("fromAcquireAsync provides both services with no Scope") {
      val layer: ZLayer[Any, Nothing, FeatureFlags with AcquireStatus] = FeatureFlags.fromAcquireAsync(
        ZIO.succeed(new FlagProvider("real")),
        ZIO.succeed(new FlagProvider("fallback"))
      )
      // Evaluates right after the layer builds, racing the background swap: that must never answer
      // `ProviderNotReady` (#512).
      (AcquireStatus.get *> FeatureFlags.boolean("f", false)).provide(layer).map(v => assertTrue(v))
    },
    test("FeatureFlagRegistry.fromProvider can be provided with no Scope") {
      val layer: ZLayer[Any, Throwable, FeatureFlagRegistry] = FeatureFlagRegistry.fromProvider(new FlagProvider("d"))
      ZIO
        .serviceWithZIO[FeatureFlagRegistry](_.getClient("x"))
        .flatMap(_.boolean("f", false))
        .provide(layer)
        .map(v => assertTrue(v))
    },
    test("the layer still owns the instance's lifetime: releasing it shuts the provider down") {
      val (provider, layer) = isolated()
      for {
        _     <- ZIO.scoped(layer.build)
        count <- ZIO.succeed(provider.shutdowns.get())
      } yield assertTrue(count == 1)
    },
    test("passing an explicit Scope still compiles (source compatibility)") {
      val (_, layer) = isolated()
      FeatureFlags.boolean("f", false).provide(Scope.default >>> layer).map(v => assertTrue(v))
    }
  )
}
