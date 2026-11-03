package zio.openfeature

import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters._
import scala.util.{Try, Using}

import zio._
import zio.test._

import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider, NoOpProvider, ProviderState => JavaProviderState}
import zio.openfeature.internal.{
  Bound,
  EventBridge,
  LifecycleEffect,
  LifecycleInput,
  LifecycleState,
  LifecycleStep,
  Lock,
  ManagedProvider,
  ProviderName,
  Refusal
}
import zio.openfeature.internal.ProviderRegistration.Settled
import zio.openfeature.internal.ProviderStatusMachine.Signal

/** Behaviours of the TLA+ model (`formal/lifecycle/Generate.tla`, written by `generate.py`), replayed through
  * `LifecycleStep.step`: after every step the record's library variables must equal the model's. The map from a model
  * action to the inputs the code sends for it is `formal/lifecycle/replay-map.md`; `inputsFor` is its twin. The model's
  * SDK actions send no input: they change the SDK variables the next library input reads.
  *
  * The behaviours come from the paths in `zof.lifecycle.behaviours` (a system property, or the
  * `ZOF_LIFECYCLE_BEHAVIOURS` environment variable), comma-separated files or directories of `*.ndjson`; without it
  * that suite is ignored, unless `ZOF_LIFECYCLE_BEHAVIOURS_REQUIRED` is set, when it fails. The `Formal model` workflow
  * generates them and runs this suite in its `traces` job, with that variable set so a lost property cannot pass as a
  * skip. The second suite needs no behaviours: it replays a hand-written one and checks that each comparison can fail.
  */
object LifecycleReplaySpec extends ZIOSpecDefault {

  // A small JSON reader for what TLC's Json module writes: objects, arrays, strings, integers, booleans and null.
  sealed trait Json
  object Json {
    final case class Str(value: String)             extends Json
    final case class Num(value: Long)               extends Json
    final case class Bool(value: Boolean)           extends Json
    case object Null                                extends Json
    final case class Arr(items: Vector[Json])       extends Json
    final case class Obj(fields: Map[String, Json]) extends Json

    def parse(text: String): Either[String, Json] = {
      val reader = new Reader(text)
      for {
        value <- reader.value()
        _     <- reader.end()
      } yield value
    }

    final private class Reader(text: String) {
      private var at = 0

      private def skip(): Unit = while (at < text.length && text.charAt(at).isWhitespace) at += 1

      private def fail(what: String): Either[String, Nothing] = Left(s"$what at offset $at")

      def end(): Either[String, Unit] = { skip(); if (at == text.length) Right(()) else fail("trailing text") }

      def value(): Either[String, Json] = {
        skip()
        if (at >= text.length) fail("unexpected end")
        else
          text.charAt(at) match {
            case '{'                        => at += 1; obj(Map.empty)
            case '['                        => at += 1; arr(Vector.empty)
            case '"'                        => str().map(Str(_))
            case 't'                        => literal("true", Bool(true))
            case 'f'                        => literal("false", Bool(false))
            case 'n'                        => literal("null", Null)
            case c if c == '-' || c.isDigit => num()
            case c                          => fail(s"unexpected '$c'")
          }
      }

      private def literal(word: String, value: Json): Either[String, Json] =
        if (text.startsWith(word, at)) { at += word.length; Right(value) }
        else fail(s"expected $word")

      private def num(): Either[String, Json] = {
        val start = at
        if (text.charAt(at) == '-') at += 1
        while (at < text.length && text.charAt(at).isDigit) at += 1
        if (at < text.length && (text.charAt(at) == '.' || text.charAt(at) == 'e' || text.charAt(at) == 'E'))
          fail("a number that is not an integer")
        else Try(text.substring(start, at).toLong).toOption.map(Num(_)).toRight(s"not an integer at offset $start")
      }

      private def str(): Either[String, String] = {
        at += 1
        val out = new StringBuilder
        var bad = Option.empty[String]
        while (bad.isEmpty && at < text.length && text.charAt(at) != '"')
          text.charAt(at) match {
            case '\\' if at + 1 < text.length =>
              text.charAt(at + 1) match {
                case '"'                         => out += '"'; at += 2
                case '\\'                        => out += '\\'; at += 2
                case '/'                         => out += '/'; at += 2
                case 'n'                         => out += '\n'; at += 2
                case 't'                         => out += '\t'; at += 2
                case 'r'                         => out += '\r'; at += 2
                case 'b'                         => out += '\b'; at += 2
                case 'f'                         => out += '\f'; at += 2
                case 'u' if at + 5 < text.length =>
                  Try(Integer.parseInt(text.substring(at + 2, at + 6), 16)).toOption match {
                    case Some(code) => out += code.toChar; at += 6
                    case None       => bad = Some(s"bad unicode escape at offset $at")
                  }
                case other => bad = Some(s"bad escape '\\$other' at offset $at")
              }
            case c => out += c; at += 1
          }
        bad match {
          case Some(why)                 => Left(why)
          case None if at >= text.length => fail("unterminated string")
          case None                      => at += 1; Right(out.toString)
        }
      }

      private def arr(items: Vector[Json]): Either[String, Json] = {
        skip()
        if (at < text.length && text.charAt(at) == ']') { at += 1; Right(Arr(items)) }
        else
          value().flatMap { v =>
            skip()
            if (at < text.length && text.charAt(at) == ',') { at += 1; arr(items :+ v) }
            else if (at < text.length && text.charAt(at) == ']') { at += 1; Right(Arr(items :+ v)) }
            else fail("expected , or ]")
          }
      }

