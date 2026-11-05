package zio.openfeature.internal

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}

import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider}
import zio._
import zio.stream.SubscriptionRef
import zio.openfeature.ProviderStatus
import zio.openfeature.internal.ProviderStatusMachine.Signal

/** The lifecycle of one instance as one record. Its fields follow the library variables of the TLA+ model
  * (`formal/lifecycle/Lifecycle.tla`), whose names they keep.
  *
  * @param status
  *   the provider status (model: `status`)
  * @param installed
  *   the provider the instance reports and evaluates through, as the SDK binds it (model: `installed`)
  * @param name
  *   what the instance knows of the installed provider's name, mirrored for the bridge and the hook pipeline, which
  *   read it synchronously; not a variable of the model
  * @param generation
  *   moves when a swap makes the status NotReady, before it touches the SDK: an evaluation that saw it move across its
  *   provider call does not trust the answer
  * @param lock
  *   which sequence holds the swap lock, which serializes them (model: `lock`)
  * @param gate
  *   a swap is running, and bridge events do not move the status (model: `gate`)
  * @param swap
  *   the swap in flight: the provider it installs, the one it replaced and the status before it (model: `swap`)
  * @param everReady
  *   the provider was ever usable: the init watchdog leaves it running
  * @param shutDown
  *   an explicit shutdown completed; its final `NotReady` is terminal (model: `shutDown`)
  * @param closed
  *   the layer's scope closed, so no swap may start (model: `closed`)
  * @param deferredInitTimeout
  *   an init-watchdog deadline that fell inside a swap, whose gate drops it: replayed when the swap ends on that
  *   provider
  * @param replaced
  *   registrations of a swap or rollback, each with the provider it replaced, until the SDK has nothing left to do to
  *   that provider. Once such a registration's `initialize()` returns, the SDK queues the replaced provider's shutdown
  *   on its executor, so that provider is refused until the call returns, and a swap to it then waits for the shutdown
  *   (model: `prevOf` with `inits`, and `oldPending`)
  * @param orphans
  *   providers a timed-out swap or rollback left behind, each with the name of the provider whose pending
  *   initialization holds it, until that entry settles; refused until then (model: `orphans`)
  */
final private[openfeature] case class LifecycleState(
  status: ProviderStatus,
  installed: OFFeatureProvider,
  name: ProviderName,
  generation: Long,
  lock: Lock,
  gate: Boolean,
  swap: Option[SwapInFlight],
  everReady: Boolean,
  shutDown: Boolean,
  closed: Boolean,
  deferredInitTimeout: Option[(OFFeatureProvider, Duration)],
  replaced: List[(ProviderRegistration.Registration, ManagedProvider)],
  orphans: List[(OFFeatureProvider, String)]
) {

  /** The context the status table reads. */
  def context: ProviderStatusMachine.Context = ProviderStatusMachine.Context(everReady, gate, shutDown)
}

private[openfeature] object LifecycleState {

  /** A built instance before its provider has settled. */
  def initial(installed: OFFeatureProvider, name: ProviderName): LifecycleState =
    LifecycleState(
      status = ProviderStatus.NotReady,
      installed = installed,
      name = name,
      generation = 0L,
      lock = Lock.Free,
      gate = false,
      swap = None,
      everReady = false,
      shutDown = false,
      closed = false,
      deferredInitTimeout = None,
      replaced = Nil,
      orphans = Nil
    )
}

/** Which of the library's sequences holds the swap lock (model: `lock`). */
sealed private[openfeature] trait Lock

private[openfeature] object Lock {
  case object Free     extends Lock
  case object Swap     extends Lock
  case object Shutdown extends Lock
}

/** A swap in flight (model: `swap`): the provider it installs, the one it replaced, and the status before it. */
final private[openfeature] case class SwapInFlight(
  incoming: OFFeatureProvider,
  previous: OFFeatureProvider,
  before: ProviderStatus
)

/** What moves the lifecycle: a status signal, or a step of the library's own sequences. */
sealed private[openfeature] trait LifecycleInput

