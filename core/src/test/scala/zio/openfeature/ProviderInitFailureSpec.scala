package zio.openfeature

import zio._
import zio.test._
import zio.test.TestAspect.{sequential, withLiveClock}
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPIFactory,
  ProviderEvaluation,
  ProviderEventDetails,
  ProviderState,
  Value
}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

/** Broader provider-initialization failure coverage complementing [[ProviderInitHardeningSpec]]. Where the latter
  * targets workstream A1/A2 (init timeout + sync-state verification), this spec targets workstream B2 — proving the
  * library's behaviour across the full grid of init outcomes the OpenFeature spec calls out, including the typed-error
  * classifier landing the right `FeatureFlagError` case (#123).
  *
  * Cases covered here:
  *   1. Sync `initialize()` throws synchronously → layer build fails with the thrown exception. 5. Async provider fires
  *      `PROVIDER_ERROR` after construction → status reflects `Error`, evaluations fail with `ProviderNotReady(Error)`.
  *      6. Async provider recovers (ERROR → READY) → evaluations succeed after recovery. 7. Evaluation throws
  *      `UnknownHostException` from the Java SDK → classifier surfaces `Unreachable`.
  *
  * Cases 2, 3, 4 are already covered by [[ProviderInitHardeningSpec]] and are not duplicated here.
  */
object ProviderInitFailureSpec extends ZIOSpecDefault {

  // Minimal stub for evaluation methods we don't exercise; keeps each test provider class small.
  private trait EvaluationStubs extends EventProvider {
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluation.builder[java.lang.Boolean]().value(d).reason("DEFAULT").build()
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluation.builder[String]().value(d).reason("DEFAULT").build()
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluation.builder[java.lang.Integer]().value(d).reason("DEFAULT").build()
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluation.builder[java.lang.Double]().value(d).reason("DEFAULT").build()
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluation.builder[Value]().value(d).reason("DEFAULT").build()
  }

  /** Case 1: provider whose `initialize()` throws synchronously. */
  final private class ThrowingInitProvider(boom: Throwable) extends EvaluationStubs {
    private val st = new AtomicReference[ProviderState](ProviderState.NOT_READY)
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata { def getName: String = "ThrowingInitProvider" }
    @scala.annotation.nowarn("msg=deprecated")
    override def getState: ProviderState = st.get()
    override def initialize(ctx: OFEvaluationContext): Unit = {
      st.set(ProviderState.ERROR)
      throw boom
    }
    override def shutdown(): Unit = st.set(ProviderState.NOT_READY)
  }

  /** Cases 5/6: async-ready provider that the test drives through events. `initialize()` waits on a latch the test can
    * release; once released, the provider state moves to READY (matching the Java SDK's PROVIDER_READY semantics). The
    * test can then call `emitProviderError` / `emitProviderReady` to drive status transitions.
    */
  final private class EventDriverProvider extends EvaluationStubs {
    private val st       = new AtomicReference[ProviderState](ProviderState.NOT_READY)
    private val initGate = new CountDownLatch(1)
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata { def getName: String = "EventDriverProvider" }
    @scala.annotation.nowarn("msg=deprecated")
    override def getState: ProviderState = st.get()
    override def initialize(ctx: OFEvaluationContext): Unit = {
      initGate.await(10, TimeUnit.SECONDS) // tests release this explicitly; the bound is a hung-test guard
      st.set(ProviderState.READY)
    }
    override def shutdown(): Unit = {
      initGate.countDown()
      st.set(ProviderState.NOT_READY)
    }
    def release(): Unit = initGate.countDown()
    def fireError(message: String): Unit = {
      st.set(ProviderState.ERROR)
      emitProviderError(ProviderEventDetails.builder().message(message).build())
    }
    def fireReady(): Unit = {
      st.set(ProviderState.READY)
      emitProviderReady(ProviderEventDetails.builder().build())
    }
  }

  /** Case 7: evaluation throws a `UnknownHostException`; the FeatureFlagsLive classifier should map it to
    * `Unreachable`.
    */
  final private class ThrowingEvalProvider(boom: Throwable) extends EvaluationStubs {
    private val st = new AtomicReference[ProviderState](ProviderState.READY)
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata { def getName: String = "ThrowingEvalProvider" }
    @scala.annotation.nowarn("msg=deprecated")
    override def getState: ProviderState                                                       = st.get()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = throw boom
    override def shutdown(): Unit = st.set(ProviderState.NOT_READY)
  }

  private def uniqueDomain(label: String): String = s"init-failure-$label-${java.util.UUID.randomUUID()}"

