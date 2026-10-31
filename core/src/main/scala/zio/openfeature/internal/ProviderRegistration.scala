package zio.openfeature.internal

import java.util.concurrent.{TimeUnit, TimeoutException}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.util.Try

import dev.openfeature.sdk.{
  ErrorCode,
  EvaluationContext => OFEvaluationContext,
  EventDetails,
  FeatureProvider => OFFeatureProvider,
  OpenFeatureAPI,
  ProviderEvent => JavaProviderEvent,
  ProviderState,
  ProviderStateAccess
}
import dev.openfeature.sdk.exceptions.{FatalError, GeneralError}
import zio._

/** Registers a provider and waits, bounded, for its initialization outcome — without holding the SDK's API write lock
  * across `initialize()`.
  *
  * `setProviderAndWait` holds that lock for the whole of `initialize()`, so a hanging provider wedges every later
  * `setProvider`, rollback re-registration and `api.shutdown()` behind it, and a timeout around it cannot release the
  * lock. The non-blocking `setProvider` holds the lock only for the registration bookkeeping and runs `initialize()` on
  * the SDK's own executor; this object then polls the slot's state until it settles or the deadline passes. The poll is
  * a hand-rolled loop on a blocking-pool thread rather than a `Schedule` because its deadline must be wall-clock:
  * construction has to stay bounded inside zio-test suites whose TestClock is frozen.
  *
  * The outcome is decided by whether `initialize()` returned, not by the state the slot shows afterwards: a provider
  * may report `ERROR` the moment it has initialized, and the SDK state of a failed `initialize()` is the same. The SDK
  * dispatches its own `PROVIDER_READY` for a provider if and only if its `initialize()` returned, and before it shuts
  * the replaced provider down, so the wait listens for that event on the slot's domain. A provider that initialized and
  * then reported an error settles as [[Settled.Error]] or [[Settled.Fatal]]: the registration succeeded, in that
  * status.
  *
  * The SDK's async path hands the init exception only to its PROVIDER_ERROR event, as a message, so a failure here
  * carries the provider's message in a `GeneralError` (or `FatalError` for a FATAL outcome) rather than the original
  * exception instance. A PROVIDER_READY that a provider emits from inside its own `initialize()` reads as an
  * initialization that returned, and a PROVIDER_ERROR it emits there reads as a failure unless `initialize()` then
  * returns within the outcome grace.
  *
  * A provider already registered on another slot of the same API is reused: the SDK binds its existing state, runs no
  * `initialize()` and sends no event. In `ERROR` or `FATAL`, the slot shows that state on the first poll, never
  * `NOT_READY`, and neither the SDK's READY nor its ERROR arrives within the outcome grace, which a fresh registration
  * always produces one of. That settles as [[Settled.Error]] or [[Settled.Fatal]] with `reused`. A fresh registration
  * whose ERROR event is delayed past the grace reads the same way: the swap then reports `Error` rather than rolling
  * back, the same latency assumption the wait makes for the SDK's READY.
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

    /** `initialize()` returned and the provider then reported `ERROR`, with that event's message; or, `reused`, the
      * provider was already registered elsewhere in this API, in `ERROR`, and no initialization ran.
      */
    final case class Error(message: String, reused: Boolean = false) extends Settled

    /** As [[Error]], for `FATAL`. */
    final case class Fatal(message: String, reused: Boolean = false) extends Settled
  }

  private val PollMillis = 10L
  // Events are dispatched asynchronously after the state flips. On `ERROR` or `FATAL` the wait gives the SDK's READY,
  // and the error's message, this long to arrive.
  private val OutcomeGraceMillis = 500L
  // Once the error's message has arrived, how much longer the READY may take. The SDK submits its READY before any
  // event the provider emits after `initialize()` returns, to the same pool, so it trails that error only by
  // scheduling; a READY missed here makes the registration read as failed, as it did before the READY was observed.
  private val ReadyAfterErrorMillis = 100L

  /** Cap on the scope-close `api.shutdown()`. The SDK's own shutdown waits a few seconds per executor before forcing
    * them down, so this only trips when a provider's `shutdown()` itself hangs.
    */
  val DefaultShutdownTimeout: Duration = 15.seconds

  final private case class InitError(message: String, fatal: Boolean)

  /** Register `provider` and wait for its outcome, succeeding with how it settled. Fails if its `initialize()` threw,
    * did not return within `timeout`, or the slot was rebound meanwhile. The registration itself is not interruptible
    * (it mutates SDK state); the wait is, so a caller's own timeout or interruption stops it.
    */
  def registerAndAwait(
    api: OpenFeatureAPI,
    domain: Option[String],
    provider: OFFeatureProvider,
    timeout: Duration
  ): Task[Settled] =
    registerThenAwait(api, domain, provider, timeout)(ZIO.unit)

  /** [[registerAndAwait]], running `afterRegister` once the slot is bound to `provider` and before the wait. The build
    * uses it to attach its event bridge to a slot that already holds the new provider. `ready`, when given, is the
    * caller's watch for `provider`'s READY, opened before this call and closed by the caller; otherwise the wait opens
    * and closes its own.
    */
  def registerThenAwait[R](
    api: OpenFeatureAPI,
    domain: Option[String],
    provider: OFFeatureProvider,
    timeout: Duration,
    ready: Option[ReadyWatch] = None
  )(afterRegister: ZIO[R, Throwable, Unit]): ZIO[R, Throwable, Settled] =
    ZIO.suspendSucceed {
      val observer = new InitObserver(api, domain, provider, ready)
      // Both handlers go on before the registration, so no event of it can come before them.
      ZIO.acquireReleaseWith(ZIO.attempt(observer.open()))(_ => ZIO.succeed(observer.close())) { _ =>
        ZIO.attemptBlocking(register(api, domain, provider)) *>
          ZIO.attempt(observer.arm()) *>
          afterRegister *>
          ZIO.attemptBlockingInterrupt(awaitOutcome(api, domain, provider, timeout, observer))
      }
    }

  /** Whether the SDK's `PROVIDER_READY` for `provider` reached the slot of `domain` (the default slot for `None`) since
    * the watch was opened, i.e. whether `provider`'s `initialize()` returned. Opened before `provider` is registered,
    * so it cannot miss that READY; a timed-out swap hands it on to the watcher of the provider it replaced, which reads
    * it long after the wait ended.
    *
    * A READY counts only when it names `provider` and the slot binds `provider` when the handler runs. A domain handler
    * receives only the events of the provider bound to the slot when they are emitted, so the provider being replaced
    * cannot mark it, nor can a provider a later swap installs; one the slot held before `provider` could only if it had
    * the same name and emitted READY just before the registration. The SDK's own run of a new handler, for a slot
    * already READY when the watch opens, names no provider and is not counted there.
    *
    * A second handler is armed once the registration has bound `provider` (`arm`). From then on a READY can only come
    * from the provider the slot binds when it is dispatched, so that handler also counts one with no name, and the
    * SDK's immediate run of it, for a slot already READY, means `provider`'s registration is. That is what covers a
    * provider whose metadata has no name. Such a provider is still judged by its state alone if it initializes and
    * reports an error in the instant between the registration and the arming.
    */
  final class ReadyWatch private[ProviderRegistration] (
    api: OpenFeatureAPI,
    domain: Option[String],
    provider: OFFeatureProvider
  ) {
    private val seen    = new AtomicBoolean(false)
    private val handler = new java.util.function.Consumer[EventDetails] {
      // The name is read when the event arrives: a MultiProvider has none until it has initialized. `==` for the slot,
      // as the SDK matches a provider to its registration. Anything unreadable counts nothing.
      def accept(details: EventDetails): Unit = {
        val named = Option(details.getProviderName).exists { sent =>
          Try(Option(provider.getMetadata).map(_.getName)).toOption.flatten.contains(sent)
        }
        if (named && Try(domain.fold(api.getProvider())(d => api.getProvider(d)) == provider).getOrElse(false))
          seen.set(true)
      }
    }
    // Added once the slot binds `provider`: a READY now, named or not, comes from the provider the slot binds when it is
    // dispatched, and the SDK's run of the new handler, for a slot already READY, means `provider`'s registration is.
    // So this one also counts a provider without a name, which `handler` cannot tell from the slot's previous one.
    private val armedHandler = new java.util.function.Consumer[EventDetails] {
      def accept(details: EventDetails): Unit = {
        val name = Try(Option(provider.getMetadata).map(_.getName)).toOption.flatten
        val sent = Option(details.getProviderName)
        if ((sent.isEmpty || sent == name) && Try(bound == provider).getOrElse(false)) seen.set(true)
      }
    }
    private val armed  = new AtomicBoolean(false)
    private val client = domain.fold(api.getClient())(d => api.getClient(d))
    private def bound  = domain.fold(api.getProvider())(d => api.getProvider(d))

    def initialized: Boolean = seen.get()

    // Never let handler cleanup replace a caller's own outcome.
    def close(): Unit = {
      val _ = Try(client.removeHandler(JavaProviderEvent.PROVIDER_READY, handler))
      if (armed.get()) { val _ = Try(client.removeHandler(JavaProviderEvent.PROVIDER_READY, armedHandler)) }
      ()
    }

    private[ProviderRegistration] def open(): ReadyWatch = {
      client.on(JavaProviderEvent.PROVIDER_READY, handler)
      this
    }

    /** Add the second handler, once `provider` is registered on the slot. Idempotent. */
    private[ProviderRegistration] def arm(): Unit =
      if (armed.compareAndSet(false, true)) { val _ = client.on(JavaProviderEvent.PROVIDER_READY, armedHandler) }
  }

  /** Watch the slot of `domain` for the SDK's `PROVIDER_READY` for `provider`. Close it when done. */
  def watchReady(api: OpenFeatureAPI, domain: Option[String], provider: OFFeatureProvider): ReadyWatch =
    new ReadyWatch(api, domain, provider).open()

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
      .attemptBlocking(ProviderStateAccess.retryFailedInit(api, domain.orNull, provider))
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

  private def register(api: OpenFeatureAPI, domain: Option[String], provider: OFFeatureProvider): Unit =
    domain match {
      case Some(d) => api.setProvider(d, provider)
      case None    => api.setProvider(provider)
    }

  /** What one registration's wait hears: the provider's last error message, and whether the SDK's READY reached the
    * slot.
    */
  final private class InitObserver(
    api: OpenFeatureAPI,
    domain: Option[String],
    provider: OFFeatureProvider,
    external: Option[ReadyWatch]
  ) {
    val lastError                                 = new AtomicReference[Option[InitError]](None)
    @volatile private var own: Option[ReadyWatch] = None

    def initialized: Boolean = external.orElse(own).exists(_.initialized)

    def arm(): Unit = external.orElse(own).foreach(_.arm())

    private val errorHandler = new java.util.function.Consumer[EventDetails] {
      def accept(details: EventDetails): Unit = {
        // The name is read when the event arrives, not up front: a MultiProvider (and a wrapper around one) has no
        // metadata until it has initialized. An unreadable name matches any event; it only selects the message.
        val name = Try(Option(provider.getMetadata).map(_.getName)).toOption.flatten
        if (name.forall(_ == details.getProviderName))
          lastError.set(
            Some(InitError(Option(details.getMessage).getOrElse(""), details.getErrorCode == ErrorCode.PROVIDER_FATAL))
          )
      }
    }

    def open(): Unit = {
      val _ = api.on(JavaProviderEvent.PROVIDER_ERROR, errorHandler)
      if (external.isEmpty) own = Some(ProviderRegistration.watchReady(api, domain, provider))
    }

    // Never let handler cleanup replace the registration's own outcome. A watch the caller passed is the caller's.
    def close(): Unit = {
      val _ = Try(api.removeHandler(JavaProviderEvent.PROVIDER_ERROR, errorHandler))
      own.foreach(_.close())
    }
  }

  private def awaitOutcome(
    api: OpenFeatureAPI,
    domain: Option[String],
    provider: OFFeatureProvider,
    timeout: Duration,
    observer: InitObserver
  ): Settled = {
    // A client reads the state of whatever manager is bound to the slot, i.e. the one this registration installed.
    val stateClient = domain.fold(api.getClient())(d => api.getClient(d))
    val deadline    = java.lang.System.nanoTime() + timeout.toNanos

    def checkBound(): Unit = {
      val bound = domain.fold(api.getProvider())(d => api.getProvider(d))
      // `!=` (equals), not `ne`: the SDK itself matches a non-domain-scoped provider to an existing registration by
      // equals and binds that registration's instance.
      if (bound != provider) throw new RegistrationSuperseded
    }

    // A registration that reuses a manager the provider already has elsewhere in this API runs no `initialize()`: the
    // slot reads its settled state on the first poll, never NOT_READY, and the SDK sends neither its READY nor its
    // ERROR for it. A fresh registration whose `initialize()` throws can read ERROR first too, but its ERROR follows.
    var sawNotReady = false

    @annotation.tailrec
    def loop(): Settled = {
      checkBound()
      stateClient.getProviderState match {
        case ProviderState.READY                                  => Settled.Ready
        case ProviderState.STALE                                  => Settled.Stale
        case failed @ (ProviderState.ERROR | ProviderState.FATAL) =>
          val error = awaitReadyOrError(observer)
          checkBound()
          if (observer.initialized)
            // `initialize()` returned and the provider reported a state since: the registration succeeded in it.
            stateClient.getProviderState match {
              case ProviderState.STALE => Settled.Stale
              case ProviderState.FATAL => Settled.Fatal(error.fold("")(_.message))
              case ProviderState.ERROR => Settled.Error(error.fold("")(_.message))
              case _                   => Settled.Ready
            }
          else if (!sawNotReady && error.isEmpty) {
            val message = s"Provider was already registered in this API in state $failed; no initialization ran"
            if (failed == ProviderState.FATAL) Settled.Fatal(message, reused = true)
            else Settled.Error(message, reused = true)
          } else {
            val message =
              error.map(_.message).filter(_.nonEmpty).getOrElse(s"Provider entered $failed during initialization")
            if (failed == ProviderState.FATAL || error.exists(_.fatal)) throw new FatalError(message)
            else throw new GeneralError(message)
          }
        case _ if java.lang.System.nanoTime() - deadline >= 0 =>
          throw new TimeoutException(s"Provider initialization exceeded $timeout")
        case _ =>
          sawNotReady = true
          Thread.sleep(PollMillis)
          loop()
      }
    }

    loop()
  }

  /** Wait, bounded, until the SDK's READY and the error's message have both arrived, or the message has been followed
    * by [[ReadyAfterErrorMillis]] without the READY; the message, if it arrived. Both are needed when `initialize()`
    * returned: the slot's state is already ERROR, but the handlers run unordered, so the READY can arrive before the
    * message the settled state carries.
    */
  private def awaitReadyOrError(observer: InitObserver): Option[InitError] = {
    def now                       = java.lang.System.nanoTime()
    val until                     = now + TimeUnit.MILLISECONDS.toNanos(OutcomeGraceMillis)
    var errorSeenAt: Option[Long] = None
    def errorWaitOver             =
      errorSeenAt.exists(t => now - t >= TimeUnit.MILLISECONDS.toNanos(ReadyAfterErrorMillis))
    while (!(observer.initialized && observer.lastError.get().isDefined) && !errorWaitOver && now - until < 0) {
      if (errorSeenAt.isEmpty && observer.lastError.get().isDefined) errorSeenAt = Some(now)
      Thread.sleep(PollMillis)
    }
    observer.lastError.get()
  }
}
