package zio.openfeature.testkit

import dev.openfeature.sdk.{ImmutableContext, OpenFeatureAPI}
import zio._
import zio.openfeature._
import zio.test._

/** #371: every `TestFeatureProvider` reported the same metadata name and there was no way to change it. The Java SDK
  * keys a `MultiProvider`'s providers by that name and silently keeps only the last of two same-named instances, and
  * this library's event-identity guard compares the same name across a `setProvider` swap — so two test providers could
  * not coexist wherever identity matters. `makeNamed` gives them distinct names.
  *
  * The chain tests are the load-bearing ones, and each carries its own evidence: precedence FAILS against a same-named
  * pair (the first provider is gone, so its value cannot win), and fall-through asserts that the first provider was
  * actually consulted rather than only that the value looks right — a value assertion alone cannot see the collapse,
  * because the surviving provider holds the key either way. The collapse itself is pinned in its own test so the trap
  * stays visible if anyone later changes it.
  *
  * Shared test source dir → compiles on 2.13 and 3: braces only, no `given`/`using`, no `enum`.
  */
object NamedProviderSpec extends ZIOSpecDefault {

  private val ctx = new ImmutableContext()

  private def chainOf(providers: TestFeatureProvider*): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags
      .fromProvider(
        FeatureFlags.multiProvider(providers.toList),
        FeatureFlagsConfig(),
        statusRef = None,
        apiOverride = Some(OpenFeatureAPI.createIsolated())
      )
      .build
      .map(_.get[FeatureFlags])

