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
  * '''Why no `CircuitBreakerProvider`?''' The breaker in `zio-openfeature-extras` requires an `EventProvider`
  * (so it can emit `PROVIDER_*` events when it trips). The OFREP contrib provider extends `FeatureProvider`
  * directly, not `EventProvider`, so wrapping it requires a small adapter not provided by the library. The
  * Optimizely integration (`OptimizelyFeatureProvider`) does extend `EventProvider` — see `docs/optimizely.md`
  * for a circuit-breaker composition example.
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
