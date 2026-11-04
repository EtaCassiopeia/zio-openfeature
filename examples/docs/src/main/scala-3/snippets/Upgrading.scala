package snippets

import dev.openfeature.sdk.{EvaluationContext as SdkContext, FeatureProvider, ProviderEvaluation}
import zio.*
import zio.openfeature.*
import zio.openfeature.extras.{CachingConfig, CircuitBreakerProviderConfig, EnvVarProvider}
import zio.openfeature.ofrep.{OFREPFeatureProvider, OFREPProvider, OFREPProviderConfig}
import zio.openfeature.optimizely.{OptimizelyEvents, OptimizelyProviderConfig}
import zio.openfeature.testkit.TestFeatureProvider

/** The "after" code on the upgrade guide (docs/upgrading.md). Nothing here is run: it only has to compile. */
object Upgrading:

  // --8<-- [start:configs]
  val flagsConfig: FeatureFlagsConfig =
    FeatureFlagsConfig.default.withAsyncInit
      .withDomain("checkout")
      .withEvaluationTimeout(300.millis)

  val callOptions: EvaluationOptions =
    EvaluationOptions.empty.withTimeout(100.millis)

  val caching: CachingConfig =
    CachingConfig.default.withTtl(1.minute).withMaxEntries(1000)

  val breaker: CircuitBreakerProviderConfig =
    CircuitBreakerProviderConfig.default.withFailureThreshold(3).withResetTimeout(10.seconds)

  val ofrepConfig: OFREPProviderConfig =
    OFREPProviderConfig("https://flags.example.com").withRequestTimeout(java.time.Duration.ofSeconds(2))

  val optimizelyConfig: OptimizelyProviderConfig =
    OptimizelyProviderConfig("my-sdk-key")
      .withDatafileUrl("https://agent.example.com/datafile.json") // the bare value, not Some(url)
      .withEvents(OptimizelyEvents.Batched.default.withBatchSize(50))
  // --8<-- [end:configs]

  // --8<-- [start:batched-match]
  // A type pattern and the fields: Batched has no unapply
  def batchSize(events: OptimizelyEvents): Int =
    events match
      case batched: OptimizelyEvents.Batched => batched.batchSize
      case OptimizelyEvents.Disabled         => 0
  // --8<-- [end:batched-match]

  // --8<-- [start:admin]
  def swapThenRead(next: FeatureProvider): ZIO[FeatureFlags & FeatureFlagsAdmin, FeatureFlagError, Boolean] =
    for
      _       <- FeatureFlagsAdmin.onProviderReady(meta => ZIO.logInfo(s"ready: ${meta.name}"))
      _       <- FeatureFlagsAdmin.setProvider(next)
      enabled <- FeatureFlags.boolean("new-checkout", default = false)
    yield enabled

  // The factories' layers provide both services, so `provide` does not change
  def runSwap(first: FeatureProvider, next: FeatureProvider): Task[Boolean] =
    swapThenRead(next).provide(FeatureFlags.fromProvider(first))
  // --8<-- [end:admin]

  // --8<-- [start:admin-build]
  // Name the service: a Tag cannot hold the intersection the layer now provides
  def buildFlags(provider: FeatureProvider): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.fromProvider(provider).build.map(_.get[FeatureFlags])
  // --8<-- [end:admin-build]

  // --8<-- [start:admin-registry]
  // getClient returns FeatureFlags; FeatureFlagsAdmin.layer gives the same instance's administration
  val billingReady: ZIO[FeatureFlagRegistry, FeatureFlagError, UIO[Unit]] =
    FeatureFlagRegistry.getClient("billing").flatMap { client =>
      FeatureFlagsAdmin
        .onProviderReady(meta => ZIO.logInfo(s"billing: ${meta.name}"))
        .provide(ZLayer.succeed[FeatureFlags](client) >>> FeatureFlagsAdmin.layer)
    }
  // --8<-- [end:admin-registry]

  // --8<-- [start:factories]
  def configured(provider: FeatureProvider): ZLayer[Any, Throwable, FeatureFlags & FeatureFlagsAdmin] =
    FeatureFlags.fromProvider(
      provider,
      FeatureFlagsConfig.default
        .withDomain("checkout")
        .withVersion("2.4.0")
        .withHooks(List(FeatureHook.logging()))
        .withEvaluationTimeout(300.millis)
        .withInitTimeout(10.seconds)
        .withAsyncInit // the old *Async factories
    )

  def chain(providers: List[FeatureProvider]): ZLayer[Any, Throwable, FeatureFlags & FeatureFlagsAdmin] =
    FeatureFlags.fromProvider(
      FeatureFlags.multiProvider(providers, MultiProviderStrategy.firstSuccessful),
      FeatureFlagsConfig.default
    )

  // Validates the URL, shuts the provider down with the scope, and reads Long flags exactly
  val ofrepProvider: ZIO[Scope, FeatureFlagError.InvalidConfiguration, OFREPFeatureProvider] =
    OFREPProvider.scoped("https://flags.example.com")
  // --8<-- [end:factories]

  // --8<-- [start:errors]
  // Evaluations fail with EvaluationError: no arm for ProviderInitializationFailed or InvalidConfiguration
  def describe(error: FeatureFlagError.EvaluationError): String =
    error match
      case FeatureFlagError.FlagNotFound(key)            => s"no flag $key"
      case FeatureFlagError.TypeMismatch(key, _, detail) => s"$key: $detail" // `actual` is now `detail`
      case FeatureFlagError.Unauthorized(reason, _)      => s"rejected: $reason" // two fields now
      case FeatureFlagError.Unreachable(underlying)      => s"unreachable: ${underlying.getMessage}"
      case other                                         => other.message

  // A FeatureFlagError is a Throwable, so orDie takes it as it is
  val killSwitch: URIO[FeatureFlags, Boolean] =
    FeatureFlags.boolean("kill-switch", default = false).orDie

  def rejected(cause: Throwable): FeatureFlagError.Unauthorized =
    FeatureFlagError.Unauthorized("bad SDK key", Some(cause))
  // --8<-- [end:errors]

  // --8<-- [start:transaction-either]
  // Reads the same on Scala 2.13 and 3: Left is the block's error, Right the transaction's
  val overridden: ZIO[FeatureFlags, String, Boolean] =
    FeatureFlags
      .transactionEither(overrides = Map("new-checkout" -> true)) {
        FeatureFlags.boolean("new-checkout", default = false).mapError(_.message)
      }
      .mapError {
        case Left(mine)         => mine
        case Right(transaction) => transaction.message
      }
      .map(_.result)
  // --8<-- [end:transaction-either]

  // --8<-- [start:strategies]
  def builtIn(providers: List[FeatureProvider]): FeatureProvider =
    FeatureFlags.multiProvider(providers, MultiProviderStrategy.FirstSuccessful)

  // Scala's List and function in place of the SDK's java.util.List and java.util.function.Function.
  // SdkContext is dev.openfeature.sdk.EvaluationContext.
  final class PrimaryOnly extends MultiProviderStrategy.CustomStrategy:
    def evaluate[T](
      providers: List[FeatureProvider],
      key: String,
      defaultValue: T,
      ctx: SdkContext,
      call: FeatureProvider => ProviderEvaluation[T]
    ): ProviderEvaluation[T] =
      call(providers.head)

  def custom(providers: List[FeatureProvider]): FeatureProvider =
    FeatureFlags.multiProvider(providers, MultiProviderStrategy.Custom(new PrimaryOnly))
  // --8<-- [end:strategies]

  // --8<-- [start:acquire]
  def fallbackFirst(
    buildRealProvider: () => FeatureProvider
  ): URLayer[Any, FeatureFlags & FeatureFlagsAdmin & AcquireStatus] =
    FeatureFlags.fromAcquireAsync(
      acquire = ZIO.attemptBlocking(buildRealProvider()),
      fallback = ZIO.succeed(EnvVarProvider()),
      config = FeatureFlagsConfig.default
        .withEvaluationTimeout(200.millis)
        .withFallbackLogging(FallbackLogging.Off),
      acquireConfig = AcquireConfig.default
        .withConstructionTimeout(10.seconds)
        .withVerify(Verify.flagExists[Boolean]("kill-switch"))
        .withOnSwapped(ZIO.logInfo("real provider is live"))
    )
  // --8<-- [end:acquire]

  // --8<-- [start:implementing]
  // Abstract only to keep this short: a real one also implements the members listed below
  abstract class NoFlags extends FeatureFlags:
    def valueDetails[A: FlagType](
      key: String,
      default: A,
      ctx: EvaluationContext,
      options: EvaluationOptions
    ): IO[FeatureFlagError.EvaluationError, FlagResolution[A]] =
      ZIO.fail(FeatureFlagError.FlagNotFound(key))

    def track(eventName: String): IO[FeatureFlagError.EvaluationError, Unit] =
      ZIO.unit
  // --8<-- [end:implementing]

  // --8<-- [start:testkit]
  val testLayer: ZLayer[Any, Throwable, TestFeatureProvider & FeatureFlags & FeatureFlagsAdmin] =
    TestFeatureProvider.layer(Map("new-checkout" -> true))

  val received: URIO[TestFeatureProvider, List[(String, EvaluationContext, Option[TrackingEventDetails])]] =
    ZIO.serviceWithZIO[TestFeatureProvider](_.trackedEvents)
  // --8<-- [end:testkit]