private[openfeature] object LifecycleInput {

  /** A signal of the status table: an event the bridge delivered, the watchdog, or a lifecycle outcome. */
  final case class Status(signal: Signal) extends LifecycleInput

  /** The sync build's verified status, applied only while the status is still the initial `NotReady`. */
  final case class Seed(status: ProviderStatus) extends LifecycleInput

  /** A test sets the status, as a provider report would, from any status: refused on a shut-down or closed instance,
    * and while a swap's gate is up.
    */
  final case class Override(status: ProviderStatus) extends LifecycleInput

  /** A swap starts (model: `SwapStart`, once admitted): the gate goes up before the status becomes NotReady, the
    * generation moves, and `incoming` is installed under `name`.
    */
  final case class SwapStart(incoming: OFFeatureProvider, name: ProviderName) extends LifecycleInput

  /** `fromAcquireAsync` asks to point `switch`, its switchable wrapper, at a provider named `name`: refused on a
    * shut-down or closed instance, and once a swap has installed another provider. Not an SDK registration, so the
    * status, the generation and the installed provider stay as they are; only the name changes.
    */
  final case class Switch(switch: OFFeatureProvider, name: ProviderName) extends LifecycleInput

  /** A failed swap installs `provider`, the one it replaced, under `name` again; a failed switch restores its name. */
  final case class Reinstall(provider: OFFeatureProvider, name: ProviderName) extends LifecycleInput

  /** `provider`, having initialized, reads as `name`: written only while `provider` is still installed and the name is
    * `Pending`. The read happens outside the step, so a swap may have installed another provider since.
    */
  final case class NameRead(provider: OFFeatureProvider, name: ProviderName) extends LifecycleInput

  /** A swap asks to install `incoming` (`managed`, its wrapper, when it has one): refused on a shut-down or closed
    * instance, and for a provider whose earlier registration is unsettled (model: the guards of `SwapStart`).
    * `initializing` tells whether a wrapper's `initialize()` runs now, `holderInitializing` whether a registration's
    * own does, and `released` whether a registration has nothing left to do to the provider it replaced.
    */
  final case class Admit(
    incoming: OFFeatureProvider,
    managed: Option[ManagedProvider],
    initializing: ManagedProvider => Boolean,
    holderInitializing: ProviderRegistration.Registration => Boolean,
    released: (ProviderRegistration.Registration, ManagedProvider) => Boolean
  ) extends LifecycleInput

  /** A timed-out swap or rollback left `previous` behind, held by the pending initialization of `incoming` (model: the
    * entry `SwapTimeout` and `SwapFailNoRollback` add to `orphans`).
    */
  final case class OrphanRecorded(previous: OFFeatureProvider, incoming: String) extends LifecycleInput

  /** The entry for `previous` settled (model: `OrphanResolved`). */
  final case class OrphanSettled(previous: OFFeatureProvider) extends LifecycleInput

  /** `holder` replaced `previous` in a swap or a rollback: the SDK shuts `previous` down through it once its
    * `initialize()` returns. Only wrappers are tracked: a foreign provider's initialization is the SDK's, which reports
    * no outcome.
    */
  final case class Replaced(holder: ProviderRegistration.Registration, previous: Bound) extends LifecycleInput

  /** The wait [[LifecycleEffect.AwaitSdkShutDown]] asked for ended, or its bound elapsed: nothing still holds
    * `provider` back.
    */
  final case class SdkShutDownAwaited(provider: ManagedProvider) extends LifecycleInput

  /** A swap drops its gate, on every exit. */
  case object GateDown extends LifecycleInput

  /** The init watchdog's deadline for `provider` passed. */
  final case class WatchdogFired(provider: OFFeatureProvider, timeout: Duration) extends LifecycleInput

  /** A swap ended: a deadline its gate deferred is replayed when the swap ended on that provider. */
  case object ReplayDeferred extends LifecycleInput

  /** The layer's scope closed (model: `ScopeClose`). */
  case object ScopeClose extends LifecycleInput

  /** Changes nothing: an SDK event that implies no signal, or a read. */
  case object Idle extends LifecycleInput
}

/** What `step` asks the interpreter to do once the record is written. */
sealed private[openfeature] trait LifecycleEffect

