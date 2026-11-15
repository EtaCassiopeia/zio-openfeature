package zio.openfeature.extras

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicReference

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import zio._
import zio.openfeature.extras.CircuitBreaker.Permit
import zio.openfeature.testsupport.ModelBehaviours
import zio.openfeature.testsupport.ModelBehaviours.{Act, Behaviour, Json, ModelState}
import zio.test._

/** Behaviours of the TLA+ model (`formal/circuitbreaker/Generate.tla`, written by `formal/generate.py`), replayed
  * through the real `CircuitBreaker` on one thread. Each model step that takes effect becomes the call the code makes
  * there (the deciding read, or the compareAndSet that wins); a step that loses its compareAndSet sends nothing, since
  * the code retries inside the call. After every step the code's state must equal the model's, and each acquire must
  * return the permit epoch the model granted. The map is `formal/circuitbreaker/replay-map.md`; `step` is its twin.
  *
  * This rests on the class being linearizable, which `CircuitBreakerLincheckSpec` checks.
  *
  * The behaviours come from the paths in `zof.breaker.behaviours` (a system property, or the `ZOF_BREAKER_BEHAVIOURS`
  * environment variable), comma-separated files or directories of `*.ndjson`; without it the suite is ignored, unless
  * `ZOF_BREAKER_BEHAVIOURS_REQUIRED` is set, when it fails. The `Formal model` workflow generates them and runs this
  * suite with that variable set, so a lost property cannot pass as a skip.
  */
object CircuitBreakerReplaySpec extends ZIOSpecDefault {

  // The code's state in the model's terms.
  final case class View(
    kind: String,
    reason: String,
    stale: Boolean,
    successes: Int,
    probing: Boolean,
    failures: Int,
    epoch: Long
  )

  final class FakeTicker extends Ticker {
    var now: Long     = 0L
    def nanos(): Long = now
  }

  /** What the replay drives: the breaker's operations at the model's grain. */
  trait Breaker {
    def stateRef: AtomicReference[CircuitBreakerState]
    def ticker: FakeTicker
    def acquire(): Option[Permit]
    def recordSuccess(permit: Permit): Unit
    def recordReachable(permit: Permit): Unit
    def recordFailure(permit: Permit): Unit
    def trip(reason: OpenReason, stale: Boolean): Unit
    def reset(): Unit
    def resetIfOpenedBy(reason: OpenReason): Unit
    def transitionToHalfOpen(): Unit
    def state: CircuitBreakerState = stateRef.get()
  }

  val ProbeAndResetTimeout: Long = 10L

  final class Real extends Breaker {
    val ticker: FakeTicker = new FakeTicker
    private val breaker    = CircuitBreaker(
      CircuitBreakerConfig.default
        .withFailureThreshold(2)
        .withHalfOpenMaxCalls(2)
        .withResetTimeout(ProbeAndResetTimeout.nanos)
        .withProbeTimeout(ProbeAndResetTimeout.nanos),
      ticker
    )
    def stateRef: AtomicReference[CircuitBreakerState] = breaker.stateRef
    def acquire(): Option[Permit]                      = breaker.acquire()
    def recordSuccess(permit: Permit): Unit            = { breaker.recordSuccess(permit); () }
    def recordReachable(permit: Permit): Unit          = breaker.recordReachable(permit)
    def recordFailure(permit: Permit): Unit            = { breaker.recordFailure(permit); () }
    def trip(reason: OpenReason, stale: Boolean): Unit = breaker.trip(reason, stale)
    def reset(): Unit                                  = breaker.reset()
    def resetIfOpenedBy(reason: OpenReason): Unit      = breaker.resetIfOpenedBy(reason)
    def transitionToHalfOpen(): Unit                   = breaker.transitionToHalfOpen()
  }

