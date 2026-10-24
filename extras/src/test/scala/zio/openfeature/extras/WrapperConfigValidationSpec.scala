package zio.openfeature.extras

import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, FeatureProvider, Metadata, Value}
import zio.openfeature.FeatureFlagError
import zio.openfeature.internal.ProviderEvaluations
import zio._
import zio.test._
import java.util.concurrent.atomic.AtomicInteger

// #451: the wrappers validate their config on every construction path, and `scoped` shuts the delegate down once.
object WrapperConfigValidationSpec extends ZIOSpecDefault {

  /** A plain delegate that counts shutdowns. */
  private class CountingDelegate extends FeatureProvider {
    val shutdownCount = new AtomicInteger(0)
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = "delegate" }
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = { shutdownCount.incrementAndGet(); () }
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) = ProviderEvaluations.of(d, "STATIC")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "STATIC")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "STATIC")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = ProviderEvaluations.of(d, "STATIC")
  }

  private val minusOneSecond: Duration = Duration.fromSeconds(-1)

  private def breakerFailure(config: CircuitBreakerProviderConfig): UIO[Option[String]] =
    CircuitBreakerProvider.make(new CountingDelegate, config).flip.option.map(_.map(_.reason))

  private def cachingFailure(config: CachingConfig): UIO[Option[String]] =
    CachingProvider.make(new CountingDelegate, config).flip.option.map(_.map(_.reason))

  private val invalidBreakerConfigs: List[(String, CircuitBreakerProviderConfig, String)] = List(
    (
      "failureThreshold = 0",
      CircuitBreakerProviderConfig(failureThreshold = 0),
      "CircuitBreakerProviderConfig.failureThreshold must be >= 1, got 0"
    ),
    (
      "failureThreshold = -1",
      CircuitBreakerProviderConfig(failureThreshold = -1),
      "CircuitBreakerProviderConfig.failureThreshold must be >= 1, got -1"
    ),
    (
      "halfOpenMaxCalls = 0",
      CircuitBreakerProviderConfig(halfOpenMaxCalls = 0),
      "CircuitBreakerProviderConfig.halfOpenMaxCalls must be >= 1, got 0"
    ),
    (
      "halfOpenMaxCalls = -1",
      CircuitBreakerProviderConfig(halfOpenMaxCalls = -1),
      "CircuitBreakerProviderConfig.halfOpenMaxCalls must be >= 1, got -1"
    ),
    (
      "resetTimeout = Zero",
      CircuitBreakerProviderConfig(resetTimeout = Duration.Zero),
      "CircuitBreakerProviderConfig.resetTimeout must be > 0, got PT0S"
    ),
    (
      "resetTimeout = -1s",
      CircuitBreakerProviderConfig(resetTimeout = minusOneSecond),
      "CircuitBreakerProviderConfig.resetTimeout must be > 0, got PT-1S"
    ),
    (
      "evaluationTimeout = Zero",
      CircuitBreakerProviderConfig(evaluationTimeout = Duration.Zero),
      "CircuitBreakerProviderConfig.evaluationTimeout must be > 0, got PT0S"
    ),
    (
      "evaluationTimeout = -1s",
      CircuitBreakerProviderConfig(evaluationTimeout = minusOneSecond),
      "CircuitBreakerProviderConfig.evaluationTimeout must be > 0, got PT-1S"
    ),
    (
      "stateCheckInterval = -1s",
      CircuitBreakerProviderConfig(stateCheckInterval = minusOneSecond),
      "CircuitBreakerProviderConfig.stateCheckInterval must be >= 0, got PT-1S"
    )
  )

  private val invalidCachingConfigs: List[(String, CachingConfig, String)] = List(
    ("maxEntries = 0", CachingConfig(maxEntries = 0), "CachingConfig.maxEntries must be >= 1, got 0"),
    ("maxEntries = -1", CachingConfig(maxEntries = -1), "CachingConfig.maxEntries must be >= 1, got -1"),
    ("ttl = Zero", CachingConfig(ttl = Duration.Zero), "CachingConfig.ttl must be > 0, got PT0S"),
    ("ttl = -1s", CachingConfig(ttl = minusOneSecond), "CachingConfig.ttl must be > 0, got PT-1S")
  )

  def spec = suite("WrapperConfigValidation (#451)")(
    suite("CircuitBreakerProvider.make")(
      invalidBreakerConfigs.map { case (label, config, expected) =>
        test(s"rejects $label") {
          for (failure <- breakerFailure(config)) yield assertTrue(failure == Some(expected))
        }
      } ++ List(
        test("reports every violation together") {
          for {
            failure <- breakerFailure(CircuitBreakerProviderConfig(failureThreshold = 0, halfOpenMaxCalls = 0))
          } yield assertTrue(
            failure == Some(
              "CircuitBreakerProviderConfig.failureThreshold must be >= 1, got 0; " +
                "CircuitBreakerProviderConfig.halfOpenMaxCalls must be >= 1, got 0"
            )
          )
        },
        test("accepts Infinity timeouts, a zero state-check interval and the default config") {
          val accepted = List(
            CircuitBreakerProviderConfig(),
            CircuitBreakerProviderConfig(evaluationTimeout = Duration.Infinity),
            CircuitBreakerProviderConfig(resetTimeout = Duration.Infinity),
            CircuitBreakerProviderConfig(stateCheckInterval = Duration.Zero),
            CircuitBreakerProviderConfig(stateCheckInterval = Duration.Infinity),
            CircuitBreakerProviderConfig(failureThreshold = 1, halfOpenMaxCalls = 1, resetTimeout = 1.nanos)
          )
          for (failures <- ZIO.foreach(accepted)(breakerFailure)) yield assertTrue(failures.forall(_.isEmpty))
        },
        test("the ticker overload validates too") {
          for {
            failure <- CircuitBreakerProvider
              .make(new CountingDelegate, CircuitBreakerProviderConfig(failureThreshold = 0), Ticker.system)
              .flip
              .option
          } yield assertTrue(
            failure == Some(
              FeatureFlagError.InvalidConfiguration("CircuitBreakerProviderConfig.failureThreshold must be >= 1, got 0")
            )
          )
        }
      )
    ),
    suite("CachingProvider.make")(
      invalidCachingConfigs.map { case (label, config, expected) =>
        test(s"rejects $label") {
          for (failure <- cachingFailure(config)) yield assertTrue(failure == Some(expected))
        }
      } ++ List(
        test("accepts an Infinity ttl, empty contextKeys and the default config") {
          val accepted = List(
            CachingConfig(),
            CachingConfig(ttl = Duration.Infinity),
            CachingConfig(maxEntries = 1, contextKeys = Some(Set.empty))
          )
          for (failures <- ZIO.foreach(accepted)(cachingFailure)) yield assertTrue(failures.forall(_.isEmpty))
        }
      )
    ),
    suite("unsafe apply throws IllegalArgumentException")(
      test("CircuitBreakerProvider.apply") {
        val thrown =
          scala.util.Try(
            CircuitBreakerProvider(new CountingDelegate, CircuitBreakerProviderConfig(failureThreshold = 0))
          )
        val message = thrown.failed.toOption.collect { case e: IllegalArgumentException => e.getMessage }
        assertTrue(message == Some("CircuitBreakerProviderConfig.failureThreshold must be >= 1, got 0"))
      },
      test("CachingProvider.apply") {
        val thrown  = scala.util.Try(CachingProvider(new CountingDelegate, CachingConfig(maxEntries = 0)))
        val message = thrown.failed.toOption.collect { case e: IllegalArgumentException => e.getMessage }
        assertTrue(message == Some("CachingConfig.maxEntries must be >= 1, got 0"))
      },
      test("CircuitBreaker standalone construction validates CircuitBreakerConfig, probeTimeout included") {
        val thrown  = scala.util.Try(CircuitBreaker(CircuitBreakerConfig(probeTimeout = Duration.Zero), Ticker.system))
        val message = thrown.failed.toOption.collect { case e: IllegalArgumentException => e.getMessage }
        assertTrue(message == Some("CircuitBreakerConfig.probeTimeout must be > 0, got PT0S"))
      },
      test("valid configs still construct") {
        val delegate = new CountingDelegate
        val built = scala.util.Try {
          CircuitBreakerProvider(delegate, CircuitBreakerProviderConfig(resetTimeout = Duration.Infinity))
          CachingProvider(delegate, CachingConfig(ttl = Duration.Infinity))
          CircuitBreaker(CircuitBreakerConfig(probeTimeout = Duration.Infinity), Ticker.system)
        }
        assertTrue(built.isSuccess)
      }
    ),
    suite("scoped")(
      test("CircuitBreakerProvider.scoped shuts the delegate down once when the scope closes") {
        val delegate = new CountingDelegate
        for {
          during <- ZIO.scoped(
            CircuitBreakerProvider.scoped(delegate).as(delegate.shutdownCount.get())
          )
          after = delegate.shutdownCount.get()
        } yield assertTrue(during == 0, after == 1)
      },
      test("CachingProvider.scoped shuts the delegate down once when the scope closes") {
        val delegate = new CountingDelegate
        for {
          during <- ZIO.scoped(CachingProvider.scoped(delegate).as(delegate.shutdownCount.get()))
          after = delegate.shutdownCount.get()
        } yield assertTrue(during == 0, after == 1)
      },
      test("an explicit shutdown inside the scope, then the finalizer, reaches the delegate once") {
        val breakerDelegate = new CountingDelegate
        val cachingDelegate = new CountingDelegate
        for {
          _ <- ZIO.scoped(
            CircuitBreakerProvider.scoped(breakerDelegate).flatMap(p => ZIO.attemptBlocking(p.shutdown()))
          )
          _ <- ZIO.scoped(
            CachingProvider.scoped(cachingDelegate).flatMap(p => ZIO.attemptBlocking(p.shutdown()))
          )
          breakerCount = breakerDelegate.shutdownCount.get()
          cachingCount = cachingDelegate.shutdownCount.get()
        } yield assertTrue(breakerCount == 1, cachingCount == 1)
      },
      test("a shutdown after the scope closed does not reach the delegate again") {
        val breakerDelegate = new CountingDelegate
        val cachingDelegate = new CountingDelegate
        for {
          breaker <- ZIO.scoped(CircuitBreakerProvider.scoped(breakerDelegate))
          cached  <- ZIO.scoped(CachingProvider.scoped(cachingDelegate))
          _       <- ZIO.attemptBlocking { breaker.shutdown(); cached.shutdown() }
          breakerCount = breakerDelegate.shutdownCount.get()
          cachingCount = cachingDelegate.shutdownCount.get()
        } yield assertTrue(breakerCount == 1, cachingCount == 1)
      },
      test("an invalid config fails scoped with InvalidConfiguration and registers no finalizer") {
        val delegate = new CountingDelegate
        for {
          breakerFailure <- ZIO
            .scoped(CircuitBreakerProvider.scoped(delegate, CircuitBreakerProviderConfig(failureThreshold = 0)))
            .flip
            .map(_.reason)
          cachingFailure <- ZIO
            .scoped(CachingProvider.scoped(delegate, CachingConfig(ttl = Duration.Zero)))
            .flip
            .map(_.reason)
          count = delegate.shutdownCount.get()
        } yield assertTrue(
          breakerFailure == "CircuitBreakerProviderConfig.failureThreshold must be >= 1, got 0",
          cachingFailure == "CachingConfig.ttl must be > 0, got PT0S",
          count == 0
        )
      }
    ) @@ TestAspect.withLiveClock
  )
}