  def spec = suite("NamedProviderSpec")(
    suite("naming a provider")(
      test("makeNamed reports the given name on both metadata surfaces") {
        // BOTH surfaces, deliberately: the Java SDK reads `getMetadata.getName` (that is what MultiProvider keys on and
        // what the identity guard compares) while ZIO callers read `metadata`. A change that updated only one would
        // still satisfy a single-surface assertion and leave half the bug in place.
        for {
          provider <- TestFeatureProvider.makeNamed("provider-a")
        } yield assertTrue(
          provider.getMetadata.getName == "provider-a",
          provider.metadata.name == "provider-a",
          provider.metadata.version.contains("1.0.0")
        )
      },
      test("makeNamed seeds initial flags like make does") {
        for {
          provider <- TestFeatureProvider.makeNamed("seeded", Map("flag" -> true))
          result = provider.getBooleanEvaluation("flag", false, ctx)
        } yield assertTrue(result.getValue == true, result.getReason == "TARGETING_MATCH")
      },
      test("makeNamed defaults to no initial flags") {
        for {
          provider <- TestFeatureProvider.makeNamed("empty")
          result = provider.getBooleanEvaluation("flag", true, ctx)
        } yield assertTrue(result.getValue == true, result.getErrorCode != null)
      },
      test("every factory that is not makeNamed still reports DefaultName") {
        // The three constructor sites are `make`, `makeReadyWithInitDone` (the sync layers) and `makeNotReady` (the
        // async ones). All three are exercised here, because threading a name through only the first would leave the
        // async layers silently unnamed.
        ZIO.scoped {
          for {
            plain     <- TestFeatureProvider.make
            seeded    <- TestFeatureProvider.make(Map("flag" -> true))
            fromSync  <- TestFeatureProvider.layer.build.map(_.get[TestFeatureProvider])
            fromAsync <- TestFeatureProvider.asyncLayer.build.map(_.get[TestFeatureProvider])
          } yield assertTrue(
            TestFeatureProvider.DefaultName == "TestFeatureProvider",
            plain.getMetadata.getName == TestFeatureProvider.DefaultName,
            plain.metadata.name == TestFeatureProvider.DefaultName,
            seeded.getMetadata.getName == TestFeatureProvider.DefaultName,
            seeded.metadata.name == TestFeatureProvider.DefaultName,
            fromSync.getMetadata.getName == TestFeatureProvider.DefaultName,
            fromSync.metadata.name == TestFeatureProvider.DefaultName,
            fromAsync.getMetadata.getName == TestFeatureProvider.DefaultName,
            fromAsync.metadata.name == TestFeatureProvider.DefaultName
          )
        }
      },
      test("makeNamed(DefaultName) reports the default name, like make") {
        // Literal expectations on both providers rather than `named == plain`: comparing two implementations to each
        // other holds even when both are wrong, which is precisely the shape a mutation slips through.
        for {
          named <- TestFeatureProvider.makeNamed(TestFeatureProvider.DefaultName)
          plain <- TestFeatureProvider.make
        } yield assertTrue(
          named.getMetadata.getName == "TestFeatureProvider",
          plain.getMetadata.getName == "TestFeatureProvider"
        )
      }
    ),
    suite("two named providers coexist in a MultiProvider chain")(
      test("a key absent from the first named provider falls through to the second") {
        // The value alone cannot see the bug — under the collapse the surviving provider is `second`, which holds the
        // key either way. The `wasEvaluated` assertions are what make this discriminating: they show the FIRST
        // provider was consulted and declined, which is what "fall-through" means.
        ZIO.scoped {
          for {
            first     <- TestFeatureProvider.makeNamed("first", Map("only-in-first" -> "from-first"))
            second    <- TestFeatureProvider.makeNamed("second", Map("only-in-second" -> "from-second"))
            ff        <- chainOf(first, second)
            result    <- ff.string("only-in-second", "fallback")
            sawFirst  <- first.wasEvaluated("only-in-second")
            sawSecond <- second.wasEvaluated("only-in-second")
          } yield assertTrue(result == "from-second", sawFirst, sawSecond)
        }
      },
      test("the first named provider wins a key both providers hold") {
        // THE discriminating assertion: under the old single-name behaviour the first provider is not in the chain at
        // all (the second replaces it at the first's slot), so this reads "from-second" and fails.
        ZIO.scoped {
          for {
            first  <- TestFeatureProvider.makeNamed("first", Map("shadowed" -> "from-first"))
            second <- TestFeatureProvider.makeNamed("second", Map("shadowed" -> "from-second"))
            ff     <- chainOf(first, second)
            result <- ff.string("shadowed", "fallback")
          } yield assertTrue(result == "from-first")
        }
      },
      test("two DEFAULT-named providers still collapse, and the last one wins") {
        // Not a bug being fixed here — it is the Java SDK keying providers by metadata name, and it is exactly why
        // `makeNamed` exists. Pinned so the trap stays visible: if this ever starts failing, the SDK's behaviour
        // changed and the `makeNamed` scaladoc and docs/testkit.md need revisiting.
        ZIO.scoped {
          for {
            first    <- TestFeatureProvider.make(Map("shadowed" -> "from-first"))
            second   <- TestFeatureProvider.make(Map("shadowed" -> "from-second"))
            ff       <- chainOf(first, second)
            result   <- ff.string("shadowed", "fallback")
            sawFirst <- first.wasEvaluated("shadowed")
          } yield assertTrue(result == "from-second", !sawFirst)
        }
      }
    ),
    suite("hot-swap tells two named providers apart")(
      test("providerMetadata reports the new provider's name after setProvider") {
        // Mirrors core's ProviderHotSwapSpec, which had to hand-roll a stub provider with a settable name precisely
        // because the testkit provider could not supply one.
        ZIO.scoped {
          for {
            a      <- TestFeatureProvider.makeNamed("provider-a", Map("flag" -> "from-a"))
            b      <- TestFeatureProvider.makeNamed("provider-b", Map("flag" -> "from-b"))
            ff     <- TestFeatureProvider.layerFrom(a).build.map(_.get[FeatureFlags])
            before <- ff.providerMetadata
            valueA <- ff.string("flag", "fallback")
            _      <- ff.setProvider(b)
            after  <- ff.providerMetadata
            valueB <- ff.string("flag", "fallback")
          } yield assertTrue(
            before.name == "provider-a",
            after.name == "provider-b",
            valueA == "from-a",
            valueB == "from-b"
          )
        }
      }
    )
  )
}
