package zio.openfeature.extras

import com.typesafe.config.ConfigFactory
import dev.openfeature.sdk.{ErrorCode, ImmutableContext}
import zio._
import zio.openfeature.FeatureFlags
import zio.test._

/** #355: `HoconProvider` and `EnvVarProvider` answered an absent key with `reason = DEFAULT` and no error code, which
  * reads to a `MultiProvider` chain as "I answered" — so the chain stopped at the first provider instead of trying the
  * next one. An absent key is `FLAG_NOT_FOUND`.
  *
  * The chain test below is the load-bearing one: the unit assertions pin the reason each provider reports, but only the
  * chain shows the user-visible consequence, and it is what settles what `FirstMatchStrategy` actually does with a
  * default-reason result (this repo had no test of chain behaviour at all — `MultiProviderStrategySpec` only checks the
  * type aliases).
  *
  * Shared test source dir → compiles on 2.13 and 3: braces only, no `given`/`using`, no `enum`.
  */
object AbsentKeyChainSpec extends ZIOSpecDefault {

  private val ctx = new ImmutableContext()

  private val hoconWithoutKey = HoconProvider.fromConfig(ConfigFactory.parseString("""
    present-in-hocon = true
  """))

  private val hoconWithShadow = HoconProvider.fromConfig(ConfigFactory.parseString("""
    shadowed = "from-first"
  """))

  /** The SECOND provider in each chain is deliberately a different provider TYPE.
    *
    * The Java SDK's `MultiProvider` keys its providers by metadata name, so two instances of the same provider type
    * collapse into one and the chain silently consults only the survivor. A first draft of this spec used two
    * `HoconProvider`s and was worthless: the fall-through test passed before the fix (because the "first" provider had
    * been discarded, not because fall-through worked) while the precedence test failed against correct code. Distinct
    * names keep both providers in the chain, so the ordering assertions mean what they say.
    *
    * `EnvVarProvider` maps a flag key to `FF_` + uppercase-with-underscores, hence the `FF_`-prefixed lookup keys.
    */
  private val envWithKeys = EnvVarProvider.withLookup(
    Map(
      "FF_ONLY_IN_SECOND" -> "from-second",
      "FF_SHADOWED"       -> "from-second"
    ).get
  )

  private val envWithoutKeys = EnvVarProvider.withLookup(Map.empty[String, String].get)

  def spec = suite("AbsentKeyChainSpec")(
    suite("an absent key is FLAG_NOT_FOUND, not DEFAULT")(
      test("HoconProvider reports FLAG_NOT_FOUND for every scalar type") {
        val b = hoconWithoutKey.getBooleanEvaluation("missing", true, ctx)
        val s = hoconWithoutKey.getStringEvaluation("missing", "d", ctx)
        val i = hoconWithoutKey.getIntegerEvaluation("missing", Int.box(1), ctx)
        val l = hoconWithoutKey.getLongEvaluation("missing", java.lang.Long.valueOf(1L), ctx)
        val d = hoconWithoutKey.getDoubleEvaluation("missing", Double.box(1.0), ctx)
        assertTrue(
          b.getErrorCode == ErrorCode.FLAG_NOT_FOUND,
          s.getErrorCode == ErrorCode.FLAG_NOT_FOUND,
          i.getErrorCode == ErrorCode.FLAG_NOT_FOUND,
          l.getErrorCode == ErrorCode.FLAG_NOT_FOUND,
          d.getErrorCode == ErrorCode.FLAG_NOT_FOUND,
          // The caller's default is still returned alongside the code, so nothing throws and nothing returns null.
          b.getValue == true,
          s.getValue == "d",
          // Pin the reason too, not just the code — `ProviderEvaluations.error` sets ERROR, and that is what flips
          // hooks from the `after` stage to `error`, which is the observable part of this change.
          b.getReason == "ERROR",
          s.getReason == "ERROR"
        )
      },
      test("HoconProvider still reports STATIC for a key that IS present") {
        val present = hoconWithoutKey.getBooleanEvaluation("present-in-hocon", false, ctx)
        assertTrue(present.getReason == "STATIC", present.getValue == true, present.getErrorCode == null)
      },
      test("EnvVarProvider reports FLAG_NOT_FOUND for an absent variable") {
        // An explicit lookup rather than the real environment, so the test does not depend on the host's env.
        val provider = EnvVarProvider.withLookup(Map("FF_PRESENT" -> "true").get)
        val b        = provider.getBooleanEvaluation("missing", true, ctx)
        val s        = provider.getStringEvaluation("missing", "d", ctx)
        val present  = provider.getBooleanEvaluation("present", false, ctx)
        assertTrue(
          b.getErrorCode == ErrorCode.FLAG_NOT_FOUND,
          s.getErrorCode == ErrorCode.FLAG_NOT_FOUND,
          b.getValue == true,
          s.getValue == "d",
          b.getReason == "ERROR",
          s.getReason == "ERROR",
          // A variable that IS set keeps reporting STATIC with no error code.
          present.getReason == "STATIC",
          present.getValue == true,
          present.getErrorCode == null
        )
      }
    ),
    suite("MultiProvider chains fall through")(
      test("a key absent from the first provider resolves from the second") {
        // THE point of the issue: the first provider does not hold the key, so the chain must consult the second.
        val chain  = FeatureFlags.multiProvider(List(hoconWithoutKey, envWithKeys))
        val result = chain.getStringEvaluation("only-in-second", "fallback", ctx)
        assertTrue(result.getValue == "from-second")
      },
      test("the first provider still wins when it does hold the key") {
        // Guards the other direction: fall-through must not become "last one wins". Both providers hold `shadowed`.
        val chain  = FeatureFlags.multiProvider(List(hoconWithShadow, envWithKeys))
        val result = chain.getStringEvaluation("shadowed", "fallback", ctx)
        assertTrue(result.getValue == "from-first")
      },
      test("a key absent from every provider still yields the caller's default through the client") {
        // Deliberately exercised through `FeatureFlags`, not the raw provider. When every provider reports
        // FLAG_NOT_FOUND the chain itself has no value to give and answers null — substituting the caller's default
        // is the SDK client's job, not the provider's. Asserting this at the provider level would be asserting the
        // wrong layer's contract; asserting it here is what proves the fix costs users nothing.
        val chain = FeatureFlags.multiProvider(List(hoconWithoutKey, envWithoutKeys))
        ZIO.scoped {
          FeatureFlags.fromProvider(chain).build.map(_.get[FeatureFlags]).flatMap { ff =>
            for {
              s <- ff.string("nowhere", "fallback")
              b <- ff.boolean("nowhere", true)
            } yield assertTrue(s == "fallback", b)
          }
        }
      }
    )
  )
}
