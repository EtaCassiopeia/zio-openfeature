package zio.openfeature.extras

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  Value
}
import dev.openfeature.sdk.exceptions.GeneralError
import zio._
import java.util.concurrent.atomic.AtomicReference

/** Policy for how the circuit breaker reacts to `STALE` provider state. */
sealed trait StalePolicy extends Product with Serializable
object StalePolicy {
  case object Open     extends StalePolicy
  case object Ignore   extends StalePolicy
  case object HalfOpen extends StalePolicy
}

/** Configuration for the circuit breaker.
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
  */
final case class CircuitBreakerConfig(
  failureThreshold: Int = 5,
  resetTimeout: Duration = 30.seconds,
  evaluationTimeout: Duration = 500.millis,
  halfOpenMaxCalls: Int = 1,
  stalePolicy: StalePolicy = StalePolicy.Open
)

/** Why the circuit was opened — determines whether state-driven recovery applies. */
sealed private[extras] trait OpenReason extends Product with Serializable
private[extras] object OpenReason {
  case object Failures      extends OpenReason
  case object DelegateState extends OpenReason
}

sealed private[extras] trait CircuitState extends Product with Serializable
private[extras] object CircuitState {
  case object Closed                                           extends CircuitState
  final case class Open(sinceMillis: Long, reason: OpenReason) extends CircuitState
  final case class HalfOpen(successes: Int, probing: Boolean)  extends CircuitState
}

final private[extras] case class CircuitBreakerState(
  circuit: CircuitState,
  consecutiveFailures: Int
)

