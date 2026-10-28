package snippets

import zio.*
import zio.openfeature.*
import zio.openfeature.extras.{CircuitBreakerProvider, CircuitBreakerProviderConfig}
import zio.openfeature.ofrep.{OFREPFeatureProvider, OFREPProvider, OFREPProviderConfig}

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
  val configured: IO[FeatureFlagError.InvalidConfiguration, OFREPFeatureProvider] =
    OFREPProvider.make(
      OFREPProviderConfig(
        baseUrl = "https://flags.example.com",
        requestTimeout = Some(java.time.Duration.ofSeconds(2)),
        connectTimeout = Some(java.time.Duration.ofSeconds(1)),
        headers = Map("Authorization" -> List("Bearer my-token"))
      )
    )
  // --8<-- [end:config]

  // --8<-- [start:lifecycle]
  // `make` leaves shutdown to the FeatureFlags layer you register the provider with; `scoped` and `layer` own it
  val inScope: ZIO[Scope, FeatureFlagError.InvalidConfiguration, OFREPFeatureProvider] =
    OFREPProvider.scoped(OFREPProviderConfig(baseUrl = "https://flags.example.com"))

  val asLayer: ZLayer[Any, FeatureFlagError.InvalidConfiguration, OFREPFeatureProvider] =
    OFREPProvider.layer("https://flags.example.com")
  // --8<-- [end:lifecycle]

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
