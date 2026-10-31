package snippets

import zio.*
import zio.openfeature.*
import zio.openfeature.extras.{CircuitBreakerProvider, CircuitBreakerProviderConfig, EnvVarProvider}
import zio.openfeature.optimizely.{
  OptimizelyEvents,
  OptimizelyFeatureProvider,
  OptimizelyProvider,
  OptimizelyProviderConfig
}

/** The code on the Optimizely page. The tests only check the parts that need no network: SDK key validation. */
object Optimizely:

  // --8<-- [start:quick-start]
  val flags: ZLayer[Any, Throwable | FeatureFlagError, FeatureFlags] =
    ZLayer.scoped {
      for
        sdkKey   <- ZIO.attempt(sys.env("OPTIMIZELY_SDK_KEY"))
        provider <- OptimizelyProvider.make(sdkKey) // validates the key and starts fetching the datafile
        env      <- FeatureFlags.fromProviderAsync(provider).build
      yield env.get[FeatureFlags]
    }

  // Optimizely decides per user: every evaluation needs a targeting key
  val checkout: ZIO[FeatureFlags, FeatureFlagError, Boolean] =
    FeatureFlags.boolean("new-checkout", default = false, EvaluationContext("user-42").withAttribute("plan", "pro"))
  // --8<-- [end:quick-start]

  // --8<-- [start:config]
  val tuned: IO[FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    OptimizelyProvider.make(
      OptimizelyProviderConfig(
        sdkKey = "abcd1234efgh5678",
        pollingInterval = Some(java.time.Duration.ofMinutes(1)), // also turns on the staleness watchdog
        staleAfter = Some(java.time.Duration.ofMinutes(5))       // STALE after 5 min without a successful fetch
      )
    )
  // --8<-- [end:config]

  // --8<-- [start:events-tuned]
  val batched: IO[FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    OptimizelyProvider.make(
      OptimizelyProviderConfig(
        sdkKey = "abcd1234efgh5678",
        events = OptimizelyEvents.Batched(batchSize = 50, flushInterval = java.time.Duration.ofSeconds(10))
      )
    )
  // --8<-- [end:events-tuned]

  // --8<-- [start:agent]
  val selfHosted: IO[FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    OptimizelyProvider.make(
      sdkKey = "abcd1234efgh5678",
      datafileUrl = "https://flags.internal.example.com/datafile.json"
    )
  // --8<-- [end:agent]

  // --8<-- [start:variables]
  // A string flag reads the variable named "value"; this call reads the variable "headline" instead
  val headline: ZIO[FeatureFlags, FeatureFlagError, String] =
    FeatureFlags.string(
      "homepage",
      default = "Welcome",
      EvaluationContext("user-42").withAttribute(OptimizelyFeatureProvider.VariableKeyAttribute, "headline")
    )
  // --8<-- [end:variables]

  // --8<-- [start:object-flag]
  // An object flag reads a JSON variable; the context names which one
  val limits: ZIO[FeatureFlags, FeatureFlagError, Map[String, Any]] =
    FeatureFlags.obj(
      "rate-limits",
      default = Map.empty,
      EvaluationContext("user-42").withAttribute(OptimizelyFeatureProvider.VariableKeyAttribute, "limits")
    )
  // --8<-- [end:object-flag]

  // --8<-- [start:track]
  // Optimizely's revenue metric reads an integer attribute named "revenue", in cents
  val purchase: ZIO[FeatureFlags, FeatureFlagError, Unit] =
    FeatureFlags.track(
      "purchase",
      EvaluationContext("user-42"),
      TrackingEventDetails.empty.withValue(49.99).withAttribute("revenue", 4999)
    )
  // --8<-- [end:track]

  // --8<-- [start:scoped]
  val inScope: ZIO[Scope, FeatureFlagError.InvalidConfiguration, OptimizelyFeatureProvider] =
    OptimizelyProvider.scoped("abcd1234efgh5678") // shut down when the scope closes
  // --8<-- [end:scoped]

  // --8<-- [start:fail-fast]
  def failFast(provider: OptimizelyFeatureProvider): ZLayer[Any, Throwable, FeatureFlags] =
    FeatureFlags.fromProvider(provider, FeatureFlagsConfig().withInitTimeout(15.seconds)) // build fails if not ready
  // --8<-- [end:fail-fast]

  // --8<-- [start:readiness]
  val ready: URIO[FeatureFlags, Boolean] =
    FeatureFlags.providerStatus.map {
      case ProviderStatus.Ready | ProviderStatus.Stale => true
      case _                                           => false
    }
  // --8<-- [end:readiness]

  // --8<-- [start:circuit-breaker]
  def resilient(provider: OptimizelyFeatureProvider): ZLayer[Any, Throwable | FeatureFlagError, FeatureFlags] =
    ZLayer.fromZIO(CircuitBreakerProvider.make(provider, CircuitBreakerProviderConfig(failureThreshold = 5))).flatMap {
      env =>
        val chain = FeatureFlags.multiProvider(
          List(env.get[CircuitBreakerProvider], EnvVarProvider()), // env vars answer while Optimizely is out
          MultiProviderStrategy.firstSuccessful
        )
        FeatureFlags.fromProvider(chain, FeatureFlagsConfig().withAsyncInit)
    }
  // --8<-- [end:circuit-breaker]

  // --8<-- [start:config-changed]
  val logChanges: URIO[FeatureFlags, UIO[Unit]] =
    FeatureFlagsAdmin.onConfigurationChanged((_, _) => ZIO.logInfo("Optimizely datafile changed"))
  // --8<-- [end:config-changed]

  // --8<-- [start:own-client]
  def fromClient(client: com.optimizely.ab.Optimizely): UIO[OptimizelyFeatureProvider] =
    OptimizelyProvider.fromOptimizelyClient(client) // you keep ownership: shutdown does not close the client
  // --8<-- [end:own-client]
