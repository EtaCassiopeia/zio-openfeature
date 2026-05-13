package zio.openfeature

import dev.openfeature.sdk.multiprovider.{FirstMatchStrategy, FirstSuccessfulStrategy}
import zio.test._

object MultiProviderStrategySpec extends ZIOSpecDefault {

  def spec = suite("MultiProviderStrategy")(
    test("firstMatch alias resolves to FirstMatchStrategy") {
      assertTrue(MultiProviderStrategy.firstMatch.isInstanceOf[FirstMatchStrategy])
    },
    test("firstSuccessful alias resolves to FirstSuccessfulStrategy") {
      assertTrue(MultiProviderStrategy.firstSuccessful.isInstanceOf[FirstSuccessfulStrategy])
    },
    test("Strategy type alias is assignable to the Java SDK Strategy type (compile-time check)") {
      // If the type alias were wrong, this assignment would not compile.
      val _: dev.openfeature.sdk.multiprovider.Strategy = MultiProviderStrategy.firstMatch
      assertCompletes
    },
    test("firstMatch and firstSuccessful return fresh instances on each call") {
      assertTrue(MultiProviderStrategy.firstMatch ne MultiProviderStrategy.firstMatch) &&
      assertTrue(MultiProviderStrategy.firstSuccessful ne MultiProviderStrategy.firstSuccessful)
    }
  )
}
