package zio.openfeature.extras

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  ProviderEvent => JavaProviderEvent,
  EventProvider,
  EventProviderBridge,
  Metadata,
  ProviderEvaluation,
  ProviderEventDetails,
  ProviderState,
  TrackingEventDetails,
  Value
}
import dev.openfeature.sdk.exceptions.GeneralError
import zio._

/** Policy for how the circuit breaker reacts to `STALE` provider state. */
sealed trait StalePolicy extends Product with Serializable
object StalePolicy {
  case object Open     extends StalePolicy
  case object Ignore   extends StalePolicy
  case object HalfOpen extends StalePolicy
}

/** Configuration for the circuit breaker provider.
  *
  * @param failureThreshold
  *   Number of consecutive failures before the circuit opens
  * @param resetTimeout
  *   How long the circuit stays open before transitioning to half-open
  * @param evaluationTimeout
  *   Maximum time to wait for a single delegate evaluation call. Timed-out calls count as infrastructure failures. Set
  *   this higher than your provider's typical response time but low enough to fail fast during outages.
  * @param halfOpenMaxCalls
  *   Number of successful probes in half-open state required to close the circuit
  * @param stalePolicy
  *   How to react when the delegate provider is in STALE state
  * @param stateCheckInterval
  *   Minimum time between polls of the delegate's `getState()` on the evaluation path. The state-driven trip mechanism
  *   only needs periodic freshness; polling on every evaluation puts delegate-defined work (locks, computed state) on
  *   the hot path. `Duration.Zero` checks on every evaluation. Failure-count tripping is unaffected and reacts to every
  *   evaluation outcome immediately.
  */
final case class CircuitBreakerProviderConfig(
  failureThreshold: Int = 5,
  resetTimeout: Duration = 30.seconds,
  evaluationTimeout: Duration = 500.millis,
  halfOpenMaxCalls: Int = 1,
  stalePolicy: StalePolicy = StalePolicy.Open,
  stateCheckInterval: Duration = 1.second
) {
  private[extras] def toCircuitBreakerConfig: CircuitBreakerConfig =
    CircuitBreakerConfig(
      failureThreshold = failureThreshold,
      resetTimeout = resetTimeout,
      halfOpenMaxCalls = halfOpenMaxCalls,
      // A probe should never be considered wedged before the evaluation it runs could itself time out; add a
      // margin so a legitimately slow probe is not stolen out from under itself.
      probeTimeout = evaluationTimeout + 1.second
    )
}

/** A provider wrapper that implements the circuit breaker pattern for fast failover.
  *
  * When the delegate provider fails repeatedly or reports an unhealthy state, the circuit opens and all evaluations
  * fail immediately (< 1ms) without calling the delegate. This enables fast failover when composed with `MultiProvider`
  * and `FirstSuccessfulStrategy`.
  *
  * State transitions happen via three mechanisms:
  *   - '''Failure-count''': after `failureThreshold` consecutive evaluation failures, the circuit opens.
  *   - '''State-driven''': before an evaluation (rate-limited by `stateCheckInterval`), the delegate's `getState()` is
  *     checked. If `ERROR` or `FATAL`, the circuit opens immediately without waiting for failures.
  *   - '''Event-driven''': events emitted by the delegate feed the breaker directly — `PROVIDER_ERROR` trips it,
  *     `PROVIDER_READY` resets an externally-opened circuit, `PROVIDER_STALE` applies `stalePolicy`. Delegate events
  *     are also re-emitted through this wrapper so downstream subscribers still see them. The wrapper takes ownership
  *     of the delegate's event channel on `initialize`; do not register the same delegate instance directly with an
  *     `OpenFeatureAPI` while it is wrapped.
  *
  * In open state, after `resetTimeout` elapses, the circuit transitions to half-open and allows a single probe
  * evaluation through. On success the circuit closes; on failure it re-opens.
  *
  * '''Error classification''': Only infrastructure errors (timeouts, connection failures, `GeneralError`,
  * `ProviderNotReadyError`, `FatalError`) count toward the failure threshold. Application-level errors
  * (`FlagNotFoundError`, `TypeMismatchError`, `ParseError`, `TargetingKeyMissingError`, `InvalidContextError`) indicate
  * the provider is reachable — they reset the failure counter and pass through without tripping the circuit.
  */
