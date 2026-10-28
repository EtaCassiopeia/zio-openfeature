package zio.openfeature

import java.util.concurrent.{CountDownLatch, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.AtomicInteger

import zio._
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  FeatureProvider,
  Metadata,
  ProviderEventDetails,
  ProviderState,
  Value
}

import scala.jdk.CollectionConverters._

// `fromAcquireAsync` swaps the real provider in without an SDK `setProvider`, so status never leaves Ready and no
// evaluation fails with ProviderNotReady while the real provider initializes.
object AcquireNoNotReadyWindowSpec extends ZIOSpecDefault {

  /** Answers `flag` for every boolean and its own name for every string, so a string evaluation says who served it.
    * `initialize()` records that it started, then throws `initError` or blocks on `initGate`; `shutdown()` is counted
    * and releases the gate, so a discarded provider never strands an init thread.
    */
  private class TestProvider(
    name: String,
    flag: Boolean,
    initGate: Option[CountDownLatch] = None,
    initError: Option[Throwable] = None
  ) extends EventProvider {
    val shutdowns   = new AtomicInteger(0)
    val initStarted = new CountDownLatch(1)

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata { def getName: String = name }
    @scala.annotation.nowarn("msg=deprecated")
    override def getState: ProviderState = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = {
      initStarted.countDown()
      initError.foreach(e => throw e)
      initGate.foreach(_.await())
    }
    override def shutdown(): Unit = {
      shutdowns.incrementAndGet()
      initGate.foreach(_.countDown())
      super.shutdown()
    }
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.valueOf(flag), "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of(name, "STATIC")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
  }

  /** Hands out `first` on the first run and a new fallback on every later one, keeping each for shutdown counts. */
  private class Fallbacks {
    val first   = new TestProvider("fb", false)
    val created = new java.util.concurrent.ConcurrentLinkedQueue[TestProvider]()
    val effect: URIO[Scope, FeatureProvider] = ZIO.succeed {
      val fb = if (created.isEmpty) first else new TestProvider("fb", false)
      created.add(fb)
      fb
    }
    def fresh: Option[TestProvider] = created.asScala.drop(1).headOption
  }

  private val bound = 10.seconds

  private def awaitLatch(latch: CountDownLatch, what: String): Task[Unit] =
    ZIO.attemptBlocking(latch.await(bound.toMillis, TimeUnit.MILLISECONDS)).flatMap { ok =>
      ZIO.unless(ok)(ZIO.fail(new RuntimeException(s"timed out waiting for $what"))).unit
    }

  private def awaitOutcome(env: ZEnvironment[FeatureFlags with AcquireStatus]): Task[AcquireState] =
    env
      .get[AcquireStatus]
      .changes
      .filter(_ != AcquireState.Constructing)
      .runHead
      .someOrFail(new RuntimeException("acquire state stream ended"))
      .timeoutFail(new RuntimeException("no acquire outcome"))(bound)

  private def awaitStatus(ff: FeatureFlags, status: ProviderStatus): Task[ProviderStatus] =
    ff.asInstanceOf[FeatureFlagsLive]
      .statusChanges
      .filter(_ == status)
      .runHead
      .someOrFail(new RuntimeException("status stream ended"))
      .timeoutFail(new RuntimeException(s"status never became $status"))(bound)

  private def poll(read: => Int, expected: Int): UIO[Int] =
    ZIO.succeed(read).repeatUntil(_ == expected).timeout(bound).map(_.getOrElse(read))