/** A provider wrapper that implements the circuit breaker pattern for fast failover.
  *
  * When the delegate provider fails repeatedly or reports an unhealthy state, the circuit opens and all evaluations
  * fail immediately (< 1ms) without calling the delegate. This enables fast failover when composed with `MultiProvider`
  * and `FirstSuccessfulStrategy`.
  *
  * State transitions happen via two mechanisms:
  *   - '''Failure-count''': after `failureThreshold` consecutive evaluation failures, the circuit opens.
  *   - '''State-driven''': before each evaluation, the delegate's `getState()` is checked. If `ERROR` or `FATAL`, the
  *     circuit opens immediately without waiting for failures.
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
  val config: CircuitBreakerConfig,
  private val stateRef: AtomicReference[CircuitBreakerState],
  private val runtime: Runtime[Any],
  private val clock: java.time.Clock
) extends EventProvider {

  import CircuitState._

  @scala.annotation.nowarn("msg=deprecated")
  override def getMetadata: Metadata = new Metadata {
    override def getName: String = s"CircuitBreakerProvider(${underlying.getMetadata.getName})"
  }

  @scala.annotation.nowarn("msg=deprecated")
  override def getState: ProviderState = stateRef.get().circuit match {
    case Closed =>
      try delegateState()
      catch { case _: Exception => ProviderState.ERROR }
    case _: Open     => ProviderState.ERROR
    case _: HalfOpen => ProviderState.STALE
  }

  override def initialize(context: OFEvaluationContext): Unit =
    try {
      underlying.initialize(context)
      checkDelegateState()
    } catch {
      case e: Exception =>
        tripCircuit()
        throw e
    }

  override def shutdown(): Unit = underlying.shutdown()

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] =
    withCircuitBreaker(() => underlying.getBooleanEvaluation(key, defaultValue, context))

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    context: OFEvaluationContext
  ): ProviderEvaluation[String] =
    withCircuitBreaker(() => underlying.getStringEvaluation(key, defaultValue, context))

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] =
    withCircuitBreaker(() => underlying.getIntegerEvaluation(key, defaultValue, context))

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] =
    withCircuitBreaker(() => underlying.getDoubleEvaluation(key, defaultValue, context))

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    context: OFEvaluationContext
  ): ProviderEvaluation[Value] =
    withCircuitBreaker(() => underlying.getObjectEvaluation(key, defaultValue, context))

  private def withCircuitBreaker[A](evaluate: () => ProviderEvaluation[A]): ProviderEvaluation[A] = {
    checkDelegateState()

    val state = stateRef.get()
    state.circuit match {
      case Closed =>
        executeWithTimeout(evaluate)

      case open: Open =>
        val elapsed = clock.millis() - open.sinceMillis
        if (elapsed >= config.resetTimeout.toMillis) {
          val halfOpen = CircuitBreakerState(HalfOpen(successes = 0, probing = true), state.consecutiveFailures)
          if (stateRef.compareAndSet(state, halfOpen)) {
            executeWithTimeout(evaluate)
          } else {
            throw new GeneralError("Circuit breaker is open")
          }
        } else {
          throw new GeneralError("Circuit breaker is open")
        }

      case ho: HalfOpen =>
        if (!ho.probing) {
          val probing = CircuitBreakerState(HalfOpen(ho.successes, probing = true), state.consecutiveFailures)
          if (stateRef.compareAndSet(state, probing)) {
            executeWithTimeout(evaluate)
          } else {
            throw new GeneralError("Circuit breaker is half-open, probe in progress")
          }
        } else {
          throw new GeneralError("Circuit breaker is half-open, probe in progress")
        }
    }
  }

  private def executeWithTimeout[A](evaluate: () => ProviderEvaluation[A]): ProviderEvaluation[A] =
    try {
      val result = Unsafe.unsafe { implicit u =>
        runtime.unsafe
          .run(
            ZIO
              .attemptBlockingInterrupt(evaluate())
              .timeoutFail(new java.util.concurrent.TimeoutException("Evaluation timed out"))(config.evaluationTimeout)
          )
          .getOrThrowFiberFailure()
      }
      onSuccess()
      result
    } catch {
      case e: Throwable if isApplicationError(e) =>
        // Application-level errors (flag not found, type mismatch, etc.) indicate
        // the provider is reachable — reset failure counter and re-throw.
        onSuccess()
        throw unwrapFiberFailure(e)
      case e: VirtualMachineError => throw e
      case e: LinkageError        => throw e
      case e: Throwable =>
        onFailure()
        val unwrapped = unwrapFiberFailure(e)
        val error     = new GeneralError(s"Circuit breaker: delegate failed: ${unwrapped.getMessage}")
        error.initCause(unwrapped)
        throw error
    }

  // Application-level errors that do NOT indicate provider health issues.
  // These should pass through without affecting circuit breaker state.
  private val applicationErrorCodes: Set[dev.openfeature.sdk.ErrorCode] = Set(
    dev.openfeature.sdk.ErrorCode.FLAG_NOT_FOUND,
    dev.openfeature.sdk.ErrorCode.TYPE_MISMATCH,
    dev.openfeature.sdk.ErrorCode.PARSE_ERROR,
    dev.openfeature.sdk.ErrorCode.TARGETING_KEY_MISSING,
    dev.openfeature.sdk.ErrorCode.INVALID_CONTEXT
  )

  private def unwrapFiberFailure(e: Throwable): Throwable = e match {
    case ff: zio.FiberFailure =>
      ff.cause.failureOption
        .collect { case t: Throwable => t }
        .orElse(ff.cause.dieOption)
        .getOrElse(ff)
    case other => other
  }

  private def isApplicationError(e: Throwable): Boolean = {
    val cause = unwrapFiberFailure(e)
    cause match {
      case ofe: dev.openfeature.sdk.exceptions.OpenFeatureError =>
        applicationErrorCodes.contains(ofe.getErrorCode)
      case _ => false
    }
  }

  private def onSuccess(): Unit = {
    var done = false
    while (!done) {
      val current = stateRef.get()
      current.circuit match {
        case Closed =>
          if (current.consecutiveFailures == 0) {
            done = true
          } else {
            val next = CircuitBreakerState(Closed, consecutiveFailures = 0)
            done = stateRef.compareAndSet(current, next)
          }

        case ho: HalfOpen =>
          val newSuccesses = ho.successes + 1
          if (newSuccesses >= config.halfOpenMaxCalls) {
            val next = CircuitBreakerState(Closed, consecutiveFailures = 0)
            done = stateRef.compareAndSet(current, next)
            if (done) safeEmitReady()
          } else {
            val next = CircuitBreakerState(HalfOpen(newSuccesses, probing = false), 0)
            done = stateRef.compareAndSet(current, next)
          }

        case _: Open =>
          done = true
      }
    }
  }

  private def onFailure(): Unit = {
    var done = false
    while (!done) {
      val current     = stateRef.get()
      val newFailures = current.consecutiveFailures + 1
      current.circuit match {
        case Closed =>
          if (newFailures >= config.failureThreshold) {
            val next = CircuitBreakerState(Open(clock.millis(), OpenReason.Failures), newFailures)
            done = stateRef.compareAndSet(current, next)
            if (done) safeEmitStale("Circuit breaker opened")
          } else {
            val next = CircuitBreakerState(Closed, newFailures)
            done = stateRef.compareAndSet(current, next)
          }

        case _: HalfOpen =>
          val next = CircuitBreakerState(Open(clock.millis(), OpenReason.Failures), newFailures)
          done = stateRef.compareAndSet(current, next)
          if (done) safeEmitStale("Circuit breaker re-opened after failed probe")

        case _: Open =>
          done = true
      }
    }
  }

  // Guard event emission so failures don't corrupt circuit state or propagate
  // up through executeWithTimeout's catch block.
  private def safeEmitReady(): Unit =
    try emitProviderReady(dev.openfeature.sdk.ProviderEventDetails.builder().build())
    catch { case _: Exception => () }

  private def safeEmitStale(message: String): Unit =
    try emitProviderStale(dev.openfeature.sdk.ProviderEventDetails.builder().message(message).build())
    catch { case _: Exception => () }

  // Uses the deprecated FeatureProvider.getState() because it is the only way
  // for a provider wrapper to query the delegate's state. The deprecation targets
  // application code (which should use Client.getProviderState() instead), not
  // provider-to-provider communication. EventProvider.attach() — the event-based
  // alternative — is package-private in the Java SDK and inaccessible from here.
  // This call is isolated in a single method so it can be updated if the SDK
  // provides a replacement API for provider wrappers in the future.
  @scala.annotation.nowarn("msg=deprecated")
  private def delegateState(): ProviderState = underlying.getState

  /** Check the delegate provider's state and open the circuit immediately if unhealthy. */
  private def checkDelegateState(): Unit = {
    val state            = delegateState()
    val shouldOpen       = state == ProviderState.ERROR || state == ProviderState.FATAL
    val shouldApplyStale = state == ProviderState.STALE

    if (shouldOpen) {
      tripCircuit()
    } else if (shouldApplyStale) {
      config.stalePolicy match {
        case StalePolicy.Open     => tripCircuit()
        case StalePolicy.HalfOpen => transitionToHalfOpen()
        case StalePolicy.Ignore   => ()
      }
    } else if (state == ProviderState.READY) {
      // Only reset if the circuit was opened by delegate state detection.
      // Failure-count opens should recover through the half-open probe mechanism.
      val current = stateRef.get()
      current.circuit match {
        case Open(_, OpenReason.DelegateState) => resetCircuit()
        case _                                 => ()
      }
    }
  }

  private def tripCircuit(): Unit = {
    var done = false
    while (!done) {
      val current = stateRef.get()
      current.circuit match {
        case _: Open => done = true
        case _ =>
          val next = CircuitBreakerState(Open(clock.millis(), OpenReason.DelegateState), current.consecutiveFailures)
          done = stateRef.compareAndSet(current, next)
      }
    }
  }

  private def transitionToHalfOpen(): Unit = {
    var done = false
    while (!done) {
      val current = stateRef.get()
      current.circuit match {
        case _: HalfOpen => done = true
        case _ =>
          val next = CircuitBreakerState(HalfOpen(successes = 0, probing = false), current.consecutiveFailures)
          done = stateRef.compareAndSet(current, next)
      }
    }
  }

  private def resetCircuit(): Unit = {
    var done = false
    while (!done) {
      val current = stateRef.get()
      current.circuit match {
        case Closed if current.consecutiveFailures == 0 => done = true
        case Open(_, OpenReason.DelegateState) =>
          val next = CircuitBreakerState(Closed, consecutiveFailures = 0)
          done = stateRef.compareAndSet(current, next)
        case _ =>
          // Do not reset failure-opened or half-open circuits
          done = true
      }
    }
  }
}

