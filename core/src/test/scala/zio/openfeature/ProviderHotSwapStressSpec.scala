package zio.openfeature

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
import zio.test.TestAspect.{withLiveClock, timeout}

/** Stress-tests hot-swap under concurrent evaluation load.
  *
  * Proves that while N fibers are hammering evaluations, swapping providers (including through a failing intermediate
  * provider) never produces defects, torn state, or deadlocks. All errors observed during the swap window must be typed
  * FeatureFlagErrors — never raw Throwables.
  */
object ProviderHotSwapStressSpec extends ZIOSpecDefault {

  private class ConstantProvider(name: String, value: Boolean) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = name }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()

    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluation.builder[java.lang.Boolean]().value(value).reason("STATIC").build()
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluation.builder[String]().value(d).reason("DEFAULT").build()
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluation.builder[java.lang.Integer]().value(d).reason("DEFAULT").build()
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluation.builder[java.lang.Double]().value(d).reason("DEFAULT").build()
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluation.builder[Value]().value(d).reason("DEFAULT").build()
  }

  // Provider whose initialize() throws so setProvider → fails → rollback is triggered.
  private class FailingInitProvider extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "FailingInit" }
    override def getState: ProviderState                    = ProviderState.ERROR
    override def initialize(ctx: OFEvaluationContext): Unit = throw new RuntimeException("init boom")
    override def shutdown(): Unit                           = ()

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

  private val FiberCount    = 200
  private val EvalsPerFiber = 5

  // Classify each concurrent evaluation so we can assert the error shape without checking exact values
  // (during swaps evaluations may legitimately fail with ProviderNotReady or ProviderError).
  sealed private trait Outcome
  private object Outcome {
    case object Value          extends Outcome
    case object TypedException extends Outcome
    case object DefectSeen     extends Outcome
  }

  private def runOne(ff: FeatureFlags): UIO[Outcome] =
    ff.boolean("flag", default = false)
      .sandbox // converts defects → Failure(Cause.die(...)) in typed error channel
      .either  // Left = Cause[FeatureFlagError], Right = Boolean
      .map {
        case Right(_)                   => Outcome.Value
        case Left(cause) if cause.isDie => Outcome.DefectSeen
        case Left(_)                    => Outcome.TypedException
      }

  def spec = suite("ProviderHotSwapStressSpec")(
    test(s"$FiberCount concurrent fibers survive a bad→good provider swap without defects") {
      val providerA   = new ConstantProvider("A", true)
      val badProvider = new FailingInitProvider
      val providerB   = new ConstantProvider("B", false)
      val api         = OpenFeatureAPIFactory.create()
      val domain      = s"stress-swap-${java.util.UUID.randomUUID()}"

      ZIO.scoped {
        for {
          ff <- FeatureFlags.build(
            providerA,
            domain = Some(domain),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = false,
            apiOverride = Some(api),
            // Long eval timeout so concurrent evals aren't aborted by timeout during the swap window
            evaluationTimeout = Some(5.seconds)
          )
          // Fan out evaluation fibers before swapping
          evalFibers <- ZIO.foreach(1 to FiberCount) { _ =>
            ZIO
              .foreach(1 to EvalsPerFiber) { _ =>
                runOne(ff)
              }
              .fork
          }
          // Swap to a failing provider first (triggers rollback) then to a good one
          _ <- ff.setProvider(badProvider).either.ignore
          _ <- ff.setProvider(providerB).either.ignore
          // Wait for all eval fibers to finish
          results <- ZIO.foreach(evalFibers)(_.join)
          flat    = results.toList.flatten
          defects = flat.count(_ == Outcome.DefectSeen)
          _ <- ZIO.logInfo(
            s"Stress results: value=${flat.count(_ == Outcome.Value)} typed=${flat.count(_ == Outcome.TypedException)} defects=$defects"
          )
          finalStatus <- ff.providerStatus
        } yield assertTrue(
          defects == 0,
          flat.size == FiberCount * EvalsPerFiber,
          finalStatus == ProviderStatus.Ready
        )
      }
    } @@ withLiveClock @@ timeout(30.seconds),
    test("50 concurrent setProvider calls — all serialized, final provider coherent") {
      val api    = OpenFeatureAPIFactory.create()
      val domain = s"concurrent-swap-${java.util.UUID.randomUUID()}"
      ZIO.scoped {
        for {
          ff <- FeatureFlags.build(
            new ConstantProvider("init", true),
            domain = Some(domain),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = false,
            apiOverride = Some(api),
            evaluationTimeout = Some(5.seconds)
          )
          // Launch 50 concurrent setProvider calls; they will serialize on swapLock.
          // Only track if any swap threw a defect (typed swap errors are acceptable).
          swapOutcomes <- ZIO.foreachPar(1 to 50) { i =>
            ff.setProvider(new ConstantProvider(s"P$i", i % 2 == 0))
              .sandbox
              .either
              .map {
                case Right(_)    => true
                case Left(cause) => !cause.isDie
              }
          }
          status <- ff.providerStatus
        } yield assertTrue(
          swapOutcomes.forall(identity),
          status == ProviderStatus.Ready || status == ProviderStatus.Error
        )
      }
    } @@ withLiveClock @@ timeout(30.seconds)
  )
}
