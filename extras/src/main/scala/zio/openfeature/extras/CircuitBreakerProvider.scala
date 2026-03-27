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
  */
final case class CircuitBreakerProviderConfig(
  failureThreshold: Int = 5,
  resetTimeout: Duration = 30.seconds,
  evaluationTimeout: Duration = 500.millis,
  halfOpenMaxCalls: Int = 1,
  stalePolicy: StalePolicy = StalePolicy.Open
) {
  private[extras] def toCircuitBreakerConfig: CircuitBreakerConfig =
    CircuitBreakerConfig(
      failureThreshold = failureThreshold,
      resetTimeout = resetTimeout,
      halfOpenMaxCalls = halfOpenMaxCalls
    )
}

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

  override def initialize(context: OFEvaluationContext): Unit =
    try {
      underlying.initialize(context)
      checkDelegateState()
    } catch {
      case e: Exception =>
        breaker.trip()
        throw e
    }

  override def shutdown(): Unit = underlying.shutdown()

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
          case CircuitState.Open(sinceMillis, reason) =>
            val ago = breaker.clock.millis() - sinceMillis
            val cause = reason match {
              case OpenReason.Failures => "consecutive failures"
              case OpenReason.External => "delegate reported unhealthy state"
            }
            s"open for ${ago}ms due to $cause, resets after ${config.resetTimeout.toMillis}ms"
          case CircuitState.HalfOpen(_, _) => "half-open, probe in progress"
          case CircuitState.Closed         => "closed"
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
              .attemptBlocking(evaluate())
              .disconnect // detach so timeout completes without waiting for the blocking call
              .timeoutFail(new java.util.concurrent.TimeoutException("Evaluation timed out"))(config.evaluationTimeout)
          )
          .getOrThrowFiberFailure()
      }
      if (breaker.recordSuccess()) safeEmitReady()
      result
    } catch {
      case e: Throwable if isApplicationError(e) =>
        // Application-level errors (flag not found, type mismatch, etc.) indicate
        // the provider is reachable — reset failure counter in closed state, but do
        // NOT count toward closing the circuit in half-open state (only actual
        // successful evaluations should close the circuit).
        if (!breaker.isHalfOpen) {
          if (breaker.recordSuccess()) safeEmitReady()
        }
        throw unwrapFiberFailure(e)
      case e: VirtualMachineError => throw e
      case e: LinkageError =>
        breaker.recordFailure()
        throw e
      case e: Throwable =>
        val didOpen = breaker.recordFailure()
        if (didOpen) safeEmitStale("Circuit breaker opened")
        val unwrapped = unwrapFiberFailure(e)
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

  private def unwrapFiberFailure(e: Throwable): Throwable = e match {
    case ff: zio.FiberFailure =>
      ff.cause.failureOption match {
        case Some(t: Throwable) => t
        case _ =>
          ff.cause.dieOption match {
            case Some(t) => t
            case None =>
              if (ff.cause.isInterrupted)
                new java.util.concurrent.TimeoutException("Evaluation was interrupted")
              else ff
          }
      }
    case other => other
  }

  private def isApplicationError(e: Throwable): Boolean =
    unwrapFiberFailure(e) match {
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

  private def checkDelegateState(): Unit = {
    val state =
      try delegateState()
      catch {
        case _: Exception =>
          breaker.trip()
          return
      }
    val shouldOpen       = state == ProviderState.ERROR || state == ProviderState.FATAL
    val shouldApplyStale = state == ProviderState.STALE

    if (shouldOpen) {
      breaker.trip()
    } else if (shouldApplyStale) {
      config.stalePolicy match {
        case StalePolicy.Open     => breaker.trip()
        case StalePolicy.HalfOpen => breaker.transitionToHalfOpen()
        case StalePolicy.Ignore   => ()
      }
    } else if (state == ProviderState.READY) {
      breaker.reset()
    }
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
    make(underlying, config, java.time.Clock.systemUTC())

  private[extras] def make(
    underlying: EventProvider,
    config: CircuitBreakerProviderConfig,
    clock: java.time.Clock
  ): UIO[CircuitBreakerProvider] =
    ZIO.runtime[Any].map { rt =>
      val breaker = CircuitBreaker(config.toCircuitBreakerConfig, clock)
      new CircuitBreakerProvider(underlying, config, breaker, rt)
    }

  def apply(
    underlying: EventProvider,
    config: CircuitBreakerProviderConfig = CircuitBreakerProviderConfig()
  ): CircuitBreakerProvider =
    apply(underlying, config, java.time.Clock.systemUTC())

  private[extras] def apply(
    underlying: EventProvider,
    config: CircuitBreakerProviderConfig,
    clock: java.time.Clock
  ): CircuitBreakerProvider = {
    val rt      = Runtime.default
    val breaker = CircuitBreaker(config.toCircuitBreakerConfig, clock)
    new CircuitBreakerProvider(underlying, config, breaker, rt)
  }
}