      private def obj(fields: Map[String, Json]): Either[String, Json] = {
        skip()
        if (at < text.length && text.charAt(at) == '}') { at += 1; Right(Obj(fields)) }
        else if (at >= text.length || text.charAt(at) != '"') fail("expected a key")
        else
          str().flatMap { key =>
            skip()
            if (at >= text.length || text.charAt(at) != ':') fail("expected :")
            else {
              at += 1
              value().flatMap { v =>
                skip()
                if (at < text.length && text.charAt(at) == ',') { at += 1; obj(fields + (key -> v)) }
                else if (at < text.length && text.charAt(at) == '}') { at += 1; Right(Obj(fields + (key -> v))) }
                else fail("expected , or }")
              }
            }
          }
      }
    }
  }

  /** One state of a behaviour: the model's variables after `act`. */
  final case class ModelState(act: Act, vars: Map[String, Json]) {
    private def field(name: String): Json = vars.getOrElse(name, sys.error(s"no variable $name"))

    private def asStr(j: Json): String = j match {
      case Json.Str(s) => s
      case other       => sys.error(s"not a string: $other")
    }
    private def asNum(j: Json): Long = j match {
      case Json.Num(n) => n
      case other       => sys.error(s"not a number: $other")
    }
    private def str(name: String): String   = asStr(field(name))
    private def bool(name: String): Boolean = field(name) match {
      case Json.Bool(b) => b
      case other        => sys.error(s"$name is not a boolean: $other")
    }
    private def fun(name: String): Map[String, Json] = field(name) match {
      case Json.Obj(fields) => fields
      case other            => sys.error(s"$name is not a function: $other")
    }
    private def record(name: String, fields: Map[String, Json], key: String): String =
      asStr(fields.getOrElse(key, sys.error(s"$name has no field $key")))

    lazy val status: String              = str("status")
    lazy val installed: String           = str("installed")
    lazy val lock: String                = str("lock")
    lazy val gate: Boolean               = bool("gate")
    lazy val shutDown: Boolean           = bool("shutDown")
    lazy val closed: Boolean             = bool("closed")
    lazy val owns: Boolean               = bool("owns")
    lazy val bound: String               = str("bound")
    lazy val sdk: Map[String, String]    = fun("sdk").map { case (p, s) => p -> asStr(s) }
    lazy val inits: Map[String, Long]    = fun("inits").map { case (p, n) => p -> asNum(n) }
    lazy val prevOf: Map[String, String] = fun("prevOf").map { case (p, s) => p -> asStr(s) }

    /** The swap in flight: its new and previous provider, the status before it, and its phase. */
    lazy val swap: Option[(String, String, String, String)] = field("swap") match {
      case Json.Str("None") => None
      case Json.Obj(fields) =>
        Some(
          (
            record("swap", fields, "new"),
            record("swap", fields, "prev"),
            record("swap", fields, "before"),
            record("swap", fields, "phase")
          )
        )
      case other => sys.error(s"swap is neither None nor a record: $other")
    }

    /** Each orphan entry as the provider left behind and the one watched. */
    lazy val orphans: Set[(String, String)] = field("orphans") match {
      case Json.Arr(items) =>
        items.map {
          case Json.Obj(fields) =>
            (record("an orphan entry", fields, "prev"), record("an orphan entry", fields, "watch"))
          case other => sys.error(s"an orphan entry is not a record: $other")
        }.toSet
      case other => sys.error(s"orphans is not a set: $other")
    }

    /** The registrations still initializing with the provider each replaced, as the code's `replaced` holds them:
      * `timedOut` is the providers whose current registration a timed-out swap made (the replay derives it from the
      * behaviour's actions), the only ones the code records. A swap in flight is awaited rather than recorded, and a
      * swap that settled on a `STALE` reported from inside `initialize()` takes the provider at its word.
      */
    def held(timedOut: Set[String]): Set[(String, String)] =
      inits.collect { case (q, n) if n > 0 && prevOf(q) != "None" && timedOut.contains(q) => (q, prevOf(q)) }.toSet

    def lib(timedOut: Set[String]): Lib = Lib(
      status = status,
      installed = installed,
      lock = lock,
      gate = gate,
      swap = swap.map { case (p, prev, before, _) => (p, prev, before) },
      held = held(timedOut),
      orphans = orphans,
      shutDown = shutDown,
      closed = closed
    )
  }

  /** The action that produced a state, with its parameters. */
  final case class Act(name: String, params: Map[String, Json]) {
    def str(name: String): String = params.get(name) match {
      case Some(Json.Str(s)) => s
      case other             => sys.error(s"$this has no string $name: $other")
    }
    def bool(name: String): Boolean = params.get(name) match {
      case Some(Json.Bool(b)) => b
      case other              => sys.error(s"$this has no boolean $name: $other")
    }
  }

  object Act {
    def apply(name: String, params: (String, Json)*): Act = Act(name, params.toMap)
  }

  /** The library variables, as the README's variables table maps them, from either side. */
  final case class Lib(
    status: String,
    installed: String,
    lock: String,
    gate: Boolean,
    swap: Option[(String, String, String)],
    held: Set[(String, String)],
    orphans: Set[(String, String)],
    shutDown: Boolean,
    closed: Boolean
  )

  final case class Behaviour(name: String, states: List[ModelState])

  final case class Mismatch(behaviour: String, step: Int, action: String, what: String) {
    override def toString: String = s"$behaviour step $step ($action): $what"
  }

  /** What the replay found over a set of behaviours: the mismatches, how often each library action and each ordered
    * pair of consecutive library actions occurred, and how often each named case did.
    */
  final case class Replay(
    mismatches: Chunk[Mismatch],
    actions: Map[String, Int],
    pairs: Map[(String, String), Int],
    cases: Map[String, Int]
  )