  /** The real breaker, with the methods a planted bug changes left to override. */
  class Wrapped(underlying: Breaker) extends Breaker {
    def stateRef: AtomicReference[CircuitBreakerState] = underlying.stateRef
    def ticker: FakeTicker                             = underlying.ticker
    def acquire(): Option[Permit]                      = underlying.acquire()
    def recordSuccess(permit: Permit): Unit            = underlying.recordSuccess(permit)
    def recordReachable(permit: Permit): Unit          = underlying.recordReachable(permit)
    def recordFailure(permit: Permit): Unit            = underlying.recordFailure(permit)
    def trip(reason: OpenReason, stale: Boolean): Unit = underlying.trip(reason, stale)
    def reset(): Unit                                  = underlying.reset()
    def resetIfOpenedBy(reason: OpenReason): Unit      = underlying.resetIfOpenedBy(reason)
    def transitionToHalfOpen(): Unit                   = underlying.transitionToHalfOpen()
  }

  // transition: the new epoch is from.epoch + 1
  final class EpochNotBumped(underlying: Breaker) extends Wrapped(underlying) {
    private def keepEpoch[A](call: => A): A = {
      val before = state.epoch
      val result = call
      stateRef.updateAndGet(s => if (s.epoch > before) s.copy(epoch = before) else s)
      result
    }
    override def acquire(): Option[Permit]             = keepEpoch(super.acquire()).map(_ => Permit(state.epoch))
    override def recordSuccess(permit: Permit): Unit   = keepEpoch(super.recordSuccess(permit))
    override def recordReachable(permit: Permit): Unit = keepEpoch(super.recordReachable(permit))
    override def recordFailure(permit: Permit): Unit   = keepEpoch(super.recordFailure(permit))
    override def trip(reason: OpenReason, stale: Boolean): Unit = keepEpoch(super.trip(reason, stale))
    override def reset(): Unit                                  = keepEpoch(super.reset())
    override def resetIfOpenedBy(reason: OpenReason): Unit      = keepEpoch(super.resetIfOpenedBy(reason))
    override def transitionToHalfOpen(): Unit                   = keepEpoch(super.transitionToHalfOpen())
  }

  // reset(): resetIf(r => r == External || r == Polled) becomes resetIf(_ => true)
  final class ResetFromAnyReason(underlying: Breaker) extends Wrapped(underlying) {
    override def reset(): Unit = {
      super.reset()
      super.resetIfOpenedBy(OpenReason.Failures)
    }
  }

  // probeFrom: the probeTimeout branch of a half-open circuit becomes false
  final class NoProbeTakeover(underlying: Breaker) extends Wrapped(underlying) {
    override def acquire(): Option[Permit] = state.circuit match {
      case CircuitState.HalfOpen(_, true, _) => None
      case _                                 => super.acquire()
    }
  }

  // current(permit): permit.forall(_.epoch == state.epoch) becomes true, which is the permit-less API's behaviour
  final class StalePermitIsCurrent(underlying: Breaker) extends Wrapped(underlying) {
    override def recordSuccess(permit: Permit): Unit   = super.recordSuccess(Permit(state.epoch))
    override def recordReachable(permit: Permit): Unit = super.recordReachable(Permit(state.epoch))
    override def recordFailure(permit: Permit): Unit   = super.recordFailure(Permit(state.epoch))
  }

  // What the replay reads of a model state.
  implicit final class BreakerModel(m: ModelState) {
    private def obj(j: Json): Map[String, Json] = j match {
      case Json.Obj(fields) => fields
      case other            => sys.error(s"not an object: $other")
    }
    private def field(of: Map[String, Json], key: String): Json =
      of.getOrElse(key, sys.error(s"no field $key in $of"))

    def view: View = {
      val st      = obj(m.field("st"))
      val circuit = obj(field(st, "circuit"))
      View(
        m.asStr(field(circuit, "kind")),
        m.asStr(field(circuit, "reason")),
        m.asBool(field(circuit, "stale")),
        m.asNum(field(circuit, "successes")).toInt,
        m.asBool(field(circuit, "probing")),
        m.asNum(field(st, "failures")).toInt,
        m.asNum(field(st, "epoch"))
      )
    }
    def pc(process: String): String       = m.asStr(field(m.fun("pc"), process))
    def permitEpoch(caller: String): Long =
      m.asNum(field(obj(field(m.fun("permit"), caller)), "epoch"))
    def resetElapsed: Boolean = m.bool("resetElapsed")
    def probeElapsed: Boolean = m.bool("probeElapsed")
  }