final class CircuitBreakerProvider private (
  val underlying: EventProvider,
  val config: CircuitBreakerProviderConfig,
  private[extras] val breaker: CircuitBreaker,
  private val runtime: Runtime[Any]
) extends EventProvider {

  @scala.annotation.nowarn("msg=deprecated")
  override def getMetadata: Metadata = new Metadata {
    override def getName: String = s"CircuitBreakerProvider(${underlying.getMetadata.getName})"
  }

  @scala.annotation.nowarn("msg=deprecated")
  override def getState: ProviderState = breaker.currentState match {
    case CircuitState.Closed =>
      try delegateState()
      catch { case _: Exception => ProviderState.ERROR }
    case _: CircuitState.Open     => ProviderState.ERROR
    case _: CircuitState.HalfOpen => ProviderState.STALE
  }

  private val delegateAttached = new java.util.concurrent.atomic.AtomicBoolean(false)

  // Forward delegate emissions upward (the delegate is never registered with an API, so without this its
  // events go nowhere) and feed them into the breaker so an unhealthy delegate is detected as soon as it
  // says so, not only via polling or evaluation failures.
  private def onDelegateEvent(event: JavaProviderEvent, details: ProviderEventDetails): Unit = {
    event match {
      case JavaProviderEvent.PROVIDER_ERROR => breaker.trip()
      case JavaProviderEvent.PROVIDER_READY => breaker.reset()
      case JavaProviderEvent.PROVIDER_STALE =>
        config.stalePolicy match {
          case StalePolicy.Open     => breaker.trip()
          case StalePolicy.HalfOpen => breaker.transitionToHalfOpen()
          case StalePolicy.Ignore   => ()
        }
      case _ => ()
    }
    emit(event, details)
    ()
  }

  private def doInitialize(runDelegateInit: => Unit): Unit =
    try {
      if (delegateAttached.compareAndSet(false, true))
        EventProviderBridge.attach(underlying, onDelegateEvent)
      runDelegateInit
      checkDelegateState()
    } catch {
      case e: Exception =>
        breaker.trip()
        throw e
    }

  override def initialize(context: OFEvaluationContext): Unit =
    doInitialize(underlying.initialize(context))

  override def initialize(context: OFEvaluationContext, domain: String): Unit =
    doInitialize(underlying.initialize(context, domain))

  override def isDomainScoped(): Boolean = underlying.isDomainScoped()

  override def shutdown(): Unit = {
    if (delegateAttached.compareAndSet(true, false))
      scala.util.Try(EventProviderBridge.detach(underlying))
    underlying.shutdown()
  }

  // Forward the delegate's provider hooks and tracking so wrapping a provider in a circuit breaker doesn't silently
  // drop its telemetry/validation hooks or discard `track` events. `track` is fire-and-forget, so it passes through
  // without consulting the circuit. See #261.
  override def getProviderHooks = underlying.getProviderHooks

  override def track(eventName: String, context: OFEvaluationContext, details: TrackingEventDetails): Unit =
    underlying.track(eventName, context, details)

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] =
    protect(() => underlying.getBooleanEvaluation(key, defaultValue, context))

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    context: OFEvaluationContext
  ): ProviderEvaluation[String] =
    protect(() => underlying.getStringEvaluation(key, defaultValue, context))

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] =
    protect(() => underlying.getIntegerEvaluation(key, defaultValue, context))

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] =
    protect(() => underlying.getDoubleEvaluation(key, defaultValue, context))

  override def getLongEvaluation(
    key: String,
    defaultValue: java.lang.Long,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Long] =
    protect(() => underlying.getLongEvaluation(key, defaultValue, context))

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    context: OFEvaluationContext
  ): ProviderEvaluation[Value] =
    protect(() => underlying.getObjectEvaluation(key, defaultValue, context))

  // --- Provider-specific logic ---

  // Note: checkDelegateState and tryAcquire are separate calls, so there is a
  // small race window between them under concurrent access. This is an accepted
  // tradeoff — the consequence is at most one extra call leaking through, which
  // is acceptable for a best-effort circuit breaker.
  private def protect[A](evaluate: () => ProviderEvaluation[A]): ProviderEvaluation[A] = {
    checkDelegateState()

    breaker.tryAcquire match {
      case GateResult.Allowed => executeWithTimeout(evaluate)
      case GateResult.Rejected =>
        val detail = breaker.currentState match {
          case CircuitState.Open(sinceNanos, reason) =>
            val ago = (breaker.ticker.nanos() - sinceNanos) / 1000000
            val cause = reason match {
              case OpenReason.Failures => "consecutive failures"
              case OpenReason.External => "delegate reported unhealthy state"
            }
            s"open for ${ago}ms due to $cause, resets after ${config.resetTimeout.toMillis}ms"
          case CircuitState.HalfOpen(_, _, _) => "half-open, probe in progress"
          case CircuitState.Closed            => "closed"
        }
        throw new GeneralError(s"Circuit breaker rejected: $detail")
    }
  }

  private def executeWithTimeout[A](evaluate: () => ProviderEvaluation[A]): ProviderEvaluation[A] =
    try {
      val result = Unsafe.unsafe { implicit u =>
        runtime.unsafe
          .run(
            ZIO
              // attemptBlockingInterrupt (not attemptBlocking) so the timeout delivers Thread.interrupt to the
              // blocking-pool thread instead of leaking it while the delegate call runs on unbounded.
              .attemptBlockingInterrupt(evaluate())
              .disconnect // detach so timeout completes without waiting for the blocking call
              .timeoutFail(new java.util.concurrent.TimeoutException("Evaluation timed out"))(config.evaluationTimeout)
          )
          .getOrThrowFiberFailure()
      }
      if (breaker.recordSuccess()) safeEmitReady()
      result
    } catch {
      case e: Throwable if isApplicationError(e) =>
        // Application-level errors (flag not found, type mismatch, etc.) prove the provider is reachable
        // but are not real successes. `recordReachable` resets the failure counter in Closed state and
        // frees the probe slot in Half-Open, without advancing toward closing the circuit on app errors alone.
        breaker.recordReachable()
        throw FiberFailures.unwrap(e)
      // Record the failure before re-throwing: a probe dying with e.g. OOM after winning the half-open CAS must
      // re-open the circuit, not leave it wedged "half-open, probe in progress".
      case e: VirtualMachineError => breaker.recordFailure(); throw e
      case e: LinkageError =>
        breaker.recordFailure()
        throw e
      case e: Throwable =>
        val didOpen = breaker.recordFailure()
        if (didOpen) safeEmitStale("Circuit breaker opened")
        val unwrapped = FiberFailures.unwrap(e)
        val error     = new GeneralError(s"Circuit breaker: delegate failed: ${unwrapped.getMessage}")
        error.initCause(unwrapped)
        throw error
    }

  // --- Error classification ---

  private val applicationErrorCodes: Set[dev.openfeature.sdk.ErrorCode] = Set(
    dev.openfeature.sdk.ErrorCode.FLAG_NOT_FOUND,
    dev.openfeature.sdk.ErrorCode.TYPE_MISMATCH,
    dev.openfeature.sdk.ErrorCode.PARSE_ERROR,
    dev.openfeature.sdk.ErrorCode.TARGETING_KEY_MISSING,
    dev.openfeature.sdk.ErrorCode.INVALID_CONTEXT
  )

  private def isApplicationError(e: Throwable): Boolean =
    FiberFailures.unwrap(e) match {
      case ofe: dev.openfeature.sdk.exceptions.OpenFeatureError =>
        applicationErrorCodes.contains(ofe.getErrorCode)
      case _ => false
    }

  // --- Delegate state integration ---

  // Uses the deprecated FeatureProvider.getState() because it is the only way
  // for a provider wrapper to query the delegate's state. The deprecation targets
  // application code (which should use Client.getProviderState() instead), not
  // provider-to-provider communication. EventProvider.attach() — the event-based
  // alternative — is package-private in the Java SDK and inaccessible from here.
  // This call is isolated in a single method so it can be updated if the SDK
  // provides a replacement API for provider wrappers in the future.
  @scala.annotation.nowarn("msg=deprecated")
  private def delegateState(): ProviderState = underlying.getState

  // Sentinel meaning "never checked" so the very first evaluation (and initialize) always polls,
  // regardless of where the configured clock starts.
  private val NeverChecked     = Long.MinValue
  private val lastStateCheckAt = new java.util.concurrent.atomic.AtomicLong(NeverChecked)

  // Rate-limit delegate state polling on the evaluation hot path. Whichever caller wins the CAS
  // performs the poll; losers skip it — the next winner after the interval re-polls.
  private def checkDelegateState(): Unit = {
    val interval = config.stateCheckInterval.toNanos
    if (interval <= 0L) doCheckDelegateState()
    else {
      val last = lastStateCheckAt.get()
      val now  = breaker.ticker.nanos()
      if ((last == NeverChecked || now - last >= interval) && lastStateCheckAt.compareAndSet(last, now))
        doCheckDelegateState()
    }
  }

  private def doCheckDelegateState(): Unit =
    scala.util.Try(delegateState()).toOption match {
      case None => breaker.trip()
      case Some(state) =>
        if (state == ProviderState.ERROR || state == ProviderState.FATAL) breaker.trip()
        else if (state == ProviderState.STALE) config.stalePolicy match {
          case StalePolicy.Open     => breaker.trip()
          case StalePolicy.HalfOpen => breaker.transitionToHalfOpen()
          case StalePolicy.Ignore   => ()
        }
        else if (state == ProviderState.READY) breaker.reset()
    }

  // --- Event emission ---

  private def safeEmitReady(): Unit =
    try emitProviderReady(dev.openfeature.sdk.ProviderEventDetails.builder().build())
    catch { case _: Exception => () }

  private def safeEmitStale(message: String): Unit =
    try emitProviderStale(dev.openfeature.sdk.ProviderEventDetails.builder().message(message).build())
    catch { case _: Exception => () }
}

