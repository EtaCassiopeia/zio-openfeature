package zio.openfeature.internal

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import dev.openfeature.sdk.{EventProviderAccess, FeatureProvider, OpenFeatureAPI, ProviderState}
import zio._

/** Providers that a timed-out swap left registered nowhere, until the swap's new provider settles.
  *
  * The SDK binds the new provider to the slot before its `initialize()` runs, and shuts the provider it replaced down
  * only once that `initialize()` returns normally. A swap that times out leaves the previous provider held by nothing
  * but that pending call: if it later fails, or never finishes, nothing shuts the previous provider down, and the API's
  * own shutdown cannot reach it. Each entry here watches the new provider's SDK state manager, which keeps its state
  * after the slot is rebound, and settles once:
  *   - `READY` or `STALE`: the SDK has shut the previous provider down; the entry is dropped.
  *   - `ERROR` or `FATAL`: the previous provider is shut down here.
  *   - still `NOT_READY` when the owner shuts down or its scope closes: the previous provider is shut down here. An
  *     `initialize()` that ignores the SDK's interrupt and succeeds after that gets it shut down by the SDK as well.
  *
  * The SDK exposes the manager's state, not whether `initialize()` has returned, so a provider that emits `READY` or
  * `ERROR` from inside its own `initialize()` is taken at that word.
  *
  * Only an instance that owns its API records entries: a provider of a shared API may serve sibling clients.
  */
final private[openfeature] class OrphanedProviders(
  api: OpenFeatureAPI,
  pollInterval: Duration,
  probe: LifecycleProbe = LifecycleProbe.none
) {
  import OrphanedProviders.Entry

  // An entry leaves the list once it is settled, so a settle still in progress can be waited for.
  private val entries = new AtomicReference[List[Entry]](Nil)

  /** Watch `incomingState`, the state of the provider that replaced `previous` in a swap that timed out. */
  def record(
    previous: FeatureProvider,
    previousName: String,
    incomingName: String,
    incomingState: () => ProviderState
  ): UIO[Unit] =
    for {
      done <- Promise.make[Nothing, Unit]
      entry = new Entry(previous, previousName, incomingName, incomingState, done)
      _ <- ZIO.succeed(entries.updateAndGet(entry :: _))
      // A watcher that starts after a resolve has claimed the entry ends at its first tick.
      fiber <- watch(entry).forkDaemon
      _     <- ZIO.succeed(entry.watcher.set(Some(fiber)))
    } yield ()

  /** The name of the provider whose pending initialization still holds `provider`, if any. Installing `provider` again
    * meanwhile would hand it a second SDK registration, and the first one's settling would shut it down while it
    * serves. Waits for a settle already in progress.
    */
  def pendingFor(provider: FeatureProvider): UIO[Option[String]] =
    ZIO
      .foreach(entries.get.filter(_.previous eq provider)) { entry =>
        if (entry.claimed.get()) entry.done.await.as(None) else ZIO.some(entry.incomingName)
      }
      .map(_.flatten.headOption)

  /** Settle every entry now: those whose new provider has not become ready get their previous provider shut down. */
  def resolveAll: UIO[Unit] =
    ZIO.foreachDiscard(entries.get)(entry => stopWatcher(entry) *> settle(entry, forced = true).unit)

  private def watch(entry: Entry): UIO[Unit] =
    (ZIO.sleep(pollInterval) *> settle(entry, forced = false).uninterruptible).repeatUntil(identity).unit

  private def stopWatcher(entry: Entry): UIO[Unit] =
    ZIO.foreachDiscard(entry.watcher.get)(_.interrupt)

  /** Whether `entry` is settled, or being settled by another caller, once this returns. */
  private def settle(entry: Entry, forced: Boolean): UIO[Boolean] =
    ZIO
      .suspendSucceed {
        if (entry.claimed.get()) ZIO.succeed(true)
        else
          entry.incomingState() match {
            case ProviderState.READY | ProviderState.STALE => settleWith(entry, ZIO.unit)
            case _ if forced                               =>
              settleWith(
                entry,
                ZIO.logWarning(
                  s"shutting down the previous provider '${entry.previousName}': '${entry.incomingName}' replaced it " +
                    "in a swap that timed out and has not become ready"
                ) *> shutDown(entry)
              )
            case ProviderState.ERROR | ProviderState.FATAL =>
              settleWith(
                entry,
                ZIO.logWarning(
                  s"setProvider: '${entry.incomingName}' reported an error before it ever served; shutting down the " +
                    s"previous provider '${entry.previousName}', which the OpenFeature SDK no longer holds. The status " +
                    "stays Error until a later setProvider succeeds"
                ) *> shutDown(entry)
              )
            case _ => ZIO.succeed(false)
          }
      }
      .catchAllCause(c =>
        ZIO
          .logErrorCause(s"could not settle the previous provider '${entry.previousName}' of a timed-out swap", c)
          .as(true)
      )

  /** Run `release` if this caller is the one to settle `entry`; true either way. */
  private def settleWith(entry: Entry, release: UIO[Unit]): UIO[Boolean] =
    ZIO.succeed(entry.claimed.compareAndSet(false, true)).flatMap { won =>
      ZIO.when(won)(
        probe.at(LifecycleProbe.Point.OrphanResolved) *> release
          .ensuring(ZIO.succeed(entries.updateAndGet(_.filterNot(_ eq entry))) *> entry.done.succeed(()))
      )
    } *> ZIO.succeed(true)

  // What the SDK's own `shutDownOld` does: release it from the global registry, shut it down and detach its events,
  // which also lets it be registered again.
  private def shutDown(entry: Entry): UIO[Unit] =
    ProviderRegistration.boundedProviderShutdown(
      entry.previous,
      "setProvider (previous provider of a timed-out swap)"
    ) *>
      ZIO
        .attemptBlocking {
          EventProviderAccess.deregisterGlobalProvider(api, entry.previous)
          EventProviderAccess.detach(entry.previous)
        }
        .catchAllCause(c =>
          ZIO.logWarningCause(
            s"setProvider: releasing '${entry.previousName}' from the OpenFeature API failed; registering it again " +
              "may fail with 'already attached'",
            c
          )
        )
}

private[openfeature] object OrphanedProviders {

  val PollInterval: Duration = 250.millis

  def apply(api: OpenFeatureAPI, probe: LifecycleProbe = LifecycleProbe.none): OrphanedProviders =
    new OrphanedProviders(api, PollInterval, probe)

  final private class Entry(
    val previous: FeatureProvider,
    val previousName: String,
    val incomingName: String,
    val incomingState: () => ProviderState,
    val done: Promise[Nothing, Unit]
  ) {
    val claimed = new AtomicBoolean(false)
    val watcher = new AtomicReference[Option[Fiber.Runtime[Nothing, Unit]]](None)
  }
}