  private def codeView(s: CircuitBreakerState): View = s.circuit match {
    case CircuitState.Closed =>
      View("Closed", "none", stale = false, 0, probing = false, s.consecutiveFailures, s.epoch)
    case CircuitState.Open(_, reason, stale) =>
      View("Open", reason.toString, stale, 0, probing = false, s.consecutiveFailures, s.epoch)
    case CircuitState.HalfOpen(n, probing, _) =>
      View("HalfOpen", "none", stale = false, n, probing, s.consecutiveFailures, s.epoch)
  }

  /** The model's deadline flags, as the code's clock says them for the state it is in; None when the state has none. */
  private def deadlines(code: Breaker): (Option[Boolean], Option[Boolean]) = {
    val now = code.ticker.now
    code.state.circuit match {
      case CircuitState.Open(since, _, _)          => (Some(now - since >= ProbeAndResetTimeout), None)
      case CircuitState.HalfOpen(_, true, started) => (None, Some(now - started >= ProbeAndResetTimeout))
      case _                                       => (None, None)
    }
  }

  /** Whether the model's `RecordNext(outcome, st) # st` for a state of the circuit. */
  private def changesState(outcome: String, v: View): Boolean =
    v.kind != "Open" && (outcome match {
      case "failure"   => true
      case "success"   => v.kind == "HalfOpen" || v.failures != 0
      case "reachable" => if (v.kind == "Closed") v.failures != 0 else v.probing
      case other       => sys.error(s"unexpected outcome $other")
    })

  private def openReason(name: String): OpenReason = name match {
    case "External" => OpenReason.External
    case "Polled"   => OpenReason.Polled
    case other      => sys.error(s"unexpected open reason $other")
  }

  /** `fields` are the state's fields that differ, or `call` when the call's own result did; `crashed` is a replay that
    * threw instead of finding a difference.
    */
  final case class Mismatch(
    behaviour: String,
    step: Int,
    action: String,
    what: String,
    fields: List[String],
    crashed: Boolean = false
  ) {
    override def toString: String = s"$behaviour step $step ($action): $what"
  }

  private def differing(got: View, want: View): List[String] =
    got.productElementNames
      .zip(got.productIterator.zip(want.productIterator))
      .collect {
        case (name, (a, b)) if a != b => name
      }
      .toList

  /** How often each case was reached. */
  final class Reached {
    val counts: mutable.Map[String, Int] = mutable.Map.empty
    def +=(key: String): Unit            = counts.update(key, counts.getOrElse(key, 0) + 1)
  }

  final case class Replay(mismatches: List[Mismatch], reached: Map[String, Int], steps: Int)

