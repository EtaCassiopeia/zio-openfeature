package snippets

import zio.openfeature.testkit.*
import zio.test.*
import snippets.SchemaFlags.*

object SchemaFlagsSpec extends ZIOSpecDefault:

  def spec = suite("zio-schema flags")(
    test("a case class with a data-carrying sum reads back from its object form") {
      for c <- checkout
      yield assertTrue(c == Checkout(Tier.Premium, Rollout.Percentage(10), 100))
    }.provide(
      TestFeatureProvider.layer(
        Map("checkout" -> Map("tier" -> "premium", "rollout" -> Map("Percentage" -> Map("pct" -> 10.0))))
      )
    )
  )