private[openfeature] object LifecycleEffect {

  /** The watchdog made a provider that never became usable Fatal: publish the terminal error, release the onReady
    * latch, and shut the provider down, in that order.
    */
  final case class EscalateInitTimeout(provider: OFFeatureProvider, timeout: Duration) extends LifecycleEffect

  /** The swap is refused: the caller fails, and the record is unchanged. */
  final case class Refuse(refusal: Refusal) extends LifecycleEffect

  /** The swap is admitted, but a registration that replaced `provider` has returned, so the SDK has queued `provider`'s
    * shutdown on its executor, with no ordering against what the swap does next: started again now, `provider` would
    * receive it in its new cycle, while serving. The swap waits for it first, then reports
    * [[LifecycleInput.SdkShutDownAwaited]].
    */
  final case class AwaitSdkShutDown(provider: ManagedProvider) extends LifecycleEffect
}

/** Why a swap, a switch or a status override is refused. */
sealed private[openfeature] trait Refusal

private[openfeature] object Refusal {

  /** The instance shut down, or its scope closed: it is never revived. */
  case object Closed extends Refusal

  /** An earlier `initialize()` of `provider` has not returned: the SDK would run a second one alongside it. */
  final case class StillInitializing(provider: OFFeatureProvider) extends Refusal

  /** The provider of a timed-out swap or rollback, `incoming`, still holds `provider`, and shuts it down if it
    * initializes.
    */
  final case class PendingOrphan(provider: OFFeatureProvider, incoming: String) extends Refusal

  /** `holder`'s registration, still initializing, replaced `provider`, and its return shuts `provider` down. */
  final case class HeldBy(provider: OFFeatureProvider, holder: OFFeatureProvider) extends Refusal

  /** A switch of `provider`, which a swap has since replaced as the installed provider. */
  final case class NotInstalled(provider: OFFeatureProvider) extends Refusal

  /** A status override while a swap's gate is up: the swap owns the status until it ends. */
  case object SwapInFlight extends Refusal
}

/** The lifecycle's transitions: one pure function from the record and an input to the next record, whether the status
  * moved, and what the interpreter does next. The status part is [[ProviderStatusMachine.transition]].
  */