  /** The call the replay makes for one model step, if any, with the cases the step reached; a mismatch of the call's
    * own result is returned. The state comparison follows in `replayOne`.
    */
  private def step(
    code: Breaker,
    before: ModelState,
    after: ModelState,
    act: Act,
    reached: Reached
  ): Option[String] = {
    val label                        = act.name
    val p                            = act.str("p")
    def reach(variant: String): Unit = reached += s"$label:$variant"

    def expectPermit(got: Option[Permit], want: Long, what: String): Option[String] =
      if (got == Some(Permit(want))) None else Some(s"$what returned $got, the model granted a permit of epoch $want")

    def record(): Unit = {
      val permit = Permit(before.permitEpoch(p))
      act.str("outcome") match {
        case "success"   => code.recordSuccess(permit); reached += "call:recordSuccess"
        case "reachable" => code.recordReachable(permit); reached += "call:recordReachable"
        case "failure"   => code.recordFailure(permit); reached += "call:recordFailure"
        case other       => sys.error(s"unexpected outcome $other")
      }
    }

    def signal(): Unit = act.params("op") match {
      case Json.Obj(op) =>
        val kind = (op("kind"): @unchecked) match { case Json.Str(s) => s }
        kind match {
          case "Trip" =>
            val reason = (op("reason"): @unchecked) match { case Json.Str(s) => s }
            val stale  = (op("stale"): @unchecked) match { case Json.Bool(b) => b }
            code.trip(openReason(reason), stale)
            reached += "call:trip"
          case "Reset" =>
            val openers = (op("openers"): @unchecked) match {
              case Json.Arr(items) => items.collect { case Json.Str(s) => s }.toSet
            }
            if (openers == Set("External", "Polled")) { code.reset(); reached += "call:reset" }
            else if (openers == Set("Polled")) {
              code.resetIfOpenedBy(OpenReason.Polled); reached += "call:resetIfOpenedBy"
            } else sys.error(s"unexpected openers $openers")
          case "ToHalfOpen" => code.transitionToHalfOpen(); reached += "call:transitionToHalfOpen"
          case other        => sys.error(s"unexpected signal $other")
        }
      case other => sys.error(s"signal step without its op: $other")
    }

    def opKind: String = act.params("op") match {
      case Json.Obj(op) =>
        (op("kind"): @unchecked) match {
          case Json.Str("Reset") =>
            (op("openers"): @unchecked) match {
              case Json.Arr(items) => if (items.size == 2) "Reset" else "ResetPolled"
            }
          case Json.Str(k) => k
        }
      case other => sys.error(s"signal step without its op: $other")
    }

    val was = before.view
    val now = after.view

    label match {
      case "CArrive" | "SArrive" => reach("arrive"); None

      case "CAcquireRead" =>
        after.pc(p) match {
          case "CEvaluate" =>
            reach("closed"); reached += "call:acquire"
            expectPermit(code.acquire(), after.permitEpoch(p), "acquire on a closed circuit")
          case "CArrive" =>
            reach("refused"); reached += "call:acquire"
            code.acquire() match {
              case None    => None
              case granted => Some(s"acquire returned $granted, the model refused")
            }
          case "CAcquireCas" => reach("snapshot"); None
          case other         => Some(s"unexpected pc $other after $label")
        }

      case "CAcquireCas" =>
        after.pc(p) match {
          case "CEvaluate" =>
            reach("won"); reached += "call:acquire"
            if (was.probing && before.probeElapsed) reached += "case:takeover of a timed-out probe"
            if (was.kind == "Open") reached += "case:probe after resetTimeout"
            if (was.kind == "HalfOpen" && !was.probing) reached += "case:probe of a free half-open slot"
            expectPermit(code.acquire(), after.permitEpoch(p), "acquire that wins the compareAndSet")
          case "CAcquireRead" => reach("lost"); reached += "case:lost compareAndSet in acquire"; None
          case other          => Some(s"unexpected pc $other after $label")
        }

      case "CEvaluate" =>
        if (after.pc(p) == "CArrive") reach("died") else reach("outcome")
        None

      case "CRecordRead" =>
        after.pc(p) match {
          case "CRecordCas" => reach("go"); None
          case "CArrive"    =>
            if (before.permitEpoch(p) != was.epoch) {
              reach("stale")
              if (changesState(act.str("outcome"), was))
                reached += "case:stale-permit outcome that would change the state"
            } else reach("noop")
            record()
            None
          case other => Some(s"unexpected pc $other after $label")
        }

      case "CRecordCas" =>
        after.pc(p) match {
          case "CArrive" =>
            reach("won")
            reached += s"won:${act.str("outcome")}"
            if (was.kind == "HalfOpen" && now.kind == "Closed") reached += "case:probes close the circuit"
            record()
            None
          case "CRecordRead" => reach("lost"); reached += "case:lost compareAndSet in record"; None
          case other         => Some(s"unexpected pc $other after $label")
        }

      case "SRead" =>
        after.pc(p) match {
          case "SCas"    => reach("snapshot"); None
          case "SArrive" =>
            reach("noop"); reach(s"noop:$opKind")
            if (opKind == "Reset" && was.kind == "Open" && was.reason == "Failures")
              reached += "case:reset meets Open(Failures)"
            signal()
            None
          case other => Some(s"unexpected pc $other after $label")
        }

      case "SCas" =>
        after.pc(p) match {
          case "SArrive" =>
            reach("won"); reach(s"won:$opKind")
            act.params("op") match {
              case Json.Obj(op) if opKind == "Trip" =>
                val external = op("reason") == Json.Str("External")
                val stale    = op("stale") == Json.Bool(true)
                if (external && was.kind == "Open" && was.reason == "Polled")
                  reached += "case:Polled to External takeover"
                if (!stale && was.kind == "Open" && was.stale && now.kind == "Open" && !now.stale)
                  reached += "case:trip clears stale"
              case _ => ()
            }
            signal()
            None
          case "SRead" => reach("lost"); reached += "case:lost compareAndSet in signal"; None
          case other   => Some(s"unexpected pc $other after $label")
        }

      case "CTick" =>
        if (after.resetElapsed && !before.resetElapsed) {
          code.ticker.now += ProbeAndResetTimeout; reach("reset"); None
        } else if (after.probeElapsed && !before.probeElapsed) {
          code.ticker.now += ProbeAndResetTimeout; reach("probe"); None
        } else Some("the tick raised no deadline flag")

      case other => Some(s"no replay for action $other")
    }
  }

