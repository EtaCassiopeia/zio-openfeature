package zio.openfeature.internal

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import dev.openfeature.sdk.{EventProviderAccess, OpenFeatureAPI}
import zio._

/** Providers that a timed-out swap left registered nowhere, until the swap's new provider settles.
  *
  * The SDK binds the new provider to the slot before its `initialize()` runs, and shuts the provider it replaced down
  * only once that `initialize()` returns normally. A swap that times out leaves the previous provider held by nothing
  * but that pending call: if it later fails, or never finishes, nothing shuts the previous provider down, and the API's
  * own shutdown cannot reach it. Each entry awaits the new registration's `initialize()` outcome, which the registered
  * [[ManagedProvider]] reports directly, and settles once:
  *   - it returned: the SDK then shuts the previous provider down (`shutDownOld`, on its own executor), which the
  *     wrapper signals ([[ManagedProvider.shutDownSignal]]); one the SDK has not shut down within its own executor
  *     bound, because the slot was rebound meanwhile, is shut down here. A foreign previous provider is left to the
  *     SDK.
  *   - it threw: the previous provider is shut down here.
  *   - neither when the owner shuts down or its scope closes: the previous provider is shut down here. An
  *     `initialize()` that ignores the SDK's interrupt and succeeds after that gets it shut down by the SDK as well,
  *     which its wrapper turns into a no-op.
  *
  * Only an instance that owns its API records entries: a provider of a shared API may serve sibling clients.
  */
final private[openfeature] class OrphanedProviders(api: OpenFeatureAPI, probe: LifecycleProbe = LifecycleProbe.none) {
  import OrphanedProviders.Entry

  // An entry leaves the list once it is settled, so a settle still in progress can be waited for.
  private val entries = new AtomicReference[List[Entry]](Nil)

  // Completed by `resolveAll`: cuts short a settle's wait for the SDK's own shutdown, which runs uninterruptibly.
  private val closing = new java.util.concurrent.CompletableFuture[AnyRef]()

  /** Watch `incoming`, the registration of the provider that replaced `previous` in a swap that timed out. */
  def record(
    previous: Bound,
    previousName: String,
    incomingName: String,
    incoming: ProviderRegistration.Registration
  ): UIO[Unit] =
    for {
      done <- Promise.make[Nothing, Unit]
      entry = new Entry(previous, previousName, incomingName, done)
      _ <- ZIO.succeed(entries.updateAndGet(entry :: _))
      // A watcher that starts after a resolve has claimed the entry settles nothing.
      // Settling is uninterruptible, so a resolve that interrupts the watcher waits for a settle already under way.
      fiber <- incoming.initOutcome.exit
        .flatMap(outcome => settle(entry, Some(outcome.isSuccess)).uninterruptible)
        .forkDaemon
      _ <- ZIO.succeed(entry.watcher.set(Some(fiber)))
    } yield ()

  /** The name of the provider whose pending initialization still holds `provider`, if any. Installing `provider` again
    * meanwhile would hand it a second SDK registration, and the first one's settling would shut it down while it
    * serves. Waits for a settle already in progress.
    */
  def pendingFor(provider: ManagedProvider): UIO[Option[String]] =
    ZIO
      .foreach(entries.get.filter(_.previous.sdk eq provider)) { entry =>
        if (entry.claimed.get()) entry.done.await.as(None) else ZIO.some(entry.incomingName)
      }
      .map(_.flatten.headOption)

  /** Settle every entry now: those whose new provider has not initialized get their previous provider shut down. */
  def resolveAll: UIO[Unit] =
    ZIO.succeed(closing.complete(null)) *>
      ZIO.foreachDiscard(entries.get)(entry =>
        ZIO.foreachDiscard(entry.watcher.get)(_.interrupt) *> settle(entry, initialized = None) *> entry.done.await
      )

  /** `initialized`: whether the incoming `initialize()` returned (`Some(true)`), threw (`Some(false)`), or has not
    * finished (`None`, a forced resolve).
    */
  private def settle(entry: Entry, initialized: Option[Boolean]): UIO[Unit] =
    ZIO
      .succeed(entry.claimed.compareAndSet(false, true))
      .flatMap { won =>
        ZIO.when(won)(
          probe.at(LifecycleProbe.Point.OrphanResolved) *> (initialized match {
            case Some(true) =>
              entry.previous.managed match {
                // A foreign previous provider's shutdown is the SDK's, trusted as before.
                case None    => ZIO.unit
                case Some(m) =>
                  // The SDK shuts it down right after the incoming `initialize()` returns, on its executor.
                  ZIO
                    .unless(m.shutDownInCycle)(
                      ZIO.fromCompletableFuture(
                        java.util.concurrent.CompletableFuture
                          .anyOf(m.shutDownStage, closing)
                          .completeOnTimeout(
                            null,
                            OrphanedProviders.SdkShutDownWait.toMillis,
                            java.util.concurrent.TimeUnit.MILLISECONDS
                          )
                      )
                    )
                    .ignore *>
                    ZIO.unless(m.shutDownInCycle)(
                      ZIO.logWarning(
                        s"shutting down the previous provider '${entry.previousName}': '${entry.incomingName}' " +
                          "initialized, but the OpenFeature SDK did not shut it down (the slot was bound again meanwhile)"
                      ) *> shutDown(entry)
                    )
              }
            case Some(false) =>
              ZIO.logWarning(
                s"setProvider: '${entry.incomingName}' failed to initialize before it ever served; shutting down the " +
                  s"previous provider '${entry.previousName}', which the OpenFeature SDK no longer holds. The status " +
                  "stays Error until a later setProvider succeeds"
              ) *> shutDown(entry)
            case None =>
              ZIO.logWarning(
                s"shutting down the previous provider '${entry.previousName}': '${entry.incomingName}' replaced it " +
                  "in a swap that timed out and has not finished initializing"
              ) *> shutDown(entry)
          }).ensuring(ZIO.succeed(entries.updateAndGet(_.filterNot(_ eq entry))) *> entry.done.succeed(()))
        )
      }
      .unit

  // What the SDK's own `shutDownOld` does: release it from the global registry, shut it down and detach its events,
  // which also lets it be registered again. The wrapper reaches the provider's `shutdown()` once per cycle.
  private def shutDown(entry: Entry): UIO[Unit] =
    ProviderRegistration.boundedProviderShutdown(
      entry.previous.sdk,
      "setProvider (previous provider of a timed-out swap)"
    ) *>
      ZIO
        .attemptBlocking {
          EventProviderAccess.deregisterGlobalProvider(api, entry.previous.sdk)
          EventProviderAccess.detach(entry.previous.sdk)
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

  // How long the SDK's own shutdown of a replaced provider is waited for: its executor's bound.
  private val SdkShutDownWait = 3.seconds

  def apply(api: OpenFeatureAPI, probe: LifecycleProbe = LifecycleProbe.none): OrphanedProviders =
    new OrphanedProviders(api, probe)

  final private class Entry(
    val previous: Bound,
    val previousName: String,
    val incomingName: String,
    val done: Promise[Nothing, Unit]
  ) {
    val claimed = new AtomicBoolean(false)
    val watcher = new AtomicReference[Option[Fiber.Runtime[Nothing, Unit]]](None)
  }
}
