package zio.openfeature.internal

import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider}
import zio._
import zio.openfeature.ProviderStatus

/** Named points in the provider lifecycle where a test can hold a fiber and run a competing action before letting it
  * go. Production instances use [[LifecycleProbe.none]], whose `at` is a constant no-op.
  *
  * The points name the steps of the lifecycle protocol: a swap, an SDK event reaching the bridge, a status write, the
  * init watchdog, a shutdown and the scope close; prefixed `Acquire`, the steps of `fromAcquireAsync`'s hand-off of the
  * provider `acquire` returns, once per attempt or per release; and the steps several instances on one API take (a
  * build's binding, a failure's decision, an owner retiring the others, the registry's promises). The probe is handed
  * only to the lifecycle collaborators: `FeatureFlagsLive` for the swap and shutdown, `EventBridge`,
  * `OrphanedProviders`, the builds for the watchdog, the scope finalizer and the hand-off, and the registry.
  * `Evaluator` and `HookPipeline` never receive it, which keeps it off the evaluation path; `FeatureFlagsLive`'s
  * evaluation methods only delegate to the `Evaluator`.
  */
private[openfeature] trait LifecycleProbe {
  def at(point: LifecycleProbe.Point): UIO[Unit]

  /** Runs `effect` and records the point it yields, as one step: a recorder that takes a lock here records the point in
    * the order the effects happened, though several instances run them at once. Production and test probes that do not
    * record just run the effect, then `at`.
    */
  def step[A](effect: UIO[A])(point: A => LifecycleProbe.Point): UIO[A] = effect.tap(a => at(point(a)))
}

