package zio.openfeature.optimizely.it

import dev.openfeature.sdk.{ImmutableContext, Reason}
import zio._
import zio.openfeature.extras.{CachingConfig, CachingProvider, CircuitBreakerProvider, CircuitBreakerProviderConfig}
import zio.openfeature.optimizely.FastFailProviderHarness
import zio.openfeature.optimizely.it.RealOptimizelySupport._
import zio.test.Assertion._
import zio.test._

/** Verifies the documented production recipes from `docs/optimizely.md` work end-to-end against a real Optimizely
  * provider: wrapping with `CachingProvider` and with `CircuitBreakerProvider` from the `extras` module.
  *
  * The wrappers are `EventProvider` decorators — they delegate to the underlying Optimizely provider for evaluations
  * and surface their own decorator-specific behaviours (CACHED reason, OPEN-circuit fast-fail).
  */
object OptimizelyExtrasCompositionSpec extends ZIOSpecDefault {

  private def unsafeRun[A](effect: => UIO[A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def withCachedProvider[A](
    ttl: Duration = 5.seconds
  )(body: (zio.openfeature.optimizely.OptimizelyFeatureProvider, CachingProvider) => A): A = {
    val underlying = FastFailProviderHarness.newProvider(
      FastFailProviderHarness.buildFastFailClient(
        BasicSdkKey,
        OptimizelyItStack.datafileUrl(BasicSdkKey),
        java.time.Duration.ofSeconds(2),
        java.time.Duration.ofSeconds(3600)
      ),
      java.time.Duration.ofSeconds(5)
    )
    underlying.initialize(new ImmutableContext())
    val cached = unsafeRun(CachingProvider.make(underlying, CachingConfig(maxEntries = 256, ttl = ttl)))
    try body(underlying, cached)
    finally underlying.shutdown()
  }

  private def withCircuitBreakerOnError[A](
    body: (zio.openfeature.optimizely.OptimizelyFeatureProvider, CircuitBreakerProvider) => A
  ): A = {
    // Point the underlying provider at a bogus SDK-key filename so init fails (nginx 404) -> state == ERROR.
    val underlying = FastFailProviderHarness.newProvider(
      FastFailProviderHarness.buildFastFailClient(
        "it_no_such_key",
        OptimizelyItStack.datafileUrl("it_no_such_key"),
        java.time.Duration.ofMillis(800),
        java.time.Duration.ofSeconds(3600)
      ),
      java.time.Duration.ofMillis(500)
    )
    // Initialize but swallow the inevitable failure; state ends ERROR.
    try underlying.initialize(new ImmutableContext())
    catch { case _: Throwable => () }
    val cb = unsafeRun(
      CircuitBreakerProvider.make(
        underlying,
        CircuitBreakerProviderConfig(failureThreshold = 1, resetTimeout = 5.seconds, evaluationTimeout = 500.millis)
      )
    )
    try body(underlying, cb)
    finally underlying.shutdown()
  }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Optimizely + extras composition")(
    test("CachingProvider — first eval delegates, second eval returns CACHED for same flag+context") {
      withCachedProvider() { (_, cached) =>
        val ctx    = userContext("user-cache")
        val first  = cached.getBooleanEvaluation(BoolFlagKey, java.lang.Boolean.FALSE, ctx)
        val second = cached.getBooleanEvaluation(BoolFlagKey, java.lang.Boolean.FALSE, ctx)
        assert(first.getValue)(equalTo(java.lang.Boolean.TRUE)) &&
        assert(second.getValue)(equalTo(java.lang.Boolean.TRUE)) &&
        // First call goes through to Optimizely → reason is TARGETING_MATCH. Second call is served from the
        // CachingProvider's cache → reason is CACHED.
        assert(first.getReason)(equalTo(Reason.TARGETING_MATCH.name())) &&
        assert(second.getReason)(equalTo("CACHED"))
      }
    },
    test("CachingProvider — different contexts bypass each other's cached entries") {
      withCachedProvider() { (_, cached) =>
        val ctxA   = userContext("user-cache-a")
        val ctxB   = userContext("user-cache-b")
        val evA1   = cached.getStringEvaluation(StringFlagKey, "fallback", ctxA)
        val evB1   = cached.getStringEvaluation(StringFlagKey, "fallback", ctxB)
        val evA2   = cached.getStringEvaluation(StringFlagKey, "fallback", ctxA)
        // Both A and B see fresh decisions on their first call, then A's second call is cached.
        assert(evA1.getValue)(equalTo(StringFlagExpectedValue)) &&
        assert(evB1.getValue)(equalTo(StringFlagExpectedValue)) &&
        assert(evA1.getReason)(equalTo(Reason.TARGETING_MATCH.name())) &&
        assert(evB1.getReason)(equalTo(Reason.TARGETING_MATCH.name())) &&
        assert(evA2.getReason)(equalTo("CACHED"))
      }
    },
    test("CircuitBreakerProvider — delegate in ERROR state opens the circuit; evaluations fast-fail without delegating") {
      withCircuitBreakerOnError { (underlying, cb) =>
        @scala.annotation.nowarn("msg=deprecated")
        val underlyingState = underlying.getState
        val ctx             = userContext("user-cb")
        // OpenFeature pattern for hard failures: the CB throws `GeneralError`, which an SDK Client would catch and
        // surface to callers as a FlagEvaluationDetails with errorCode set. Direct provider call sees the throw.
        val outcome =
          try Right(cb.getBooleanEvaluation(BoolFlagKey, java.lang.Boolean.TRUE, ctx))
          catch { case t: Throwable => Left(t) }
        val fastFailed = outcome match {
          case Left(t)   => t.isInstanceOf[dev.openfeature.sdk.exceptions.GeneralError]
          case Right(ev) => ev.getErrorCode != null
        }
        assert(underlyingState.name())(equalTo("ERROR")) &&
        assert(fastFailed)(isTrue)
      }
    }
  ) @@ OptimizelyItStack.ifDockerAvailable @@ TestAspect.sequential @@ TestAspect.timeout(2.minutes) @@ TestAspect.withLiveClock
}