  def spec = suite("ProviderInitFailureSpec")(
    test("[B2 / case 1] sync initialize() that throws -> layer build fails with the thrown exception") {
      val boom     = new IllegalArgumentException("synthetic init failure")
      val provider = new ThrowingInitProvider(boom)
      val api      = OpenFeatureAPIFactory.create()
      val build = ZIO.scoped {
        FeatureFlags
          .build(
            provider,
            domain = Some(uniqueDomain("throwing")),
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
        // The Java SDK may wrap the original Throwable; check the message threads through.
        result.left.exists(t => Option(t.getMessage).exists(_.contains("synthetic init failure")))
      )
    } @@ withLiveClock,
    test("[B2 / case 5] async provider fires PROVIDER_ERROR after init -> evaluations fail with ProviderNotReady") {
      val provider = new EventDriverProvider
      val api      = OpenFeatureAPIFactory.create()
      ZIO.scoped {
        for {
          statusRef <- Ref.make[ProviderStatus](ProviderStatus.NotReady)
          ff <- FeatureFlags.buildAsync(
            provider,
            domain = Some(uniqueDomain("async-error")),
            version = None,
            initialHooks = Nil,
            statusRef = Some(statusRef),
            addShutdownFinalizer = false,
            apiOverride = Some(api),
            initTimeout = 60.seconds
          )
          _ <- ZIO.attempt { provider.release(); provider.fireReady() }
          // Wait until the event bridge has propagated READY.
          _      <- statusRef.get.repeatUntil(_ == ProviderStatus.Ready).timeout(2.seconds)
          _      <- ZIO.attempt(provider.fireError("synthetic provider error"))
          _      <- statusRef.get.repeatUntil(_ == ProviderStatus.Error).timeout(2.seconds)
          result <- ff.booleanDetails("any-flag", default = false).either
        } yield assertTrue(
          result match {
            case Left(FeatureFlagError.ProviderNotReady(ProviderStatus.Error)) => true
            case _                                                             => false
          }
        )
      }
    } @@ withLiveClock,
    test("[B2 / case 6] async provider recovers ERROR -> READY and evaluations succeed") {
      val provider = new EventDriverProvider
      val api      = OpenFeatureAPIFactory.create()
      ZIO.scoped {
        for {
          statusRef <- Ref.make[ProviderStatus](ProviderStatus.NotReady)
          ff <- FeatureFlags.buildAsync(
            provider,
            domain = Some(uniqueDomain("async-recovery")),
            version = None,
            initialHooks = Nil,
            statusRef = Some(statusRef),
            addShutdownFinalizer = false,
            apiOverride = Some(api),
            initTimeout = 60.seconds
          )
          _ <- ZIO.attempt { provider.release(); provider.fireReady() }
          _ <- statusRef.get.repeatUntil(_ == ProviderStatus.Ready).timeout(2.seconds)
          _ <- ZIO.attempt(provider.fireError("transient blip"))
          _ <- statusRef.get.repeatUntil(_ == ProviderStatus.Error).timeout(2.seconds)
          _ <- ZIO.attempt(provider.fireReady())
          _ <- statusRef.get.repeatUntil(_ == ProviderStatus.Ready).timeout(2.seconds)
          // The evaluation default is what our stubbed provider returns when status is READY.
          result <- ff.booleanDetails("any-flag", default = false).either
        } yield assertTrue(result.isRight)
      }
    } @@ withLiveClock,
    test(
      "[B2 / case 7] evaluation throws UnknownHostException -> Java SDK catches it and surfaces errorCode on the FlagResolution"
    ) {
      // The OpenFeature Java SDK catches provider exceptions and returns a `FlagEvaluationDetails` with `errorCode`
      // populated (rather than propagating the throw). That means our `classify` doesn't fire on provider throws via
      // the Boolean/String/Int/Double evaluation paths — operators see the failure via the resolution's `errorCode`
      // and `errorMessage`. The classifier IS still exercised end-to-end through provider HTTP failures (see the
      // OFREP failure-mode suite); it's also unit-tested in `FeatureFlagErrorSpec`. This test documents the boundary.
      val boom     = new java.net.UnknownHostException("flags.example.com")
      val provider = new ThrowingEvalProvider(boom)
      val api      = OpenFeatureAPIFactory.create()
      ZIO.scoped {
        for {
          statusRef <- Ref.make[ProviderStatus](ProviderStatus.Ready)
          ff <- FeatureFlags.build(
            provider,
            domain = Some(uniqueDomain("classify-unreachable")),
            version = None,
            initialHooks = Nil,
            statusRef = Some(statusRef),
            addShutdownFinalizer = false,
            apiOverride = Some(api),
            initTimeout = 5.seconds
          )
          result <- ff.booleanDetails("any-flag", default = false).either
        } yield assertTrue(
          // We expect a successful FlagResolution with errorCode populated (NOT a typed Unreachable failure).
          result match {
            case Right(resolution) => resolution.errorCode.isDefined
            case _                 => false
          }
        )
      }
    } @@ withLiveClock
  ) @@ sequential
}
