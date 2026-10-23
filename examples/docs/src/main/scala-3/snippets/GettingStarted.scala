package snippets

import dev.openfeature.sdk.FeatureProvider
import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*

/** The code on the Getting started page. */
object GettingStarted:

  // --8<-- [start:first-flag]
  object FirstFlag extends ZIOAppDefault:

    val program: ZIO[FeatureFlags, FeatureFlagError, String] =
      for
        enabled <- FeatureFlags.boolean("new-checkout", default = false)
        message = if enabled then "new checkout" else "old checkout"
        _ <- ZIO.logInfo(message)
      yield message

    // An in-memory provider holding one flag
    def run = program.provide(TestFeatureProvider.scopedLayer(Map("new-checkout" -> true)))
  // --8<-- [end:first-flag]

  // --8<-- [start:real-provider]
  // Any OpenFeature Java provider: flagd, LaunchDarkly, Flagsmith, OFREP, Optimizely, ...
  def withProvider(provider: FeatureProvider): ZIO[Any, Throwable | FeatureFlagError, String] =
    FirstFlag.program.provide(
      Scope.default,
      FeatureFlags.fromProvider(provider) // initializes the provider, shuts it down when the scope closes
    )
  // --8<-- [end:real-provider]

  // --8<-- [start:two-tiers]
  val strict: ZIO[FeatureFlags, FeatureFlagError, Boolean] =
    FeatureFlags.boolean("payments-enabled", default = false) // fails if the flag cannot be read

  val lenient: ZIO[FeatureFlags, Nothing, Boolean] =
    FeatureFlags.booleanOrDefault("payments-enabled", default = false) // serves false instead
  // --8<-- [end:two-tiers]

  // --8<-- [start:flag-def]
  object Flags:
    val NewCheckout = FlagDef("new-checkout", false, "the redesigned checkout flow")

  val enabled: ZIO[FeatureFlags, FeatureFlagError, Boolean] = FeatureFlags.value(Flags.NewCheckout)
  // --8<-- [end:flag-def]
