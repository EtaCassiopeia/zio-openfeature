package zio.openfeature

import java.util.concurrent.{CountDownLatch, TimeoutException}
import java.util.concurrent.atomic.AtomicInteger

import zio._
import zio.test._
import zio.openfeature.internal.{Bound, ManagedProvider, ProviderEvaluations, ProviderRegistration}
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Value
}
import dev.openfeature.sdk.exceptions.FatalError

// A provider whose initialize() never returns must not wedge setProvider, the sync build's scope close, or
// fromAcquireAsync. Every timing assertion runs on the live clock: the code under test must bound itself even when
// the test's own TestClock is frozen.
object ProviderInitTimeoutSpec extends ZIOSpecDefault {

  private class BoolProvider(name: String, value: Boolean) extends FeatureProvider {
    val shutdowns = new AtomicInteger(0)
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = name }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = { shutdowns.incrementAndGet(); () }
    override def getBooleanEvaluation(key: String, default: java.lang.Boolean, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(java.lang.Boolean.valueOf(value), "STATIC")
    override def getStringEvaluation(key: String, default: String, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getIntegerEvaluation(key: String, default: java.lang.Integer, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getDoubleEvaluation(key: String, default: java.lang.Double, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
    override def getObjectEvaluation(key: String, default: Value, ctx: OFEvaluationContext) =
      ProviderEvaluations.of(default, "DEFAULT")
  }

  /** initialize() blocks until the TEST releases it and ignores shutdown() — unbounded, uninterruptible-by-API I/O. */
  private class StuckProvider(name: String) extends BoolProvider(name, true) {
    private val gate                                        = new CountDownLatch(1)
    override def initialize(ctx: OFEvaluationContext): Unit = gate.await()
    def release(): Unit                                     = gate.countDown()
  }

  /** initialize() blocks until shutdown() — the common "wait for first config, shutdown aborts the wait" shape. */
  private class ShutdownReleasedProvider(name: String) extends BoolProvider(name, true) {
    private val gate                                        = new CountDownLatch(1)
    override def initialize(ctx: OFEvaluationContext): Unit = gate.await()
    override def shutdown(): Unit                           = { super.shutdown(); gate.countDown() }
  }

  private val initTimeout = 300.millis
  private val testBound   = 20.seconds

  private def bounded[E, A](label: String)(effect: ZIO[Scope, E, A]): ZIO[Any, Any, A] =
    Live.live(ZIO.scoped(effect).timeoutFail(s"$label did not complete within $testBound")(testBound))

  private def buildIsolated(provider: FeatureProvider): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      provider,
      domain = Some(s"init-timeout-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      initTimeout = initTimeout
    )

  private def initFailure(e: FeatureFlagError): Option[Throwable] = e match {
    case FeatureFlagError.ProviderInitializationFailed(t) => Some(t)
    case _                                                => None
  }

  private def elapsedMillis[R, E, A](effect: ZIO[R, E, A]): ZIO[R, E, (A, Long)] =
    for {
      start <- Clock.nanoTime
      a     <- effect
      end   <- Clock.nanoTime
    } yield (a, (end - start) / 1000000L)

  def spec = suite("ProviderInitTimeoutSpec")(
    test("a swap whose initialize() never returns fails at the init timeout with status Error; the API stays usable") {
      val stuck = new StuckProvider("stuck")
      bounded("timed-out swap") {
        for {
          ff     <- buildIsolated(new BoolProvider("old", false))
          timed  <- elapsedMillis(ff.setProvider(stuck).either)
          status <- ff.providerStatus
          meta   <- ff.providerMetadata
          next   <- ff.setProvider(new BoolProvider("next", true)).either
          value  <- ff.boolean("x", false)
        } yield assertTrue(
          timed._1.left.toOption.flatMap(initFailure).exists(_.isInstanceOf[TimeoutException]),
          timed._2 < 3000L,
          status == ProviderStatus.Error,
          meta.name == "stuck",
          next.isRight,
          value
        )
      }.ensuring(ZIO.succeed(stuck.release()))
    },
    test("a timed-out swap never gets the previous provider shut down when the new one finishes later") {
      val old = new BoolProvider("old", false)
      bounded("timed-out swap") {
        for {
          ff     <- buildIsolated(old)
          result <- ff.setProvider(new ShutdownReleasedProvider("slow")).either
          // A rollback would re-register `old`, which makes the SDK shut the slow provider down, which releases its
          // initialize(), whose pending cleanup then shuts down `old`'s original registration — i.e. `old` itself.
          _ <- ZIO.sleep(1.second)
          // Read inside the scope: closing it (after the yield) legitimately shuts `old` down.
          shutdowns <- ZIO.succeed(old.shutdowns.get())
        } yield assertTrue(result.isLeft, shutdowns == 0)
      }
    },
    test("a timed-out swap recovers to Ready once the new provider finishes initializing") {
      val slow = new BoolProvider("slow", true) {
        override def initialize(ctx: OFEvaluationContext): Unit = Thread.sleep(800)
      }
      bounded("late-ready swap") {
        for {
          ff     <- buildIsolated(new BoolProvider("old", false))
          result <- ff.setProvider(slow).either
          status <- ff.providerStatus.repeatUntil(_ == ProviderStatus.Ready).timeout(5.seconds)
          value  <- ff.boolean("x", false)
        } yield assertTrue(
          result.left.toOption.flatMap(initFailure).exists(_.isInstanceOf[TimeoutException]),
          status.contains(ProviderStatus.Ready),
          value
        )
      }
    },
    test("sync build with a stuck initialize() fails at the init timeout and the scope close does not wait for it") {
      val stuck = new StuckProvider("stuck")
      Live
        .live(
          elapsedMillis(
            ZIO
              .scoped(
                FeatureFlags
                  .fromProvider(
                    stuck,
                    FeatureFlagsConfig().withInitTimeout(initTimeout),
                    statusRef = None,
                    apiOverride = Some(OpenFeatureAPI.createIsolated())
                  )
                  .build
              )
              .either
          ).timeoutFail("layer build or its scope close hung")(testBound)
        )
        .map { case (result, elapsedMs) =>
          // The SDK's own shutdown waits a few seconds for its executor before interrupting the stuck init thread.
          assertTrue(result.left.toOption.exists(_.isInstanceOf[TimeoutException]), elapsedMs < 10000L)
        }
        .ensuring(ZIO.succeed(stuck.release()))
    },
    test("the scope-close API shutdown is bounded even though finalizers run uninterruptibly") {
      val api          = OpenFeatureAPI.createIsolated()
      val slowShutdown = new BoolProvider("slow-shutdown", true) {
        override def shutdown(): Unit = Thread.sleep(4000)
      }
      Live.live(
        for {
          _     <- ZIO.attemptBlocking(api.setProviderAndWait(slowShutdown))
          timed <- elapsedMillis(
            ZIO.scoped(ZIO.addFinalizer(ProviderRegistration.boundedApiShutdown(api, 300.millis)))
          )
        } yield assertTrue(timed._2 < 2000L)
      )
    },
    test("the bounded API shutdown still runs when the scope closes because its fiber was interrupted") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new BoolProvider("interrupted-close", true)
      Live.live(
        for {
          _     <- ZIO.attemptBlocking(api.setProviderAndWait(provider))
          ready <- Promise.make[Nothing, Unit]
          fiber <- ZIO
            .scoped(
              ZIO.addFinalizer(ProviderRegistration.boundedApiShutdown(api, 5.seconds)) *> ready.succeed(
                ()
              ) *> ZIO.never
            )
            .fork
          _         <- ready.await
          _         <- fiber.interrupt
          shutdowns <- ZIO.succeed(provider.shutdowns.get())
        } yield assertTrue(shutdowns == 1)
      )
    },
    test("a FATAL init outcome surfaces as FatalError carrying the provider's message") {
      val fatal = new BoolProvider("fatal", true) {
        override def initialize(ctx: OFEvaluationContext): Unit = throw new FatalError("bad sdk key")
      }
      bounded("fatal swap") {
        for {
          ff     <- buildIsolated(new BoolProvider("old", false))
          result <- ff.setProvider(fatal).either
          cause = result.left.toOption.flatMap(initFailure)
        } yield assertTrue(
          cause.exists(_.isInstanceOf[FatalError]),
          cause.exists(_.getMessage.contains("bad sdk key"))
        )
      }
    },
    test("a registration superseded by a concurrent one fails promptly instead of waiting for the deadline") {
      val api    = OpenFeatureAPI.createIsolated()
      val domain = s"superseded-${java.util.UUID.randomUUID()}"
      val stuck  = new StuckProvider("stuck")
      Live
        .live(
          for {
            waiter <- ProviderRegistration
              .registerAndAwait(api, Some(domain), Bound.Managed(ManagedProvider(stuck)), 10.seconds)
              .either
              .fork
            _ <- ZIO
              .attemptBlocking(api.getProvider(domain))
              .repeatUntil(p => ManagedProvider.unwrap(p) eq stuck)
              .timeoutFail("first registration never bound")(5.seconds)
            _     <- ZIO.attemptBlocking(api.setProvider(domain, new BoolProvider("winner", true)))
            timed <- elapsedMillis(waiter.join)
          } yield assertTrue(
            timed._1.left.toOption.exists(_.isInstanceOf[ProviderRegistration.RegistrationSuperseded]),
            timed._2 < 2000L
          )
        )
        .ensuring(ZIO.succeed(stuck.release()) *> ZIO.attemptBlocking(api.shutdown()).ignore)
    },
    test("fromAcquireAsync with a stuck real provider keeps the fallback serving and reports the failure") {
      val firstFallback                          = new BoolProvider("fb", false)
      val fallbacks                              = new java.util.concurrent.ConcurrentLinkedQueue[BoolProvider]()
      val fallback: URIO[Scope, FeatureProvider] = ZIO.succeed {
        val fb = if (fallbacks.isEmpty) firstFallback else new BoolProvider("fb", false)
        fallbacks.add(fb)
        fb
      }
      val acquire: RIO[Scope, FeatureProvider] = ZIO.succeed(new BoolProvider("real", true))
      val stuck                                = new StuckProvider("composed")
      bounded("fromAcquireAsync") {
        for {
          errP <- Promise.make[Nothing, Throwable]
          env  <- FeatureFlags
            .fromAcquireAsync(
              acquire,
              fallback,
              FeatureFlagsConfig(
                initTimeout = initTimeout
              ),
              AcquireConfig.default
                .withCompose((_, _) => stuck)
                .withOnConstructionError(e => errP.succeed(e).unit)
            )
            .build
          ff = env.get[FeatureFlags]
          err    <- errP.await
          state  <- env.get[AcquireStatus].get
          status <- ff.providerStatus
          meta   <- ff.providerMetadata
          value  <- ff.boolean("x", true)
          // The stuck composite never reached the SDK, so the first fallback is still the one serving.
          firstShutdowns <- ZIO.succeed(firstFallback.shutdowns.get())
        } yield assertTrue(
          firstShutdowns == 0,
          err.isInstanceOf[ProviderSwapFailed],
          state.isInstanceOf[AcquireState.Failed],
          status == ProviderStatus.Ready,
          meta.name == "fb",
          !value
        )
      }.ensuring(ZIO.succeed(stuck.release()))
    }
  ) @@ TestAspect.sequential
}
