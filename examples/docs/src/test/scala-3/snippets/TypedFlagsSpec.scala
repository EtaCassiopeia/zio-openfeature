package snippets

import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*
import zio.test.*
import snippets.TypedFlags.*
import snippets.TypedFlags.given

object TypedFlagsSpec extends ZIOSpecDefault:

  object LevelLawsSpec extends ZIOSpecDefault:
    def spec = FlagTypeLaws.all(Gen.int.map(Level(_)))

  def spec = suite("Typed flags")(
    test("a fixture is read back at the domain type") {
      for
        p <- plan
        c <- value
      yield assertTrue(p == Tier.Premium, c)
    }.provide(fixture),
    test("derived enum decodes case-insensitively and encodes the declared label") {
      val ft = summon[FlagType[Tier]]
      assertTrue(
        ft.wire == WireType.String,
        ft.decode("PREMIUM") == Right(Tier.Premium),
        ft.encode(Tier.Enterprise) == "Enterprise",
        ft.decode("gold").isLeft
      )
    },
    test("derived product: Scala default, then Option, then an error") {
      val ft = summon[FlagType[Rollout]]
      assertTrue(
        ft.decode(Map("tier" -> "beta")) == Right(Rollout("beta", 10, None)),
        ft.decode(Map("tier" -> "beta", "extra" -> 1)) == Right(Rollout("beta", 10, None)),
        ft.decode(Map("pct" -> 5)).isLeft,
        ft.defaultValue == Rollout("", 0, None)
      )
    },
    test("mapped inherits the wire type") {
      assertTrue(summon[FlagType[Level]].wire == WireType.Int, summon[FlagType[Level]].typeName == "Level")
    },
    test("an unknown enum value is a TypeMismatch, not the default") {
      for
        provider <- ZIO.service[TestFeatureProvider]
        _        <- provider.setFlag("user-plan", "gold")
        result   <- plan.either
      yield assertTrue(result.left.exists(_.isInstanceOf[FeatureFlagError.TypeMismatch]))
    }.provide(fixture),
    test("an unseeded flag serves the FlagDef default on the total tier") {
      FeatureFlags.valueOrDefault(Flags.PageSize).map(l => assertTrue(l == Level(1)))
    }.provide(fixture),
    test("object-backed and fallible types evaluate") {
      for
        provider <- ZIO.service[TestFeatureProvider]
        _        <- provider.setFlags(Map("plan" -> Map("tier" -> "pro", "percentage" -> 50), "region" -> "us"))
        p        <- FeatureFlags.value("plan", RolloutPlan("free", 0))
        r        <- FeatureFlags.value("region", Region.Eu)
        _        <- provider.setFlag("region", "mars")
        bad      <- FeatureFlags.value("region", Region.Eu).either
      yield assertTrue(
        p == RolloutPlan("pro", 50),
        r == Region.Us,
        bad.left.exists(_.isInstanceOf[FeatureFlagError.TypeMismatch])
      )
    }.provide(TestFeatureProvider.layer(Map.empty[String, Any])),
    test("equality and sameKey") {
      assertTrue(!equal, same)
    },
    LevelLawsSpec.spec
  )
