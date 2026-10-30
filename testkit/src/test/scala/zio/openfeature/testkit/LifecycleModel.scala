package zio.openfeature.testkit

import zio._
import zio.openfeature._

/** The documented contract of the provider lifecycle, as a model a test can step through: what the status is, which
  * provider answers, and what an evaluation returns after each action.
  *
  * It is written from `docs/lifecycle.md` ("What changes the status") and `docs/providers.md` ("Replace the provider at
  * runtime"), NOT from `ProviderStatusMachine`: copying the code would only check the code against itself. Where the
  * docs say nothing (which provider `providerMetadata` names while a swap is parked, how an evaluation in `Error`
  * ends), the model asserts nothing.
  */
object LifecycleModel {

  // Actions

  /** How a provider handed to `setProvider` behaves. */
  sealed trait Kind extends Product with Serializable

  object Kind {
    case object Good        extends Kind
    case object FailingInit extends Kind

    /** The first `initialize()` parks until [[Action.ReleaseHangs]] (ignoring `shutdown()`); later ones succeed. */
    case object HangingInit extends Kind

    /** `initialize()` takes a few hundred milliseconds, well inside the init timeout. */
    case object SlowInit extends Kind

    /** The first `initialize()` parks until [[Action.ReleaseHangs]], then throws; every later one throws at once. */
    case object HangThenFail extends Kind

    /** Starts at once; `shutdown()` takes a few hundred milliseconds. */
    case object SlowShutdown extends Kind
  }

  sealed trait Action extends Product with Serializable

  object Action {

    /** Install a new provider. With `reuseName` it reports the metadata name of the provider it replaces. */
    final case class SetProvider(kind: Kind, reuseName: Boolean = false) extends Action

    /** The provider reports READY: the installed provider, or during a parked swap the provider being replaced. */
    case object EmitReady                      extends Action
    final case class EmitError(fatal: Boolean) extends Action
    case object EmitStale                      extends Action

    /** The provider most recently replaced reports `event` (one of the three above). */
    final case class EmitFromReplaced(event: Action) extends Action

    case object Shutdown extends Action

    /** Eight concurrent typed evaluations, each checked against the model. */
    case object Evaluate extends Action

    /** Interrupt the fiber running a parked swap (no-op without one). */
    case object InterruptSwap extends Action

    /** Unpark every hung `initialize()`. */
    case object ReleaseHangs extends Action

    /** Hand `setProvider` the provider a timed-out swap replaced, while that swap is still pending (no-op otherwise).
      */
    case object ReinstallPrevious extends Action

    /** Run two actions at once. */
    final case class Both(first: Action, second: Action) extends Action

    def isEmit(action: Action): Boolean = action match {
      case EmitReady | EmitError(_) | EmitStale => true
      case _                                    => false
    }
  }

  // The model, from docs/lifecycle.md and docs/providers.md

  /** A provider as the model sees it: `id` is what it answers for the `who` flag, `name` its metadata name. */
  final case class Installed(id: String, name: String)