  /** The code under replay: the step, and the bridge's choice of signal for a delivered event given the SDK's state for
    * the slot. The teeth tests decorate one or the other.
    */
  final case class Code(
    step: (LifecycleState, LifecycleInput) => LifecycleStep.Outcome,
    bridge: (String, String) => Option[Signal]
  )

  /** The library actions the replay must see; `CallerInterrupt` is never enabled and `Die` is off. */
  val libraryActions: Set[String] = Set(
    "SwapStart",
    "SwapDone",
    "SwapTimeout",
    "SwapFailNoRollback",
    "SwapRollback",
    "SwapRollbackUnbound",
    "SwapReconcile",
    "Deliver",
    "OrphanResolved",
    "ShutdownStart",
    "ShutdownDone",
    "ScopeClose"
  )

  /** The actions that send the step no input: the SDK's, and the caller's interrupt, which is never enabled. */
  val inputless: Set[String] = Set(
    "SdkBind",
    "SdkBindFail",
    "SdkInitOk",
    "SdkInitFail",
    "Emit",
    "EmitStaleWhileStarting",
    "Die",
    "Discard",
    "CallerInterrupt"
  )

  /** The cases the generated set must contain, counted by `casesOf` and by the refusals `guards` sees. */
  val expectedCases: Set[String] = Set(
    "async build",
    "shared API",
    "swap left behind on a shared API",
    "rollback whose registration failed",
    "rollback whose registration failed with a fatal state",
    "delivery to a slot still initializing",
    "delivery under the gate",
    "second shutdown",
    "shutdown after the scope closed",
    "provider installed again",
    "swap settled by a STALE reported while starting",
    "orphan released by the SDK",
    "orphan settled by the library",
    "teardown settling orphans",
    "refused: closed",
    "refused: still initializing",
    "refused: pending orphan",
    "refused: held"
  )

  /** The signal the bridge applies for the SDK's state of the slot, as the bridge maps it. */
  def slotSignal(slot: String): Option[Signal] =
    if (slot == "NONE") None else EventBridge.signalForSlot(JavaProviderState.valueOf(slot))

  /** The bridge as written: a fatal error as sent, every other event by the slot's state (`applySdkSignal`). */
  val realBridge: (String, String) => Option[Signal] =
    (event, slot) => if (event == "FATAL") Some(Signal.EventError(true)) else slotSignal(slot)

  val real: Code = Code(LifecycleStep.step, realBridge)

  def status(name: String): ProviderStatus = name match {
    case "NotReady"     => ProviderStatus.NotReady
    case "Ready"        => ProviderStatus.Ready
    case "Stale"        => ProviderStatus.Stale
    case "Error"        => ProviderStatus.Error
    case "Fatal"        => ProviderStatus.Fatal
    case "ShuttingDown" => ProviderStatus.ShuttingDown
    case other          => sys.error(s"not a status: $other")
  }

  def settled(state: String): Settled = state match {
    case "READY" => Settled.Ready
    case "STALE" => Settled.Stale
    case "ERROR" => Settled.Error("replay")
    case "FATAL" => Settled.Fatal("replay")
    case other   => sys.error(s"not a settled state: $other")
  }

  /** The stand-ins for the model's providers: one wrapper per name, which `Admit` and `Replaced` take. */
  final class Providers(names: Set[String]) {
    val byName: Map[String, ManagedProvider] = names.map(n => n -> ManagedProvider(new NoOpProvider())).toMap
    private val byIdentity: Map[OFFeatureProvider, String] =
      byName.map { case (n, m) => (m: OFFeatureProvider) -> n }

    def apply(name: String): ManagedProvider        = byName(name)
    def nameOf(provider: OFFeatureProvider): String =
      byIdentity.getOrElse(provider, sys.error(s"a provider the replay did not make: $provider"))
  }

