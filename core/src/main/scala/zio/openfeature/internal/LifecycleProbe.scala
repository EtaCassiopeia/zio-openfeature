package zio.openfeature.internal

import zio._

/** Named points in the provider lifecycle where a test can hold a fiber and run a competing action before letting it
  * go. Production instances use [[LifecycleProbe.none]], whose `at` is a constant no-op.
  *
  * The points name the steps of the lifecycle protocol: a swap, an SDK event reaching the bridge, a status write, the
  * init watchdog, a shutdown and the scope close. The probe is handed only to the lifecycle collaborators:
  * `FeatureFlagsLive` for the swap and shutdown, `EventBridge`, `OrphanedProviders`, and the builds for the watchdog
  * and the scope finalizer. `Evaluator` and `HookPipeline` never receive it, which keeps it off the evaluation path;
  * `FeatureFlagsLive`'s evaluation methods only delegate to the `Evaluator`.
  */
private[openfeature] trait LifecycleProbe {
  def at(point: LifecycleProbe.Point): UIO[Unit]
}

private[openfeature] object LifecycleProbe {

  sealed trait Point extends Product with Serializable {

    /** The point's name, without its payload. */
    def name: String = productPrefix
  }

  object Point {

    /** `setProvider` holds the swap lock and has passed the orphan check; the swap gate is not up yet. */
    case object SwapStart extends Point

    /** The SDK registration has returned and binds the new provider; its initialization is in flight. */
    case object SdkBind extends Point

    /** The wait for the new provider reached `initTimeout`; no outcome signal has been applied yet. */
    case object SwapTimeout extends Point

    /** The new provider failed to initialize and `SwapFailed` is applied; the previous provider is not restored yet. */
    case object SwapRollback extends Point

    /** The rollback's registration has returned and binds the previous provider again; its wait has not started. */
    case object RollbackBind extends Point

    /** The swap has its outcome and the gate is down; the swap lock is still held. */
    case object SwapDone extends Point

    /** After a timed-out swap, about to re-read the SDK's state for a READY that arrived while the gate was up. */
    case object LateReady extends Point

    /** A timed-out swap recorded the provider it replaced as pending. */
    case object OrphanRecorded extends Point

    /** A pending previous provider is about to be settled: shut down, or released to the SDK. */
    case object OrphanResolved extends Point

    /** An event the SDK delivered reached the bridge, on the SDK's handler thread. */
    final case class DeliverEvent(event: String) extends Point

    /** A status signal is about to be applied, outside the serialized write. */
    final case class ApplySignal(signal: ProviderStatusMachine.Signal) extends Point

    /** The async build's init watchdog woke up, before it escalates. */
    case object Watchdog extends Point

    /** `shutdown` holds the swap lock; the status has not moved yet. */
    case object ShutdownStart extends Point

    /** `shutdown` has torn the API and pending providers down; `ShutdownCompleted` is not applied yet. */
    case object ShutdownDone extends Point

    /** The API's scope finalizer started. */
    case object ScopeClose extends Point
  }

  val none: LifecycleProbe = new LifecycleProbe {
    def at(point: Point): UIO[Unit] = ZIO.unit
  }
}
