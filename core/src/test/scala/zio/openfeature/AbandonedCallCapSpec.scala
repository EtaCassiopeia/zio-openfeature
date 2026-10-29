package zio.openfeature

import zio._
import zio.test._
import zio.test.TestAspect.{samples, sequential, withLiveClock}
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, FeatureProvider, Metadata, OpenFeatureAPI, Value}

import java.util.concurrent.{CountDownLatch, RejectedExecutionException, TimeoutException}
import java.util.concurrent.atomic.AtomicInteger

/** A timed-out provider call is abandoned, not cancelled, and keeps a blocking-pool thread until the provider returns.
  * Each `FeatureFlags` instance caps how many such calls it tolerates; past the cap, timed evaluations fail fast with
  * `ProviderError(RejectedExecutionException)` without reaching the provider.
  */
object AbandonedCallCapSpec extends ZIOSpecDefault {

  /** `block` waits on `gate`; `sleep-<ms>` sleeps that long; any other key answers `true` at once. */
  final private class GatedProvider extends FeatureProvider {
    val gate     = new CountDownLatch(1)
    val entered  = new AtomicInteger(0)
    val returned = new AtomicInteger(0)

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata { override def getName: String = "gated" }

    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = {
      entered.incrementAndGet()
      try {
        if (k == "block") gate.await()
        else if (k.startsWith("sleep-")) Thread.sleep(k.stripPrefix("sleep-").toLong)
        ProviderEvaluations.of(java.lang.Boolean.TRUE, "STATIC")
      } finally returned.incrementAndGet()
    }
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = ProviderEvaluations.of(d, "DEFAULT")
  }

  // Released on scope close, so no test leaves a thread parked on the gate.
  private val gated: URIO[Scope, GatedProvider] =
    ZIO.acquireRelease(ZIO.succeed(new GatedProvider))(p => ZIO.succeed(p.gate.countDown()))

