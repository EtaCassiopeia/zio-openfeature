package snippets

import com.typesafe.config.ConfigFactory
import dev.openfeature.sdk.FeatureProvider
import zio.*
import zio.openfeature.*
import zio.openfeature.extras.*

/** The code on the Extras page. `remote` stands for any provider you already have (a vendor SDK, OFREP, Optimizely). */
object Extras:

  // --8<-- [start:hocon]
  val fromApplicationConf: HoconProvider = HoconProvider()               // the `feature-flags` block
  val fromOtherPath: HoconProvider       = HoconProvider("my-app.flags") // any other path
  val fromConfig: HoconProvider          =
    HoconProvider.fromConfig(ConfigFactory.parseString("new-checkout = true"))

  val hoconFlags: ZLayer[Any, Throwable, FeatureFlags] = FeatureFlags.fromProvider(fromApplicationConf)
  // --8<-- [end:hocon]

  // --8<-- [start:hocon-reload]
  val reload: Task[Unit] = fromApplicationConf.reload() // re-reads application.conf; on failure keeps the old config
  // --8<-- [end:hocon-reload]

  // --8<-- [start:env]
  val fromEnv: EnvVarProvider     = EnvVarProvider()                  // new-checkout -> FF_NEW_CHECKOUT
  val withPrefix: EnvVarProvider  = EnvVarProvider(prefix = "MYAPP_") // new-checkout -> MYAPP_NEW_CHECKOUT
  val customNames: EnvVarProvider = EnvVarProvider(keyTransform = _.toUpperCase.replace(".", "__"))
  val fromAMap: EnvVarProvider    = EnvVarProvider.withLookup(Map("FF_NEW_CHECKOUT" -> "true").get)
  // --8<-- [end:env]

  // --8<-- [start:chain]
  // Environment variables override application.conf; a key neither holds falls back to the caller's default.
  val layered: ZLayer[Any, Throwable, FeatureFlags] =
    FeatureFlags.fromProvider(FeatureFlags.multiProvider(List(EnvVarProvider(), HoconProvider())))
  // --8<-- [end:chain]

  // --8<-- [start:caching]
  def cached(remote: FeatureProvider): ZLayer[Any, Throwable | FeatureFlagError, FeatureFlags] =
    ZLayer.scoped(CachingProvider.scoped(remote, CachingConfig(maxEntries = 1000, ttl = 1.minute))).flatMap { env =>
      FeatureFlags.fromProvider(env.get[CachingProvider])
    }
  // --8<-- [end:caching]

  // --8<-- [start:caching-context-keys]
  val byPlanAndRegion: CachingConfig = CachingConfig(ttl = 5.minutes, contextKeys = Some(Set("plan", "region")))
  // --8<-- [end:caching-context-keys]

  // --8<-- [start:caching-invalidate]
  def refreshEveryMinute(cache: CachingProvider): URIO[Scope, Unit] =
    cache.invalidateAll.repeat(Schedule.fixed(1.minute)).forkScoped.unit
  // --8<-- [end:caching-invalidate]

  // --8<-- [start:circuit-breaker]
  def resilient(remote: FeatureProvider): ZLayer[Any, Throwable | FeatureFlagError, FeatureFlags] =
    ZLayer
      .scoped(
        CircuitBreakerProvider.scoped(
          remote,
          CircuitBreakerProviderConfig(
            failureThreshold = 3,          // open after 3 consecutive infrastructure failures
            resetTimeout = 30.seconds,     // then wait 30 s before letting one probe through
            evaluationTimeout = 200.millis // a call slower than this counts as a failure
          )
        )
      )
      .flatMap { env =>
        // While the circuit is open, the breaker fails at once and the next provider answers
        val chain = FeatureFlags.multiProvider(
          List(env.get[CircuitBreakerProvider], EnvVarProvider()),
          MultiProviderStrategy.firstSuccessful
        )
        FeatureFlags.fromProvider(chain)
      }
  // --8<-- [end:circuit-breaker]

  // --8<-- [start:deferred]
  // `construct` runs inside initialize(), on the SDK's init thread, not where the layer is built
  def deferred(construct: () => FeatureProvider): ZLayer[Any, Throwable, FeatureFlags] =
    FeatureFlags.fromProviderAsync(DeferredProvider("my-vendor")(construct))
  // --8<-- [end:deferred]

  // --8<-- [start:integer-widening]
  def widened(legacy: FeatureProvider): ZLayer[Any, Throwable, FeatureFlags] =
    FeatureFlags.fromProvider(IntegerWideningLongProvider(legacy))
  // --8<-- [end:integer-widening]