  /** The inputs the code sends for a library action, in order, given the model's state before it; None for an SDK
    * action.
    */
  def inputsFor(act: Act, pre: ModelState, providers: Providers, code: Code): Option[List[LifecycleInput]] = {
    def named(p: String)                          = ProviderName.Named(p)
    def swap                                      = pre.swap.getOrElse(sys.error(s"${act.name} with no swap in flight"))
    def initializing(m: ManagedProvider): Boolean = pre.inits(providers.nameOf(m)) > 0
    def orphanRecorded(prev: String, incoming: String): List[LifecycleInput] =
      if (pre.owns) List(LifecycleInput.OrphanRecorded(providers(prev), incoming)) else Nil
    // The owner's teardown settles every entry (`OrphanedProviders.resolveAll`).
    def resolveAll: List[LifecycleInput] =
      if (pre.owns) pre.orphans.toList.map { case (prev, _) => LifecycleInput.OrphanSettled(providers(prev)) } else Nil

    act.name match {
      case "SwapStart" =>
        val p = act.str("p")
        Some(
          List(
            LifecycleInput.Admit(providers(p), Some(providers(p)), initializing),
            LifecycleInput.SwapStart(providers(p), named(p))
          )
        )
      case "SwapDone" =>
        Some(List(LifecycleInput.Status(Signal.SwapSucceeded(settled(act.str("settled"))))))
      case "SwapTimeout" =>
        val (p, prev, _, _) = swap
        Some(
          List(
            LifecycleInput.Status(Signal.SwapFailed),
            LifecycleInput.Replaced(Bound.Managed(providers(p)), Bound.Managed(providers(prev)))
          ) ++ orphanRecorded(prev, p)
        )
      // The failure branch the code takes is decided by the previous provider's wrapper count (the model's `inits`),
      // and the unbound one by the slot still binding the previous provider; the label must agree with them.
      case "SwapFailNoRollback" =>
        val (p, prev, _, _) = swap
        if (pre.inits(prev) == 0) sys.error(s"${act.name} with the previous provider's initialize() returned")
        Some(List(LifecycleInput.Status(Signal.SwapFailed)) ++ orphanRecorded(prev, p))
      case "SwapRollback" =>
        val (_, prev, _, _) = swap
        if (pre.inits(prev) > 0) sys.error(s"${act.name} with the previous provider still initializing")
        val succeeded =
          if (act.bool("ok")) List(LifecycleInput.Status(Signal.RollbackSucceeded(settled(act.str("r"))))) else Nil
        Some(
          List(LifecycleInput.Reinstall(providers(prev), named(prev)), LifecycleInput.Status(Signal.SwapFailed)) ++
            succeeded
        )
      case "SwapRollbackUnbound" =>
        val (_, prev, before, _) = swap
        if (pre.bound != prev) sys.error(s"${act.name} with the slot not binding the previous provider")
        Some(
          List(
            LifecycleInput.Reinstall(providers(prev), named(prev)),
            LifecycleInput.Status(Signal.SwapFailed),
            LifecycleInput.Status(Signal.RollbackUnbound(status(before)))
          )
        )
      case "SwapReconcile" =>
        // GateDown on every exit, then the slot's state read as an event would be, then the deferred watchdog deadline.
        Some(
          List(LifecycleInput.GateDown) ++
            slotSignal(pre.sdk(pre.bound)).map(LifecycleInput.Status(_)).toList ++
            List(LifecycleInput.ReplayDeferred)
        )
      case "Deliver" =>
        val signal = code.bridge(act.str("e"), pre.sdk(pre.bound))
        Some(List(signal.fold[LifecycleInput](LifecycleInput.Idle)(LifecycleInput.Status(_))))
      case "OrphanResolved"                 => Some(List(LifecycleInput.OrphanSettled(providers(act.str("prev")))))
      case "ShutdownStart"                  => Some(List(LifecycleInput.Status(Signal.ShutdownStarted)))
      case "ShutdownDone"                   => Some(resolveAll ++ List(LifecycleInput.Status(Signal.ShutdownCompleted)))
      case "ScopeClose"                     => Some(List(LifecycleInput.ScopeClose) ++ resolveAll)
      case name if inputless.contains(name) => None
      case other                            => sys.error(s"a model action the replay does not map: $other")
    }
  }

  /** The named cases a library action is an instance of, for the coverage check. */
  def casesOf(act: Act, pre: ModelState): List[String] = {
    def swapNew = pre.swap.map(_._1).getOrElse("")
    act.name match {
      case "SwapTimeout" | "SwapFailNoRollback" if !pre.owns => List("swap left behind on a shared API")
      case "SwapRollback" if !act.bool("ok")                 =>
        "rollback whose registration failed" ::
          (if (act.str("r") == "FATAL") List("rollback whose registration failed with a fatal state") else Nil)
      case "Deliver" =>
        (if (pre.sdk(pre.bound) == "NOT_READY") List("delivery to a slot still initializing") else Nil) ++
          (if (pre.gate) List("delivery under the gate") else Nil)
      case "ShutdownStart" =>
        (if (pre.shutDown) List("second shutdown") else Nil) ++
          (if (pre.closed) List("shutdown after the scope closed") else Nil)
      case "SwapStart" if pre.sdk(act.str("p")) != "NONE" => List("provider installed again")
      case "SwapDone" if pre.inits(swapNew) > 0           => List("swap settled by a STALE reported while starting")
      case "OrphanResolved"                               =>
        List(if (act.bool("released")) "orphan released by the SDK" else "orphan settled by the library")
      case "ShutdownDone" if pre.owns && !pre.shutDown && !pre.closed && pre.orphans.nonEmpty =>
        List("teardown settling orphans")
      case "ScopeClose" if pre.owns && !pre.shutDown && pre.orphans.nonEmpty => List("teardown settling orphans")
      case _                                                                 => Nil
    }
  }

  def lockName(lock: Lock): String = lock match {
    case Lock.Free     => "free"
    case Lock.Swap     => "swap"
    case Lock.Shutdown => "shutdown"
  }

  def statusName(status: ProviderStatus): String = status match {
    case ProviderStatus.NotReady     => "NotReady"
    case ProviderStatus.Ready        => "Ready"
    case ProviderStatus.Stale        => "Stale"
    case ProviderStatus.Error        => "Error"
    case ProviderStatus.Fatal        => "Fatal"
    case ProviderStatus.ShuttingDown => "ShuttingDown"
  }

  /** The record's library variables, with `replaced` read as the model reads `prevOf`: only the holders whose
    * `initialize()` still runs (the model's `inits`) hold anything.
    */
  def abstraction(state: LifecycleState, inits: Map[String, Long], providers: Providers): Lib =
    Lib(
      status = statusName(state.status),
      installed = providers.nameOf(state.installed),
      lock = lockName(state.lock),
      gate = state.gate,
      swap = state.swap.map(s => (providers.nameOf(s.incoming), providers.nameOf(s.previous), statusName(s.before))),
      held = state.replaced.collect {
        case (holder, previous) if inits(providers.nameOf(holder)) > 0 =>
          (providers.nameOf(holder), providers.nameOf(previous))
      }.toSet,
      orphans = state.orphans.map { case (previous, incoming) => (providers.nameOf(previous), incoming) }.toSet,
      shutDown = state.shutDown,
      closed = state.closed
    )

