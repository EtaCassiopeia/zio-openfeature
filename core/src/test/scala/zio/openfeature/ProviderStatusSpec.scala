package zio.openfeature

import zio.*
import zio.test.*
import zio.test.Assertion.*

object ProviderStatusSpec extends ZIOSpecDefault:

  def spec = suite("ProviderStatusSpec")(
    suite("ProviderStatus enum")(
      test("all statuses exist") {
        assertTrue(ProviderStatus.NotReady != null) &&
        assertTrue(ProviderStatus.Ready != null) &&
        assertTrue(ProviderStatus.Error != null) &&
        assertTrue(ProviderStatus.Stale != null) &&
        assertTrue(ProviderStatus.ShuttingDown != null)
      }
    ),
    suite("canEvaluate")(
      test("Ready can evaluate") {
        assertTrue(ProviderStatus.Ready.canEvaluate)
      },
      test("Stale can evaluate") {
        assertTrue(ProviderStatus.Stale.canEvaluate)
      },
      test("NotReady cannot evaluate") {
        assertTrue(!ProviderStatus.NotReady.canEvaluate)
      },
      test("Error cannot evaluate") {
        assertTrue(!ProviderStatus.Error.canEvaluate)
      },
      test("ShuttingDown cannot evaluate") {
        assertTrue(!ProviderStatus.ShuttingDown.canEvaluate)
      }
    )
  )
