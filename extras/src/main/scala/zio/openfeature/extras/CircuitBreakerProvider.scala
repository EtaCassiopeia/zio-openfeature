package zio.openfeature.extras

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  ProviderEvent => JavaProviderEvent,
  EventProvider,
  FeatureProvider,
  Metadata,
  ProviderEvaluation,
  ProviderEventDetails,
  ProviderState,
  TrackingEventDetails,
  Value
}
import dev.openfeature.sdk.exceptions.GeneralError
import zio._
import zio.openfeature.FeatureFlagError
import java.util.concurrent.atomic.AtomicBoolean

/** Policy for how the circuit breaker reacts to `STALE` provider state. */
sealed trait StalePolicy extends Product with Serializable
object StalePolicy {
  case object Open     extends StalePolicy
  case object Ignore   extends StalePolicy
  case object HalfOpen extends StalePolicy
}

/** Configuration for the circuit breaker provider.
  *
  * `CircuitBreakerProvider.make`, `CircuitBreakerProvider.scoped` and `CircuitBreakerProvider.apply` reject a config
  * that breaks the bounds given for each field below.
  *
  * @param failureThreshold
  *   Number of consecutive failures before the circuit opens. Must be `>= 1`.
  * @param resetTimeout
  *   How long the circuit stays open before transitioning to half-open. Must be `> 0`. `Duration.Infinity` is a real
  *   mode, not an accident: the circuit never half-opens by elapsed time, and recovers only when the delegate emits
  *   `PROVIDER_READY` (or, for a circuit a state poll opened, when a later poll reports `READY`).
  * @param evaluationTimeout
  *   Maximum time to wait for a single delegate evaluation call. Timed-out calls count as infrastructure failures. Set
  *   this higher than your provider's typical response time but low enough to fail fast during outages.
  *   `Duration.Infinity` disables the timeout and evaluates the delegate on the calling thread, which lets the members
  *   of a wrapped `MultiProvider` chain see the caller's client metadata in their provider hooks. Any finite value
  *   needs the delegate on another thread, where that context is out of reach. Without a timeout a hanging delegate
  *   blocks the caller and is never counted as a failure. Must be `> 0` (`Duration.Infinity` is legal).
  * @param halfOpenMaxCalls
  *   Number of successful probes in half-open state required to close the circuit. Must be `>= 1`.
  * @param stalePolicy
  *   How to react when the delegate provider is in STALE state
  * @param stateCheckInterval
  *   Minimum time between polls of the delegate's `getState()` on the evaluation path. The state-driven trip mechanism
  *   only needs periodic freshness; polling on every evaluation puts delegate-defined work (locks, computed state) on
  *   the hot path. `Duration.Zero` checks on every evaluation. Failure-count tripping is unaffected and reacts to every
  *   evaluation outcome immediately. Must be `>= 0`; `Duration.Infinity` polls only once.
  */