  private def refusalOf(outcome: LifecycleStep.Outcome): Option[Refusal] =
    outcome.effects.collectFirst { case LifecycleEffect.Refuse(r) => r }

  /** What the step refuses at a state, against the model's guards the code has: a swap of a provider on a shut-down or
    * closed instance, of one still initializing, of one a timed-out swap left behind, or of one a registration still
    * initializing replaced; and a switch of a provider other than the installed one. Returns the mismatches and the
    * refusals that were expected and given, for the coverage check.
    */
  def guards(
    state: LifecycleState,
    model: ModelState,
    timedOut: Set[String],
    providers: Providers,
    code: Code
  ): (List[String], List[String]) = {
    val closed = model.shutDown || model.closed
    val admits =
      if (model.lock != "free") Nil
      else
        providers.byName.toList.sortBy(_._1).map { case (p, m) =>
          val still   = model.inits(p) > 0
          val orphan  = model.orphans.exists(_._1 == p)
          val held    = model.held(timedOut).exists(_._2 == p)
          val refusal = refusalOf(
            code.step(state, LifecycleInput.Admit(m, Some(m), h => model.inits(providers.nameOf(h)) > 0))
          )
          val expected = closed || still || orphan || held
          val seen     = refusal match {
            case None                               => if (expected) None else Some(Nil)
            case Some(Refusal.Closed)               => if (closed) Some(List("refused: closed")) else None
            case Some(Refusal.StillInitializing(_)) => if (still) Some(List("refused: still initializing")) else None
            case Some(Refusal.PendingOrphan(_, _))  => if (orphan) Some(List("refused: pending orphan")) else None
            case Some(Refusal.HeldBy(_, _))         => if (held) Some(List("refused: held")) else None
            case Some(_)                            => None
          }
          seen.toRight(
            s"Admit($p): refused $refusal, model guards closed=$closed still=$still orphan=$orphan held=$held"
          )
        }
    val switches = providers.byName.toList.sortBy(_._1).map { case (q, m) =>
      val refusal = refusalOf(code.step(state, LifecycleInput.Switch(m, ProviderName.Named(q))))
      val fits    = refusal match {
        case None                          => !closed && q == model.installed
        case Some(Refusal.Closed)          => closed
        case Some(Refusal.NotInstalled(_)) => !closed && q != model.installed
        case Some(_)                       => false
      }
      if (fits) Right(Nil) else Left(s"Switch($q): refused $refusal, closed=$closed installed=${model.installed}")
    }
    val all = admits ++ switches
    (all.collect { case Left(what) => what }, all.collect { case Right(seen) => seen }.flatten)
  }

  /** Replay one behaviour: the mismatches, the library actions it took in order, and the cases it covered. */
  def replayOne(
    behaviour: Behaviour,
    providers: Providers,
    code: Code
  ): (Chunk[Mismatch], List[String], List[String]) = {
    val states = behaviour.states
    val first  = states.head
    val p0     = first.installed
    val fresh  = LifecycleState.initial(providers(p0), ProviderName.Named(p0))
    val seeded =
      if (first.status == "NotReady") fresh else code.step(fresh, LifecycleInput.Seed(status(first.status))).state

    def mismatch(step: Int, action: String, what: String) = Mismatch(behaviour.name, step, action, what)

    // The record against the model's state, and what the step refuses there.
    def check(
      step: Int,
      action: String,
      state: LifecycleState,
      model: ModelState,
      timedOut: Set[String]
    ): (Chunk[Mismatch], List[String]) = {
      val actual   = abstraction(state, model.inits, providers)
      val expected = model.lib(timedOut)
      val fields   = Chunk(
        ("status", actual.status, expected.status),
        ("installed", actual.installed, expected.installed),
        ("lock", actual.lock, expected.lock),
        ("gate", actual.gate, expected.gate),
        ("swap", actual.swap, expected.swap),
        ("replaced (prevOf with inits)", actual.held, expected.held),
        ("orphans", actual.orphans, expected.orphans),
        ("shutDown", actual.shutDown, expected.shutDown),
        ("closed", actual.closed, expected.closed)
      ).collect { case (name, a, e) if a != e => mismatch(step, action, s"$name: code $a, model $e") }
      val deferred =
        if (state.deferredInitTimeout.isEmpty) Chunk.empty
        else
          Chunk(
            mismatch(step, action, s"deferredInitTimeout: code ${state.deferredInitTimeout}, model has no watchdog")
          )
      val (refused, seen) = guards(state, model, timedOut, providers, code)
      (fields ++ deferred ++ Chunk.fromIterable(refused).map(mismatch(step, action, _)), seen)
    }

    // The providers whose current registration a timed-out swap made: recorded by the code as `replaced` until that
    // registration's initialize() returns; a new registration (the next swap's bind, a rollback) replaces it.
    def timedOutAfter(timedOut: Set[String], act: Act, pre: ModelState): Set[String] = act.name match {
      case "SwapTimeout"  => timedOut ++ pre.swap.map(_._1)
      case "SdkBind"      => timedOut -- pre.swap.map(_._1)
      case "SwapRollback" => timedOut -- pre.swap.map(_._2)
      case _              => timedOut
    }

    val (initial, initialCases) = check(0, "Init", seeded, first, Set.empty)
    val buildCases              =
      List(
        if (first.status == "NotReady") "async build" else "sync build",
        if (first.owns) "owned API" else "shared API"
      )

    val (_, mismatches, taken, cases, _) =
      states
        .zip(states.tail)
        .zipWithIndex
        .foldLeft(
          (seeded, initial, List.empty[String], initialCases ++ buildCases, Set.empty[String])
        ) { case ((state, found, taken, cases, timedOut), ((pre, model), index)) =>
          val step  = index + 1
          val after = timedOutAfter(timedOut, model.act, pre)
          inputsFor(model.act, pre, providers, code) match {
            case None =>
              // An SDK step: the record does not move, but what the registrations still initializing hold does.
              val (checked, seen) = check(step, model.act.name, state, model, after)
              (state, found ++ checked, taken, seen ++ cases, after)
            case Some(inputs) =>
              val (next, effects) = inputs.foldLeft((state, Chunk.empty[LifecycleEffect])) { case ((s, fx), input) =>
                val outcome = code.step(s, input)
                (outcome.state, fx ++ outcome.effects)
              }
              val refused   = effects.collect { case LifecycleEffect.Refuse(r) => r }
              val escalated = effects.collect { case e: LifecycleEffect.EscalateInitTimeout => e }
              val onEffects =
                (if (refused.isEmpty) Chunk.empty
                 else
                   Chunk(
                     mismatch(step, model.act.name, s"refused ${refused.mkString(", ")} on an action the model took")
                   )) ++
                  (if (escalated.isEmpty) Chunk.empty
                   else Chunk(mismatch(step, model.act.name, s"escalated $escalated; the model has no watchdog")))
              val (checked, seen) = check(step, model.act.name, next, model, after)
              (
                next,
                found ++ onEffects ++ checked,
                model.act.name :: taken,
                casesOf(model.act, pre) ++ seen ++ cases,
                after
              )
          }
        }
    (mismatches, taken.reverse, cases)
  }

