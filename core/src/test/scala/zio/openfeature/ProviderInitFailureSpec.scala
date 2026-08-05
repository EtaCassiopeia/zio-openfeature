package zio.openfeature
import zio.openfeature.internal.ProviderEvaluations

import zio._
import zio.stream.SubscriptionRef
import zio.test._
import zio.test.TestAspect.{sequential, withLiveClock}
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
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
  *      `PROVIDER_ERROR` after construction → status reflects `Error`, but evaluations still proceed (library policy:
  *      only NOT_READY and FATAL fail-fast, permitted — no longer required — under spec v0.9.0). 5b. Fail-fast
  *      contract: NOT_READY and FATAL block evaluation; Ready/Error proceed. 6. Async provider recovers (ERROR → READY)
  *      → evaluations succeed after recovery. 7. Evaluation throws `UnknownHostException` from the Java SDK →
  *      classifier surfaces `Unreachable`.
  *
  * Cases 2, 3, 4 are already covered by [[ProviderInitHardeningSpec]] and are not duplicated here.
  */
object ProviderInitFailureSpec extends ZIOSpecDefault {

  // Minimal stub for evaluation methods we don't exercise; keeps each test provider class small.
  private trait EvaluationStubs extends EventProvider {
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

  /** Case 8a: provider whose `getMetadata` throws synchronously — exercises the `providerName <- ZIO.attempt(...)`
    * guard in `buildAsync` directly (the registry test relies on `runBuild`'s backstop, so it can't isolate this).
    */
  final private class ThrowingMetadataProvider extends EvaluationStubs {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = throw new RuntimeException("metadata boom")
    @scala.annotation.nowarn("msg=deprecated")
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
  }

  private def uniqueDomain(label: String): String = s"init-failure-$label-${java.util.UUID.randomUUID()}"

