package zio.openfeature.internal

import java.util.concurrent.{CompletableFuture, TimeUnit, TimeoutException}

import dev.openfeature.sdk.{
  ErrorCode,
  EvaluationContext => OFEvaluationContext,
  FeatureProvider => OFFeatureProvider,
  OpenFeatureAPI,
  ProviderState,
  ProviderStateAccess
}
import dev.openfeature.sdk.exceptions.{FatalError, GeneralError, OpenFeatureError}
import zio._

/** Registers a [[ManagedProvider]] and waits, bounded, for its initialization outcome — without holding the SDK's API
  * write lock across `initialize()`.
  *
  * `setProviderAndWait` holds that lock for the whole of `initialize()`, so a hanging provider wedges every later
  * `setProvider`, rollback re-registration and `api.shutdown()` behind it, and a timeout around it cannot release the
  * lock. The non-blocking `setProvider` holds the lock only for the registration bookkeeping and runs `initialize()` on
  * the SDK's own executor. The wrapper reports that call's outcome directly ([[ManagedProvider.initOutcome]]), so the
  * wait awaits it instead of polling the slot; its deadline is wall-clock, so construction stays bounded inside
  * zio-test suites whose TestClock is frozen.
  *
  * Before it binds, a registration arms the wrapper's next attempt ([[ManagedProvider.arm]]): the `initialize()` its
  * binding leads to, or the one already running for the provider's registration in this API, which the SDK shares
  * between slots. Right after the binding it is classified once: a slot not `NOT_READY` that no `initialize()` entered
  * the attempt for is a reused registration, settled on that state — [[Settled.Error]] or [[Settled.Fatal]] with
  * `reused` when it is `ERROR` or `FATAL`. A provider that initialized and then reported an error settles as
  * [[Settled.Error]] or [[Settled.Fatal]]: the registration succeeded, in that status.
  *
  * A failed `initialize()` fails the wait with a `GeneralError` carrying the provider's message and exception, or a
  * `FatalError` when the provider threw an OpenFeature error with code `PROVIDER_FATAL`, as the SDK's own state does.
  */