  def replay(behaviours: List[Behaviour], code: Code): Replay = {
    val names                              = behaviours.flatMap(_.states).flatMap(s => s.inits.keys).toSet
    val providers                          = new Providers(names)
    val runs                               = behaviours.map(b => replayOne(b, providers, code))
    def count[A](as: List[A]): Map[A, Int] = as.groupBy(identity).map { case (a, xs) => a -> xs.size }
    Replay(
      Chunk.fromIterable(runs.flatMap(_._1)),
      count(runs.flatMap(_._2)),
      count(runs.flatMap { case (_, taken, _) => taken.zip(taken.drop(1)) }),
      count(runs.flatMap(_._3))
    )
  }

  // Loading

  def parseBehaviour(name: String, lines: List[String]): Either[String, Behaviour] = {
    val parsed = lines.zipWithIndex.filter(_._1.trim.nonEmpty).map { case (line, i) =>
      Json.parse(line).left.map(e => s"$name line ${i + 1}: $e").flatMap {
        case Json.Obj(fields) =>
          fields.get("act") match {
            case Some(Json.Obj(act)) =>
              act.get("name") match {
                case Some(Json.Str(actName)) =>
                  Right(ModelState(Act(actName, act - "name"), fields - "act" - "_action"))
                case _ => Left(s"$name line ${i + 1}: act has no name")
              }
            case _ => Left(s"$name line ${i + 1}: no act")
          }
        case _ => Left(s"$name line ${i + 1}: not an object")
      }
    }
    parsed
      .foldLeft[Either[String, Vector[ModelState]]](Right(Vector.empty)) { (acc, next) =>
        acc.flatMap(states => next.map(states :+ _))
      }
      .flatMap { states =>
        if (states.size < 2) Left(s"$name: fewer than two states")
        else if (states.head.act.name != "Init") Left(s"$name: does not start with Init")
        else Right(Behaviour(name, states.toList))
      }
  }

  def files(path: Path): List[Path] =
    if (Files.isDirectory(path))
      Using.resource(Files.list(path))(
        _.iterator().asScala.filter(_.toString.endsWith(".ndjson")).toList.sortBy(_.getFileName.toString)
      )
    else if (Files.exists(path)) List(path)
    else sys.error(s"$path does not exist")

  val location: Option[String] =
    sys.props
      .get("zof.lifecycle.behaviours")
      .orElse(sys.env.get("ZOF_LIFECYCLE_BEHAVIOURS"))
      .map(_.trim)
      .filter(_.nonEmpty)

  def load(location: String): Task[List[Behaviour]] =
    ZIO.attempt(location.split(',').toList.map(_.trim).filter(_.nonEmpty).map(Paths.get(_)).flatMap(files)).flatMap {
      paths =>
        ZIO.foreach(paths) { p =>
          ZIO
            .attempt(Files.readAllLines(p).asScala.toList)
            .flatMap(lines =>
              ZIO.fromEither(parseBehaviour(p.getFileName.toString, lines)).mapError(new IllegalArgumentException(_))
            )
        }
    }

  private val loaded: Task[List[Behaviour]] =
    location.fold[Task[List[Behaviour]]](
      ZIO.fail(new IllegalStateException("zof.lifecycle.behaviours (or ZOF_LIFECYCLE_BEHAVIOURS) is not set"))
    )(load)

  // A hand-written behaviour for the checks that need no generated set: the sync build on p0 with a timed-out swap
  // to p1, whose initialize() then throws, so the orphan is settled by the library; then a shutdown and the close.

