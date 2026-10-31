package zio.openfeature.extras

import zio.openfeature.internal.ProviderEvaluations

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  FeatureProvider,
  ImmutableContext,
  Metadata,
  OpenFeatureAPI,
  ProviderEvent,
  ProviderEventDetails,
  ProviderState,
  Value
}
import dev.openfeature.sdk.exceptions.GeneralError
import zio._
import zio.test._

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// A wrapper's emissions reach the SDK slot in the order the wrapper issued them: two events forwarded in order, or the
// breaker's own STALE followed by a forwarded ERROR, leave the slot on the later one every time.
object WrapperEmissionOrderSpec extends ZIOSpecDefault {

  private val Rounds        = 200
  private val DeliveryBound = 5.seconds.toNanos

  /** A delegate that awaits each of its own emissions, so the order it hands them to a wrapper is the order issued. */
  final private class Delegate extends EventProvider {
    @volatile var failEvaluations = false

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = "delegate" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      if (failEvaluations) throw new GeneralError("down") else ProviderEvaluations.of[java.lang.Boolean](d, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) = ProviderEvaluations.of(d, "STATIC")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "STATIC")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "STATIC")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = ProviderEvaluations.of(d, "STATIC")

    def emitAndWait(event: ProviderEvent): Unit = {
      emit(event, ProviderEventDetails.builder().build()).await()
      ()
    }
  }

  /** `wrapper` registered alone in an isolated API, with a count of the events its handlers have seen. */
  final private class Registered(wrapper: FeatureProvider) {
    private val api    = OpenFeatureAPI.createIsolated()
    private val domain = s"order-${java.util.UUID.randomUUID()}"
    private val counts = new ConcurrentHashMap[ProviderEvent, AtomicInteger]()

    api.setProviderAndWait(domain, wrapper)
    List(ProviderEvent.PROVIDER_ERROR, ProviderEvent.PROVIDER_STALE).foreach { e =>
      counts.put(e, new AtomicInteger(0))
      api.getClient(domain).on(e, _ => { counts.get(e).incrementAndGet(); () })
    }

    def delivered(e: ProviderEvent): Int = counts.get(e).get()

    def slotState: ProviderState = api.getClient(domain).getProviderState

    /** Wait until the handlers have seen `wanted`; a handler runs after the slot write of its event. */
    def awaitDelivered(wanted: (ProviderEvent, Int)*): Boolean = {
      val deadline = java.lang.System.nanoTime() + DeliveryBound
      def reached  = wanted.forall { case (e, n) => delivered(e) >= n }
      while (!reached && java.lang.System.nanoTime() < deadline) Thread.sleep(1)
      reached
    }

    def close(): Unit = api.shutdown()
  }

  /** `Rounds` rounds of `first` then `second` from the delegate; each round must leave the slot on `second`. */
  private def rounds(
    make: Delegate => FeatureProvider,
    first: ProviderEvent,
    second: ProviderEvent,
    expected: ProviderState
  ): Task[TestResult] =
    ZIO
      .attemptBlocking {
        val delegate = new Delegate
        val reg      = new Registered(make(delegate))
        try {
          val failure = (1 to Rounds).iterator
            .map { r =>
              delegate.emitAndWait(first)
              delegate.emitAndWait(second)
              if (!reg.awaitDelivered(first -> r, second -> r)) Some(s"round $r: an event was never delivered")
              else {
                val state = reg.slotState
                if (state != expected) Some(s"round $r ended $state") else None
              }
            }
            .collectFirst { case Some(message) => message }
          failure
        } finally reg.close()
      }
      .map(failure => assertTrue(failure.isEmpty))

  private def cache(d: FeatureProvider)   = CachingProvider(d)
  private def breaker(d: FeatureProvider) = CircuitBreakerProvider(d)

  private val wrappers: List[(String, Delegate => FeatureProvider)] = List(
    "CachingProvider"        -> (d => cache(d)),
    "CircuitBreakerProvider" -> (d => breaker(d)),
    "Cache(Breaker(_))"      -> (d => cache(breaker(d)))
  )

  private val orderTests = wrappers.flatMap { case (name, make) =>
    List(
      test(s"$name: a delegate's ERROR then STALE leaves the slot STALE") {
        rounds(make, ProviderEvent.PROVIDER_ERROR, ProviderEvent.PROVIDER_STALE, ProviderState.STALE)
      },
      test(s"$name: a delegate's STALE then ERROR leaves the slot ERROR") {
        rounds(make, ProviderEvent.PROVIDER_STALE, ProviderEvent.PROVIDER_ERROR, ProviderState.ERROR)
      }
    )
  }

  // The breaker's own STALE, emitted on the evaluation thread when a failure opens the circuit, and a delegate ERROR
  // that follows it share one lane.
  private val crossSource = test("a breaker's own STALE followed by a delegate's ERROR leaves the slot ERROR") {
    ZIO
      .attemptBlocking {
        (1 to 50).iterator
          .map { r =>
            val delegate = new Delegate
            val wrapper  = CircuitBreakerProvider(
              delegate,
              CircuitBreakerProviderConfig(failureThreshold = 1, evaluationTimeout = Duration.Infinity)
            )
            val reg = new Registered(wrapper)
            try {
              delegate.failEvaluations = true
              scala.util.Try(wrapper.getBooleanEvaluation("flag", false, new ImmutableContext())): Unit
              delegate.emitAndWait(ProviderEvent.PROVIDER_ERROR)
              if (!reg.awaitDelivered(ProviderEvent.PROVIDER_STALE -> 1, ProviderEvent.PROVIDER_ERROR -> 1))
                Some(s"run $r: an event was never delivered")
              else {
                val state = reg.slotState
                if (state != ProviderState.ERROR) Some(s"run $r ended $state") else None
              }
            } finally reg.close()
          }
          .collectFirst { case Some(message) => message }
      }
      .map(failure => assertTrue(failure.isEmpty))
  }

  def spec = suite("WrapperEmissionOrderSpec")(
    orderTests :+ crossSource: _*
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(3.minutes)
}
