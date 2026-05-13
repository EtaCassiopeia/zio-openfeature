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
    test("Strategy type alias matches the Java SDK Strategy type") {
      val s: MultiProviderStrategy.Strategy = MultiProviderStrategy.firstMatch
      assertTrue(s.isInstanceOf[dev.openfeature.sdk.multiprovider.Strategy])
    }
  )
}