  private def replayOne(b: Behaviour, fresh: () => Breaker, reached: Reached): Option[Mismatch] = {
    val code                       = fresh()
    var mismatch: Option[Mismatch] = None
    val steps                      = b.states.zip(b.states.drop(1)).zipWithIndex.iterator
    while (mismatch.isEmpty && steps.hasNext) {
      val ((before, after), i)                                             = steps.next()
      val act                                                              = after.act
      def at(what: String, fields: List[String], crashed: Boolean = false) =
        Mismatch(b.name, i + 1, act.name, what, fields, crashed)
      val result =
        try step(code, before, after, act, reached).map(at(_, List("call")))
        catch { case e: RuntimeException => Some(at(s"replay failed: $e", Nil, crashed = true)) }
      mismatch = result.orElse {
        val want = after.view
        val got  = codeView(code.state)
        if (got != want) Some(at(s"code state $got, model state $want", differing(got, want)))
        else {
          val (reset, probe) = deadlines(code)
          if (reset.exists(_ != after.resetElapsed))
            Some(at(s"code says resetTimeout elapsed=${reset.get}, model ${after.resetElapsed}", List("resetElapsed")))
          else if (probe.exists(_ != after.probeElapsed))
            Some(at(s"code says probeTimeout elapsed=${probe.get}, model ${after.probeElapsed}", List("probeElapsed")))
          else None
        }
      }
    }
    mismatch
  }

  /** Replays the behaviours one file at a time, so that a nightly set need not fit in memory. */
  def replay(files: List[Path], fresh: () => Breaker): Replay = {
    val reached    = new Reached
    val mismatches = List.newBuilder[Mismatch]
    var steps      = 0
    val remaining  = files.iterator
    while (remaining.hasNext) {
      val b = read(remaining.next())
      steps += b.states.size - 1
      mismatches ++= replayOne(b, fresh, reached)
    }
    Replay(mismatches.result(), reached.counts.toMap, steps)
  }

  private def read(file: Path): Behaviour =
    ModelBehaviours.parseBehaviour(file.getFileName.toString, Files.readAllLines(file).asScala.toList) match {
      case Right(b)  => b
      case Left(why) => throw new IllegalArgumentException(why)
    }

  val real: () => Breaker = () => new Real