  def spec = suite("ProviderInitFailureSpec")(
    test("[B2 / case 1] sync initialize() that throws -> layer build fails with the thrown exception") {
      val boom     = new IllegalArgumentException("synthetic init failure")
      val provider = new ThrowingInitProvider(boom)
      val api      = OpenFeatureAPI.createIsolated()
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
    test("[B2 / case 8a] buildAsync: a throwing getMetadata surfaces a typed failure, not a defect (#242)") {
      val provider = new ThrowingMetadataProvider
      val api      = OpenFeatureAPI.createIsolated()
      val build = ZIO.scoped {
        FeatureFlags
          .buildAsync(
            provider,
            domain = Some(uniqueDomain("throwing-metadata")),
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
        exit <- build.exit
      } yield assertTrue(
        exit.isFailure,
        // Typed Throwable in the failure channel, NOT a defect — before the fix this was a `Die`.
        exit match {
          case Exit.Failure(cause) => cause.failureOption.isDefined && cause.dieOption.isEmpty
          case _                   => false
        }
      )
    } @@ withLiveClock,
    test("[B2 / case 8b] buildAsync: a null provider surfaces a typed failure from setProvider, not a defect (#242)") {
      val nullProvider: dev.openfeature.sdk.FeatureProvider = null
      val api                                               = OpenFeatureAPI.createIsolated()
      val build = ZIO.scoped {
        FeatureFlags
          .buildAsync(
            nullProvider,
            domain = Some(uniqueDomain("null-provider")),
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
        exit <- build.exit
      } yield assertTrue(
        exit.isFailure,
        exit match {
          case Exit.Failure(cause) => cause.failureOption.isDefined && cause.dieOption.isEmpty
          case _                   => false
        }
      )
    } @@ withLiveClock,
    test(
      "[B2 / case 5] async provider fires PROVIDER_ERROR after init -> evaluations still proceed (library policy)"
    ) {
      val provider = new EventDriverProvider
      val api      = OpenFeatureAPI.createIsolated()
      ZIO.scoped {
        for {
          statusRef <- SubscriptionRef.make[ProviderStatus](ProviderStatus.NotReady)
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
          // release() unblocks initialize(); the Java SDK fires exactly one PROVIDER_READY when it returns.
          // We must NOT also call provider.fireReady() here because that adds a second PROVIDER_READY
          // which can arrive in the event queue *after* PROVIDER_ERROR, flipping statusRef back to Ready.
          _ <- ZIO.attempt(provider.release())
          _ <- statusRef.get
            .repeatUntil(_ == ProviderStatus.Ready)
            .timeout(5.seconds)
            .someOrFail(new Exception("timed out waiting for PROVIDER_READY to propagate"))
          _ <- ZIO.attempt(provider.fireError("synthetic provider error"))
          _ <- statusRef.get
            .repeatUntil(_ == ProviderStatus.Error)
            .timeout(5.seconds)
            .someOrFail(new Exception("timed out waiting for PROVIDER_ERROR to propagate"))
          // Library policy: only NOT_READY and FATAL fail-fast (permitted, not required, under spec v0.9.0). In ERROR
          // the evaluation proceeds to the provider (which serves cached values or errors on its own) instead of a
          // blanket ProviderNotReady failure.
          result <- ff.booleanDetails("any-flag", default = false).either
        } yield assertTrue(result.isRight)
      }
    } @@ withLiveClock,
    test("[B2 / case 5b] checkProviderStatus fail-fast contract: only NOT_READY and FATAL block evaluation") {
      val provider = new EventDriverProvider
      val api      = OpenFeatureAPI.createIsolated()
      ZIO.scoped {
        for {
          statusRef <- SubscriptionRef.make[ProviderStatus](ProviderStatus.NotReady)
          ff <- FeatureFlags.buildAsync(
            provider,
            domain = Some(uniqueDomain("status-gate")),
            version = None,
            initialHooks = Nil,
            statusRef = Some(statusRef),
            addShutdownFinalizer = false,
            apiOverride = Some(api),
            initTimeout = 60.seconds
          )
          _ <- ZIO.attempt(provider.release())
          _ <- statusRef.get
            .repeatUntil(_ == ProviderStatus.Ready)
            .timeout(5.seconds)
            .someOrFail(new Exception("timed out waiting for PROVIDER_READY to propagate"))
          // Ready -> proceeds
          readyEval <- ff.booleanDetails("any-flag", default = false).either
          // Error -> proceeds (no longer fail-fast)
          _         <- statusRef.set(ProviderStatus.Error)
          errorEval <- ff.booleanDetails("any-flag", default = false).either
          // NotReady -> fail-fast
          _            <- statusRef.set(ProviderStatus.NotReady)
          notReadyEval <- ff.booleanDetails("any-flag", default = false).either
          // Fatal -> fail-fast
          _         <- statusRef.set(ProviderStatus.Fatal)
          fatalEval <- ff.booleanDetails("any-flag", default = false).either
        } yield assertTrue(
          readyEval.isRight,
          errorEval.isRight,
          notReadyEval == Left(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady)),
          fatalEval == Left(FeatureFlagError.ProviderFatal)
        )
      }
    } @@ withLiveClock,
    test("[B2 / case 6] async provider recovers ERROR -> READY and evaluations succeed") {
      val provider = new EventDriverProvider
      val api      = OpenFeatureAPI.createIsolated()
      ZIO.scoped {
        for {
          statusRef <- SubscriptionRef.make[ProviderStatus](ProviderStatus.NotReady)
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
          _ <- ZIO.attempt(provider.release())
          _ <- statusRef.get
            .repeatUntil(_ == ProviderStatus.Ready)
            .timeout(5.seconds)
            .someOrFail(new Exception("timed out waiting for PROVIDER_READY to propagate"))
          _ <- ZIO.attempt(provider.fireError("transient blip"))
          _ <- statusRef.get
            .repeatUntil(_ == ProviderStatus.Error)
            .timeout(5.seconds)
            .someOrFail(new Exception("timed out waiting for PROVIDER_ERROR to propagate"))
          _ <- ZIO.attempt(provider.fireReady())
          _ <- statusRef.get
            .repeatUntil(_ == ProviderStatus.Ready)
            .timeout(5.seconds)
            .someOrFail(new Exception("timed out waiting for recovery PROVIDER_READY to propagate"))
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
      val api      = OpenFeatureAPI.createIsolated()
      ZIO.scoped {
        for {
          statusRef <- SubscriptionRef.make[ProviderStatus](ProviderStatus.Ready)
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
