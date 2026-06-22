package zio.openfeature
import zio.openfeature.internal.ProviderEvaluations

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPIFactory,
  ProviderEvaluation,
  ProviderState,
  Value
}
import zio._
import zio.test._
import zio.test.TestAspect.{withLiveClock, timeout, sequential}

/** Verifies the bounded-latency contract of the default evaluationTimeout.
  *
  * With the 1-second default, any provider whose evaluation takes longer than 1 second must fail with
  * FeatureFlagError.ProviderError wrapping a TimeoutException — the calling fiber must not block indefinitely.
  *
  * These tests use a slow inline provider that sleeps inside evaluation, simulating a hung or very slow remote call
  * (e.g., Optimizely CDN unreachable, OFREP server not responding).
  */
object BoundedLatencyContractSpec extends ZIOSpecDefault {

  private class SlowProvider(delayMillis: Long) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "SlowProvider" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()

    override def getBooleanEvaluation(
      k: String,
      d: java.lang.Boolean,
      c: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Boolean] = {
      Thread.sleep(delayMillis)
      ProviderEvaluations.of[java.lang.Boolean](true, "STATIC")
    }
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) = {
      Thread.sleep(delayMillis)
      ProviderEvaluations.of[String](d, "DEFAULT")
    }
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) = {
      Thread.sleep(delayMillis)
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    }
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) = {
      Thread.sleep(delayMillis)
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    }
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = {
      Thread.sleep(delayMillis)
      ProviderEvaluations.of[Value](d, "DEFAULT")
    }
  }

  private def buildWithTimeout(provider: EventProvider, evalTimeout: Duration): ZIO[Scope, Throwable, FeatureFlags] = {
    val api    = OpenFeatureAPIFactory.create()
    val domain = s"latency-${java.util.UUID.randomUUID()}"
    FeatureFlags.build(
      provider,
      domain = Some(domain),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(api),
      evaluationTimeout = Some(evalTimeout)
    )
  }

  def spec = suite("BoundedLatencyContractSpec")(
    test("slow provider (2s delay) fails with ProviderError within 1s default timeout") {
      ZIO.scoped {
        for {
          ff     <- buildWithTimeout(new SlowProvider(2000), 1.second)
          start  <- Clock.nanoTime
          result <- ff.boolean("flag", default = false).either
          end    <- Clock.nanoTime
          elapsed = Duration.fromNanos(end - start)
          _ <- ZIO.logInfo(s"Elapsed: ${elapsed.toMillis}ms")
        } yield assertTrue(
          result.isLeft,
          result.left.exists(_.isInstanceOf[FeatureFlagError.ProviderError]),
          // The ProviderError above already proves the 1s timeout fired before the provider's 2s sleep returned;
          // this only guards against pathological lateness. Generous bound so a slow/loaded CI runner can't flake it.
          elapsed.toMillis < 1900L
        )
      }
    } @@ withLiveClock,
    test("fast provider (10ms delay) succeeds within 1s default timeout") {
      ZIO.scoped {
        for {
          ff     <- buildWithTimeout(new SlowProvider(10), 1.second)
          result <- ff.boolean("flag", default = false)
        } yield assertTrue(result == true)
      }
    } @@ withLiveClock,
    test("all flag types respect the default timeout") {
      ZIO.scoped {
        for {
          ff      <- buildWithTimeout(new SlowProvider(2000), 500.millis)
          bResult <- ff.boolean("b", default = false).either
          sResult <- ff.string("s", default = "x").either
          iResult <- ff.int("i", default = 0).either
          dResult <- ff.double("d", default = 0.0).either
        } yield assertTrue(
          bResult.isLeft && bResult.left.exists(_.isInstanceOf[FeatureFlagError.ProviderError]),
          sResult.isLeft && sResult.left.exists(_.isInstanceOf[FeatureFlagError.ProviderError]),
          iResult.isLeft && iResult.left.exists(_.isInstanceOf[FeatureFlagError.ProviderError]),
          dResult.isLeft && dResult.left.exists(_.isInstanceOf[FeatureFlagError.ProviderError])
        )
      }
    } @@ withLiveClock,
    test("per-call timeout is tighter than global — per-call fires") {
      ZIO.scoped {
        for {
          // Global 5s, per-call 100ms; slow provider takes 2s → per-call fires first
          ff    <- buildWithTimeout(new SlowProvider(2000), 5.seconds)
          start <- Clock.nanoTime
          result <- ff
            .booleanDetails(
              "flag",
              default = false,
              ctx = EvaluationContext.empty,
              options = EvaluationOptions.empty.withTimeout(100.millis)
            )
            .either
          end <- Clock.nanoTime
          elapsed = Duration.fromNanos(end - start)
        } yield assertTrue(
          result.isLeft,
          result.left.exists(_.isInstanceOf[FeatureFlagError.ProviderError]),
          // ProviderError proves a timeout fired; `< 1900` proves it was the 100ms per-call timeout, not the 5s
          // global one (and beat the provider's 2s). Loose enough not to flake on a slow runner.
          elapsed.toMillis < 1900L
        )
      }
    } @@ withLiveClock,
    test("1000 concurrent slow evaluations all return within bounded time") {
      ZIO.scoped {
        for {
          ff      <- buildWithTimeout(new SlowProvider(2000), 500.millis)
          start   <- Clock.nanoTime
          results <- ZIO.foreachPar(1 to 1000)(_ => ff.boolean("flag", default = false).either)
          end     <- Clock.nanoTime
          elapsed = Duration.fromNanos(end - start)
          errors  = results.count(_.isLeft)
        } yield assertTrue(
          errors == 1000,
          // `errors == 1000` already proves every fiber was bounded (timed out, none ran the full 2s). This guards
          // against serial execution — serial would be ~1000 × 500ms = 500s — with a very generous bound (the suite's
          // `timeout(20.seconds)` is the real hang-guard) so thread-pool scheduling on a slow CI runner can't flake it.
          elapsed.toMillis < 15000L
        )
      }
    } @@ withLiveClock @@ timeout(20.seconds)
  ) @@ sequential
}