object CircuitBreakerProvider {

  def make(
    underlying: EventProvider,
    config: CircuitBreakerConfig = CircuitBreakerConfig()
  ): UIO[CircuitBreakerProvider] =
    make(underlying, config, java.time.Clock.systemUTC())

  private[extras] def make(
    underlying: EventProvider,
    config: CircuitBreakerConfig,
    clock: java.time.Clock
  ): UIO[CircuitBreakerProvider] =
    ZIO.runtime[Any].map { rt =>
      val state = new AtomicReference(CircuitBreakerState(CircuitState.Closed, consecutiveFailures = 0))
      new CircuitBreakerProvider(underlying, config, state, rt, clock)
    }

  def apply(
    underlying: EventProvider,
    config: CircuitBreakerConfig = CircuitBreakerConfig()
  ): CircuitBreakerProvider =
    apply(underlying, config, java.time.Clock.systemUTC())

  private[extras] def apply(
    underlying: EventProvider,
    config: CircuitBreakerConfig,
    clock: java.time.Clock
  ): CircuitBreakerProvider = {
    val rt    = Runtime.default
    val state = new AtomicReference(CircuitBreakerState(CircuitState.Closed, consecutiveFailures = 0))
    new CircuitBreakerProvider(underlying, config, state, rt, clock)
  }
}
