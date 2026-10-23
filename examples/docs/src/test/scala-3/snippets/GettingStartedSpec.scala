package snippets

import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*
import zio.test.*
import snippets.GettingStarted.*

object GettingStartedSpec extends ZIOSpecDefault:

  def spec = suite("Getting started")(
    test("the first flag reads true from the in-memory provider") {
      FirstFlag.program
        .map(m => assertTrue(m == "new checkout"))
        .provide(TestFeatureProvider.layer(Map("new-checkout" -> true)))
    },
    test("a real provider is wired the same way") {
      for
        provider <- TestFeatureProvider.make(Map("new-checkout" -> false))
        message  <- withProvider(provider)
      yield assertTrue(message == "old checkout")
    },
    test("the two tiers on a missing flag") {
      for
        s <- strict.either
        l <- lenient
      yield assertTrue(s.isLeft, !l)
    }.provide(TestFeatureProvider.layer(Map.empty[String, Any])),
    test("FlagDef") {
      enabled.map(e => assertTrue(e)).provide(TestFeatureProvider.layer(Map("new-checkout" -> true)))
    }
  )