  final case class Vars(
    status: String = "Ready",
    installed: String = "p0",
    lock: String = "free",
    gate: Boolean = false,
    swap: Option[(String, String, String, String)] = None,
    pending: String = "None",
    orphans: Set[(String, String)] = Set.empty,
    shutDown: Boolean = false,
    closed: Boolean = false,
    owns: Boolean = true,
    bound: String = "p0",
    sdk: Map[String, String] = Map("p0" -> "READY", "p1" -> "NONE"),
    inits: Map[String, Long] = Map("p0" -> 0L, "p1" -> 0L),
    prevOf: Map[String, String] = Map("p0" -> "None", "p1" -> "None")
  ) {
    def after(act: Act): ModelState = {
      def fun[A](m: Map[String, A])(f: A => Json) = Json.Obj(m.map { case (k, v) => k -> f(v) })
      ModelState(
        act,
        Map(
          "status"    -> Json.Str(status),
          "installed" -> Json.Str(installed),
          "lock"      -> Json.Str(lock),
          "gate"      -> Json.Bool(gate),
          "swap"      -> swap.fold[Json](Json.Str("None")) { case (p, prev, before, phase) =>
            Json.Obj(
              Map(
                "new"     -> Json.Str(p),
                "prev"    -> Json.Str(prev),
                "before"  -> Json.Str(before),
                "phase"   -> Json.Str(phase),
                "settled" -> Json.Str("NONE")
              )
            )
          },
          "pending" -> Json.Str(pending),
          "orphans" -> Json.Arr(orphans.toVector.map { case (prev, watch) =>
            Json.Obj(
              Map(
                "prev"   -> Json.Str(prev),
                "watch"  -> Json.Str(watch),
                "frozen" -> Json.Str("LIVE"),
                "ran"    -> Json.Bool(false)
              )
            )
          }),
          "shutDown" -> Json.Bool(shutDown),
          "closed"   -> Json.Bool(closed),
          "owns"     -> Json.Bool(owns),
          "bound"    -> Json.Str(bound),
          "sdk"      -> fun(sdk)(Json.Str(_)),
          "inits"    -> fun(inits)(Json.Num(_)),
          "prevOf"   -> fun(prevOf)(Json.Str(_))
        )
      )
    }
  }

  val handWritten: Behaviour = {
    val s0 = Vars()
    val s1 = s0.copy(
      status = "NotReady",
      installed = "p1",
      lock = "swap",
      gate = true,
      swap = Some(("p1", "p0", "Ready", "bind"))
    )
    val s2 = s1.copy(
      bound = "p1",
      sdk = s1.sdk + ("p1"       -> "NOT_READY"),
      inits = s1.inits + ("p1"   -> 1L),
      prevOf = s1.prevOf + ("p1" -> "p0"),
      swap = Some(("p1", "p0", "Ready", "wait"))
    )
    val s3 = s2.copy(
      status = "Error",
      pending = "p1",
      orphans = Set(("p0", "p1")),
      swap = Some(("p1", "p0", "Ready", "reconcile"))
    )
    val s4 = s3.copy(gate = false, lock = "free", swap = None)
    val s5 = s4.copy(inits = s4.inits + ("p1" -> 0L), sdk = s4.sdk + ("p1" -> "ERROR"))
    val s6 = s5.copy(orphans = Set.empty)
    val s7 = s6.copy(status = "ShuttingDown", lock = "shutdown")
    val s8 = s7.copy(status = "NotReady", lock = "free", shutDown = true)
    val s9 = s8.copy(closed = true)
    Behaviour(
      "hand-written",
      List(
        s0.after(Act("Init")),
        s1.after(Act("SwapStart", "p" -> Json.Str("p1"))),
        s2.after(Act("SdkBind")),
        s3.after(Act("SwapTimeout")),
        s4.after(Act("SwapReconcile")),
        s5.after(Act("SdkInitFail", "p" -> Json.Str("p1"))),
        s6.after(
          Act("OrphanResolved", "prev" -> Json.Str("p0"), "watch" -> Json.Str("p1"), "released" -> Json.Bool(false))
        ),
        s7.after(Act("ShutdownStart")),
        s8.after(Act("ShutdownDone")),
        s9.after(Act("ScopeClose"))
      )
    )
  }

  /** A step that differs from the real one on one input. */
  private def planted(
    bug: PartialFunction[(LifecycleState, LifecycleInput, LifecycleStep.Outcome), LifecycleStep.Outcome]
  ): Code =
    real.copy(step = (state, input) => {
      val outcome = LifecycleStep.step(state, input)
      bug.lift((state, input, outcome)).getOrElse(outcome)
    })

  private def unchanged(state: LifecycleState) = LifecycleStep.Outcome(state, None, Chunk.empty)

  val noGate: Code = planted { case (_, _: LifecycleInput.SwapStart, outcome) =>
    outcome.copy(state = outcome.state.copy(gate = false))
  }

  val byEvent: Code = real.copy(bridge = (event, _) => slotSignal(event))

  private def mismatchOn(result: Replay, action: String, field: String): Boolean =
    result.mismatches.exists(m => m.action == action && m.what.startsWith(field))