  /** @param installed
    *   the provider that answers and that `providerMetadata` names; None where the docs do not say
    * @param previous
    *   during a parked swap, the provider it replaces
    * @param swap
    *   the provider a parked swap is installing, and its kind
    * @param parked
    *   ids of providers whose first `initialize()` is still parked
    * @param failing
    *   ids of providers whose `initialize()` always throws (a parked one once released)
    * @param orphans
    *   ids of providers a timed-out swap replaced, until that swap's provider settles
    * @param swapCompleted
    *   a swap has finished, with any outcome
    */
  final case class Model(
    status: ProviderStatus,
    installed: Option[Installed],
    previous: Option[Installed],
    swap: Option[(Installed, Kind)],
    parked: Set[String],
    failing: Set[String],
    orphans: Set[String],
    swapCompleted: Boolean,
    shutDown: Boolean
  ) {
    import ProviderStatus._

    /** `status.canEvaluate`: an evaluation runs and the provider answers. */
    def serving: Boolean = status == Ready || status == Stale

    // "Events that arrive during a swap, or after a shutdown, do not change the status."
    private def quiet: Boolean = swap.nonEmpty || shutDown

    // "The provider reports READY: NotReady, Stale or Error -> Ready"
    def onReady: Model =
      if (quiet) this
      else
        status match {
          case NotReady | Stale | Error => copy(status = Ready)
          case _                        => this
        }

    // "ERROR: NotReady, Ready or Stale -> Error"; "ERROR with code ProviderFatal: any status except ShuttingDown -> Fatal"
    def onError(fatal: Boolean): Model =
      if (quiet) this
      else if (fatal) { if (status == ShuttingDown) this else copy(status = Fatal) }
      else
        status match {
          case NotReady | Ready | Stale => copy(status = Error)
          case _                        => this
        }

    // "a STALE event, or the SDK reporting STALE for the provider, from NotReady, Ready or Error"
    def onStale: Model =
      if (!quiet && (status == NotReady || status == Ready || status == Error)) copy(status = Stale) else this

    /** A parked swap reaches `initTimeout`: "the status becomes Error. This swap is not rolled back: the new provider
      * stays registered". The provider it replaced is shut down once the new one settles ("If it fails instead ... the
      * old provider ... is shut down"), and `setProvider` refuses it until then. After a shutdown nothing moves the
      * status ("evaluations fail from then on").
      */
    def settle: Model = swap match {
      case None                => this
      case Some(_) if shutDown => copy(swap = None, previous = None, installed = None, swapCompleted = true)
      case Some((incoming, _)) =>
        copy(
          status = Error,
          installed = Some(incoming),
          swap = None,
          previous = None,
          swapCompleted = true,
          orphans = orphans ++ previous.map(_.id)
        )
    }

    /** A failed swap's rollback: the previous provider is registered again, which initializes it again (even while its
      * first `initialize()` is still parked) and fails when that throws. Either way the previous provider stays the one
      * the instance reports, and the one a later failed swap rolls back to.
      */
    def rollback: Model =
      if (installed.exists(i => failing(i.id))) copy(status = Error, swapCompleted = true)
      else copy(status = Ready, swapCompleted = true)

    /** A provider still parked in `initialize()`. Its events are not generated: a provider that reports a state before
      * its `initialize()` returns is taken at its word ("Replace the provider at runtime"), so an ERROR from it shuts
      * down the provider its swap replaced while its initialization may still succeed.
      */
    def silent(id: String): Boolean = parked(id)

    /** The model after `action`. `incoming` is the provider a [[Action.SetProvider]] hands in. [[Action.Both]] is not a
      * step: the harness checks it against the serial orders of its two actions.
      */
    def step(action: Action, incoming: Installed): Model = action match {
      case Action.SetProvider(kind, _) =>
        // "Swaps are serialized": a swap waits for a parked one to settle first.
        val m = settle
        kind match {
          // "On success, the status becomes Ready and the new provider serves."
          case Kind.Good | Kind.SlowInit | Kind.SlowShutdown =>
            m.copy(status = Ready, installed = Some(incoming), swapCompleted = true)
          // "If the new provider's initialize() throws ... the previous provider is registered again, and the status
          // returns to Ready", unless "that fails too", and then "the status stays Error".
          case Kind.FailingInit => m.rollback
          // "The status becomes NotReady" while the SDK initializes the new provider.
          case Kind.HangingInit | Kind.HangThenFail =>
            m.copy(
              status = NotReady,
              previous = m.installed,
              installed = None,
              swap = Some((incoming, kind)),
              parked = m.parked + incoming.id,
              failing = if (kind == Kind.HangThenFail) m.failing + incoming.id else m.failing
            )
        }
      case Action.EmitReady        => onReady
      case Action.EmitError(fatal) => onError(fatal)
      case Action.EmitStale        => onStale
      // "An event from a provider that is no longer installed does not change the status."
      case Action.EmitFromReplaced(_) => this
      // "A shutdown called while a provider swap is in flight waits for that swap to finish first"; then "Shutdown
      // moves the status to ShuttingDown and then NotReady, and evaluations fail from then on." It also shuts down a
      // provider a timed-out swap replaced.
      case Action.Shutdown => settle.copy(status = NotReady, shutDown = true, orphans = Set.empty)
      case Action.Evaluate => this
      // "Once a swap has started it finishes, with one of the outcomes above, even if the calling fiber is interrupted"
      case Action.InterruptSwap => this
      // "Swaps are serialized", so a parked swap settles first; then "Until the new provider has settled, setProvider
      // refuses the old provider with ProviderInitializationFailed".
      case Action.ReinstallPrevious => settle
      case Action.ReleaseHangs      =>
        val afterSwap = swap match {
          case None                => this
          case Some(_) if shutDown =>
            copy(swap = None, previous = None, installed = None, swapCompleted = true)
          // initialize() throws within initTimeout: the previous provider is registered again. Its own parked
          // initialize() was released too, so the rollback fails only if that one throws as well.
          case Some((_, Kind.HangThenFail)) =>
            val restored = previous.exists(p => !failing(p.id))
            copy(
              status = if (restored) Ready else Error,
              installed = previous,
              swap = None,
              previous = None,
              swapCompleted = true
            )
          // initialize() finishes within initTimeout: "On success, the status becomes Ready and the new provider serves."
          case Some((started, _)) =>
            copy(status = Ready, installed = Some(started), swap = None, previous = None, swapCompleted = true)
        }
        // A timed-out provider that is still installed: "the status becomes Ready on its own if it finishes later"
        // (the SDK reports READY for it); one that fails instead leaves the status Error.
        val lateReady = afterSwap.installed.exists { i =>
          parked(i.id) && !failing(i.id) && !swap.exists(_._1.id == i.id)
        }
        // Every timed-out swap's provider has now settled, so the providers they replaced are shut down.
        (if (lateReady) afterSwap.onReady else afterSwap).copy(parked = Set.empty, orphans = Set.empty)
      case Action.Both(_, _) => this
    }
  }