final case class CircuitBreakerProviderConfig(
  failureThreshold: Int = CircuitBreaker.DefaultFailureThreshold,
  resetTimeout: Duration = CircuitBreaker.DefaultResetTimeout,
  evaluationTimeout: Duration = CircuitBreakerProvider.DefaultEvaluationTimeout,
  halfOpenMaxCalls: Int = CircuitBreaker.DefaultHalfOpenMaxCalls,
  stalePolicy: StalePolicy = StalePolicy.Open,
  stateCheckInterval: Duration = CircuitBreakerProvider.DefaultStateCheckInterval
) {
  private[extras] def toCircuitBreakerConfig: CircuitBreakerConfig =
    CircuitBreakerConfig(
      failureThreshold = failureThreshold,
      resetTimeout = resetTimeout,
      halfOpenMaxCalls = halfOpenMaxCalls,
      // A probe should never be considered wedged before the evaluation it runs could itself time out; add a
      // margin so a legitimately slow probe is not stolen out from under itself.
      probeTimeout = evaluationTimeout + CircuitBreakerProvider.ProbeTimeoutMargin
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
  *     checked. If `ERROR` or `FATAL`, the circuit opens immediately without waiting for failures; a later polled
  *     `READY` closes only a circuit a poll opened, since a delegate that reports health by event usually polls as the
  *     SDK's `READY` default.
  *   - '''Event-driven''': events emitted by the delegate feed the breaker directly — `PROVIDER_ERROR` trips it,
  *     `PROVIDER_READY` resets an externally-opened circuit, `PROVIDER_STALE` applies `stalePolicy`. Delegate events
  *     are also re-emitted through this wrapper so downstream subscribers still see them. The wrapper takes ownership
  *     of the delegate's event channel on `initialize`; do not register the same delegate instance directly with an
  *     `OpenFeatureAPI` while it is wrapped. This mechanism requires an `EventProvider` delegate — see below.
  *
  * '''Plain providers''': the delegate is a `FeatureProvider`, so a provider that does not extend `EventProvider` can
  * be wrapped too. Only the event-driven mechanism needs the richer type, and it degrades cleanly: nothing is attached,
  * no delegate events arrive, and the breaker relies on the failure-count and state-driven mechanisms exactly as it
  * already does for an `EventProvider` that never emits. Every other part of the delegate's surface — all six
  * resolvers, both `initialize` overloads, `isDomainScoped`, `getProviderHooks`, `track`, `shutdown`, `getMetadata` and
  * `getState` — is `FeatureProvider` surface and is forwarded unchanged. The delegate is held as given, with no adapter
  * interposed, so no hand-mirrored forwarding layer exists to fall behind the SDK.
  *
  * In open state, after `resetTimeout` elapses, the circuit transitions to half-open and allows a single probe
  * evaluation through. On success the circuit closes; on failure it re-opens.
  *
  * '''Error classification''': Only infrastructure errors (timeouts, connection failures, `GeneralError`,
  * `ProviderNotReadyError`, `FatalError`) count toward the failure threshold. Application-level errors
  * (`FlagNotFoundError`, `TypeMismatchError`, `ParseError`, `TargetingKeyMissingError`, `InvalidContextError`) indicate
  * the provider is reachable — they reset the failure counter and pass through without tripping the circuit. A delegate
  * that returns an evaluation carrying an error code, instead of throwing, is classified by that code in the same way,
  * and the evaluation is returned unchanged.
  */
final class CircuitBreakerProvider private (
  val underlying: FeatureProvider,
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
    // A circuit the delegate's STALE opened reports the delegate's health, not the rejection: STALE is evaluable for
    // the SDK, so a stack that is serving last-known data does not fail a build the way an ERROR would.
    case CircuitState.Open(_, _, true) => ProviderState.STALE
    case _: CircuitState.Open          => ProviderState.ERROR
    case _: CircuitState.HalfOpen      => ProviderState.STALE
  }

  private val channel = new DelegateEventChannel(underlying, onDelegateEvent)

  // Forward delegate emissions upward (the delegate is never registered with an API, so without this its
  // events go nowhere) and feed them into the breaker so an unhealthy delegate is detected as soon as it
  // says so, not only via polling or evaluation failures.
  private def onDelegateEvent(event: JavaProviderEvent, details: ProviderEventDetails): Unit = {
    event match {
      case JavaProviderEvent.PROVIDER_ERROR => breaker.trip()
      case JavaProviderEvent.PROVIDER_READY => breaker.reset()
      case JavaProviderEvent.PROVIDER_STALE =>
        config.stalePolicy match {
          case StalePolicy.Open     => breaker.trip(OpenReason.External, stale = true)
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
      channel.attach()
      runDelegateInit
      checkDelegateState()
    } catch {
      // An External trip: a failed initialize is not undone by the next getState() poll, which for most providers is
      // the SDK's READY default. It recovers through the reset-timeout probe or an event READY.
      case e: Exception =>
        breaker.trip()
        throw e
    }

  override def initialize(context: OFEvaluationContext): Unit =
    doInitialize(underlying.initialize(context))

  override def initialize(context: OFEvaluationContext, domain: String): Unit =
    doInitialize(underlying.initialize(context, domain))

  override def isDomainScoped(): Boolean = underlying.isDomainScoped()

  private val shutdownRan = new AtomicBoolean(false)

  // `super.shutdown()` (EventProvider's) last: it is what stops this wrapper's own event emitter. It waits up to
  // a few seconds for in-flight emissions to drain. Idempotent: a wrapper that is both scoped and registered
  // with `FeatureFlags` is shut down by the SDK and by the scope finalizer, and the delegate must see only the first.
  override def shutdown(): Unit =
    if (shutdownRan.compareAndSet(false, true)) {
      channel.detach()
      try underlying.shutdown()
      finally super.shutdown()
    }

  // Forward the delegate's provider hooks and tracking so wrapping a provider in a circuit breaker doesn't silently
  // drop its telemetry/validation hooks or discard `track` events. `track` is fire-and-forget, so it passes through
  // without consulting the circuit.
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

  // Note: checkDelegateState and acquire are separate calls, so there is a
  // small race window between them under concurrent access. This is an accepted
  // tradeoff — the consequence is at most one extra call leaking through, which
  // is acceptable for a best-effort circuit breaker.
  private def protect[A](evaluate: () => ProviderEvaluation[A]): ProviderEvaluation[A] = {
    checkDelegateState()

    breaker.acquire() match {
      case Some(permit) => executeWithTimeout(permit, evaluate)
      case None         =>
        val detail = breaker.currentState match {
          case CircuitState.Open(sinceNanos, reason, stale) =>
            val ago   = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(breaker.ticker.nanos() - sinceNanos)
            val cause = reason match {
              case OpenReason.Failures          => "consecutive failures"
              case OpenReason.External if stale => "delegate reported STALE"
              case OpenReason.External          => "delegate reported unhealthy state"
              case OpenReason.Polled if stale   => "delegate state poll reported STALE"
              case OpenReason.Polled            => "delegate state poll reported unhealthy"
            }
            s"open for ${ago}ms due to $cause, resets after ${config.resetTimeout.toMillis}ms"
          case CircuitState.HalfOpen(_, _, _) => "half-open, probe in progress"
          case CircuitState.Closed            => "closed"
        }
        throw new GeneralError(s"Circuit breaker rejected: $detail")
    }
  }

  // Every outcome is recorded against the permit the call was admitted under, so a slow call admitted in an earlier
  // circuit state cannot close, re-open or free the probe slot of the current one.
  private def executeWithTimeout[A](
    permit: CircuitBreaker.Permit,
    evaluate: () => ProviderEvaluation[A]
  ): ProviderEvaluation[A] =
    try {
      val guarded = config.evaluationTimeout match {
        case Duration.Finite(_) =>
          ZIO
            // attemptBlockingInterrupt (not attemptBlocking) so the timeout delivers Thread.interrupt to the
            // blocking-pool thread instead of leaking it while the delegate call runs on unbounded.
            .attemptBlockingInterrupt(evaluate())
            .disconnect // detach so timeout completes without waiting for the blocking call
            .timeoutFail(new java.util.concurrent.TimeoutException("Evaluation timed out"))(config.evaluationTimeout)
        // No timeout to enforce, so stay on the calling thread: the SDK's MultiProvider hands hook context to its
        // resolvers through a thread-local, which another thread cannot see.
        case _ => ZIO.attempt(evaluate())
      }
      val result = Unsafe.unsafe { implicit u =>
        runtime.unsafe.run(guarded).getOrThrowFiberFailure()
      }
      // A provider may report failure by returning an error code instead of throwing (the contrib OFREP resolver does
      // for network errors), so a returned evaluation is classified exactly like a thrown error. It is passed
      // back unchanged either way.
      classify(result.getErrorCode) match {
        case Outcome.Success   => if (breaker.recordSuccess(permit)) safeEmitReady()
        case Outcome.Reachable => breaker.recordReachable(permit)
        case Outcome.Failure   => if (breaker.recordFailure(permit)) safeEmitStale("Circuit breaker opened")
      }
      result
    } catch {
      case e: Throwable if isApplicationError(e) =>
        // Application-level errors (flag not found, type mismatch, etc.) prove the provider is reachable
        // but are not real successes. `recordReachable` resets the failure counter in Closed state and
        // frees the probe slot in Half-Open, without advancing toward closing the circuit on app errors alone.
        breaker.recordReachable(permit)
        throw FiberFailures.unwrap(e)
      // Record the failure before re-throwing: a probe dying with e.g. OOM after winning the half-open CAS must
      // re-open the circuit, not leave it wedged "half-open, probe in progress".
      case e: VirtualMachineError => val _ = breaker.recordFailure(permit); throw e
      case e: LinkageError        =>
        val _ = breaker.recordFailure(permit)
        throw e
      case e: Throwable =>
        val didOpen = breaker.recordFailure(permit)
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

  sealed private trait Outcome
  private object Outcome {
    case object Success   extends Outcome
    case object Reachable extends Outcome
    case object Failure   extends Outcome
  }

  // One table for returned and thrown errors. Fails closed: any code that is not a known application error counts as
  // an infrastructure failure.
  private def classify(code: dev.openfeature.sdk.ErrorCode): Outcome =
    if (code == null) Outcome.Success
    else if (applicationErrorCodes.contains(code)) Outcome.Reachable
    else Outcome.Failure

  private def isApplicationError(e: Throwable): Boolean =
    FiberFailures.unwrap(e) match {
      case ofe: dev.openfeature.sdk.exceptions.OpenFeatureError => classify(ofe.getErrorCode) == Outcome.Reachable
      case _                                                    => false
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

  // A poll trips with OpenReason.Polled, and a polled READY clears only such a trip: since SDK 1.22 `getState()`
  // defaults to READY, so a delegate that reports health only by event would otherwise have its event-driven trip
  // undone by the next poll.
  private def doCheckDelegateState(): Unit =
    scala.util.Try(delegateState()).toOption match {
      case None        => breaker.trip(OpenReason.Polled)
      case Some(state) =>
        if (state == ProviderState.ERROR || state == ProviderState.FATAL) breaker.trip(OpenReason.Polled)
        else if (state == ProviderState.STALE) config.stalePolicy match {
          case StalePolicy.Open     => breaker.trip(OpenReason.Polled, stale = true)
          case StalePolicy.HalfOpen => breaker.transitionToHalfOpen()
          case StalePolicy.Ignore   => ()
        }
        else if (state == ProviderState.READY) breaker.resetIfOpenedBy(OpenReason.Polled)
    }

  // --- Event emission ---

  private def safeEmitReady(): Unit =
    try emitProviderReady(dev.openfeature.sdk.ProviderEventDetails.builder().build()): Unit
    catch { case _: Exception => () }

  private def safeEmitStale(message: String): Unit =
    try emitProviderStale(dev.openfeature.sdk.ProviderEventDetails.builder().message(message).build()): Unit
    catch { case _: Exception => () }
}

object CircuitBreakerProvider {

  val DefaultEvaluationTimeout: Duration  = 500.millis
  val DefaultStateCheckInterval: Duration = 1.second

  // Added to the evaluation timeout to get the probe timeout: a probe is never considered wedged before the evaluation
  // it runs could itself time out.
  private[extras] val ProbeTimeoutMargin: Duration = 1.second

  /** Upper bound on the scope finalizer's `shutdown()`: the delegate's own shutdown may block, and a pathological one
    * must not hang scope teardown.
    */
  private[extras] val ShutdownTimeout: Duration = 10.seconds

  /** Wrap `underlying` in a circuit breaker.
    *
    * Fails with `InvalidConfiguration` naming every field of `config` that breaks the bounds documented on
    * [[CircuitBreakerProviderConfig]]. The caller owns the wrapper's lifecycle: use `scoped` to have it shut down when
    * a `Scope` closes.
    */
  def make(
    underlying: FeatureProvider,
    config: CircuitBreakerProviderConfig = CircuitBreakerProviderConfig()
  ): IO[FeatureFlagError.InvalidConfiguration, CircuitBreakerProvider] =
    make(underlying, config, Ticker.system)

  private[extras] def make(
    underlying: FeatureProvider,
    config: CircuitBreakerProviderConfig,
    ticker: Ticker
  ): IO[FeatureFlagError.InvalidConfiguration, CircuitBreakerProvider] =
    ZIO.fromEither(ConfigValidation.circuitBreakerProvider(config)) *>
      ZIO.runtime[Any].map { rt =>
        val breaker = CircuitBreaker(config.toCircuitBreakerConfig, ticker)
        new CircuitBreakerProvider(underlying, config, breaker, rt)
      }

  /** `make` with scope-managed shutdown: when the surrounding `Scope` closes, the wrapper is shut down (bounded by a
    * timeout; a failure is logged and ignored), which shuts down `underlying` too. Safe to combine with registering the
    * wrapper with `FeatureFlags`: `shutdown()` is idempotent, so the delegate is shut down once. Use
    * `ZLayer.scoped(CircuitBreakerProvider.scoped(provider, config))` for a layer.
    */
  def scoped(
    underlying: FeatureProvider,
    config: CircuitBreakerProviderConfig = CircuitBreakerProviderConfig()
  ): ZIO[Scope, FeatureFlagError.InvalidConfiguration, CircuitBreakerProvider] =
    ZIO.acquireRelease(make(underlying, config))(p =>
      WrapperRelease.release("CircuitBreakerProvider", ShutdownTimeout)(p.shutdown())
    )

  /** Unsafe constructor for code without a ZIO runtime at hand; prefer `make` or `scoped`.
    *
    * @throws IllegalArgumentException
    *   if `config` breaks the bounds documented on [[CircuitBreakerProviderConfig]]
    */
  def apply(
    underlying: FeatureProvider,
    config: CircuitBreakerProviderConfig = CircuitBreakerProviderConfig()
  ): CircuitBreakerProvider =
    apply(underlying, config, Ticker.system)

  // Uses Runtime.default intentionally — this constructor exists for test helpers and Java-side
  // construction where a ZIO runtime is not available. Production code should use `make`, which
  // captures the application runtime via ZIO.runtime[Any] and avoids spawning an extra thread pool.
  private[extras] def apply(
    underlying: FeatureProvider,
    config: CircuitBreakerProviderConfig,
    ticker: Ticker
  ): CircuitBreakerProvider = {
    val _       = ConfigValidation.orThrow(ConfigValidation.circuitBreakerProvider(config))
    val rt      = Runtime.default
    val breaker = CircuitBreaker(config.toCircuitBreakerConfig, ticker)
    new CircuitBreakerProvider(underlying, config, breaker, rt)
  }
}
