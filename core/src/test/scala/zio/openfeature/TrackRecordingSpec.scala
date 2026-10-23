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

// #442: a production instance must not retain tracked events (their merged contexts routinely hold PII).
@scala.annotation.nowarn("cat=deprecation")
object TrackRecordingSpec extends ZIOSpecDefault {

  private class PlainProvider extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "plain" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = ProviderEvaluations.of(d, "DEFAULT")
  }

  private val user = EvaluationContext("user-1").withAttribute("email", "someone@example.com")

  private val trackThrice
    : ZIO[FeatureFlags, FeatureFlagError, List[(String, EvaluationContext, Option[TrackingEventDetails])]] =
    FeatureFlags.track("a", user) *> FeatureFlags.track("b", user) *> FeatureFlags.track("c", user) *>
      FeatureFlags.trackedEvents

  private def isolated(config: FeatureFlagsConfig) =
    FeatureFlags.fromProvider(
      new PlainProvider,
      config,
      statusRef = None,
      apiOverride = Some(OpenFeatureAPI.createIsolated())
    )

  def spec = suite("TrackRecordingSpec (#442)")(
    test("a fromProvider instance retains no tracked events") {
      trackThrice.provide(isolated(FeatureFlagsConfig())).map(events => assertTrue(events.isEmpty))
    },
    test("an async-init fromProvider instance retains no tracked events") {
      (FeatureFlags.awaitReady(5.seconds) *> trackThrice)
        .provide(isolated(FeatureFlagsConfig(initMode = InitMode.Async)))
        .map(events => assertTrue(events.isEmpty))
    } @@ TestAspect.withLiveClock,
    test("a registry client retains no tracked events") {
      ZIO
        .serviceWithZIO[FeatureFlagRegistry](_.getClient("d"))
        .flatMap(client => trackThrice.provideEnvironment(ZEnvironment(client)))
        .provide(FeatureFlagRegistry.fromProvider(new PlainProvider))
        .map(events => assertTrue(events.isEmpty))
    }
  )
}