  object Model {
    def initial(provider: Installed): Model =
      Model(
        ProviderStatus.Ready,
        Some(provider),
        previous = None,
        swap = None,
        parked = Set.empty,
        failing = Set.empty,
        orphans = Set.empty,
        swapCompleted = false,
        shutDown = false
      )
  }

  /** What the instance reported after a step. `who` is the evaluated `who` flag: the id of the provider that answered.
    */
  final case class Observation(
    status: ProviderStatus,
    providerName: String,
    typed: Either[FeatureFlagError, Boolean],
    orDefault: Exit[Nothing, Boolean],
    who: Either[FeatureFlagError, String]
  )

  /** Whether an evaluation of the `who` flag may return `result` in `model`. */
  def whoAllowed(model: Model, result: Either[FeatureFlagError, String]): Boolean = {
    import ProviderStatus._
    def fromInstalled = model.installed.forall(i => result == Right(i.id))
    model.status match {
      case Ready | Stale => fromInstalled
      case NotReady      => result == Left(FeatureFlagError.ProviderNotReady(NotReady))
      case ShuttingDown  => result == Left(FeatureFlagError.ProviderNotReady(ShuttingDown))
      case Fatal         => result == Left(FeatureFlagError.ProviderFatal)
      // "runs: the provider usually serves its last good values or answers with an error code": the installed one.
      case Error => result.isLeft || fromInstalled
    }
  }

  /** Every invariant the observation breaks, as messages; empty when it agrees with the model. */
  def violations(model: Model, o: Observation): List[String] = {
    import ProviderStatus._
    val expectedRejection: Option[FeatureFlagError] = model.status match {
      case NotReady     => Some(FeatureFlagError.ProviderNotReady(NotReady))
      case ShuttingDown => Some(FeatureFlagError.ProviderNotReady(ShuttingDown))
      case Fatal        => Some(FeatureFlagError.ProviderFatal)
      case _            => None
    }
    List(
      (o.status != model.status) -> s"providerStatus is ${o.status}, the model says ${model.status}",
      model.installed.exists(_.name != o.providerName) ->
        s"providerMetadata names '${o.providerName}', the model says '${model.installed.fold("")(_.name)}'",
      (model.shutDown && o.status != NotReady) -> s"providerStatus is ${o.status} after shutdown",
      (o.status == NotReady && model.swap.isEmpty && model.swapCompleted && !model.shutDown) ->
        "NotReady with no swap in flight, after a swap completed",
      (model.serving && o.typed != Right(true)) -> s"typed evaluation gave ${o.typed} while ${model.status}",
      !whoAllowed(model, o.who)                 ->
        s"evaluation was answered by ${o.who}, the model says '${model.installed.fold("")(_.id)}'",
      expectedRejection.exists(e => o.typed != Left(e)) ->
        s"typed evaluation gave ${o.typed} while ${model.status}, expected Left(${expectedRejection.getOrElse("")})",
      (o.orDefault match {
        case Exit.Success(v) => (model.serving && !v) || (expectedRejection.nonEmpty && v)
        case Exit.Failure(_) => true
      }) -> s"booleanOrDefault gave ${o.orDefault} while ${model.status}"
    ).collect { case (true, message) => message }
  }
}
