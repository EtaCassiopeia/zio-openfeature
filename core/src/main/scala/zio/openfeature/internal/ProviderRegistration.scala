package zio.openfeature.internal

import java.util.concurrent.{TimeUnit, TimeoutException}
import java.util.concurrent.atomic.AtomicReference

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
  * The SDK's async path hands the init exception only to its PROVIDER_ERROR event, as a message, so a failure here
  * carries the provider's message in a `GeneralError` (or `FatalError` for a FATAL outcome) rather than the original
  * exception instance. A PROVIDER_ERROR that a provider emits from inside its own `initialize()` therefore reads as an
  * init failure too.
  */
private[openfeature] object ProviderRegistration {

  /** The slot was rebound to another provider while this registration waited (a concurrent sibling swap). Not an
    * `IllegalStateException`: the swap rollback treats that one as the SDK's "already attached" signal.
    */
  final class RegistrationSuperseded
      extends RuntimeException("Provider registration was superseded by a concurrent registration")

  private val PollMillis = 10L
  // The PROVIDER_ERROR event is dispatched asynchronously after the state flips, so give its message a short grace.
  private val ErrorMessageGraceMillis = 500L

  /** Cap on the scope-close `api.shutdown()`. The SDK's own shutdown waits a few seconds per executor before forcing
    * them down, so this only trips when a provider's `shutdown()` itself hangs.
    */
  val DefaultShutdownTimeout: Duration = 15.seconds

  final private case class InitError(message: String, fatal: Boolean)

  /** Blocking form, for callers already on the blocking pool (the swap rollback). Throws on failure or timeout. */
  def registerAndAwaitBlocking(
    api: OpenFeatureAPI,
    domain: Option[String],
    provider: OFFeatureProvider,
    timeout: Duration
  ): Unit = {
    val lastError = new AtomicReference[Option[InitError]](None)
    val handler   = errorHandler(provider, lastError)
    api.on(JavaProviderEvent.PROVIDER_ERROR, handler)
    try {
      register(api, domain, provider)
      awaitOutcome(api, domain, provider, timeout, lastError)
    } finally removeHandler(api, handler)
  }

  /** Effect form. The registration itself is not interruptible (it mutates SDK state); the wait is, so a caller's own
    * timeout or interruption stops it.
    */
  def registerAndAwait(
    api: OpenFeatureAPI,
    domain: Option[String],
    provider: OFFeatureProvider,
    timeout: Duration
  ): Task[Unit] =
    registerThenAwait(api, domain, provider, timeout)(ZIO.unit)

  /** [[registerAndAwait]], running `afterRegister` once the slot is bound to `provider` and before the wait. The build
    * uses it to attach its event bridge to a slot that already holds the new provider.
    */
  def registerThenAwait[R](
    api: OpenFeatureAPI,
    domain: Option[String],
    provider: OFFeatureProvider,
    timeout: Duration
  )(afterRegister: ZIO[R, Throwable, Unit]): ZIO[R, Throwable, Unit] =
    ZIO.suspendSucceed {
      val lastError = new AtomicReference[Option[InitError]](None)
      val handler   = errorHandler(provider, lastError)
      ZIO.acquireReleaseWith(ZIO.attempt(api.on(JavaProviderEvent.PROVIDER_ERROR, handler)))(_ =>
        ZIO.succeed(removeHandler(api, handler))
      ) { _ =>
        ZIO.attemptBlocking(register(api, domain, provider)) *>
          afterRegister *>
          ZIO.attemptBlockingInterrupt(awaitOutcome(api, domain, provider, timeout, lastError))
      }
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
    * a later `api.shutdown()` may call the same provider's `shutdown()` while it is still in progress.
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

  private def errorHandler(
    provider: OFFeatureProvider,
    lastError: AtomicReference[Option[InitError]]
  ): java.util.function.Consumer[EventDetails] = details => {
    // The name is read when the event arrives, not up front: a MultiProvider (and a wrapper around one) has no metadata
    // until it has initialized. An unreadable name matches any event; it only selects the message to report.
    val name = Try(Option(provider.getMetadata).map(_.getName)).toOption.flatten
    if (name.forall(_ == details.getProviderName))
      lastError.set(
        Some(InitError(Option(details.getMessage).getOrElse(""), details.getErrorCode == ErrorCode.PROVIDER_FATAL))
      )
  }

  // Never let handler cleanup replace the registration's own outcome.
  private def removeHandler(api: OpenFeatureAPI, handler: java.util.function.Consumer[EventDetails]): Unit = {
    val _ = Try(api.removeHandler(JavaProviderEvent.PROVIDER_ERROR, handler))
    ()
  }

  private def awaitOutcome(
    api: OpenFeatureAPI,
    domain: Option[String],
    provider: OFFeatureProvider,
    timeout: Duration,
    lastError: AtomicReference[Option[InitError]]
  ): Unit = {
    // A client reads the state of whatever manager is bound to the slot, i.e. the one this registration installed.
    val stateClient = domain.fold(api.getClient())(d => api.getClient(d))
    val deadline    = java.lang.System.nanoTime() + timeout.toNanos

    @annotation.tailrec
    def loop(): Unit = {
      val bound = domain.fold(api.getProvider())(d => api.getProvider(d))
      // `!=` (equals), not `ne`: the SDK itself matches a non-domain-scoped provider to an existing registration by
      // equals and binds that registration's instance.
      if (bound != provider) throw new RegistrationSuperseded
      stateClient.getProviderState match {
        case ProviderState.READY | ProviderState.STALE            => ()
        case failed @ (ProviderState.ERROR | ProviderState.FATAL) =>
          val error   = awaitInitError(lastError)
          val message =
            error.map(_.message).filter(_.nonEmpty).getOrElse(s"Provider entered $failed during initialization")
          if (failed == ProviderState.FATAL || error.exists(_.fatal)) throw new FatalError(message)
          else throw new GeneralError(message)
        case _ if java.lang.System.nanoTime() - deadline >= 0 =>
          throw new TimeoutException(s"Provider initialization exceeded $timeout")
        case _ =>
          Thread.sleep(PollMillis)
          loop()
      }
    }

    loop()
  }

  private def awaitInitError(lastError: AtomicReference[Option[InitError]]): Option[InitError] = {
    val until = java.lang.System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ErrorMessageGraceMillis)
    while (lastError.get().isEmpty && java.lang.System.nanoTime() - until < 0) Thread.sleep(PollMillis)
    lastError.get()
  }
}