private[openfeature] object ProviderRegistration {

  /** The slot was rebound to another provider while this registration waited (a concurrent sibling swap). Not an
    * `IllegalStateException`: the swap rollback treats that one as the SDK's "already attached" signal.
    */
  final class RegistrationSuperseded
      extends RuntimeException("Provider registration was superseded by a concurrent registration")

  /** How a registration settled: the provider's `initialize()` returned, and the SDK reports it in this state. Never
    * `NOT_READY`.
    */
  sealed trait Settled extends Product with Serializable
  object Settled {
    case object Ready extends Settled
    case object Stale extends Settled

    /** `initialize()` returned and the provider then reported `ERROR`; or, `reused`, the provider was already
      * registered elsewhere in this API, in `ERROR`, and no initialization ran.
      */
    final case class Error(message: String, reused: Boolean = false) extends Settled

    /** As [[Error]], for `FATAL`. */
    final case class Fatal(message: String, reused: Boolean = false) extends Settled
  }

  /** Cap on the scope-close `api.shutdown()`. The SDK's own shutdown waits a few seconds per executor before forcing
    * them down, so this only trips when a provider's `shutdown()` itself hangs.
    */
  val DefaultShutdownTimeout: Duration = 15.seconds

  /** One registration of `provider` in `domain`'s slot (the default slot for `None`), as the SDK bound it.
    *
    * @param reused
    *   the slot's state when the SDK reused a registration the provider already had elsewhere in this API, settled;
    *   `None` when this registration runs, or waits on, an `initialize()`
    * @param armed
    *   for the library's wrapper, the attempt and shutdown signal taken before the binding
    * @param foreignState
    *   for a foreign provider, the state the SDK holds for its registration; `None` once the slot no longer binds it
    */
  final class Registration private[ProviderRegistration] (
    api: OpenFeatureAPI,
    val bound: Bound,
    val domain: Option[String],
    val reused: Option[ProviderState],
    armed: Option[ManagedProvider.Armed],
    foreignState: () => Option[ProviderState]
  ) {

    /** The object the SDK binds. */
    def provider: OFFeatureProvider = bound.sdk

    /** This registration's `initialize()` outcome. A foreign provider's is read from the SDK's state for it. */
    def initOutcome: Task[Unit] =
      bound match {
        case Bound.Managed(_) =>
          ZIO.fromCompletableFuture(
            armed.fold(CompletableFuture.completedFuture(()))(_.attempt.outcome).thenApply[Unit](_ => ())
          )
        // Read from the SDK, as for no library-registered provider; slowly, since only an orphan's watcher waits on it.
        case Bound.Foreign(_) =>
          ZIO
            .succeed(foreignState())
            .repeat(
              Schedule.spaced(ForeignWatchPoll) *> Schedule.recurWhile[Option[ProviderState]](
                _.contains(ProviderState.NOT_READY)
              )
            )
            .flatMap {
              case Some(ProviderState.READY | ProviderState.STALE) => ZIO.unit
              case Some(other) => ZIO.fail(new GeneralError(s"Provider reported $other while it initialized"))
              case None        => ZIO.fail(new RegistrationSuperseded)
            }
            .withClock(Clock.ClockLive)
      }

    /** Wait, bounded by `timeout`, for how the registration settled. Fails with the provider's initialization failure,
      * a `TimeoutException`, or [[RegistrationSuperseded]] when the slot was rebound meanwhile.
      *
      * The wait is one future that completes with the outcome, fails when the SDK shuts the registration down because a
      * later one replaced it, and times out by itself. Nothing in it needs interrupting, so it keeps its deadline
      * inside the swap's uninterruptible region, and the caller's own interrupt cannot cut it short there.
      */
    def settle(timeout: Duration): Task[Settled] =
      reused match {
        case Some(state) => ZIO.attempt(checkBound()).as(fromReused(state))
        case None        =>
          bound match {
            case Bound.Managed(m) => settleManaged(m, armed.getOrElse(m.arm()), timeout)
            case Bound.Foreign(_) => settleForeign(timeout)
          }
      }

    private def settleManaged(
      provider: ManagedProvider,
      armed: ManagedProvider.Armed,
      timeout: Duration
    ): Task[Settled] =
      ZIO
        .suspend {
          val outcome = new CompletableFuture[Unit]()
          // A STALE the provider reports while it initializes is what the SDK reports for the slot until that call
          // returns: the registration has settled, Stale.
          armed.attempt.stale.thenRun(() => outcome.complete(()): Unit): Unit
          armed.attempt.outcome.whenComplete { (_: Unit, error: Throwable) =>
            if (error == null) outcome.complete(()): Unit else outcome.completeExceptionally(unwrap(error)): Unit
          }
          armed.shutDown.thenRun { () =>
            if (!isBound) outcome.completeExceptionally(new RegistrationSuperseded): Unit
          }
          ZIO.fromCompletableFuture(outcome.orTimeout(timeout.toMillis, TimeUnit.MILLISECONDS))
        }
        .mapError {
          case _: TimeoutException => new TimeoutException(s"Provider initialization exceeded $timeout")
          case other               => initFailure(other)
        }
        .zipRight(ZIO.attemptBlocking { checkBound(); fromSlot(awaitWritten(), provider.lastErrorMessage) })

    // Only for a provider registered with the API outside the library and not yet initialized there: no wrapper reports
    // its outcome, so the SDK's state for it is read until it leaves NOT_READY, within the deadline. Every provider the
    // library registers settles on its wrapper's outcome instead. Blocking and bounded, so it needs no interruption.
    private def settleForeign(timeout: Duration): Task[Settled] =
      ZIO.attemptBlocking {
        val start = java.lang.System.nanoTime()
        val limit = if (timeout == Duration.Infinity) Long.MaxValue else timeout.toNanos
        var state = foreignState()
        // Compared as an elapsed time, which cannot overflow as a deadline would for a very long timeout.
        while (state.contains(ProviderState.NOT_READY) && java.lang.System.nanoTime() - start < limit) {
          java.util.concurrent.locks.LockSupport.parkNanos(ForeignPoll.toNanos)
          state = foreignState()
        }
        state match {
          case None                          => throw new RegistrationSuperseded
          case Some(ProviderState.NOT_READY) => throw new TimeoutException(s"Provider initialization exceeded $timeout")
          case Some(other)                   => checkBound(); fromSlot(other, None)
        }
      }

    /** The SDK's state for this registration's slot. */
    def slotState: ProviderState = domain.fold(api.getClient())(d => api.getClient(d)).getProviderState

    // The SDK writes READY right after `initialize()` returns, on the thread that called it: a slot read in between is
    // still NOT_READY. Wait the few microseconds for that write, so the instance reports Ready only once the SDK does.
    private def awaitWritten(): ProviderState = {
      val deadline = java.lang.System.nanoTime() + WriteWaitNanos
      var state    = slotState
      while (state == ProviderState.NOT_READY && java.lang.System.nanoTime() < deadline) {
        java.util.concurrent.locks.LockSupport.parkNanos(20000L)
        state = slotState
      }
      state
    }

    private def isBound: Boolean = (domain.fold(api.getProvider())(d => api.getProvider(d))) eq provider

    private def checkBound(): Unit = if (!isBound) throw new RegistrationSuperseded
  }

  /** Bind `provider` to `domain`'s slot and classify the registration. Not interruptible: it mutates SDK state. */
  def register(api: OpenFeatureAPI, domain: Option[String], bound: Bound): Task[Registration] =
    ZIO.attemptBlocking {
      val armed = bound.managed.map(_.arm())
      domain match {
        case Some(d) => api.setProvider(d, bound.sdk)
        case None    => api.setProvider(bound.sdk)
      }
      val state  = domain.fold(api.getClient())(d => api.getClient(d)).getProviderState
      val reader = ProviderStateAccess.stateReader(api, domain.orNull, bound.sdk)
      // The SDK's state for the registration, read through its manager; without one the slot was rebound already.
      val foreignState: () => Option[ProviderState] = () => reader.map(_())
      // An `initialize()` this binding led to may already have returned, or thrown, when the slot is read: a slot not
      // NOT_READY is a reuse only when no call entered the armed attempt.
      val reused = bound.managed match {
        case Some(_) if armed.exists(_.attempt.entered) => None
        case _                                          => if (state == ProviderState.NOT_READY) None else Some(state)
      }
      new Registration(api, bound, domain, reused, armed, foreignState)
    }

  /** Register `provider` and wait for its outcome, succeeding with how it settled; see [[Registration.settle]]. */
  def registerAndAwait(
    api: OpenFeatureAPI,
    domain: Option[String],
    provider: Bound,
    timeout: Duration
  ): Task[Settled] =
    registerThenAwait(api, domain, provider, timeout)(ZIO.unit)

  /** [[registerAndAwait]], running `afterRegister` once the slot binds `provider` and before the wait. The build uses
    * it to attach its event bridge to a slot that already holds the new provider.
    */
  def registerThenAwait[R](
    api: OpenFeatureAPI,
    domain: Option[String],
    provider: Bound,
    timeout: Duration
  )(afterRegister: ZIO[R, Throwable, Unit]): ZIO[R, Throwable, Settled] =
    register(api, domain, provider).flatMap(r => afterRegister *> r.settle(timeout))

  private def fromReused(state: ProviderState): Settled = state match {
    case ProviderState.READY => Settled.Ready
    case ProviderState.STALE => Settled.Stale
    case ProviderState.FATAL =>
      Settled.Fatal(
        s"Provider was already registered in this API in state $state; no initialization ran",
        reused = true
      )
    case other =>
      Settled.Error(
        s"Provider was already registered in this API in state $other; no initialization ran",
        reused = true
      )
  }

  // Bounds the wait for the SDK's write after `initialize()`; a slot still NOT_READY after it counts as about to be READY.
  private val WriteWaitNanos = 1000000000L

  // How often a foreign provider's state is read while the SDK initializes it: by a settle, and by an orphan's watcher.
  private val ForeignPoll      = 10.millis
  private val ForeignWatchPoll = 100.millis

  private def fromSlot(state: ProviderState, message: Option[String]): Settled = state match {
    case ProviderState.STALE => Settled.Stale
    case ProviderState.ERROR => Settled.Error(message.getOrElse("Provider reported ERROR once initialize() returned"))
    case ProviderState.FATAL => Settled.Fatal(message.getOrElse("Provider reported FATAL once initialize() returned"))
    case _                   => Settled.Ready
  }

  private def unwrap(error: Throwable): Throwable = error match {
    case c: java.util.concurrent.CompletionException if c.getCause != null => c.getCause
    case other                                                             => other
  }

  private def initFailure(e: Throwable): Throwable = e match {
    case timeout: TimeoutException                                           => timeout
    case superseded: RegistrationSuperseded                                  => superseded
    case of: OpenFeatureError if of.getErrorCode == ErrorCode.PROVIDER_FATAL =>
      new FatalError(Option(of.getMessage).getOrElse("")).initCause(of)
    case other =>
      new GeneralError(Option(other.getMessage).getOrElse(other.getClass.getSimpleName)).initCause(other)
  }

  /** The API-shutdown scope finalizer, bounded by `timeout`. Scope finalizers run uninterruptibly, so `.interruptible`
    * is what lets the timeout abandon a hung shutdown at all; `.disconnect` lets the blocking call keep running in the
    * background, and its eventual outcome is still logged. The whole thing runs on a daemon fiber: a finalizer running
    * because its fiber was interrupted (the normal application-shutdown path) carries that pending interrupt, which
    * would otherwise fire on entering the interruptible region and skip `api.shutdown()` altogether.
    */
  def boundedApiShutdown(api: OpenFeatureAPI, timeout: Duration = DefaultShutdownTimeout): UIO[Unit] =
    ZIO
      .attemptBlocking(api.shutdown())
      .tapErrorCause(c => ZIO.logWarningCause("OpenFeature API shutdown failed", c))
      .disconnect
      .timeout(timeout)
      .interruptible
      .someOrElseZIO(
        ZIO.logWarning(s"OpenFeature API shutdown exceeded $timeout; closing the scope without waiting for it")
      )
      .forkDaemon
      .flatMap(_.join)
      .catchAllCause(_ => ZIO.unit) // a failure is already logged by tapErrorCause above

  /** A single provider's `shutdown()`, bounded and best-effort, with the same daemon-fiber shape as
    * [[boundedApiShutdown]] so it also runs from a finalizer carrying a pending interrupt. A failure or timeout is
    * logged under `label`, never raised: the provider is out of service either way. An abandoned call keeps running, so
    * a later `api.shutdown()` may call the same provider's `shutdown()` while it is still in progress. A provider shut
    * down here while it is still bound to its slot (the async init watchdog's) is shut down again by the SDK, at
    * `api.shutdown()` or when a later registration replaces it: the SDK has no way to unbind a provider without that.
    */
  def boundedProviderShutdown(
    provider: OFFeatureProvider,
    label: String,
    timeout: Duration = DefaultShutdownTimeout
  ): UIO[Unit] =
    ZIO
      .attemptBlocking(provider.shutdown())
      .tapErrorCause(c => ZIO.logWarningCause(s"$label: provider shutdown failed", c))
      .disconnect
      .timeout(timeout)
      .interruptible
      .someOrElseZIO(ZIO.logWarning(s"$label: provider shutdown exceeded $timeout; not waiting for it"))
      .forkDaemon
      .flatMap(_.join)
      .catchAllCause(_ => ZIO.unit) // a failure is already logged by tapErrorCause above

  /** Run `provider`'s initialization again when its registration in `domain`'s slot (the default slot for `None`) is
    * `ERROR` because its last `initialize()` failed: the SDK would otherwise reuse that registration and never call
    * `initialize()` again. Anything else — another provider in the slot, `FATAL`, a provider that is initializing or
    * initialized once — is left alone. Fails with what `initialize()` throws, or a `TimeoutException` after `timeout`.
    */
  def retryFailedInitialization(
    api: OpenFeatureAPI,
    domain: Option[String],
    provider: OFFeatureProvider,
    timeout: Duration
  ): Task[Unit] =
    ZIO
      // The slot's manager holds the provider's wrapper, when the library registered it.
      .attemptBlocking(
        ProviderStateAccess
          .retryFailedInit(
            api,
            domain.orNull,
            ManagedProvider.existing(provider).fold[OFFeatureProvider](provider)(m => m)
          )
      )
      .disconnect
      .timeoutFail(new TimeoutException(s"Provider initialization exceeded $timeout"))(timeout)

  /** How long a failure path the caller is waiting on gives the failed provider's `shutdown()`: the initialization
    * budget the caller already granted that provider, capped at [[DefaultShutdownTimeout]].
    */
  def failedProviderShutdownTimeout(initTimeout: Duration): Duration =
    initTimeout.min(DefaultShutdownTimeout)

  /** A provider's `initialize()` run by us rather than by the SDK, failing with a `TimeoutException` after `timeout`.
    * `.disconnect` lets the timeout fire although a blocking `initialize()` cannot be interrupted; the call may keep
    * running in the background, and a failure it reports after the timeout is still logged.
    */
  def boundedInitialize(provider: OFFeatureProvider, context: OFEvaluationContext, timeout: Duration): Task[Unit] =
    ZIO
      .attemptBlocking(provider.initialize(context))
      .tapError(e => ZIO.logWarning(s"provider initialize failed: $e"))
      .disconnect
      .timeoutFail(new TimeoutException(s"Provider initialization exceeded $timeout"))(timeout)

}