object CircuitBreakerProvider {

  def make(
    underlying: EventProvider,
    config: CircuitBreakerProviderConfig = CircuitBreakerProviderConfig()
  ): UIO[CircuitBreakerProvider] =
    make(underlying, config, Ticker.system)

  private[extras] def make(
    underlying: EventProvider,
    config: CircuitBreakerProviderConfig,
    ticker: Ticker
  ): UIO[CircuitBreakerProvider] =
    ZIO.runtime[Any].map { rt =>
      val breaker = CircuitBreaker(config.toCircuitBreakerConfig, ticker)
      new CircuitBreakerProvider(underlying, config, breaker, rt)
    }

  def apply(
    underlying: EventProvider,
    config: CircuitBreakerProviderConfig = CircuitBreakerProviderConfig()
  ): CircuitBreakerProvider =
    apply(underlying, config, Ticker.system)

  // Uses Runtime.default intentionally — this constructor exists for test helpers and Java-side
  // construction where a ZIO runtime is not available. Production code should use `make`, which
  // captures the application runtime via ZIO.runtime[Any] and avoids spawning an extra thread pool.
  private[extras] def apply(
    underlying: EventProvider,
    config: CircuitBreakerProviderConfig,
    ticker: Ticker
  ): CircuitBreakerProvider = {
    val rt      = Runtime.default
    val breaker = CircuitBreaker(config.toCircuitBreakerConfig, ticker)
    new CircuitBreakerProvider(underlying, config, breaker, rt)
  }
}