  val epochNotBumped: () => Breaker       = () => new EpochNotBumped(new Real)
  val resetFromAnyReason: () => Breaker   = () => new ResetFromAnyReason(new Real)
  val noProbeTakeover: () => Breaker      = () => new NoProbeTakeover(new Real)
  val stalePermitIsCurrent: () => Breaker = () => new StalePermitIsCurrent(new Real)

  // Every label of the map, with the case each has, and the named cases of the issue.
  val expectedLabels: Set[String] = Set(
    "CArrive:arrive",
    "CAcquireRead:closed",
    "CAcquireRead:refused",
    "CAcquireRead:snapshot",
    "CAcquireCas:won",
    "CAcquireCas:lost",
    "CEvaluate:died",
    "CEvaluate:outcome",
    "CRecordRead:stale",
    "CRecordRead:noop",
    "CRecordRead:go",
    "CRecordCas:won",
    "CRecordCas:lost",
    "SArrive:arrive",
    "SRead:snapshot",
    "SRead:noop",
    "SCas:won",
    "SCas:lost",
    "CTick:reset",
    "CTick:probe",
    "call:acquire",
    "call:recordSuccess",
    "call:recordReachable",
    "call:recordFailure",
    "call:trip",
    "call:reset",
    "call:resetIfOpenedBy",
    "call:transitionToHalfOpen",
    "won:success",
    "won:reachable",
    "won:failure"
  ) ++ (for {
    when <- List("noop", "won")
    op   <- List("Trip", "Reset", "ResetPolled", "ToHalfOpen")
  } yield s"${if (when == "won") "SCas" else "SRead"}:$when:$op")

  val expectedCases: Set[String] = Set(
    "case:lost compareAndSet in acquire",
    "case:lost compareAndSet in record",
    "case:lost compareAndSet in signal",
    "case:stale-permit outcome that would change the state",
    "case:takeover of a timed-out probe",
    "case:reset meets Open(Failures)",
    "case:Polled to External takeover",
    "case:trip clears stale",
    "case:probes close the circuit",
    "case:probe after resetTimeout",
    "case:probe of a free half-open slot"
  )

  private val location: Option[String] =
    ModelBehaviours.location("zof.breaker.behaviours", "ZOF_BREAKER_BEHAVIOURS")

  private val loaded: Task[List[Path]] =
    ZIO.attempt(location.getOrElse(sys.error("zof.breaker.behaviours (or ZOF_BREAKER_BEHAVIOURS) is not set"))).map {
      _.split(',').toList.map(_.trim).filter(_.nonEmpty).flatMap(d => ModelBehaviours.files(Paths.get(d)))
    }

  // The bug checks replay this many behaviours, so that they cost the same on a nightly set.
  private val SampleSize = 2000

  private def named(files: List[Path]): Set[String] = files.map(_.getFileName.toString).toSet

  private val required: Boolean = sys.env.contains("ZOF_BREAKER_BEHAVIOURS_REQUIRED")

  val targetNames: Set[String] = Set(
    "probes-close",
    "takeover",
    "reset-meets-failures",
    "polled-takeover",
    "stale-trip-cleared",
    "stale-outcome-counts",
    "replaced-probe-late"
  ).map(n => s"target-$n.ndjson")

  /** A planted bug, the replay's mismatch that must show it, and the targeted behaviours (`target-*`, from
    * formal/circuitbreaker/targets.py) that must show it on their own, so the catch does not rest on the random set
    * reaching the case. A crash of the replay is no catch.
    */
  final case class Planted(name: String, bug: () => Breaker, shows: Mismatch => Boolean, targets: Set[String])

