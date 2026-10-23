package snippets

import zio.*
import zio.openfeature.*
import zio.openfeature.extras.{CircuitBreakerProvider, CircuitBreakerProviderConfig}
import zio.openfeature.ofrep.{OFREPProvider, OFREPProviderConfig}

/** The code on the OFREP page. Nothing here is run by the tests: every call goes to an OFREP server. */
object Ofrep:

  // --8<-- [start:quick-start]
  val flags: ZLayer[Any, Throwable | FeatureFlagError, FeatureFlags] =
    ZLayer.scoped {
      for
        provider <- OFREPProvider.make("https://flags.example.com") // fails here if the URL is malformed
        env      <- FeatureFlags.fromProviderAsync(provider).build
      yield env.get[FeatureFlags]
    }

  val checkout: ZIO[FeatureFlags, Nothing, Boolean] =
    FeatureFlags.booleanOrDefault("new-checkout", default = false, EvaluationContext("user-42"))
  // --8<-- [end:quick-start]

  // --8<-- [start:config]
  val configured: IO[FeatureFlagError.InvalidConfiguration, dev.openfeature.contrib.providers.ofrep.OfrepProvider] =
    OFREPProvider.make(
      OFREPProviderConfig(
        baseUrl = "https://flags.example.com",
        requestTimeout = Some(java.time.Duration.ofSeconds(2)),
        connectTimeout = Some(java.time.Duration.ofSeconds(1)),
        headers = Map("Authorization" -> List("Bearer my-token"))
      )
    )
  // --8<-- [end:config]

  // --8<-- [start:timeouts]
  def bounded(provider: dev.openfeature.contrib.providers.ofrep.OfrepProvider): ZLayer[Scope, Throwable, FeatureFlags] =
    FeatureFlags.fromProvider(
      provider,
      FeatureFlagsConfig(initMode = InitMode.Async)
        .withEvaluationTimeout(500.millis) // each evaluation is an HTTP call; give up on it after 500 ms
        .withInitTimeout(5.seconds)        // status goes Fatal if the provider is not usable by then
    )
  // --8<-- [end:timeouts]

  // --8<-- [start:circuit-breaker]
  val resilient: ZLayer[Any, Throwable | FeatureFlagError, FeatureFlags] =
    ZLayer.scoped {
      for
        ofrep   <- OFREPProvider.make("https://flags.example.com")
        breaker <- CircuitBreakerProvider.make(ofrep, CircuitBreakerProviderConfig(failureThreshold = 5))
        env     <- FeatureFlags.fromProviderAsync(breaker).build
      yield env.get[FeatureFlags]
    }
  // --8<-- [end:circuit-breaker]

  // --8<-- [start:alerting]
  val audited: ZIO[FeatureFlags, Nothing, Boolean] =
    FeatureFlags
      .resolveOrDefault[Boolean]("new-checkout", default = false)
      .tap { resolution =>
        ZIO.when(resolution.errorCode.isDefined)(
          ZIO.logWarning(s"new-checkout served its default: ${resolution.errorMessage.getOrElse("")}")
        )
      }
      .map(_.value)
  // --8<-- [end:alerting]