private[openfeature] object LifecycleStep {

  /** The outcome of one step: the next record, the status it moved to, if it moved, and the effects to run after the
    * step.
    */
  final case class Outcome(
    state: LifecycleState,
    transitioned: Option[ProviderStatus],
    effects: Chunk[LifecycleEffect]
  )

  def step(state: LifecycleState, input: LifecycleInput): Outcome =
    input match {
      case LifecycleInput.Status(signal) => status(state, signal)

      case LifecycleInput.Seed(seed) =>
        state.status match {
          case ProviderStatus.NotReady =>
            Outcome(state.copy(status = seed, everReady = state.everReady || usable(seed)), Some(seed), Chunk.empty)
          case _ => unchanged(state)
        }

      case LifecycleInput.Override(to) =>
        if (state.shutDown || state.closed) Outcome(state, None, Chunk(LifecycleEffect.Refuse(Refusal.Closed)))
        else if (state.gate) Outcome(state, None, Chunk(LifecycleEffect.Refuse(Refusal.SwapInFlight)))
        else Outcome(state.copy(status = to, everReady = state.everReady || usable(to)), Some(to), Chunk.empty)

      case LifecycleInput.SwapStart(incoming, name) =>
        val notReady = status(state.copy(gate = true), Signal.SwapStarted)
        notReady.copy(
          state = notReady.state.copy(
            installed = incoming,
            name = name,
            generation = state.generation + 1,
            lock = Lock.Swap,
            swap = Some(SwapInFlight(incoming, state.installed, state.status))
          )
        )

      case LifecycleInput.Reinstall(provider, name) =>
        Outcome(state.copy(installed = provider, name = name), None, Chunk.empty)

      case LifecycleInput.NameRead(provider, name) =>
        if (state.name == ProviderName.Pending && (state.installed eq provider))
          Outcome(state.copy(name = name), None, Chunk.empty)
        else unchanged(state)

      case admit: LifecycleInput.Admit => this.admit(state, admit)

      case LifecycleInput.Switch(switch, name) =>
        val refusal =
          if (state.shutDown || state.closed) Some(Refusal.Closed)
          else if (!(state.installed eq switch)) Some(Refusal.NotInstalled(switch))
          else None
        refusal match {
          case Some(r) => Outcome(state, None, Chunk(LifecycleEffect.Refuse(r)))
          case None    => Outcome(state.copy(name = name), None, Chunk.empty)
        }

      case LifecycleInput.Replaced(holder, previous) =>
        (holder.bound.managed, previous.managed) match {
          case (Some(_), Some(p)) => Outcome(state.copy(replaced = (holder -> p) :: state.replaced), None, Chunk.empty)
          case _                  => unchanged(state)
        }

      case LifecycleInput.SdkShutDownAwaited(provider) =>
        Outcome(state.copy(replaced = state.replaced.filterNot(_._2 eq provider)), None, Chunk.empty)

      case LifecycleInput.OrphanRecorded(previous, incoming) =>
        Outcome(state.copy(orphans = (previous -> incoming) :: state.orphans), None, Chunk.empty)

      case LifecycleInput.OrphanSettled(previous) =>
        // One entry per settle: a provider left behind twice has two.
        val (before, from) = state.orphans.span { case (p, _) => !(p eq previous) }
        Outcome(state.copy(orphans = before ++ from.drop(1)), None, Chunk.empty)

      case LifecycleInput.GateDown =>
        Outcome(state.copy(gate = false, lock = Lock.Free, swap = None), None, Chunk.empty)

      case LifecycleInput.WatchdogFired(provider, timeout) => watchdog(state, provider, timeout)

      case LifecycleInput.ReplayDeferred =>
        state.deferredInitTimeout match {
          case Some((provider, timeout)) if provider eq state.installed =>
            watchdog(state.copy(deferredInitTimeout = None), provider, timeout)
          case Some(_) => Outcome(state.copy(deferredInitTimeout = None), None, Chunk.empty)
          case None    => unchanged(state)
        }

      case LifecycleInput.ScopeClose => Outcome(state.copy(closed = true), None, Chunk.empty)
      case LifecycleInput.Idle       => unchanged(state)
    }

  // In the order the swap checks them: what can never be revived, then the provider's own call, then what still holds it.
  // Refusing leaves the record as it was; admitting drops the replacements with nothing left to do. Refusing and asking
  // for the wait read the same record, so a holder returning between them cannot skip the wait. An entry the wait is for
  // stays until the wait ends: a caller interrupted during it leaves the next swap to wait in its place.
  private def admit(state: LifecycleState, input: LifecycleInput.Admit): Outcome = {
    val refusal =
      if (state.shutDown || state.closed) Some(Refusal.Closed)
      else
        input.managed.flatMap { m =>
          if (input.initializing(m)) Some(Refusal.StillInitializing(m))
          else
            state.orphans
              .collectFirst { case (previous, incoming) if previous eq m => Refusal.PendingOrphan(m, incoming) }
              .orElse(state.replaced.collectFirst {
                case (holder, previous)
                    if (previous eq m) && (holder.provider ne m) && input.holderInitializing(holder) =>
                  Refusal.HeldBy(m, holder.provider)
              })
        }
    refusal match {
      case Some(r) => Outcome(state, None, Chunk(LifecycleEffect.Refuse(r)))
      case None    =>
        val (own, others) = state.replaced.partition { case (_, previous) => input.managed.exists(_ eq previous) }
        val kept          = others.filterNot { case (holder, previous) => input.released(holder, previous) }
        val due           = own.exists { case (holder, previous) =>
          (holder.provider ne previous) && !input.released(holder, previous)
        }
        input.managed.filter(_ => due) match {
          case Some(m) => Outcome(state.copy(replaced = own ++ kept), None, Chunk(LifecycleEffect.AwaitSdkShutDown(m)))
          case None    => Outcome(state.copy(replaced = kept), None, Chunk.empty)
        }
    }
  }

  private def status(state: LifecycleState, signal: Signal): Outcome =
    ProviderStatusMachine.transition(state.status, signal, state.context) match {
      case None       => unchanged(state)
      case Some(next) =>
        // A provider that initialized and then reported an error became usable: the watchdog must not treat it as one
        // that never did.
        val ready = state.everReady || usable(next) || (signal match {
          case Signal.SwapSucceeded(_) | Signal.RollbackSucceeded(_) => true
          case _                                                     => false
        })
        val shutDown = state.shutDown || signal == Signal.ShutdownCompleted
        val lock     = signal match {
          case Signal.ShutdownStarted   => Lock.Shutdown
          case Signal.ShutdownCompleted => Lock.Free
          case _                        => state.lock
        }
        Outcome(
          state.copy(status = next, everReady = ready, shutDown = shutDown, lock = lock),
          Some(next),
          Chunk.empty
        )
    }

  // Escalates only a provider that never became usable; a deadline that falls inside a swap is kept for the swap to
  // replay if it ends on this provider still unready.
  private def watchdog(state: LifecycleState, provider: OFFeatureProvider, timeout: Duration): Outcome =
    status(state, Signal.InitTimeout) match {
      case moved if moved.transitioned.isDefined =>
        moved.copy(effects = Chunk(LifecycleEffect.EscalateInitTimeout(provider, timeout)))
      case same if state.gate => same.copy(state = same.state.copy(deferredInitTimeout = Some((provider, timeout))))
      case same               => same
    }

  private def usable(status: ProviderStatus): Boolean =
    status == ProviderStatus.Ready || status == ProviderStatus.Stale

  private def unchanged(state: LifecycleState): Outcome = Outcome(state, None, Chunk.empty)
}

