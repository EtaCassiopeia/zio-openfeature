package snippets

import dev.openfeature.sdk.FeatureProvider
import zio.*
import zio.openfeature.*
import zio.openfeature.extras.EnvVarProvider

/** The code on the "Wiring a provider" page (docs/providers.md). */
object Providers:

  val program: ZIO[FeatureFlags, FeatureFlagError, Boolean] =
    FeatureFlags.boolean("new-checkout", default = false)

  // --8<-- [start:any-provider]
  // Any OpenFeature Java provider, e.g. `new FlagdProvider()` from
  // "dev.openfeature.contrib.providers" % "flagd"
  def run(provider: FeatureProvider): ZIO[Any, Throwable | FeatureFlagError, Boolean] =
    program.provide(FeatureFlags.fromProvider(provider))
  // --8<-- [end:any-provider]

  // --8<-- [start:shorthands]
  // Waits for the provider to be ready; a provider that cannot start fails the layer
  def blocking(provider: FeatureProvider): ZLayer[Any, Throwable, FeatureFlags] =
    FeatureFlags.fromProvider(provider)

  // Returns at once; evaluations fail with ProviderNotReady until the provider is ready
  def nonBlocking(provider: FeatureProvider): ZLayer[Any, Throwable, FeatureFlags] =
    FeatureFlags.fromProviderAsync(provider)
  // --8<-- [end:shorthands]

  // --8<-- [start:config]
  def configured(provider: FeatureProvider): ZLayer[Any, Throwable, FeatureFlags] =
    FeatureFlags.fromProvider(
      provider,
      FeatureFlagsConfig()
        .withDomain("checkout")            // a named client
        .withVersion("2.4.0")              // reported in clientMetadata
        .withHook(FeatureHook.logging())   // client-level hooks from the start
        .withEvaluationTimeout(300.millis) // bound every evaluation (default 1 second)
        .withInitTimeout(10.seconds)       // bound initialization (default 30 seconds)
        .withAsyncInit                     // do not block the layer build
        .withFallbackLogging(FallbackLogging.Off)
    )
  // --8<-- [end:config]

  // --8<-- [start:owned-domain]
  // A domain client shares the process-wide API by default; `Owned` makes shutdown stop its provider
  def checkoutClient(provider: FeatureProvider): ZLayer[Any, Throwable, FeatureFlags] =
    FeatureFlags.fromProvider(
      provider,
      FeatureFlagsConfig().withDomain("checkout").withApiOwnership(ApiOwnership.Owned)
    )
  // --8<-- [end:owned-domain]

  // --8<-- [start:multi-provider]
  // Ask `overrides` first; a flag it does not hold falls through to `remote`
  def layered(overrides: FeatureProvider, remote: FeatureProvider): ZLayer[Any, Throwable, FeatureFlags] =
    FeatureFlags.fromProvider(
      FeatureFlags.multiProvider(List(overrides, remote)), // MultiProviderStrategy.firstMatch by default
      FeatureFlagsConfig()
    )
  // --8<-- [end:multi-provider]

  // --8<-- [start:multi-provider-strategy]
  // Also fall through on errors: a failing `primary` is answered by `backup`
  def resilient(primary: FeatureProvider, backup: FeatureProvider): FeatureProvider =
    FeatureFlags.multiProvider(List(primary, backup), MultiProviderStrategy.firstSuccessful)
  // --8<-- [end:multi-provider-strategy]

  // --8<-- [start:acquire]
  def fallbackFirst(
    buildRealProvider: () => FeatureProvider // e.g. a constructor that downloads a datafile
  ): URLayer[Any, FeatureFlags & AcquireStatus] =
    FeatureFlags.fromAcquireAsync(
      acquire = ZIO.attemptBlocking(buildRealProvider()),
      fallback = ZIO.succeed(EnvVarProvider()), // a fresh instance each time it runs
      config = FeatureFlagsConfig(),         // what every instance takes
      acquireConfig = AcquireConfig.default // what only this path takes
        .withVerify(Verify.flagExists[Boolean]("kill-switch"))
        .withOnSwapped(ZIO.logInfo("real provider is live"))
        .withOnConstructionError(e => ZIO.logWarning(s"staying on env vars: ${e.getMessage}"))
    )
  // --8<-- [end:acquire]

  // --8<-- [start:acquire-verify]
  // Reject a candidate unless it knows both sentinel flags
  val checks: FeatureProvider => Task[Unit] =
    Verify.all(Verify.flagExists[Boolean]("kill-switch"), Verify.flagExists[String]("banner"))

  // Ask the real provider first, and fall through to the fallback only when it lacks the flag
  val compose: (FeatureProvider, FeatureProvider) => FeatureProvider =
    (real, fallback) => FeatureFlags.multiProvider(List(real, fallback))
  // --8<-- [end:acquire-verify]

  // --8<-- [start:acquire-status]
  // True only once the real provider is serving (`isLive`: the state is `Live`)
  val ready: URIO[AcquireStatus, Boolean] =
    AcquireStatus.get.map(_.isLive)

  // Wait for the outcome, either way: Live or Failed(cause)
  val outcome: URIO[AcquireStatus, Option[AcquireState]] =
    AcquireStatus.changes.filter(_ != AcquireState.Constructing).runHead
  // --8<-- [end:acquire-status]

  // --8<-- [start:registry]
  val billingFlag: ZIO[FeatureFlagRegistry, FeatureFlagError, Boolean] =
    for
      billing <- FeatureFlagRegistry.getClient("billing") // built once, then cached
      enabled <- billing.boolean("new-pricing", default = false)
    yield enabled

  def withRegistry(
    defaultProvider: FeatureProvider,
    billingProvider: FeatureProvider
  ): ZIO[Any, Throwable | FeatureFlagError, Boolean] =
    (FeatureFlagRegistry.setProvider("billing", billingProvider) *> billingFlag)
      .provide(FeatureFlagRegistry.fromProvider(defaultProvider))
  // --8<-- [end:registry]

  // --8<-- [start:hot-swap]
  def swap(next: FeatureProvider): ZIO[FeatureFlagsAdmin, FeatureFlagError, Unit] =
    FeatureFlagsAdmin.setProvider(next).catchSome { case e: FeatureFlagError.ProviderInitializationFailed =>
      // If initialize() threw, the previous provider is back and still serving.
      // If it timed out, the new provider stays registered and the status is Error until it finishes.
      ZIO.logWarning(s"swap failed: ${e.message}")
    }
  // --8<-- [end:hot-swap]

  // --8<-- [start:client-metadata]
  val describe: URIO[FeatureFlags, String] =
    for
      client   <- FeatureFlags.clientMetadata   // ClientMetadata(domain, version)
      provider <- FeatureFlags.providerMetadata // ProviderMetadata(name, version)
    yield s"client $client served by ${provider.name}"
  // --8<-- [end:client-metadata]