  val planted: List[Planted] = List(
    Planted(
      "epoch",
      epochNotBumped,
      m => m.fields.contains("epoch") || (m.fields == List("call") && m.what.contains("permit of epoch")),
      targetNames
    ),
    Planted("reset", resetFromAnyReason, m => m.fields.contains("kind"), Set("target-reset-meets-failures.ndjson")),
    Planted(
      "takeover",
      noProbeTakeover,
      m => m.action == "CAcquireCas" && m.fields.contains("call"),
      Set("target-takeover.ndjson")
    ),
    Planted(
      "stale",
      stalePermitIsCurrent,
      m => m.action == "CRecordRead" && m.fields.nonEmpty,
      Set("target-stale-outcome-counts.ndjson", "target-replaced-probe-late.ndjson")
    )
  )

  private def caught(p: Planted): URIO[List[Path], TestResult] =
    ZIO.serviceWithZIO[List[Path]] { behaviours =>
      val sample        = behaviours.take(SampleSize)
      val red           = replay(sample, p.bug).mismatches
      val targeted      = behaviours.filter(f => p.targets.contains(f.getFileName.toString))
      val redByTargeted = replay(targeted, p.bug).mismatches
      val shown         = red.exists(p.shows)
      val shownByTarget = redByTargeted.exists(p.shows)
      val allLoaded     = !required || named(targeted) == p.targets
      ZIO
        .logWarning(
          s"${p.name}: ${red.count(p.shows)} of ${sample.size} sampled behaviours red, ${redByTargeted.count(p.shows)} " +
            s"of ${targeted.size} targeted; first: ${red.headOption.getOrElse("none")}"
        )
        .as(
          (assertTrue(shown) ?? "the replay did not show the planted bug") &&
            (assertTrue(shownByTarget) ?? "the targeted behaviours did not show it") &&
            (assertTrue(allLoaded) ?? "a targeted behaviour is not loaded")
        )
    }

  def spec = suite("CircuitBreakerReplaySpec")(
    test("every behaviour of the model replays through the breaker with the model's state after each step") {
      ZIO.serviceWith[List[Path]] { behaviours =>
        val result = replay(behaviours, real)
        val report = result.mismatches.take(10).mkString("\n")
        assertTrue(behaviours.nonEmpty, result.mismatches.isEmpty) ??
          s"${behaviours.size} behaviours, ${result.mismatches.size} mismatches:\n$report"
      }
    },
    test("every label of the map and every named case is reached") {
      ZIO.serviceWithZIO[List[Path]] { behaviours =>
        val result  = replay(behaviours, real)
        val missing = (expectedLabels ++ expectedCases) -- result.reached.keySet
        val counts  = result.reached.toList.sortBy(_._1).map { case (k, n) => s"$k $n" }.mkString("; ")
        ZIO
          .logWarning(s"${behaviours.size} behaviours, ${result.steps} steps; reached: $counts")
          .as(assertTrue(missing.isEmpty) ?? s"never reached: ${missing.toList.sorted}")
      }
    },
    test("a pass-through wrapper of the real breaker replays like it, so a planted bug's red is the bug's") {
      ZIO.serviceWith[List[Path]] { behaviours =>
        val result = replay(behaviours.take(SampleSize), () => new Wrapped(new Real))
        assertTrue(behaviours.nonEmpty, result.mismatches.isEmpty) ?? result.mismatches.take(3).mkString("\n")
      }
    },
    test("the targeted behaviours are all loaded when they are required") {
      ZIO.serviceWith[List[Path]] { behaviours =>
        val names = named(behaviours)
        assertTrue(!required || targetNames.subsetOf(names)) ?? s"missing: ${(targetNames -- names).toList.sorted}"
      }
    },
    test("an epoch that a transition does not bump fails the replay") {
      caught(planted(0))
    },
    test("a reset that also closes a circuit that failures opened fails the replay") {
      caught(planted(1))
    },
    test("a probe slot that is never taken over fails the replay") {
      caught(planted(2))
    },
    test("an outcome recorded under a stale permit that counts fails the replay") {
      caught(planted(3))
    }
  ).provideShared(ZLayer.fromZIO(loaded)) @@ (
    if (location.isEmpty && !required) TestAspect.ignore
    else TestAspect.identity
  )
}
