package snippets

import zio.*
import zio.openfeature.*
import zio.openfeature.extras.EnvVarProvider
import zio.openfeature.testkit.*

/** The code shown on the documentation site. It is compiled with the build so that the docs cannot drift from the API.
  * Sections between `--8<--` markers are included by `mkdocs.yml`'s snippets extension; the tests under `src/test` run
  * the ones that can run on their own and check what the page says they do.
  */
object Home:

  // --8<-- [start:at-a-glance]
  object Flags:
    val NewCheckout = FlagDef("new-checkout", false, "the redesigned checkout flow")
    val MaxItems    = FlagDef("cart-max-items", 20)

  val checkout: ZIO[FeatureFlags, FeatureFlagError, String] =
    val user = EvaluationContext("user-42").withAttribute("plan", "pro")
    for
      enabled <- FeatureFlags.value(Flags.NewCheckout)                // fails if the flag cannot be read
      limit   <- FeatureFlags.valueOrDefault(Flags.MaxItems)          // never fails: serves 20 instead
      banner  <- FeatureFlags.stringOrDefault("banner", "none", user) // targeted at one user
    yield s"checkout=${if enabled then "new" else "old"} limit=$limit banner=$banner"
  // --8<-- [end:at-a-glance]

  // --8<-- [start:quick-start]
  object MyApp extends ZIOAppDefault:

    val program: ZIO[FeatureFlags, Nothing, Unit] =
      for
        // never fails: serves false if the flag is missing or the provider is down
        enabled <- FeatureFlags.booleanOrDefault("my-feature", default = false)
        _       <- ZIO.when(enabled)(ZIO.logInfo("my-feature is on"))
      yield ()

    // Flags from environment variables (FF_MY_FEATURE=true); swap in any OpenFeature provider
    def run = program.provide(Scope.default >>> FeatureFlags.fromProvider(EnvVarProvider()))
  // --8<-- [end:quick-start]

  // --8<-- [start:quick-start-test]
  // In tests, the in-memory provider from the testkit
  val inTests: ZIO[Any, Throwable, Unit] =
    MyApp.program.provide(TestFeatureProvider.scopedLayer(Map("my-feature" -> true)))
  // --8<-- [end:quick-start-test]
