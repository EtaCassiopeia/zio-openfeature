package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.FeatureFlagsState

object FeatureFlagsStateSpec extends ZIOSpecDefault {

  def spec = suite("FeatureFlagsStateSpec")(
    test("make creates state with empty global context") {
      for {
        state <- FeatureFlagsState.make
        ctx   <- state.globalContextRef.get
      } yield assertTrue(ctx == EvaluationContext.empty)
    },
    test("make creates state with empty client context") {
      for {
        state <- FeatureFlagsState.make
        ctx   <- state.clientContextRef.get
      } yield assertTrue(ctx == EvaluationContext.empty)
    },
    test("make creates state with empty fiber context") {
      for {
        state <- FeatureFlagsState.make
        ctx   <- state.fiberContextRef.get
      } yield assertTrue(ctx == EvaluationContext.empty)
    },
    test("make creates state with no transaction") {
      for {
        state <- FeatureFlagsState.make
        tx    <- state.transactionRef.get
      } yield assertTrue(tx.isEmpty)
    },
    test("make creates state with empty hooks list") {
      for {
        state <- FeatureFlagsState.make
        hooks <- state.hooksRef.get
      } yield assertTrue(hooks.isEmpty)
    },
    test("make creates state with NotReady status") {
      for {
        state  <- FeatureFlagsState.make
        status <- state.statusRef.get
      } yield assertTrue(status == ProviderStatus.NotReady)
    },
    test("make creates independent state instances") {
      for {
        state1 <- FeatureFlagsState.make
        state2 <- FeatureFlagsState.make
        _      <- state1.statusRef.set(ProviderStatus.Ready)
        s1     <- state1.statusRef.get
        s2     <- state2.statusRef.get
      } yield assertTrue(s1 == ProviderStatus.Ready) &&
        assertTrue(s2 == ProviderStatus.NotReady)
    }
  )
}
