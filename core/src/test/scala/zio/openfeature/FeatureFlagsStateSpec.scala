package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.FeatureFlagsState

object FeatureFlagsStateSpec extends ZIOSpecDefault {
  def spec = suite("FeatureFlagsState")(
    test("make creates state with defaults") {
      for {
        state  <- FeatureFlagsState.make()
        global <- state.globalContext.get
        client <- state.clientContext.get
        hooks  <- state.hooks.get
        status <- state.status.get
      } yield assertTrue(global.isEmpty) &&
        assertTrue(client.isEmpty) &&
        assertTrue(hooks.isEmpty) &&
        assertTrue(status == ProviderStatus.Ready)
    },
    test("make creates state with initial hooks") {
      val hook = FeatureHook.noop
      for {
        state <- FeatureFlagsState.make(initialHooks = List(hook))
        hooks <- state.hooks.get
      } yield assertTrue(hooks.length == 1)
    },
    test("make uses provided statusRef") {
      for {
        customRef <- Ref.make[ProviderStatus](ProviderStatus.Stale)
        state     <- FeatureFlagsState.make(statusRef = Some(customRef))
        status    <- state.status.get
      } yield assertTrue(status == ProviderStatus.Stale)
    }
  )
}
