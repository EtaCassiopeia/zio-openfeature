package zio.openfeature.extras

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{CountDownLatch, Semaphore, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import java.util.concurrent.locks.LockSupport

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import dev.openfeature.sdk.{
  ErrorCode,
  EvaluationContext => OFEvaluationContext,
  ImmutableContext,
  ProviderEvaluation,
  Reason
}
import zio._
import zio.openfeature.internal.ProviderEvaluations
import zio.openfeature.testsupport.ModelBehaviours
import zio.openfeature.testsupport.ModelBehaviours.{Behaviour, Json, ModelState}
import zio.test._

/** Behaviours of the TLA+ model (`formal/caching/Generate.tla`, written by `formal/generate.py`), replayed through the
  * real `CachingProvider` under a schedule the test controls. Each caller is a thread of its own. The delegate holds
  * every call at two gates: gate A before it reads its version, which a miss reaches and `CEval` releases, and gate B
  * before it returns, which `CWrite` releases with the model's result. Joins and hits are told apart by zio-cache's hit
  * count and by whether the caller returns or parks. The map is `formal/caching/replay-map.md`; `step` is its twin.
  *
  * Once the model's state is settled (no process in the middle of a step the replay cannot split), the code's epoch,
  * the keys the cache holds, its hit and miss counts, each caller's delegate calls and each answer must equal the
  * model's. Every wait is bounded: a gate that is never reached fails the behaviour, never passes it.
  *
  * The behaviours come from the paths in `zof.caching.behaviours` (a system property, or the `ZOF_CACHING_BEHAVIOURS`
  * environment variable), comma-separated files or directories of `*.ndjson`; without it the suite is ignored, unless
  * `ZOF_CACHING_BEHAVIOURS_REQUIRED` is set, when it fails. The `Formal model` workflow generates them and runs this
  * suite with that variable set, so a lost property cannot pass as a skip.
  */
object CachingReplaySpec extends ZIOSpecDefault {

  // How long the replay waits for the code to reach a point, and how long the delegate holds a gate on its own. The
  // hold is the longer, so that a call the replay holds never lets itself go.
  val WaitMillis: Long = 5000L
  val HoldMillis: Long = 30000L

  val FlagKey: String  = "flag"
  val FlagType: String = "string"
  private val ctx      = new ImmutableContext("user-1")

  final class Gate {
    val reached = new Semaphore(0)
    val release = new Semaphore(0)
  }

  /** One caller's two gates, the result gate B returns, and how often the caller called the delegate. */
  final class CallerGates {
    val a                = new Gate
    val b                = new Gate
    @volatile var result = "ok"
    val calls            = new AtomicInteger(0)
  }

  /** Answers `r@pv` for the model's result `r` and the version it read at gate A. A caller is named by the default
    * value it passes, which reaches the delegate through its own `CacheKey`'s thunk. `soft` is a `DEFAULT` result or an
    * error code, as `softAsDefault` says; `fail` throws.
    */
  final class ReplayDelegate(callers: Int, softAsDefault: Boolean) extends CachingInvalidationRaceSpec.GatedProvider {
    val pv                           = new AtomicLong(0L)
    val gates: Map[Int, CallerGates] = (1 to callers).map(_ -> new CallerGates).toMap

    private def hold(gate: Gate, name: String): Unit = {
      gate.reached.release()
      if (!gate.release.tryAcquire(HoldMillis, TimeUnit.MILLISECONDS))
        throw new IllegalStateException(s"gate $name was never released")
    }

    override def getStringEvaluation(
      key: String,
      default: String,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[String] = {
      val g = gates(default.toInt)
      hold(g.a, "A")
      g.calls.incrementAndGet()
      val version = pv.get()
      hold(g.b, "B")
      g.result match {
        case "ok"                    => ProviderEvaluations.of(s"ok@$version", Reason.STATIC.toString)
        case "soft" if softAsDefault => ProviderEvaluations.of(s"soft@$version", Reason.DEFAULT.toString)
        case "soft"                  => ProviderEvaluations.error(s"soft@$version", ErrorCode.GENERAL, "soft")
        case _                       => throw new RuntimeException(s"fail@$version")
      }
    }
  }

  /** One caller's evaluation, on a thread of its own. */
  final class Call(caller: Int, provider: CachingProvider) {
    @volatile var answer: Option[Either[Throwable, ProviderEvaluation[String]]] = None
    val done                                                                    = new CountDownLatch(1)
    val thread                                                                  = new Thread(
      () => {
        answer = Some(
          try Right(provider.getStringEvaluation(FlagKey, caller.toString, ctx))
          catch { case e: Throwable => Left(e) }
        )
        done.countDown()
      },
      s"caching-replay-caller-$caller"
    )
    thread.setDaemon(true)
    def returned: Boolean = done.getCount == 0
    def parked: Boolean   = !returned && Set(Thread.State.WAITING, Thread.State.TIMED_WAITING)(thread.getState)
  }

  // What the replay reads of a model state.
  implicit final class CacheModel(m: ModelState) {
    private def obj(j: Json): Map[String, Json] = j match {
      case Json.Obj(fields) => fields
      case other            => sys.error(s"not an object: $other")
    }
    private def at(name: String, key: Int): Json = m.fun(name).getOrElse(key.toString, sys.error(s"no $name[$key]"))

    def callers: Int                = m.fun("ran").size
    def changes: Int                = m.fun("map").size - 1
    def invalidators: Range         = (callers + 1) to (callers + changes)
    def pc(p: Int): String          = m.asStr(at("pc", p))
    def ck(c: Int): Long            = m.asNum(at("ck", c))
    def ran(c: Int): Boolean        = m.asBool(at("ran", c))
    def joined(c: Int): Int         = m.asNum(at("joined", c)).toInt
    def got(c: Int): (String, Long) = outcome(at("got", c))
    def promised(c: Int): String    = outcome(at("promise", c))._1
    def mapSt(e: Int): String       = m.asStr(obj(at("map", e)).getOrElse("st", sys.error(s"map[$e] has no st")))
    def epoch: Long                 = m.num("epoch")
    def pv: Long                    = m.num("pv")
    def actor: Int                  = m.act.params.get("p") match {
      case Some(Json.Num(n)) => n.toInt
      case other             => sys.error(s"${m.act} has no process: $other")
    }

    private def outcome(j: Json): (String, Long) = {
      val o = obj(j)
      (m.asStr(o.getOrElse("res", sys.error(s"no res in $o"))), m.asNum(o.getOrElse("pv", sys.error(s"no pv in $o"))))
    }

    /** Generate.tla's `Settled`: no process is in the middle of a sequence the replay cannot split. */
    def settled: Boolean =
      (1 to callers).forall { c =>
        val at = pc(c)
        !Set("CGet", "CTail", "CRetry")(at) && !(at == "CAwait" && promised(joined(c)) != "none")
      } && invalidators.forall(i => pc(i) != "IClear")
  }

  /** `fields` name what differs: a call's observation (`lookup`, `gate`, `retry`, `answer`) or the compared state;
    * `crashed` is a replay that threw instead of finding a difference.
    */
  final case class Mismatch(
    behaviour: String,
    step: Int,
    action: String,
    what: String,
    fields: List[String],
    crashed: Boolean = false
  ) {
    override def toString: String = s"$behaviour step $step ($action) [${fields.mkString(",")}]: $what"
  }

  final class Reached {
    val counts: mutable.Map[String, Int] = mutable.Map.empty
    def +=(key: String): Unit            = counts.update(key, counts.getOrElse(key, 0) + 1)
  }

  final case class Replay(mismatches: List[Mismatch], reached: Map[String, Int], behaviours: Int, steps: Int)

  /** Polls `observe` until it answers or `WaitMillis` passes. */
  private def within[A](observe: => Option[A]): Option[A] = {
    val deadline = java.lang.System.nanoTime() + WaitMillis * 1000000L
    var seen     = observe
    while (seen.isEmpty && java.lang.System.nanoTime() < deadline) {
      LockSupport.parkNanos(200000L)
      seen = observe
    }
    seen
  }

  private def acquired(s: Semaphore): Boolean = s.tryAcquire(WaitMillis, TimeUnit.MILLISECONDS)

  /** The code under one behaviour: a fresh delegate and wrapper, and the callers started so far. */
  final class Run(val callers: Int, val changes: Int, val softAsDefault: Boolean) {
    val delegate = new ReplayDelegate(callers, softAsDefault)
    val provider = CachingProvider(delegate)
    provider.initialize(ctx)
    val calls: mutable.Map[Int, Call] = mutable.Map.empty
    // The model's own counts, which zio-cache's must equal.
    var hits: Long              = 0L
    var misses: Long            = 0L
    val evaluations: Array[Int] = Array.fill(callers + 1)(0)

    def probe(gen: Long): (Boolean, zio.cache.CacheStats) = provider.probe(FlagKey, FlagType, ctx, gen)

    /** Emits the delegate's configuration change and waits for `onDelegateEvent` to have handled it. */
    def configurationChanged(): Boolean = {
      val emitter = new Thread(() => delegate.configChanged(), "caching-replay-emitter")
      emitter.setDaemon(true)
      emitter.start()
      emitter.join(WaitMillis)
      !emitter.isAlive
    }

    /** Lets every held call go, so that no thread outlives the behaviour; true when every caller returned. */
    def release(): Boolean = {
      delegate.gates.values.foreach { g =>
        g.result = "ok"
        g.a.release.release(4)
        g.b.release.release(4)
      }
      val finished = calls.values.forall(_.done.await(WaitMillis, TimeUnit.MILLISECONDS))
      provider.shutdown()
      finished
    }
  }

  private def expectedAnswer(run: Run, res: String, pv: Long, ran: Boolean): String = {
    def reason(own: Reason) = if (ran) own.toString else Reason.CACHED.toString
    res match {
      case "ok"                        => s"ok@$pv reason=${reason(Reason.STATIC)} error=null"
      case "soft" if run.softAsDefault => s"soft@$pv reason=${reason(Reason.DEFAULT)} error=null"
      case "soft"                      => s"soft@$pv reason=${reason(Reason.ERROR)} error=${ErrorCode.GENERAL}"
      case _                           => s"threw fail@$pv"
    }
  }

  private def codeAnswer(answer: Either[Throwable, ProviderEvaluation[String]]): String = answer match {
    case Left(e)   => s"threw ${e.getMessage}"
    case Right(ev) => s"${ev.getValue} reason=${ev.getReason} error=${ev.getErrorCode}"
  }

  /** The code's state against a settled model state: each difference, by the field it is in. */
  private def compare(run: Run, m: ModelState): List[(String, String)] = {
    val out = List.newBuilder[(String, String)]
    val gen = run.provider.currentGeneration
    if (gen != m.epoch) out += "epoch" -> s"generation $gen, model epoch ${m.epoch}"
    val held                          = (0 to run.changes).map(e => e -> run.probe(e.toLong)._1).toMap
    val want                          = (0 to run.changes).map(e => e -> (m.mapSt(e) != "absent")).toMap
    def epochs(of: Map[Int, Boolean]) = of.filter(_._2).keys.toList.sorted.mkString("{", ",", "}")
    if (held != want) out += "map" -> s"the cache holds a key under epochs ${epochs(held)}, the model ${epochs(want)}"
    val stats = run.probe(0L)._2
    if (stats.size != want.count(_._2))
      out += "size"                           -> s"the cache holds ${stats.size} entries, the model ${want.count(_._2)}"
    if (stats.hits != run.hits) out += "hits" -> s"zio-cache counts ${stats.hits} hits, the model ${run.hits}"
    if (stats.misses != run.misses)
      out += "misses" -> s"zio-cache counts ${stats.misses} misses, the model ${run.misses}"
    (1 to run.callers).foreach { c =>
      val calls = run.delegate.gates(c).calls.get()
      if (calls != run.evaluations(c))
        out += "calls" -> s"caller $c called the delegate $calls times, the model ${run.evaluations(c)}"
      val returned = run.calls.get(c).flatMap(_.answer)
      (m.pc(c), returned) match {
        case ("Done", Some(answer)) =>
          val (res, pv) = m.got(c)
          val expected  = expectedAnswer(run, res, pv, m.ran(c))
          val got       = codeAnswer(answer)
          if (got != expected) out += "answer" -> s"caller $c answered '$got', the model '$expected'"
        case ("Done", None)     => out += "answer" -> s"caller $c has not returned, the model's has"
        case (at, Some(answer)) => out += "answer" -> s"caller $c returned '${codeAnswer(answer)}' while at $at"
        case _                  => ()
      }
    }
    out.result()
  }

  /** The code's part of one model step, with the cases it reached; a difference in what the code did is returned. */
  private def step(run: Run, before: ModelState, after: ModelState, reached: Reached): Option[(String, String)] = {
    val label                        = after.act.name
    def reach(variant: String): Unit = reached += s"$label:$variant"

    label match {
      case "Done" => reach("stutter"); None

      case "Change" =>
        if (after.pv > before.pv) { run.delegate.pv.incrementAndGet(); reach("bump") }
        else reach("exit")
        None

      case "IQueued" => reach("queued"); None

      case "IBump" =>
        reach("emit")
        if (run.configurationChanged()) None
        else Some("gate" -> "the delegate's configuration change was not handled")

      case "IClear" => reach("fused"); None

      case "CStart" => reach("fused"); None

      case "CGet" =>
        val c    = after.actor
        val g    = run.delegate.gates(c)
        val hits = run.probe(0L)._2.hits
        val call = new Call(c, run.provider)
        run.calls.update(c, call)
        call.thread.start()
        def joined: Boolean = call.parked && g.a.reached.availablePermits == 0 && run.probe(0L)._2.hits > hits
        val seen            = within {
          if (g.a.reached.tryAcquire()) Some("miss")
          else if (call.returned) Some("hit")
          else if (joined && { LockSupport.parkNanos(1000000L); joined }) Some("join")
          else None
        }
        val expected = after.pc(c) match {
          case "CEval"  => run.misses += 1; "miss"
          case "CAwait" => run.hits += 1; "join"
          case "CTail"  => run.hits += 1; "hit"
          case other    => sys.error(s"unexpected pc $other after CGet")
        }
        reach(expected)
        if (expected == "hit" && after.got(c)._1 == "ok") reached += "case:hit on a kept ok"
        seen match {
          case Some(`expected`) => None
          case Some(other)      => Some("lookup" -> s"caller $c's lookup was a $other, the model's a $expected")
          case None             => Some("lookup" -> s"caller $c neither reached gate A, returned nor parked")
        }

      case "CEval" =>
        val c = after.actor
        run.evaluations(c) += 1
        reach("release A")
        run.delegate.gates(c).a.release.release()
        if (acquired(run.delegate.gates(c).b.reached)) None
        else Some("gate" -> s"caller $c's evaluation did not reach gate B")

      case "CWrite" =>
        val c   = after.actor
        val res = after.got(c)._1
        reach(res)
        if (before.ck(c) < before.epoch) reached += "case:stranded write"
        if (res == "soft") reached += s"case:soft as ${if (run.softAsDefault) "DEFAULT" else "an error code"}"
        run.delegate.gates(c).result = res
        run.delegate.gates(c).b.release.release()
        None

      case "CAwait" => reach("woken"); None

      case "CTail" =>
        val c   = after.actor
        val res = before.got(c)._1
        reach(if (res == "ok") "keep" else "remove")
        if (res == "soft") reached += "case:soft result removed"
        after.pc(c) match {
          case "Done" =>
            reach("return")
            if (run.calls.get(c).exists(call => call.done.await(WaitMillis, TimeUnit.MILLISECONDS))) None
            else Some("answer" -> s"caller $c did not return")
          case "CRetry" => reach("retry"); None
          case other    => sys.error(s"unexpected pc $other after CTail")
        }

      case "CRetry" =>
        val c = after.actor
        reach("to gate A")
        val g = run.delegate.gates(c)
        within {
          if (g.a.reached.tryAcquire()) Some(true)
          else if (run.calls.get(c).exists(_.returned)) Some(false)
          else None
        } match {
          case Some(true)  => None
          case Some(false) => Some("retry" -> s"caller $c returned instead of retrying")
          case None        => Some("retry" -> s"caller $c's retry did not reach gate A")
        }

      case "CRetryEval" =>
        val c   = after.actor
        val res = after.got(c)._1
        val g   = run.delegate.gates(c)
        run.evaluations(c) += 1
        reach(res)
        if (before.joined(c) != 0) reached += "case:join on a failed lookup, then its retry"
        if (res == "soft") reached += s"case:soft as ${if (run.softAsDefault) "DEFAULT" else "an error code"}"
        g.result = res
        g.a.release.release()
        if (!acquired(g.b.reached)) Some("gate" -> s"caller $c's retry did not reach gate B")
        else {
          g.b.release.release()
          if (run.calls.get(c).exists(_.done.await(WaitMillis, TimeUnit.MILLISECONDS))) None
          else Some("answer" -> s"caller $c's retry did not return")
        }

      case other => Some("action" -> s"no replay for action $other")
    }
  }

  private def replayOne(b: Behaviour, reached: Reached): Option[Mismatch] = {
    val first                      = b.states.head
    val run                        = new Run(first.callers, first.changes, (b.name.hashCode & 1) == 0)
    var mismatch: Option[Mismatch] = None
    try {
      val steps = b.states.zip(b.states.drop(1)).zipWithIndex.iterator
      while (mismatch.isEmpty && steps.hasNext) {
        val ((before, after), i)                        = steps.next()
        def at(found: List[(String, String)]): Mismatch =
          Mismatch(b.name, i + 1, after.act.name, found.map(_._2).mkString("; "), found.map(_._1).distinct)
        val result =
          try
            step(run, before, after, reached).map(f => at(List(f))).orElse {
              if (after.settled) Some(compare(run, after)).filter(_.nonEmpty).map(at) else None
            }
          catch {
            case e: RuntimeException =>
              Some(Mismatch(b.name, i + 1, after.act.name, s"replay failed: $e", Nil, crashed = true))
          }
        mismatch = result
      }
      if (mismatch.isEmpty && (1 to run.callers).exists(c => b.states.last.pc(c) != "Done"))
        reached += "case:held at the end"
    } finally {
      val finished = run.release()
      if (mismatch.isEmpty && !finished)
        mismatch = Some(Mismatch(b.name, b.states.size, "release", "a caller did not return once released", Nil))
    }
    mismatch
  }

  /** Replays the behaviours one file at a time, so that a nightly set need not fit in memory. */
  def replay(files: List[Path]): Replay = {
    val reached    = new Reached
    val mismatches = List.newBuilder[Mismatch]
    var steps      = 0
    files.foreach { file =>
      val b = read(file)
      steps += b.states.size - 1
      mismatches ++= replayOne(b, reached)
    }
    Replay(mismatches.result(), reached.counts.toMap, files.size, steps)
  }

  private def read(file: Path): Behaviour =
    ModelBehaviours.parseBehaviour(file.getFileName.toString, Files.readAllLines(file).asScala.toList) match {
      case Right(b)  => b
      case Left(why) => throw new IllegalArgumentException(why)
    }

  // Every label of the map, with the variants each has, and the named cases of the issue.
  val expectedLabels: Set[String] = Set(
    "Change:bump",
    "Change:exit",
    "IQueued:queued",
    "IBump:emit",
    "IClear:fused",
    "CStart:fused",
    "CGet:miss",
    "CGet:join",
    "CGet:hit",
    "CEval:release A",
    "CWrite:ok",
    "CWrite:soft",
    "CWrite:fail",
    "CAwait:woken",
    "CTail:keep",
    "CTail:remove",
    "CTail:return",
    "CTail:retry",
    "CRetry:to gate A",
    "CRetryEval:ok",
    "CRetryEval:soft",
    "CRetryEval:fail",
    "Done:stutter"
  )

  val expectedCases: Set[String] = Set(
    "case:stranded write",
    "case:join on a failed lookup, then its retry",
    "case:hit on a kept ok",
    "case:soft result removed",
    "case:soft as DEFAULT",
    "case:soft as an error code",
    "case:held at the end"
  )

  val targetNames: Set[String] = Set(
    "in-flight-stale",
    "stranded-write",
    "joiner-held-for-retry",
    "joiner-retry-returns",
    "hit-kept-ok",
    "soft-removed",
    "own-failure-removed",
    "stranded-then-miss",
    "miss-beside-retry"
  ).map(n => s"target-$n.ndjson")

  private val location: Option[String] =
    ModelBehaviours.location("zof.caching.behaviours", "ZOF_CACHING_BEHAVIOURS")

  private val required: Boolean = sys.env.contains("ZOF_CACHING_BEHAVIOURS_REQUIRED")

  private val loaded: Task[List[Path]] =
    ZIO.attempt(location.getOrElse(sys.error("zof.caching.behaviours (or ZOF_CACHING_BEHAVIOURS) is not set"))).map {
      _.split(',').toList.map(_.trim).filter(_.nonEmpty).flatMap(d => ModelBehaviours.files(Paths.get(d)))
    }

  /** How many mismatches each field has, `crashed` for a replay that threw; formal/caching/codemutants.py reads it. */
  def byField(mismatches: List[Mismatch]): String = {
    val keys = mismatches.flatMap(m => if (m.crashed) List("crashed") else m.fields)
    keys.groupBy(identity).toList.sortBy(_._1).map { case (k, v) => s"$k=${v.size}" }.mkString("[", " ", "]")
  }

  // One replay, shared by the tests below: each behaviour runs on real threads.
  private val replayed: ZLayer[Any, Throwable, (List[Path], Replay)] =
    ZLayer.fromZIO(for {
      files  <- loaded
      result <- ZIO.attemptBlocking(replay(files))
      _      <- ZIO.logWarning(
        s"caching replay: ${result.behaviours} behaviours, ${result.steps} steps, " +
          s"${result.mismatches.size} mismatches" +
          s"; by field: ${byField(result.mismatches)}" +
          s"; targeted by field: ${byField(result.mismatches.filter(m => targetNames(m.behaviour)))}" +
          result.mismatches.take(10).map("\n  " + _).mkString
      )
    } yield (files, result))

  def spec = suite("CachingReplaySpec")(
    test("every behaviour of the model replays through the cache with the model's state once settled") {
      ZIO.serviceWith[(List[Path], Replay)] { case (files, result) =>
        val report     = result.mismatches.take(10).mkString("\n")
        val mismatches = result.mismatches.size
        assertTrue(files.nonEmpty, mismatches == 0) ??
          s"${files.size} behaviours, ${result.mismatches.size} mismatches:\n$report"
      }
    },
    test("every label of the map and every named case is reached") {
      ZIO.serviceWithZIO[(List[Path], Replay)] { case (_, result) =>
        val missing = (expectedLabels ++ expectedCases) -- result.reached.keySet
        val counts  = result.reached.toList.sortBy(_._1).map { case (k, n) => s"$k $n" }.mkString("; ")
        ZIO
          .logWarning(s"caching replay reached: $counts")
          .as(assertTrue(missing.isEmpty) ?? s"never reached: ${missing.toList.sorted}")
      }
    },
    test("the targeted behaviours are all loaded when they are required") {
      ZIO.serviceWith[(List[Path], Replay)] { case (files, _) =>
        val names = files.map(_.getFileName.toString).toSet
        assertTrue(!required || targetNames.subsetOf(names)) ?? s"missing: ${(targetNames -- names).toList.sorted}"
      }
    }
  ).provideShared(replayed) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ (
    if (location.isEmpty && !required) TestAspect.ignore
    else TestAspect.identity
  )
}