  def spec = suite("LifecycleReplaySpec")(
    suite("the generated behaviours")(
      test("every behaviour of the model replays through the step with the model's library state after each step") {
        ZIO.serviceWith[List[Behaviour]] { behaviours =>
          val result = replay(behaviours, real)
          val report = result.mismatches.take(10).mkString("\n")
          assertTrue(behaviours.nonEmpty, result.mismatches.isEmpty) ??
            s"${behaviours.size} behaviours, ${result.mismatches.size} mismatches:\n$report"
        }
      },
      test(
        "every library action and every named case occurs in the set, and the pairs of consecutive actions are reported"
      ) {
        ZIO.serviceWithZIO[List[Behaviour]] { behaviours =>
          val result  = replay(behaviours, real)
          val missing = (libraryActions -- result.actions.keySet) ++ (expectedCases -- result.cases.keySet)
          val actions = result.actions.toList.sortBy(-_._2).map { case (a, n) => s"$a $n" }.mkString(", ")
          val cases   = result.cases.toList.sortBy(-_._2).map { case (c, n) => s"$c $n" }.mkString(", ")
          val pairs   = result.pairs.toList.sortBy(-_._2).map { case ((a, b), n) => s"$a>$b $n" }.mkString(", ")
          ZIO
            .logInfo(s"${behaviours.size} behaviours; actions: $actions; cases: $cases; pairs: $pairs")
            .as(assertTrue(missing.isEmpty) ?? s"never generated: $missing")
        }
      },
      test("a step that never raises the swap gate fails the replay") {
        ZIO.serviceWith[List[Behaviour]](behaviours =>
          assertTrue(mismatchOn(replay(behaviours, noGate), "SwapStart", "gate"))
        )
      },
      test("a bridge that follows the event's type instead of the SDK's state fails the replay") {
        ZIO.serviceWith[List[Behaviour]](behaviours =>
          assertTrue(mismatchOn(replay(behaviours, byEvent), "Deliver", "status"))
        )
      }
    ).provideShared(ZLayer.fromZIO(loaded)) @@ (
      if (location.isEmpty && !sys.env.contains("ZOF_LIFECYCLE_BEHAVIOURS_REQUIRED")) TestAspect.ignore
      else TestAspect.identity
    ),
    suite("the replay's own checks")(
      test("the JSON reader reads what TLC writes") {
        // Plain strings with the backslashes doubled: Scala 2.13 would read a `\u` escape even in a raw string.
        val text = "{\"a\":[1,-2,{\"b\":\"x\\u0041\\n\"}],\"c\":true,\"d\":null,\"e\":{},\"f\":\"None\"}"
        assertTrue(
          Json.parse(text) == Right(
            Json.Obj(
              Map(
                "a" -> Json.Arr(Vector(Json.Num(1), Json.Num(-2), Json.Obj(Map("b" -> Json.Str("xA\n"))))),
                "c" -> Json.Bool(true),
                "d" -> Json.Null,
                "e" -> Json.Obj(Map.empty),
                "f" -> Json.Str("None")
              )
            )
          ),
          Json.parse("""{"a":1.5}""").isLeft,
          Json.parse("{\"a\":\"\\u00zz\"}").isLeft,
          Json.parse("""[1,2""").isLeft,
          Json.parse("""{"a":1} x""").isLeft
        )
      },
      test("a behaviour must be states with an act, starting with Init, at least two of them") {
        val init = """{"act":{"name":"Init"},"status":"Ready"}"""
        val next = """{"act":{"name":"ScopeClose"},"status":"Ready"}"""
        assertTrue(
          parseBehaviour("ok", List(init, next)).map(_.states.map(_.act.name)) == Right(List("Init", "ScopeClose")),
          parseBehaviour("one", List(init)).isLeft,
          parseBehaviour("no init", List(next, next)).isLeft,
          parseBehaviour("no act", List(init, """{"status":"Ready"}""")).isLeft,
          parseBehaviour("bad json", List(init, "{")).isLeft,
          parseBehaviour("empty", Nil).isLeft
        )
      },
      test("an action the map does not know fails the replay rather than being skipped") {
        val providers = new Providers(Set("p0", "p1"))
        assertTrue(Try(inputsFor(Act("SdkShutDownOld"), handWritten.states.head, providers, real)).isFailure)
      },
      test("the hand-written behaviour replays with no mismatch and covers its cases") {
        val result = replay(List(handWritten), real)
        assertTrue(
          result.mismatches.isEmpty,
          result.actions.keySet == Set(
            "SwapStart",
            "SwapTimeout",
            "SwapReconcile",
            "OrphanResolved",
            "ShutdownStart",
            "ShutdownDone",
            "ScopeClose"
          ),
          result.cases.keySet.contains("orphan settled by the library"),
          result.cases.keySet.contains("refused: pending orphan"),
          result.cases.keySet.contains("refused: still initializing"),
          result.cases.keySet.contains("refused: closed")
        ) ?? result.mismatches.mkString("\n")
      },
      test("each comparison can fail: six planted bugs each fail the hand-written behaviour") {
        val admitsEverything = planted { case (state, _: LifecycleInput.Admit, _) => unchanged(state) }
        val keepsOrphans     = planted { case (state, _: LifecycleInput.OrphanSettled, _) => unchanged(state) }
        val wrongReplacement = planted { case (state, LifecycleInput.Replaced(_, previous), outcome) =>
          outcome.copy(state = state.copy(replaced = previous.managed.map(p => (p, p)).toList ++ state.replaced))
        }
        val defersWatchdog = planted { case (_, LifecycleInput.ReplayDeferred, outcome) =>
          outcome.copy(state = outcome.state.copy(deferredInitTimeout = Some((outcome.state.installed, 1.second))))
        }
        val neverCloses     = planted { case (state, LifecycleInput.ScopeClose, _) => unchanged(state) }
        val forgetsShutdown = planted { case (_, LifecycleInput.Status(Signal.ShutdownCompleted), outcome) =>
          outcome.copy(state = outcome.state.copy(shutDown = false))
        }
        def on(code: Code) = replay(List(handWritten), code)
        assertTrue(
          mismatchOn(on(admitsEverything), "SwapReconcile", "Admit(p0)"),
          mismatchOn(on(keepsOrphans), "OrphanResolved", "orphans"),
          mismatchOn(on(wrongReplacement), "SwapTimeout", "replaced"),
          mismatchOn(on(defersWatchdog), "SwapReconcile", "deferredInitTimeout"),
          mismatchOn(on(neverCloses), "ScopeClose", "closed"),
          mismatchOn(on(forgetsShutdown), "ShutdownDone", "shutDown")
        )
      }
    )
  )
}