/** The interpreter: the one place the record is read and written.
  *
  * The status lives in `statusRef`, which the testkit may share and read, and every step runs under its `modifyZIO`,
  * whose semaphore makes the status write and the write of the rest of the record one atomic step. The rest lives in a
  * `Ref` nothing else touches. The mirrors read synchronously from SDK threads and the evaluator are written so that
  * none leads the status: the gate and the provider name inside the step, where an early gate only makes the evaluator
  * stricter; the generation once the step has published the status, so an evaluation that reads a new generation also
  * reads the swap's NotReady. That write is outside the semaphore, so only the step that moved the generation makes it:
  * a swap's start, under the swap lock, so these writes cannot reorder. A write from every step could land after a
  * later swap's and move the mirror back, and `Evaluator.gated` would read that as a swap. The effects a step returns
  * run after it, never under the semaphore; `whenAdmitted` is the one thing that runs an effect under it.
  */
final private[openfeature] class Lifecycle(
  statusRef: SubscriptionRef[ProviderStatus],
  installed: OFFeatureProvider,
  gateMirror: AtomicBoolean,
  generationMirror: AtomicLong,
  nameMirror: AtomicReference[ProviderName]
) {
  private val initial = LifecycleState.initial(installed, nameMirror.get())
  private val rest    = Unsafe.unsafe(implicit u => Ref.unsafe.make(initial))
  private val view    = new AtomicReference[LifecycleState](initial)

  /** Run `input` through `step` and write the record. */
  def run(input: LifecycleInput): UIO[LifecycleStep.Outcome] =
    runWith(_ => (input, ())).map(_._2)

  /** [[run]] with an input decided from the record under the same semaphore, alongside a value `decide` computes. */
  def runWith[A](decide: LifecycleState => (LifecycleInput, A)): UIO[(A, LifecycleStep.Outcome)] =
    statusRef
      .modifyZIO { status =>
        rest.get.flatMap { stored =>
          val before        = stored.copy(status = status)
          val (input, side) = decide(before)
          val outcome       = LifecycleStep.step(before, input)
          val after         = outcome.state
          rest.set(after) *> ZIO
            .succeed {
              gateMirror.set(after.gate)
              nameMirror.set(after.name)
              view.set(after)
            }
            .as(((side, outcome, after.generation != before.generation), after.status))
        }
      }
      .flatMap { case (side, outcome, moved) =>
        ZIO.succeed(generationMirror.set(outcome.state.generation)).when(moved).as((side, outcome))
      }

  /** The record, read under the same semaphore as every step. */
  def read: UIO[LifecycleState] = runWith(state => (LifecycleInput.Idle, state)).map(_._1)

  /** Run `effect` under the semaphore every step runs under, if `admit` accepts the record, and say whether it ran. It
    * is then ordered against every step: one admitted before a swap's start has finished when that swap starts. Every
    * step waits for it, so it is only for an effect that cannot block, such as a publish to the sliding event hub.
    */
  def whenAdmitted(admit: LifecycleState => Boolean)(effect: UIO[Unit]): UIO[Boolean] =
    statusRef.modifyZIO { status =>
      rest.get.flatMap { stored =>
        if (admit(stored.copy(status = status))) effect.as((true, status)) else ZIO.succeed((false, status))
      }
    }

  /** The record as of the last step, for a synchronous reader. */
  def snapshot: LifecycleState = view.get()
}