  private def build(p: FeatureProvider, timeout: Option[Duration], cap: Int): ZIO[Scope, Throwable, FeatureFlagsLive] =
    FeatureFlags.buildWithTracking(
      p,
      domain = Some(s"cap-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      evaluationTimeout = timeout,
      maxAbandonedCalls = cap
    )

  private def timedOut(r: Either[FeatureFlagError, Boolean]): Boolean = r match {
    case Left(FeatureFlagError.ProviderError(_: TimeoutException)) => true
    case _                                                         => false
  }

  private def rejection(r: Either[FeatureFlagError, Boolean]): Option[String] = r match {
    case Left(FeatureFlagError.ProviderError(e: RejectedExecutionException)) => Some(e.getMessage)
    case _                                                                   => None
  }

  /** Polls `cond` on the live clock; false if it does not hold within 5 seconds. */
  private def eventually(cond: => Boolean): UIO[Boolean] =
    ZIO
      .succeed(cond)
      .flatMap(ok => if (ok) ZIO.succeed(true) else ZIO.sleep(5.millis).as(false))
      .repeatUntil(identity)
      .timeout(5.seconds)
      .map(_.isDefined)

  private def baseline: UIO[Int] = ZTestLogger.logOutput.map(_.length)
  // The cap's own line only, not the total tier's fallback line, which quotes the same message.
  private def capWarnings(since: Int): UIO[Chunk[String]] =
    ZTestLogger.logOutput.map(
      _.drop(since)
        .filter(e => e.logLevel == LogLevel.Warning && e.message().startsWith("Evaluation of"))
        .map(_.message())
    )

  private val untimed = EvaluationOptions.empty.withoutTimeout

  def spec = suite("AbandonedCallCapSpec")(
    test("at the cap, the next timed evaluation fails fast without reaching the provider") {
      ZIO.scoped {
        for {
          p      <- gated
          ff     <- build(p, Some(50.millis), cap = 3)
          first  <- ZIO.foreach(List(1, 2, 3))(_ => ff.boolean("block", default = false).either)
          start  <- Clock.nanoTime
          fourth <- ff.boolean("block", default = false).either
          end    <- Clock.nanoTime
          _      <- eventually(p.entered.get() == 3)
          elapsedMs = (end - start) / 1000000L
          entered   = p.entered.get()
          count     = ff.abandonedCallCount
          message   = rejection(fourth)
        } yield assertTrue(
          first.forall(timedOut),
          message.exists(m => m.contains("3 abandoned provider calls") && m.contains("cap 3")),
          elapsedMs < 20L,
          entered == 3,
          count == 3
        )
      }
    },
    test("once the abandoned calls return, the counter drains and evaluations reach the provider again") {
      ZIO.scoped {
        for {
          p        <- gated
          ff       <- build(p, Some(50.millis), cap = 3)
          _        <- ZIO.foreachDiscard(List(1, 2, 3))(_ => ff.boolean("block", default = false).either)
          rejected <- ff.boolean("block", default = false).either
          _        <- ZIO.succeed(p.gate.countDown())
          drained  <- eventually(ff.abandonedCallCount == 0)
          value    <- ff.boolean("block", default = false)
          entered = p.entered.get()
        } yield assertTrue(rejection(rejected).isDefined, drained, value, entered == 4)
      }
    },
    test("healthy traffic is never throttled: 1000 concurrent fast evaluations with cap 3 all succeed") {
      ZIO.scoped {
        for {
          p      <- gated
          ff     <- build(p, Some(5.seconds), cap = 3)
          values <- ZIO.foreachPar(1 to 1000)(_ => ff.boolean("fast", default = false))
          entered = p.entered.get()
          count   = ff.abandonedCallCount
        } yield assertTrue(values.forall(identity), entered == 1000, count == 0)
      }
    },
    test("a call completing at the timeout boundary never leaves the counter off zero") {
      check(Gen.listOfN(20)(Gen.long(10L, 30L))) { delays =>
        ZIO.scoped {
          for {
            p       <- gated
            ff      <- build(p, Some(20.millis), cap = Int.MaxValue)
            _       <- ZIO.foreachParDiscard(delays)(d => ff.boolean(s"sleep-$d", default = false).either)
            drained <- eventually(p.returned.get() == 20 && ff.abandonedCallCount == 0)
            _       <- ZIO.sleep(20.millis)
            after = ff.abandonedCallCount
          } yield assertTrue(drained, after == 0)
        }
      }
    } @@ samples(25),
    test("a caller interrupted mid-call abandons it too, and it counts") {
      ZIO.scoped {
        for {
          p       <- gated
          ff      <- build(p, Some(5.seconds), cap = 3)
          fiber   <- ff.boolean("block", default = false).fork
          started <- eventually(p.entered.get() == 1)
          _       <- fiber.interrupt
          count = ff.abandonedCallCount
        } yield assertTrue(started, count == 1)
      }
    },
    test("an untimed evaluation (withoutTimeout) is not gated, even at the cap") {
      ZIO.scoped {
        for {
          p     <- gated
          ff    <- build(p, Some(50.millis), cap = 1)
          first <- ff.boolean("block", default = false).either
          timed <- ff.boolean("fast", default = false).either
          free  <- ff.booleanDetails("fast", default = false, ctx = EvaluationContext.empty, options = untimed)
        } yield assertTrue(timedOut(first), rejection(timed).isDefined, free.value)
      }
    },
    test("an instance with EvaluationTimeout.Disabled never counts; only per-call timed evaluations are gated") {
      ZIO.scoped {
        for {
          p      <- gated
          ff     <- build(p, None, cap = 1)
          values <- ZIO.foreachPar(1 to 50)(_ => ff.boolean("fast", default = false))
          before   = ff.abandonedCallCount
          timedOpt = EvaluationOptions.empty.withTimeout(50.millis)
          abandon <- ff
            .booleanDetails("block", default = false, ctx = EvaluationContext.empty, options = timedOpt)
            .either
          rejected <- ff
            .booleanDetails("fast", default = false, ctx = EvaluationContext.empty, options = timedOpt)
            .either
          free <- ff.boolean("fast", default = false)
        } yield assertTrue(
          values.forall(identity),
          before == 0,
          abandon.isLeft,
          rejected.left.exists {
            case FeatureFlagError.ProviderError(_: RejectedExecutionException) => true
            case _                                                             => false
          },
          free
        )
      }
    },
    test("rejections log one warning per window, and the total tier serves the default") {
      ZIO.scoped {
        for {
          p      <- gated
          ff     <- build(p, Some(50.millis), cap = 1)
          base   <- baseline
          _      <- ff.boolean("block", default = false).either
          errors <- ZIO.foreach(List(1, 2, 3))(_ => ff.boolean("fast", default = false).either)
          total  <- ff.booleanOrDefault("fast", default = false)
          logs   <- capWarnings(base)
        } yield assertTrue(errors.forall(e => rejection(e).isDefined), !total, logs.length == 1)
      }
    },
    test("FeatureFlagsConfig.withMaxAbandonedCalls reaches the instance, sync and async") {
      def viaConfig(config: FeatureFlagsConfig) =
        ZIO.scoped {
          for {
            p  <- gated
            ff <- FeatureFlags
              .fromProvider(
                p,
                config
                  .withDomain(s"cap-${java.util.UUID.randomUUID()}")
                  .withEvaluationTimeout(50.millis)
                  .withMaxAbandonedCalls(1),
                statusRef = None,
                apiOverride = Some(OpenFeatureAPI.createIsolated())
              )
              .build
              .map(_.get[FeatureFlags])
            _      <- ff.awaitReady(5.seconds)
            first  <- ff.boolean("block", default = false).either
            second <- ff.boolean("fast", default = false).either
          } yield (timedOut(first), rejection(second).isDefined)
        }
      for {
        sync  <- viaConfig(FeatureFlagsConfig())
        async <- viaConfig(FeatureFlagsConfig().withAsyncInit)
      } yield assertTrue(sync == ((true, true)), async == ((true, true)))
    },
    test("fromAcquireAsync's maxAbandonedCalls reaches the instance") {
      ZIO.scoped {
        for {
          p  <- gated
          ff <- FeatureFlags
            .fromAcquireAsync(
              acquire = ZIO.never,
              fallback = ZIO.succeed(p),
              evaluationTimeout = 50.millis,
              maxAbandonedCalls = 1
            )
            .build
            .map(_.get[FeatureFlags])
          first  <- ff.boolean("block", default = false).either
          second <- ff.boolean("fast", default = false).either
        } yield assertTrue(timedOut(first), rejection(second).isDefined)
      }
    },
    test("a cap below 1 is rejected at construction; the default is 256") {
      val fallback: URIO[Scope, FeatureProvider] = ZIO.succeed(new GatedProvider)
      val config                                 = scala.util.Try(FeatureFlagsConfig(maxAbandonedCalls = 0))
      val setter                                 = scala.util.Try(FeatureFlagsConfig().withMaxAbandonedCalls(-1))
      val acquire = scala.util.Try(FeatureFlags.fromAcquireAsync(ZIO.never, fallback, maxAbandonedCalls = 0))
      val default = FeatureFlagsConfig().maxAbandonedCalls
      assertTrue(
        config.failed.toOption.exists(_.isInstanceOf[IllegalArgumentException]),
        setter.failed.toOption.exists(_.isInstanceOf[IllegalArgumentException]),
        acquire.failed.toOption.exists(_.isInstanceOf[IllegalArgumentException]),
        default == 256
      )
    }
  ) @@ withLiveClock @@ sequential
}