  def spec = suite("AcquireNoNotReadyWindowSpec")(
    test("evaluations during a blocked initialize all answer the fallback and status never leaves Ready") {
      val gate      = new CountDownLatch(1)
      val real      = new TestProvider("real", true, initGate = Some(gate))
      val fallbacks = new Fallbacks
      ZIO.scoped {
        for {
          env <- FeatureFlags.fromAcquireAsync(ZIO.succeed(real), fallbacks.effect).build
          ff = env.get[FeatureFlags]
          seen     <- Ref.make(Vector.empty[ProviderStatus])
          _        <- ff.asInstanceOf[FeatureFlagsLive].statusChanges.runForeach(s => seen.update(_ :+ s)).forkScoped
          _        <- awaitLatch(real.initStarted, "the real provider's initialize()")
          during   <- ZIO.foreachPar(1 to 200)(_ => ff.boolean("x", true).either.zip(ff.providerStatus))
          state    <- env.get[AcquireStatus].get
          _        <- ZIO.succeed(gate.countDown())
          outcome  <- awaitOutcome(env)
          after    <- ff.boolean("x", false)
          statuses <- seen.get
        } yield assertTrue(
          during.forall(_._1 == Right(false)),
          during.forall(_._2 == ProviderStatus.Ready),
          state == AcquireState.Constructing,
          outcome == AcquireState.Live,
          after,
          statuses.nonEmpty,
          !statuses.contains(ProviderStatus.NotReady)
        )
      }
    },
    test("an initialize() that exceeds initTimeout fails the swap and leaves the fallback serving") {
      val real      = new TestProvider("real", true, initGate = Some(new CountDownLatch(1)))
      val fallbacks = new Fallbacks
      ZIO.scoped {
        for {
          released <- Ref.make(0)
          errP     <- Promise.make[Nothing, Throwable]
          acquire = ZIO.acquireRelease(ZIO.succeed(real: FeatureProvider))(_ => released.update(_ + 1))
          env <- FeatureFlags
            .fromAcquireAsync(
              acquire,
              fallbacks.effect,
              onConstructionError = e => errP.succeed(e).unit,
              initTimeout = 300.millis
            )
            .build
          ff = env.get[FeatureFlags]
          err      <- errP.await.timeoutFail(new RuntimeException("onConstructionError did not run"))(bound)
          state    <- env.get[AcquireStatus].get
          status   <- ff.providerStatus
          served   <- ff.string("x", "default")
          rel      <- released.get
          realDown <- poll(real.shutdowns.get(), 1)
        } yield assertTrue(
          err match {
            case ProviderSwapFailed(FeatureFlagError.ProviderInitializationFailed(_: TimeoutException)) => true
            case _                                                                                      => false
          },
          state == AcquireState.Failed(err),
          status == ProviderStatus.Ready,
          served == "fb",
          rel == 1,
          realDown == 1
        )
      }
    },
    test("an initialize() that throws fails the swap with that error and leaves the fallback serving") {
      val boom      = new RuntimeException("init boom")
      val real      = new TestProvider("real", true, initError = Some(boom))
      val fallbacks = new Fallbacks
      ZIO.scoped {
        for {
          released <- Ref.make(0)
          errP     <- Promise.make[Nothing, Throwable]
          acquire = ZIO.acquireRelease(ZIO.succeed(real: FeatureProvider))(_ => released.update(_ + 1))
          env <- FeatureFlags
            .fromAcquireAsync(
              acquire,
              fallbacks.effect,
              compose = (r, _) => r,
              onConstructionError = e => errP.succeed(e).unit
            )
            .build
          ff = env.get[FeatureFlags]
          err    <- errP.await.timeoutFail(new RuntimeException("onConstructionError did not run"))(bound)
          state  <- env.get[AcquireStatus].get
          status <- ff.providerStatus
          served <- ff.string("x", "default")
          rel    <- released.get
          fbDown <- ZIO.succeed(fallbacks.first.shutdowns.get())
        } yield assertTrue(
          err match {
            case ProviderSwapFailed(FeatureFlagError.ProviderInitializationFailed(e)) => e eq boom
            case _                                                                    => false
          },
          state == AcquireState.Failed(err),
          status == ProviderStatus.Ready,
          served == "fb",
          rel == 1,
          fbDown == 0
        )
      }
    },
    test("events the real provider emits after the switch drive status and reach handlers") {
      val real      = new TestProvider("real", true)
      val fallbacks = new Fallbacks
      ZIO.scoped {
        for {
          env <- FeatureFlags.fromAcquireAsync(ZIO.succeed(real), fallbacks.effect, compose = (r, _) => r).build
          ff = env.get[FeatureFlags]
          _       <- awaitOutcome(env)
          changed <- Promise.make[Nothing, Set[String]]
          _ <- ff.on(
            ProviderEventType.ConfigurationChanged,
            {
              case ProviderEvent.ConfigurationChanged(flags, _, _) => changed.succeed(flags).unit
              case _                                               => ZIO.unit
            }
          )
          _       <- ZIO.succeed(real.emitProviderError(ProviderEventDetails.builder().message("degraded").build()))
          errored <- awaitStatus(ff, ProviderStatus.Error)
          _       <- ZIO.succeed(real.emitProviderReady(ProviderEventDetails.builder().build()))
          ready   <- awaitStatus(ff, ProviderStatus.Ready)
          _ <- ZIO.succeed(
            real.emitProviderConfigurationChanged(
              ProviderEventDetails.builder().flagsChanged(List("f").asJava).build()
            )
          )
          flags <- changed.await.timeoutFail(new RuntimeException("no ConfigurationChanged"))(bound)
        } yield assertTrue(
          errored == ProviderStatus.Error,
          ready == ProviderStatus.Ready,
          flags == Set("f")
        )
      }
    },
    test("each provider is shut down exactly once: the first fallback at the switch, the rest at layer close") {
      val real      = new TestProvider("real", true)
      val fallbacks = new Fallbacks
      for {
        atSwitch <- ZIO.scoped {
          for {
            env <- FeatureFlags.fromAcquireAsync(ZIO.succeed(real), fallbacks.effect).build
            _   <- awaitOutcome(env)
          } yield (fallbacks.first.shutdowns.get(), real.shutdowns.get(), fallbacks.fresh.map(_.shutdowns.get()))
        }
        firstAfter <- poll(fallbacks.first.shutdowns.get(), 1)
        realAfter  <- poll(real.shutdowns.get(), 1)
        freshAfter <- poll(fallbacks.fresh.fold(-1)(_.shutdowns.get()), 1)
      } yield assertTrue(
        atSwitch == ((1, 0, Some(0))),
        firstAfter == 1,
        realAfter == 1,
        freshAfter == 1,
        fallbacks.created.size == 2
      )
    },
    test("a user setProvider before the switch wins: the acquired provider is discarded and the state is Failed") {
      val gate      = new CountDownLatch(1)
      val real      = new TestProvider("real", true, initGate = Some(gate))
      val other     = new TestProvider("other", true)
      val fallbacks = new Fallbacks
      ZIO.scoped {
        for {
          released <- Ref.make(0)
          errP     <- Promise.make[Nothing, Throwable]
          acquire = ZIO.acquireRelease(ZIO.succeed(real: FeatureProvider))(_ => released.update(_ + 1))
          env <- FeatureFlags
            .fromAcquireAsync(acquire, fallbacks.effect, onConstructionError = e => errP.succeed(e).unit)
            .build
          ff = env.get[FeatureFlags]
          _        <- awaitLatch(real.initStarted, "the real provider's initialize()")
          _        <- ff.setProvider(other)
          _        <- ZIO.succeed(gate.countDown())
          err      <- errP.await.timeoutFail(new RuntimeException("onConstructionError did not run"))(bound)
          state    <- env.get[AcquireStatus].get
          served   <- ff.string("x", "default")
          meta     <- ff.providerMetadata
          rel      <- released.get
          realDown <- poll(real.shutdowns.get(), 1)
        } yield assertTrue(
          err.isInstanceOf[IllegalStateException],
          err.getMessage == "provider replaced by setProvider before the acquired provider was swapped in",
          state == AcquireState.Failed(err),
          served == "other",
          meta.name == "other",
          rel == 1,
          realDown == 1
        )
      }
    },
    test("providerMetadata reports the fallback before the switch and the composed stack after it") {
      val gate      = new CountDownLatch(1)
      val real      = new TestProvider("real", true, initGate = Some(gate))
      val fallbacks = new Fallbacks
      ZIO.scoped {
        for {
          env <- FeatureFlags
            .fromAcquireAsync(
              ZIO.succeed(real),
              fallbacks.effect,
              // Initialize the real provider through the composed stack, as MultiProvider does.
              compose = (r, _) =>
                new TestProvider("composed", true) {
                  override def initialize(ctx: OFEvaluationContext): Unit = r.initialize(ctx)
                }
            )
            .build
          ff = env.get[FeatureFlags]
          _      <- awaitLatch(real.initStarted, "the real provider's initialize()")
          before <- ff.providerMetadata
          _      <- ZIO.succeed(gate.countDown())
          _      <- awaitOutcome(env)
          after  <- ff.providerMetadata
          served <- ff.string("x", "default")
        } yield assertTrue(before.name == "fb", after.name == "composed", served == "composed")
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
}
