package example

import zio._
import zio.openfeature._
import zio.openfeature.ofrep.OFREPProvider

/** Reference wiring for a production OFREP-backed app.
  *
  * Demonstrates two of the library's resilience patterns:
  *
  *   1. `OFREPProvider.make(...)` validates the base URL before constructing — bad config fails at layer build, not at
  *      first evaluation. The returned `FeatureFlagError.InvalidConfiguration` is a typed value the caller can match
  *      on for actionable startup errors.
  *   2. `FeatureFlags.fromProvider(provider, FeatureFlagsConfig(initMode = InitMode.Async).withEvaluationTimeout(...))`
  *      bounds initialization via the default 30 s `initTimeout` and bounds per-evaluation latency via the explicit
  *      `evaluationTimeout`. If the OFREP endpoint is unreachable, `providerStatus` transitions to `Fatal` after the
  *      init timeout so the app stops polling for ready.
  *
  * '''On `CircuitBreakerProvider`:''' this used to say the breaker required an `EventProvider` and that wrapping
  * the OFREP contrib provider — which extends `FeatureProvider` directly — needed a hand-rolled adapter. That is
  * no longer true: since #379 the breaker (and since #382 `CachingProvider`) takes a plain `FeatureProvider`, so
  * the OFREP provider can be wrapped directly and no adapter is needed. The only thing a plain delegate gives up
  * is the event-driven trip; failure-count and state polling work unchanged. See `docs/optimizely.md` for a
  * circuit-breaker composition example.
  *
  * Set `OFREP_BASE_URL` in the environment to point at your OFREP gateway. Run via:
  *
  * {{{
  *   sbt "examplesOfrepInitTimeout/run"
  * }}}
  */
object OfrepInitTimeoutExample extends ZIOAppDefault {

  def run: ZIO[Any, Throwable, Unit] = ZIO.scoped {
    for {
      baseUrl  <- ZIO.succeed(sys.env.getOrElse("OFREP_BASE_URL", "http://localhost:8016"))
      provider <- OFREPProvider.make(baseUrl).mapError(e => new RuntimeException(e.message))
      // Per-evaluation latency cap. Init timeout uses the library default (30 s); override via .withInitTimeout(...):
      //   FeatureFlagsConfig(initMode = InitMode.Async).withEvaluationTimeout(500.millis).withInitTimeout(5.seconds)
      env <- FeatureFlags
        .fromProvider(provider, FeatureFlagsConfig(initMode = InitMode.Async).withEvaluationTimeout(500.millis))
        .build
      ff = env.get[FeatureFlags]
      _      <- ZIO.logInfo("Resolving feature flag 'new-checkout-flow'…")
      value  <- ff.boolean("new-checkout-flow", default = false).mapError(e => new RuntimeException(e.message))
      _      <- ZIO.logInfo(s"new-checkout-flow = $value")
      status <- ff.providerStatus
      _      <- ZIO.logInfo(s"providerStatus = $status")
    } yield ()
  }
}
