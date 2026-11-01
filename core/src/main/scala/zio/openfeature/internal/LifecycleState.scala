package zio.openfeature.internal

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import zio._
import zio.stream.SubscriptionRef
import zio.openfeature.ProviderStatus
import zio.openfeature.internal.ProviderStatusMachine.Signal

/** The lifecycle of one instance as one record. Its fields follow the library variables of the TLA+ model
  * (`formal/lifecycle/Lifecycle.tla`), whose names they keep.
  *
  * @param status
  *   the provider status (model: `status`)
  * @param gate
  *   a swap is running, and bridge events do not move the status (model: `gate`; `swapInProgress`)
  * @param everReady
  *   the provider was ever usable: the init watchdog leaves it running
  * @param shutDown
  *   an explicit shutdown completed; its final `NotReady` is terminal (model: `shutDown`)
  * @param closed
  *   the layer's scope closed, so no swap may start (model: `closed`)
  */
final private[openfeature] case class LifecycleState(
  status: ProviderStatus,
  gate: Boolean,
  everReady: Boolean,
  shutDown: Boolean,
  closed: Boolean
) {

  /** The context the status table reads. */
  def context: ProviderStatusMachine.Context = ProviderStatusMachine.Context(everReady, gate, shutDown)
}

private[openfeature] object LifecycleState {
  val initial: LifecycleState =
    LifecycleState(status = ProviderStatus.NotReady, gate = false, everReady = false, shutDown = false, closed = false)
}

/** What moves the lifecycle: a status signal, or a step of the library's own sequences. */
sealed private[openfeature] trait LifecycleInput

private[openfeature] object LifecycleInput {

  /** A signal of the status table: an event the bridge delivered, the watchdog, or a lifecycle outcome. */
  final case class Status(signal: Signal) extends LifecycleInput

  /** The sync build's verified status, applied only while the status is still the initial `NotReady`. */
  final case class Seed(status: ProviderStatus) extends LifecycleInput

  /** The layer's scope closed (model: `ScopeClose`). */
  case object ScopeClose extends LifecycleInput

  /** Changes nothing: an SDK event that implies no signal. */
  case object Idle extends LifecycleInput

  /** A swap raises its gate (model: the `gate` part of `SwapStart`). */
  case object GateUp extends LifecycleInput

  /** A swap drops its gate, on every exit. */
  case object GateDown extends LifecycleInput
}

/** What `step` asks the interpreter to do once the record is written. */
sealed private[openfeature] trait LifecycleEffect

/** The lifecycle's transitions: one pure function from the record and an input to the next record, whether the status
  * moved, and the effects to run. The status part is [[ProviderStatusMachine.transition]].
  */
private[openfeature] object LifecycleStep {

  /** The outcome of one step. */
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

      case LifecycleInput.Idle       => unchanged(state)
      case LifecycleInput.ScopeClose => Outcome(state.copy(closed = true), None, Chunk.empty)
      case LifecycleInput.GateUp     => Outcome(state.copy(gate = true), None, Chunk.empty)
      case LifecycleInput.GateDown   => Outcome(state.copy(gate = false), None, Chunk.empty)
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
        Outcome(state.copy(status = next, everReady = ready, shutDown = shutDown), Some(next), Chunk.empty)
    }

  private def usable(status: ProviderStatus): Boolean =
    status == ProviderStatus.Ready || status == ProviderStatus.Stale

  private def unchanged(state: LifecycleState): Outcome = Outcome(state, None, Chunk.empty)
}

/** The interpreter: the one place the record is read and written.
  *
  * The status lives in `statusRef`, which the testkit may share and set directly, and every step runs under its
  * `modifyZIO`, whose semaphore makes the status write and the write of the rest of the record one atomic step. The
  * rest lives in a `Ref` nothing else touches. Inside the same step the interpreter writes the mirrors read
  * synchronously from SDK threads and the evaluator, so they never lag the record. The effects a step returns run after
  * it, never under the semaphore.
  */
final private[openfeature] class Lifecycle(
  statusRef: SubscriptionRef[ProviderStatus],
  gateMirror: AtomicBoolean
) {
  private val rest = Unsafe.unsafe(implicit u => Ref.unsafe.make(LifecycleState.initial))
  private val view = new AtomicReference[LifecycleState](LifecycleState.initial)

  /** Run `input` through `step` and write the record. */
  def run(input: LifecycleInput): UIO[LifecycleStep.Outcome] =
    runWith(_ => (input, ())).map(_._2)

  /** [[run]] with an input decided from the record under the same semaphore, alongside a value `decide` computes. */
  def runWith[A](decide: LifecycleState => (LifecycleInput, A)): UIO[(A, LifecycleStep.Outcome)] =
    statusRef.modifyZIO { status =>
      rest.get.flatMap { stored =>
        val before        = stored.copy(status = status)
        val (input, side) = decide(before)
        val outcome       = LifecycleStep.step(before, input)
        val after         = outcome.state
        rest.set(after) *> ZIO
          .succeed {
            gateMirror.set(after.gate)
            view.set(after)
          }
          .as(((side, outcome), after.status))
      }
    }

  /** The record as of the last step, for a synchronous reader. Its `status` may lag a direct write to `statusRef`. */
  def snapshot: LifecycleState = view.get()
}
