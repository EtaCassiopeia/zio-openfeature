package snippets

import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*
import zio.test.*

object HomeSpec extends ZIOSpecDefault:

  def spec = suite("Home")(
    test("at a glance: reads the flags the page shows") {
      for
        provider <- ZIO.service[TestFeatureProvider]
        _        <- provider.setFlags(Map("new-checkout" -> true, "banner" -> "spring-sale"))
        result   <- Home.checkout
      yield assertTrue(result == "checkout=new limit=20 banner=spring-sale")
    }.provide(TestFeatureProvider.scopedLayer(Map.empty[String, Any])),
    test("quick start runs") {
      Home.inTests.as(assertCompletes)
    }
  )