private[openfeature] object LifecycleProbe {

  sealed trait Point extends Product with Serializable {

    /** The point's name, without its payload. */
    def name: String = productPrefix
  }

  object Point {

    /** `setProvider` holds the swap lock and has passed the orphan check; the swap gate is not up yet. Carries
      * `provider`, the provider being installed.
      */
    final case class SwapStart(provider: OFFeatureProvider) extends Point

    /** `setProvider` holds the swap lock and is about to wait, bounded, for the SDK's shutdown of the provider it was
      * given: a registration that replaced that provider has returned, and the shutdown it led to has not finished.
      */
    case object AwaitSdkShutDown extends Point

    /** `setProvider` was given the provider the slot already binds and returns without a swap. */
    case object SwapSkipped extends Point

    /** A swap's start step has run and admitted the new provider; the SDK does not bind it yet. */
    case object SwapBind extends Point

    /** The SDK registration has returned and binds the new provider; its initialization is in flight. */
    case object SdkBind extends Point

    /** The wait for the new provider reached `initTimeout`; no outcome signal has been applied yet. */
    case object SwapTimeout extends Point

    /** The new provider failed to initialize and `SwapFailed` is applied; the previous provider is not restored yet. */
    case object SwapRollback extends Point

    /** The rollback's registration has returned and binds the previous provider again; its wait has not started. */
    case object RollbackBind extends Point

    /** The rollback's registration of the previous provider was refused before it bound, and the slot still binds the
      * failed provider: about to install that one again.
      */
    case object RollbackRefused extends Point

    /** The swap has its outcome and the gate is down; about to re-read the slot for a state the gate dropped. Inside
      * the swap's protected region, with the swap lock held.
      */
    case object SwapReconcile extends Point

    /** The swap has its outcome and the generation gate is about to drop, on every exit of the swap. */
    case object GateDown extends Point

    /** The swap has its outcome and the gate is down; the swap lock is still held. */
    case object SwapDone extends Point

    /** After a timed-out swap, about to re-read the SDK's state for a READY that arrived while the gate was up. */
    case object LateReady extends Point

    /** A timed-out or failed swap is about to record the provider it replaced as pending: recorded before the entry is
      * published, so before the entry's watcher can settle it.
      */
    case object OrphanRecorded extends Point

    /** A pending previous provider is about to be settled: shut down, or released to the SDK. Carries `previous`, the
      * provider the entry holds, and `watch`, the provider whose registration the entry waited on.
      */
    final case class OrphanResolved(previous: OFFeatureProvider, watch: OFFeatureProvider) extends Point

    /** A pending previous provider has left the list, after it was shut down or released to the SDK: the settle that
      * began at [[OrphanResolved]] is over. Carries the same `previous` and `watch`.
      */
    final case class OrphanDropped(previous: OFFeatureProvider, watch: OFFeatureProvider) extends Point

    /** An event the SDK delivered reached the bridge, on the SDK's handler thread. Carries the event's name and the
      * name of the provider it came from.
      */
    final case class DeliverEvent(event: String, provider: String) extends Point

    /** A status signal is about to be applied, outside the serialized write. */
    final case class ApplySignal(signal: ProviderStatusMachine.Signal) extends Point

    /** The bridge's status write moved the status, recorded once per transition with the new status. */
    final case class StatusWritten(status: ProviderStatus) extends Point

    /** A registered provider's `initialize()` was entered (by the SDK, on its executor), before it runs. */
    case object InitEntered extends Point

    /** A registered provider's `initialize()` returned or threw; its outcome is recorded. */
    case object InitReturned extends Point

    /** A registered provider's `initialize()` ended with `ok` (it returned, or threw), recorded just before the outcome
      * is published to whoever waits on it, so that whoever observes the outcome records its next point after this one.
      * Carries `provider`, the one that was initialized.
      */
    final case class InitOutcome(provider: OFFeatureProvider, ok: Boolean) extends Point

    /** A registered provider's `shutdown()` is about to run, for the first time in its cycle; `call` numbers the call,
      * so that its return can be told from another call's. Carries `provider`, the one shut down, and `caller`, the
      * name of the thread running it: the SDK's executor for its queued shutdown of a replaced provider and for the
      * API's own teardown, else the library's.
      */
    final case class ProviderShutDown(call: Long, provider: OFFeatureProvider, caller: String) extends Point

    /** The `shutdown()` call `call` returned (`ok`) or threw. Not reached while it hangs: the caller's bound elapses
      * without it.
      */
    final case class ShutdownReturned(call: Long, ok: Boolean) extends Point

    /** The async build's init watchdog woke up, before it escalates. */
    case object Watchdog extends Point

    /** `shutdown` holds the swap lock; the status has not moved yet. */
    case object ShutdownStart extends Point

    /** `shutdown` has torn the API and pending providers down; `ShutdownCompleted` is not applied yet. */
    case object ShutdownDone extends Point

    /** The API's scope finalizer started. */
    case object ScopeClose extends Point

    // The steps of several instances on one API (formal/registry): a build binding its slot, a failure deciding about
    // its provider, an owner retiring the others, and the registry's promise per domain.

    /** A build has bound `provider` to `domain`'s slot: the SDK holds it there. `owns` when the instance owns its API,
      * `async` when the build returns without waiting for the provider's initialization.
      */
    final case class BuildBind(provider: OFFeatureProvider, domain: Option[String], owns: Boolean, async: Boolean)
        extends Point

    /** A failure under this instance has marked `provider` as failed on it, before looking at its siblings. */
    final case class FailedRegistration(provider: OFFeatureProvider) extends Point

    /** The failure under this instance has decided whether the library shuts `provider` down (`shut`); the shutdown has
      * not run yet.
      */
    final case class FailDecided(provider: OFFeatureProvider, shut: Boolean) extends Point

    /** An instance that owns its API is about to retire every other instance on it, before tearing the API down. */
    case object RetireSiblings extends Point

    /** The registry has inserted `domain`'s promise under its lock: its first caller builds the client. */
    final case class RegistryInsert(domain: Option[String]) extends Point

    /** The registry is completing `domain`'s promise with the client its build returned, under its lock: no caller has
      * that client yet.
      */
    final case class RegistryComplete(domain: Option[String]) extends Point

    /** The registry's finalizer is about to retire its clients and shut its API down. */
    case object ApiShutdown extends Point

    // fromAcquireAsync's hand-off of the provider `acquire` returns. `attempt` is the attempt's own scope, which tells
    // one attempt from another.

    /** An attempt has its scope; `acquire` has not run yet. */
    final case class AcquireAttemptStart(attempt: Scope) extends Point

    /** The attempt's `verify` failed, rather than being interrupted. */
    final case class AcquireVerifyFailed(attempt: Scope) extends Point

    /** The attempt is accepted: `provider` is owned and the attempt's scope is the candidate. */
    final case class AcquireAccepted(attempt: Scope, provider: OFFeatureProvider) extends Point

    /** `verify` failed or was interrupted before the attempt was abandoned; the attempt's fiber has shut `provider`
      * down and closed the attempt's scope.
      */
    final case class AcquireRejected(attempt: Scope, provider: OFFeatureProvider) extends Point

    /** The attempt's `acquire` failed or was interrupted, and its scope has closed. */
    final case class AcquireFailed(attempt: Scope) extends Point

    /** The attempt was given up, by its timeout or by the layer's release (`interrupted`): before its provider arrived,
      * while `verify` ran, or after `verify` passed. Also recorded once the attempt failed on its own, after
      * [[AcquireFailed]] or [[AcquireRejected]].
      */
    final case class AcquireAbandoned(attempt: Scope, interrupted: Boolean) extends Point

    /** `provider` arrived, or its `verify` returned, for an abandoned attempt, or the attempt was abandoned after
      * `verify` passed; it has been shut down and the attempt's scope closed.
      */
    final case class AcquireLateArrival(attempt: Scope, provider: OFFeatureProvider) extends Point

    /** `compose` returned `stack`, which is owned now. */
    final case class AcquireComposed(stack: OFFeatureProvider) extends Point

    /** The stack's `initialize()` and its state check ended, `ok` or not. */
    final case class AcquireStackInit(ok: Boolean) extends Point

    /** The switch to the stack returned: `ok` when it serves now. */
    final case class AcquireSwitched(ok: Boolean) extends Point

    /** What was owned after a failure has been shut down, and the candidate closed. */
    case object AcquireDiscarded extends Point

    /** The layer's release has interrupted the construct fiber, and the fiber has ended. */
    case object AcquireReleaseInterrupted extends Point

    /** The layer's release has shut down `owned`, what the library still owned, and closed the candidate. */
    final case class AcquireReleaseOwned(owned: Option[OFFeatureProvider]) extends Point
  }

  val none: LifecycleProbe = new LifecycleProbe {
    def at(point: Point): UIO[Unit] = ZIO.unit
  }
}
