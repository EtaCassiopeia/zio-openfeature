package example

import zio._
import zio.openfeature._
import zio.openfeature.testkit.TestFeatureProvider
import zio.test._

/** Reference spec showing how to unit-test feature-flag-driven application code. Pattern:
  *
  *   1. Build the layer with `TestFeatureProvider.scopedLayer` (or `scopedLayer(initialFlags)`).
  *   2. Resolve the `TestFeatureProvider` service in your test and call `setFlag` / `setStatus` to drive evaluations.
  *   3. Wire your application service on top of `FeatureFlags` (the testkit provides both layers).
  *
  * No network, no Scope juggling — flag values are knobs you twist between assertions.
  */
object UserServiceSpec extends ZIOSpecDefault {

  private val testEnv: ZLayer[Any, Throwable, TestFeatureProvider with FeatureFlags with UserService] =
    TestFeatureProvider.scopedLayer >>> (
      ZLayer.environment[TestFeatureProvider with FeatureFlags] ++ UserService.live
    )

  def spec = suite("UserService — flag-driven greeting")(
    test("legacy copy when the new-greeting flag is OFF (default)") {
      for {
        svc      <- ZIO.service[UserService]
        greeting <- svc.welcome("alice")
      } yield assertTrue(greeting == "Welcome, alice.")
    },
    test("new copy when the new-greeting flag is ON") {
      for {
        provider <- ZIO.service[TestFeatureProvider]
        _        <- provider.setFlag("new-greeting-copy", true)
        svc      <- ZIO.service[UserService]
        greeting <- svc.welcome("bob")
      } yield assertTrue(greeting.contains("Hey bob"))
    },
    test("evaluation fails fast when the provider is in Error state") {
      for {
        provider <- ZIO.service[TestFeatureProvider]
        _        <- provider.setStatus(ProviderStatus.Error)
        svc      <- ZIO.service[UserService]
        result   <- svc.welcome("carol").either
      } yield assertTrue(
        result match {
          case Left(_: FeatureFlagError.ProviderNotReady) => true
          case _                                          => false
        }
      )
    }
  ).provide(testEnv) @@ TestAspect.sequential
}
