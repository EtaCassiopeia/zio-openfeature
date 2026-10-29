package zio.openfeature.extras

import zio.openfeature.internal.ProviderEvaluations

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  EventProviderBridge,
  FeatureProvider,
  ImmutableContext,
  Metadata,
  ProviderEvent,
  ProviderEventDetails,
  ProviderState,
  Value
}
import zio._
import zio.test._
import java.util.concurrent.CopyOnWriteArrayList

// The wrappers' event plumbing — shutdown must stop their own emitter, and a failed attach must not later detach
// the wrapper that legitimately owns the delegate.
object WrapperEventChannelSpec extends ZIOSpecDefault {

  /** An event-emitting delegate whose shutdown() leaves its own emitter alive, so it can still emit afterwards. */
  private class EmittingDelegate extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = "delegate" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) = ProviderEvaluations.of(d, "STATIC")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "STATIC")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "STATIC")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = ProviderEvaluations.of(d, "STATIC")

    def configChanged(): Unit = {
      emitProviderConfigurationChanged(ProviderEventDetails.builder().build()).await()
      ()
    }
  }

  private val ctx = new ImmutableContext()

  /** Events the wrapper re-emits, observed the way the SDK would observe them. */
  private def record(wrapper: EventProvider): CopyOnWriteArrayList[ProviderEvent] = {
    val seen = new CopyOnWriteArrayList[ProviderEvent]()
    EventProviderBridge.attach(wrapper, (event, _) => { seen.add(event); () })
    seen
  }

  private def shutdownStopsEmission(wrapper: EventProvider with FeatureProvider): Task[TestResult] =
    for {
      seen    <- ZIO.succeed(record(wrapper))
      _       <- ZIO.attemptBlocking(wrapper.shutdown())
      emitted <- ZIO.attemptBlocking(wrapper.emitProviderReady(ProviderEventDetails.builder().build()).await()).either
      _       <- Live.live(ZIO.sleep(200.millis)) // a dispatch, if one was accepted, has run by now
    } yield assertTrue(emitted.isLeft || seen.isEmpty)

  private def failedAttachKeepsOwner(make: EmittingDelegate => EventProvider with FeatureProvider): Task[TestResult] =
    for {
      delegate  <- ZIO.succeed(new EmittingDelegate)
      owner     <- ZIO.succeed(make(delegate))
      intruder  <- ZIO.succeed(make(delegate))
      _         <- ZIO.attemptBlocking(owner.initialize(ctx))
      seen      <- ZIO.succeed(record(owner))
      second    <- ZIO.attemptBlocking(intruder.initialize(ctx)).either // the delegate is already attached
      _         <- ZIO.attemptBlocking(intruder.shutdown())
      _         <- ZIO.attemptBlocking(delegate.configChanged())
      reEmitted <- Live.live(
        ZIO
          .succeed(seen.contains(ProviderEvent.PROVIDER_CONFIGURATION_CHANGED))
          .repeatUntil(identity)
          .timeout(5.seconds)
      )
    } yield assertTrue(second.isLeft, reEmitted.contains(true))

  def spec = suite("WrapperEventChannelSpec")(
    test("CachingProvider.shutdown stops its own event emitter") {
      shutdownStopsEmission(CachingProvider(new EmittingDelegate))
    },
    test("CircuitBreakerProvider.shutdown stops its own event emitter") {
      shutdownStopsEmission(CircuitBreakerProvider(new EmittingDelegate))
    },
    test("a CachingProvider whose attach failed does not detach the wrapper that owns the delegate") {
      failedAttachKeepsOwner(d => CachingProvider(d))
    },
    test("a CircuitBreakerProvider whose attach failed does not detach the wrapper that owns the delegate") {
      failedAttachKeepsOwner(d => CircuitBreakerProvider(d))
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
}
