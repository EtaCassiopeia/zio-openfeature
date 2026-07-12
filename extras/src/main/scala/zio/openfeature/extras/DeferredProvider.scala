package zio.openfeature.extras

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  ErrorCode,
  FeatureProvider,
  Hook,
  Metadata,
  ProviderEvaluation,
  ProviderState,
  Value
}
import zio.openfeature.internal.ProviderEvaluations

import java.util.concurrent.atomic.AtomicReference

/** Adapts a constructor-blocking provider into an `initialize()`-blocking one.
  *
  * Some providers do all their network work in the Java constructor (blocking the call site). Wrapping such a provider
  * in `DeferredProvider` defers construction to `initialize(ctx)`, which the OpenFeature SDK runs on its own init
  * executor — so `fromProviderAsync` (or any `FeatureFlagsConfig(initMode = InitMode.Async)`) already keeps that work
  * off the caller's thread. Use this when you want plain async semantics (typed `PROVIDER_NOT_READY` until ready); use
  * [[zio.openfeature.FeatureFlags.fromAcquireAsync]] instead when a fallback must answer during the init window (inside
  * a `MultiProvider`, `DeferredProvider` still gates overall readiness, since the SDK awaits all children).
  *
  *   - Metadata name is stable (`name`) before and after construction, so the event bridge and `MultiProvider` keying
  *     see a single identity.
  *   - Evaluations before construction completes return a typed `ProviderEvaluation` with
  *     `ErrorCode.PROVIDER_NOT_READY` — never an NPE on the null delegate.
  *   - `shutdown()` racing an in-flight `initialize()` shuts the delegate down once construction completes, instead of
  *     leaking its poller / HTTP client.
  *   - `getProviderHooks` forwards to the delegate once active.
  *
  * Limitation: wrapping an `EventProvider` does not forward its events — `DeferredProvider` is a plain
  * `FeatureProvider`, which matches the typical constructor-blocking provider.
  *
  * @param name
  *   stable metadata name reported before and after construction
  * @param construct
  *   builds the real provider; runs on the SDK init executor when `initialize` is called
  */
final class DeferredProvider(name: String)(construct: () => FeatureProvider) extends FeatureProvider {
  import DeferredProvider._

  private val stateRef = new AtomicReference[State](State.NotStarted)

  @scala.annotation.nowarn("msg=deprecated")
  override def getMetadata: Metadata = new Metadata {
    override def getName: String = name
  }

  @scala.annotation.nowarn("msg=deprecated")
  override def getState: ProviderState =
    stateRef.get() match {
      case State.Active(delegate) => delegate.getState
      case _                      => ProviderState.NOT_READY
    }

  override def initialize(context: OFEvaluationContext): Unit =
    // Only the first initialize() constructs; a second call (or one after shutdown) is a no-op.
    if (stateRef.compareAndSet(State.NotStarted, State.Constructing)) {
      // If construction throws, don't wedge in Constructing (which would report NOT_READY forever and no-op shutdown):
      // move to Shutdown and rethrow so the SDK marks the provider errored.
      val delegate =
        try construct()
        catch { case t: Throwable => stateRef.set(State.Shutdown); throw t }
      // If delegate.initialize throws, the delegate is already built — shut it down so its poller/HTTP client doesn't
      // leak, move to Shutdown, and rethrow.
      try delegate.initialize(context)
      catch {
        case t: Throwable =>
          stateRef.set(State.Shutdown)
          delegate.shutdown()
          throw t
      }
      // Publish the delegate — unless shutdown() raced in during construction, in which case the state is already
      // Shutdown and we must tear the freshly built delegate down so its background threads don't leak.
      if (!stateRef.compareAndSet(State.Constructing, State.Active(delegate)))
        delegate.shutdown()
    }

  override def shutdown(): Unit =
    stateRef.getAndSet(State.Shutdown) match {
      case State.Active(delegate) => delegate.shutdown()
      // Constructing: initialize() will observe the Shutdown state and shut the delegate down once it finishes
      // constructing. NotStarted / Shutdown: nothing to release.
      case _ => ()
    }

  override def getProviderHooks: java.util.List[Hook[_]] =
    stateRef.get() match {
      case State.Active(delegate) => delegate.getProviderHooks
      case _                      => java.util.Collections.emptyList[Hook[_]]()
    }

  private def notReady[T](default: T): ProviderEvaluation[T] =
    ProviderEvaluations.error(default, ErrorCode.PROVIDER_NOT_READY, s"Provider '$name' is not yet initialized")

  private def delegateOr[T](default: T)(f: FeatureProvider => ProviderEvaluation[T]): ProviderEvaluation[T] =
    stateRef.get() match {
      case State.Active(delegate) => f(delegate)
      case _                      => notReady(default)
    }

  override def getBooleanEvaluation(
    key: String,
    defaultValue: java.lang.Boolean,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Boolean] =
    delegateOr(defaultValue)(_.getBooleanEvaluation(key, defaultValue, context))

  override def getStringEvaluation(
    key: String,
    defaultValue: String,
    context: OFEvaluationContext
  ): ProviderEvaluation[String] =
    delegateOr(defaultValue)(_.getStringEvaluation(key, defaultValue, context))

  override def getIntegerEvaluation(
    key: String,
    defaultValue: java.lang.Integer,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Integer] =
    delegateOr(defaultValue)(_.getIntegerEvaluation(key, defaultValue, context))

  override def getDoubleEvaluation(
    key: String,
    defaultValue: java.lang.Double,
    context: OFEvaluationContext
  ): ProviderEvaluation[java.lang.Double] =
    delegateOr(defaultValue)(_.getDoubleEvaluation(key, defaultValue, context))

  override def getObjectEvaluation(
    key: String,
    defaultValue: Value,
    context: OFEvaluationContext
  ): ProviderEvaluation[Value] =
    delegateOr(defaultValue)(_.getObjectEvaluation(key, defaultValue, context))
}

object DeferredProvider {

  /** Create a DeferredProvider. `construct` is deferred to `initialize()` (run on the SDK init executor). */
  def apply(name: String)(construct: () => FeatureProvider): DeferredProvider =
    new DeferredProvider(name)(construct)

  sealed private trait State
  private object State {
    case object NotStarted                             extends State
    case object Constructing                           extends State
    final case class Active(delegate: FeatureProvider) extends State
    case object Shutdown                               extends State
  }
}
