package zio.openfeature
import zio.openfeature.internal.ProviderEvaluations

import zio._
import zio.test._
import zio.test.TestAspect.{withLiveClock, sequential}
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPIFactory,
  ProviderEvaluation,
  ProviderState,
  Value
}
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** Covers issues #120 (init timeout) and #121 (verify sync init succeeded). The minimal test providers below avoid
  * depending on testkit so the spec can live in `core`.
  */
object ProviderInitHardeningSpec extends ZIOSpecDefault {

  /** A provider whose `initialize()` blocks until its latch is released. Used to simulate a hanging sync init. */
  private class BlockingInitProvider(latch: CountDownLatch) extends EventProvider {
    private val st = new AtomicReference[ProviderState](ProviderState.NOT_READY)

    override def getMetadata: Metadata   = new Metadata { def getName: String = "BlockingInitProvider" }
    override def getState: ProviderState = st.get()

    override def initialize(ctx: OFEvaluationContext): Unit = {
      latch.await()
      st.set(ProviderState.READY)
    }
    override def shutdown(): Unit = {
      latch.countDown() // release any blocked init so the JVM doesn't leak threads
      st.set(ProviderState.NOT_READY)
    }

    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  /** A provider that returns successfully from `initialize()` but reports ERROR. The library should refuse to mark such
    * a provider Ready and instead fail layer construction.
    */
  private class InitToErrorProvider extends EventProvider {
    private val st                       = new AtomicReference[ProviderState](ProviderState.NOT_READY)
    override def getMetadata: Metadata   = new Metadata { def getName: String = "InitToErrorProvider" }
    override def getState: ProviderState = st.get()
    override def initialize(ctx: OFEvaluationContext): Unit = st.set(ProviderState.ERROR)
    override def shutdown(): Unit                           = st.set(ProviderState.NOT_READY)

    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  // Each sync test uses a unique domain so OpenFeatureAPI clients don't share state across cases.
  private def uniqueDomain(label: String): String = s"init-hardening-$label-${java.util.UUID.randomUUID()}"

  def spec = suite("ProviderInitHardeningSpec")(
    test("[A1] sync fromProvider fails with TimeoutException when init blocks past initTimeout") {
      val latch    = new CountDownLatch(1)
      val provider = new BlockingInitProvider(latch)
      val api      = OpenFeatureAPIFactory.create()
      val build = ZIO.scoped {
        FeatureFlags
          .build(
            provider,
            domain = Some(uniqueDomain("sync-timeout")),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = false,
            apiOverride = Some(api),
            initTimeout = 200.millis
          )
          .unit
      }
      for {
        result <- build.either
        _ = latch.countDown() // unblock the background thread so it can exit
      } yield assertTrue(
        result.isLeft,
        result.left.exists(_.isInstanceOf[java.util.concurrent.TimeoutException])
      )
    } @@ withLiveClock,
    test("[A2] sync fromProvider fails if provider reports ERROR after init") {
      val provider = new InitToErrorProvider
      val api      = OpenFeatureAPIFactory.create()
      val build = ZIO.scoped {
        FeatureFlags
          .build(
            provider,
            domain = Some(uniqueDomain("sync-err")),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = false,
            apiOverride = Some(api),
            initTimeout = 5.seconds
          )
          .unit
      }
      for {
        result <- build.either
      } yield assertTrue(
        result.isLeft,
        result.left.exists(t =>
          t.isInstanceOf[IllegalStateException] && Option(t.getMessage).exists(_.contains("ERROR"))
        )
      )
    } @@ withLiveClock,
    test("[#180] provider is shut down when sync init times out") {
      val latch        = new CountDownLatch(1)
      val shutdownSeen = new java.util.concurrent.atomic.AtomicInteger(0)
      val provider = new BlockingInitProvider(latch) {
        override def shutdown(): Unit = {
          shutdownSeen.incrementAndGet()
          super.shutdown()
        }
      }
      val api = OpenFeatureAPIFactory.create()
      val build = ZIO.scoped {
        FeatureFlags
          .build(
            provider,
            domain = Some(uniqueDomain("sync-timeout-cleanup")),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = false,
            apiOverride = Some(api),
            initTimeout = 200.millis
          )
          .unit
      }
      for {
        result <- build.either
      } yield assertTrue(
        result.isLeft,
        // The failure path shuts the provider down even though no API finalizer was registered.
        // (BlockingInitProvider.shutdown also releases the latch, so the background init thread exits.)
        shutdownSeen.get() >= 1
      )
    } @@ withLiveClock,
    test("[#180] provider is shut down when it reports ERROR after init") {
      val shutdownSeen = new java.util.concurrent.atomic.AtomicInteger(0)
      val provider = new InitToErrorProvider {
        override def shutdown(): Unit = {
          shutdownSeen.incrementAndGet()
          super.shutdown()
        }
      }
      val api = OpenFeatureAPIFactory.create()
      val build = ZIO.scoped {
        FeatureFlags
          .build(
            provider,
            domain = Some(uniqueDomain("sync-err-cleanup")),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = false,
            apiOverride = Some(api),
            initTimeout = 5.seconds
          )
          .unit
      }
      for {
        result <- build.either
      } yield assertTrue(result.isLeft, shutdownSeen.get() >= 1)
    } @@ withLiveClock,
    test("[A1 async] watchdog transitions provider to Fatal after initTimeout when init hangs") {
      // initialize() blocks forever — Java SDK never fires PROVIDER_READY, so the only way out
      // of NotReady is the watchdog.
      val latch    = new CountDownLatch(1)
      val provider = new BlockingInitProvider(latch)
      val api      = OpenFeatureAPIFactory.create()
      ZIO.scoped {
        for {
          statusRef <- Ref.make[ProviderStatus](ProviderStatus.NotReady)
          _ <- FeatureFlags.buildAsync(
            provider,
            domain = Some(uniqueDomain("async-watchdog")),
            version = None,
            initialHooks = Nil,
            statusRef = Some(statusRef),
            addShutdownFinalizer = false,
            apiOverride = Some(api),
            initTimeout = 100.millis
          )
          before <- statusRef.get
          // Live clock so the watchdog fiber's ZIO.sleep actually elapses.
          _     <- ZIO.sleep(400.millis)
          after <- statusRef.get
          _ = latch.countDown()
        } yield assertTrue(before == ProviderStatus.NotReady, after == ProviderStatus.Fatal)
      }
    } @@ withLiveClock
  ) @@ sequential
}
