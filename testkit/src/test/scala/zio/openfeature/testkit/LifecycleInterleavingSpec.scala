package zio.openfeature.testkit

import zio.openfeature.testsupport.LeakCheck.{awaitNoLeaks, snapshot}

import zio._
import zio.test._
import zio.openfeature._
import zio.openfeature.internal.{FeatureFlagsFactories, LifecycleProbe}
import dev.openfeature.sdk.{
  ErrorCode => OFErrorCode,
  FeatureProvider,
  ImmutableMetadata,
  OpenFeatureAPI,
  ProviderEvent => OFProviderEvent,
  ProviderEventDetails
}

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters._

/** Deterministic interleavings of the provider lifecycle.
  *
  * A primary action (a swap that succeeds, fails or times out; a shutdown; a provider event) runs with a probe that
  * holds it at one named point of the lifecycle protocol ([[LifecycleProbe.Point]]). While it is held, a competing
  * action runs (an event, a shutdown, evaluations, another swap, releasing a hung initialization); then the primary is
  * let go. Every point a primary reaches is paired with every competitor, and each outcome must agree with
  * [[LifecycleModel]] after one of the orders the two actions can take effect in. While a swap is held, the status must
  * be the one its documented step sets, whatever the competitor did; and when the scenario ends, every provider it
  * created must have been shut down exactly once.
  *
  * The reach table lists the points each primary passes. A point a primary stops reaching, or a new one it reaches,
  * fails the table check: that is how a probe that moved is noticed.
  */
object LifecycleInterleavingSpec extends ZIOSpecDefault {

  import LifecycleModel._
  import LifecycleProbe.Point

  // The probe

  /** Records the name of every point reached once armed, and holds the first hit of `holdAt` until `release`. Points
    * reached before [[arm]] (the build's own READY) are recorded apart and never held.
    */
  final class TestProbe(
    holdAt: Option[String],
    armedAtStart: Boolean,
    val held: Promise[Nothing, Unit],
    val release: Promise[Nothing, Unit]
  ) extends LifecycleProbe {
    private val warmupQueue  = new ConcurrentLinkedQueue[String]()
    private val reachedQueue = new ConcurrentLinkedQueue[String]()
    private val armed        = new AtomicBoolean(armedAtStart)
    private val taken        = new AtomicBoolean(false)

    def warmup: Set[String]  = warmupQueue.asScala.toSet
    def reached: Set[String] = reachedQueue.asScala.toSet
    def arm: UIO[Unit]       = ZIO.succeed(armed.set(true))

    def at(point: Point): UIO[Unit] =
      ZIO.suspendSucceed {
        if (!armed.get()) { warmupQueue.add(point.name); ZIO.unit }
        else {
          reachedQueue.add(point.name)
          if (holdAt.contains(point.name) && taken.compareAndSet(false, true)) held.succeed(()) *> release.await
          else ZIO.unit
        }
      }

    /** Arm once the build's READY has reached the bridge and been applied, so it is not what gets held. */
    def armAfterBuild: UIO[Boolean] =
      ZIO
        .succeed(warmup)
        .repeat(
          Schedule.spaced(10.millis) && Schedule.recurWhile[Set[String]](w => !w("DeliverEvent") || !w("ApplySignal"))
        )
        .timeout(HoldWait)
        .map(_.isDefined) <* arm
  }

  object TestProbe {
    def make(holdAt: Option[String], armed: Boolean = false): UIO[TestProbe] =
      (Promise.make[Nothing, Unit] <*> Promise.make[Nothing, Unit]).map { case (h, r) =>
        new TestProbe(holdAt, armed, h, r)
      }
  }

  // Actions

  sealed trait Primary extends Product with Serializable
  object Primary {
    case object SwapGood    extends Primary
    case object SwapFailing extends Primary

    /** A swap whose new provider's `initialize()` parks: it reaches `initTimeout`. */
    case object SwapHanging extends Primary
    case object Shutdown    extends Primary

    /** The installed provider reports a non-fatal ERROR. */
    case object EventError extends Primary

    val all: List[Primary] = List(SwapGood, SwapFailing, SwapHanging, Shutdown, EventError)
  }

  sealed trait Competitor extends Product with Serializable
  object Competitor {
    final case class Emit(event: Action) extends Competitor
    case object Shutdown                 extends Competitor
    case object Evaluate                 extends Competitor
    case object SwapGood                 extends Competitor
    case object SwapFailing              extends Competitor

    /** Interrupt the fiber running the primary. */
    case object Interrupt    extends Competitor
    case object ReleaseHangs extends Competitor

    val all: List[Competitor] = List(
      Emit(Action.EmitReady),
      Emit(Action.EmitError(fatal = false)),
      Emit(Action.EmitError(fatal = true)),
      Emit(Action.EmitStale),
      Shutdown,
      Evaluate,
      SwapGood,
      SwapFailing,
      Interrupt,
      ReleaseHangs
    )
  }

  /** The pairs the docs give an outcome for. A swap after a shutdown is not one ("evaluations fail from then on"), and
    * releasing hangs only matters to a swap whose provider hangs.
    */
  private def applies(primary: Primary, competitor: Competitor): Boolean = (primary, competitor) match {
    case (Primary.Shutdown, Competitor.SwapGood | Competitor.SwapFailing) => false
    case (Primary.EventError, Competitor.Interrupt)                       => false
    case (Primary.SwapHanging, _)                                         => true
    case (_, Competitor.ReleaseHangs)                                     => false
    case _                                                                => true
  }

  /** The points each primary always passes, in no particular order. */
  val reachTable: Map[Primary, Set[String]] = Map(
    Primary.SwapGood    -> Set("SwapStart", "ApplySignal", "SdkBind", "DeliverEvent", "SwapDone"),
    Primary.SwapFailing -> Set("SwapStart", "ApplySignal", "SdkBind", "DeliverEvent", "SwapRollback", "SwapDone"),
    Primary.SwapHanging ->
      Set("SwapStart", "ApplySignal", "SdkBind", "SwapTimeout", "OrphanRecorded", "LateReady", "SwapDone"),
    Primary.Shutdown   -> Set("ShutdownStart", "ApplySignal", "ShutdownDone"),
    Primary.EventError -> Set("DeliverEvent", "ApplySignal")
  )

  /** Points a primary may also pass, depending on timing: an event the SDK sends while the provider initializes or
    * shuts down can reach the bridge before the primary finishes, or after.
    */
  val mayReach: Map[Primary, Set[String]] =
    Map(Primary.SwapHanging -> Set("DeliverEvent"), Primary.Shutdown -> Set("DeliverEvent")).withDefaultValue(Set.empty)

  // The system under test

  private val InitTimeout = 1.second
  private val HoldWait    = 3.seconds
  private val Bound       = 6.seconds
  private val Settle      = 300.millis
  private val OnceBound   = 3.seconds

  private val Flag   = "flag"
  private val Who    = "who"
  private val TagKey = "interleaving-tag"

  type Provider = FaultyProvider[FeatureProvider]

  private def provider(id: String, faults: Fault*): URIO[Scope, Provider] =
    FaultyProvider
      .scoped(Map(Flag -> true, Who -> id), id)
      .tap(p => ZIO.when(faults.nonEmpty)(p.script(Op.Initialize, faults: _*)))

  private def emit(p: Provider, action: Action, tag: String): UIO[Unit] = ZIO.succeed {
    val meta = ImmutableMetadata.builder().addString(TagKey, tag).build()
    def details(code: Option[OFErrorCode]): ProviderEventDetails = code match {
      case Some(c) => ProviderEventDetails.builder().message(tag).eventMetadata(meta).errorCode(c).build()
      case None    => ProviderEventDetails.builder().message(tag).eventMetadata(meta).build()
    }
    val (event, payload) = action match {
      case Action.EmitError(fatal) =>
        (OFProviderEvent.PROVIDER_ERROR, details(Some(if (fatal) OFErrorCode.PROVIDER_FATAL else OFErrorCode.GENERAL)))
      case Action.EmitStale => (OFProviderEvent.PROVIDER_STALE, details(None))
      case _                => (OFProviderEvent.PROVIDER_READY, details(None))
    }
    p.emit(event, payload)
    ()
  }

  private def observe(ff: FeatureFlags): UIO[Observation] =
    for {
      status    <- ff.providerStatus
      meta      <- ff.providerMetadata
      typed     <- ff.boolean(Flag, false).either
      orDefault <- ff.booleanOrDefault(Flag, false).exit
      who       <- ff.string(Who, "").either
    } yield Observation(status, meta.name, typed, orDefault, who)

  private def evaluate(ff: FeatureFlags): UIO[Chunk[Either[FeatureFlagError, String]]] =
    ZIO.foreachPar(Chunk.fromIterable(1 to 8))(_ => ff.string(Who, "").either)

  private val p0 = Installed("p0", "p0")
  private val p1 = Installed("p1", "p1")
  private val p2 = Installed("p2", "p2")
  private val p3 = Installed("p3", "p3")

  private def primaryModel(primary: Primary, m: Model): Model = primary match {
    case Primary.SwapGood    => m.step(Action.SetProvider(Kind.Good), p1)
    case Primary.SwapFailing => m.step(Action.SetProvider(Kind.FailingInit), p1)
    case Primary.SwapHanging => m.step(Action.SetProvider(Kind.HangingInit), p1).settle
    case Primary.Shutdown    => m.step(Action.Shutdown, p0)
    case Primary.EventError  => m.step(Action.EmitError(fatal = false), p0)
  }

  private def competitorModel(competitor: Competitor, m: Model): Model = competitor match {
    // The event comes from the provider installed when the scenario started; once replaced, it reaches no one.
    case Competitor.Emit(event) => if (m.installed.contains(p0) && m.swap.isEmpty) m.step(event, p0) else m
    case Competitor.Shutdown    => m.step(Action.Shutdown, p0)
    case Competitor.Evaluate    => m
    case Competitor.SwapGood    => m.step(Action.SetProvider(Kind.Good), p2)
    case Competitor.SwapFailing => m.step(Action.SetProvider(Kind.FailingInit), p3)
    // "Once a swap has started it finishes ... even if the calling fiber is interrupted"; a started shutdown too.
    case Competitor.Interrupt    => m
    case Competitor.ReleaseHangs => m.step(Action.ReleaseHangs, p0)
  }

  /** The models the pair can end in: either order, and a competing event dropped while a swap held the gate. A swap
    * holds the swap lock at every point it can be held, so a competing shutdown or swap always comes after it.
    */
  private def outcomes(primary: Primary, competitor: Competitor, before: Model): List[Model] = {
    val primaryFirst    = competitorModel(competitor, primaryModel(primary, before))
    val competitorFirst = primaryModel(primary, competitorModel(competitor, before))
    val swap            = primary match {
      case Primary.SwapGood | Primary.SwapFailing | Primary.SwapHanging => true
      case _                                                            => false
    }
    val locked =
      competitor == Competitor.Shutdown || competitor == Competitor.SwapGood || competitor == Competitor.SwapFailing
    val hanging = (primary, competitor) match {
      // Released before the timeout, the hanging provider's initialize() succeeds and the swap with it.
      case (Primary.SwapHanging, Competitor.ReleaseHangs) =>
        List(before.step(Action.SetProvider(Kind.HangingInit), p1).step(Action.ReleaseHangs, p0))
      case _ => Nil
    }
    val dropped = competitor match {
      case Competitor.Emit(_) if swap => List(primaryModel(primary, before))
      case _                          => Nil
    }
    // The primary's own event is sent before any point it can be held at, so the competitor's always comes second.
    val ordered =
      if ((swap && locked) || primary == Primary.EventError) List(primaryFirst) else List(primaryFirst, competitorFirst)
    ordered ++ hanging ++ dropped
  }

  /** What the status must be while the primary is held at `point`, whatever the competitor does. */
  private def heldStatus(primary: Primary, point: String): Option[ProviderStatus] = (primary, point) match {
    // "The status becomes NotReady" while the SDK initializes the new provider, and events during a swap do not
    // change it.
    case (Primary.SwapGood | Primary.SwapFailing | Primary.SwapHanging, "SdkBind") => Some(ProviderStatus.NotReady)
    case (Primary.SwapHanging, "SwapTimeout")                                      => Some(ProviderStatus.NotReady)
    case (Primary.SwapFailing, "SwapRollback")                                     => Some(ProviderStatus.Error)
    case (Primary.Shutdown, "ShutdownDone")                                        => Some(ProviderStatus.ShuttingDown)
    case _                                                                         => None
  }

  /** @param reached
    *   the points reached before the scope closed
    * @param closed
    *   the points reached by the end, scope close included
    */
  final case class Outcome(reached: Set[String], closed: Set[String], violations: Chunk[String])

  /** Run `primary`, held at `holdAt` while `competitor` runs, and check it. */
  def scenario(primary: Primary, holdAt: Option[String], competitor: Option[Competitor]): UIO[Outcome] = {
    val where = s"$primary held at ${holdAt.getOrElse("nothing")}, ${competitor.fold("alone")(c => s"beside $c")}"
    val run: URIO[Scope, (TestProbe, Set[String], Chunk[String], List[(String, Provider)])] = for {
      probe   <- TestProbe.make(holdAt)
      _       <- ZIO.addFinalizer(probe.release.succeed(()))
      initial <- provider("p0")
      ff      <- FeatureFlagsFactories
        .buildWithTracking(
          initial,
          domain = Some(s"interleaving-${java.util.UUID.randomUUID()}"),
          version = None,
          initialHooks = Nil,
          statusRef = None,
          addShutdownFinalizer = true,
          apiOverride = Some(OpenFeatureAPI.createIsolated()),
          initTimeout = InitTimeout,
          probe = probe
        )
        .orDie
      first <- provider(
        "p1",
        (primary match {
          case Primary.SwapFailing => Seq(Fault.Throw(new RuntimeException("p1: initialize fails")))
          case Primary.SwapHanging => Seq(Fault.HangUntilReleased, Fault.Succeed)
          case _                   => Nil
        }): _*
      )
      second <- provider("p2")
      third  <- provider("p3", Fault.Throw(new RuntimeException("p3: initialize fails")))
      warmed <- probe.armAfterBuild
      cold       = if (warmed) Chunk.empty else Chunk(s"$where: the build's READY never reached the bridge")
      before     = Model.initial(p0)
      primaryRun = primary match {
        case Primary.SwapGood | Primary.SwapFailing | Primary.SwapHanging => ff.setProvider(first).either.unit
        case Primary.Shutdown                                             => ff.shutdown
        case Primary.EventError => emit(initial, Action.EmitError(fatal = false), "primary")
      }
      primaryFiber <- primaryRun.fork
      held         <- probe.held.await.timeout(HoldWait)
      unheld =
        if (holdAt.nonEmpty && held.isEmpty) Chunk(s"$where: the primary never reached its point") else Chunk.empty
      none = Chunk.empty[Either[FeatureFlagError, String]]
      // Forked: a competing shutdown or swap waits for the swap lock the held primary has.
      competing <- ZIO.foreach(competitor) {
        case Competitor.Emit(event)  => emit(initial, event, "competitor").as(none).fork
        case Competitor.Shutdown     => ff.shutdown.as(none).fork
        case Competitor.Evaluate     => evaluate(ff).fork
        case Competitor.SwapGood     => ff.setProvider(second).either.as(none).fork
        case Competitor.SwapFailing  => ff.setProvider(third).either.as(none).fork
        case Competitor.Interrupt    => primaryFiber.interrupt.as(none).fork
        case Competitor.ReleaseHangs => first.release.as(none).fork
      }
      // A competitor that waits for the swap lock is still waiting; let the events it sent arrive first.
      _       <- ZIO.sleep(Settle)
      heldNow <- ff.providerStatus
      heldWrong = (for {
        expected <- heldStatus(primary, holdAt.getOrElse(""))
        if held.nonEmpty && heldNow != expected
      } yield s"$where: while held the status is $heldNow, not $expected").toList
      _         <- probe.release.succeed(())
      done      <- primaryFiber.await.timeout(Bound)
      evaluated <- ZIO.foreach(competing)(_.join.timeout(Bound)).map(_.flatten)
      _         <- ZIO.sleep(Settle)
      observed  <- observe(ff)
      candidates = competitor.fold(List(primaryModel(primary, before)))(outcomes(primary, _, before))
      agree      = candidates.exists(violations(_, observed).isEmpty)
      mismatch   =
        if (agree) Chunk.empty
        else
          Chunk(
            s"$where: no order agrees; observed $observed; against ${primaryModel(primary, before).status}: " +
              violations(candidates.head, observed).mkString("; ")
          )
      mid = primary match {
        case Primary.SwapGood | Primary.SwapHanging => List(before.copy(status = ProviderStatus.NotReady))
        // The failed provider stays bound until the previous one is registered again.
        case Primary.SwapFailing =>
          List(
            before.copy(status = ProviderStatus.NotReady),
            before.copy(status = ProviderStatus.Error, installed = Some(p1)),
            before.copy(status = ProviderStatus.Error)
          )
        case Primary.Shutdown   => List(before.copy(status = ProviderStatus.ShuttingDown))
        case Primary.EventError => List(before.copy(status = ProviderStatus.Error))
      }
      wrongEvals = evaluated.getOrElse(none).filterNot(r => (before :: mid ++ candidates).exists(whoAllowed(_, r)))
      evalsBad   = if (wrongEvals.isEmpty) Chunk.empty else Chunk(s"$where: evaluations gave $wrongEvals")
      late       = if (done.isEmpty) Chunk(s"$where: the primary did not finish within $Bound") else Chunk.empty
      // The providers handed to the instance, each of which it must shut down exactly once.
      used = List("p0" -> initial) ++
        (if (Set[Primary](Primary.SwapGood, Primary.SwapFailing, Primary.SwapHanging)(primary)) List("p1" -> first)
         else Nil) ++
        (if (competitor.contains(Competitor.SwapGood)) List("p2" -> second) else Nil) ++
        (if (competitor.contains(Competitor.SwapFailing)) List("p3" -> third) else Nil)
    } yield (
      probe,
      probe.reached,
      cold ++ unheld ++ Chunk.fromIterable(heldWrong) ++ late ++ mismatch ++ evalsBad,
      used
    )

    for {
      scope <- Scope.make
      ran   <- run.provideEnvironment(ZEnvironment[Scope](scope)).exit
      _     <- scope.close(Exit.unit).timeout(Bound)
      out   <- ran match {
        case Exit.Failure(c) =>
          ZIO.succeed(Outcome(Set.empty, Set.empty, Chunk(s"$where: the scenario failed: ${c.prettyPrint}")))
        case Exit.Success((probe, reached, found, used)) =>
          ZIO
            .foreach(Chunk.fromIterable(used)) { case (id, p) =>
              p.calls(Op.Shutdown)
                .repeat(Schedule.spaced(10.millis) && Schedule.recurWhile(_.isEmpty))
                .timeout(OnceBound) *>
                p.calls(Op.Shutdown).map(calls => id -> calls)
            }
            .map { counts =>
              val notOnce = counts.collect {
                case (id, calls) if calls.size != 1 =>
                  val by = calls.map(c => s"${c.thread} at ${c.startedAt}").mkString(", ")
                  s"$where: $id was shut down ${calls.size} times, not once ($by)"
              }
              Outcome(reached, probe.reached, found ++ notOnce)
            }
      }
    } yield out
  }

  /** A swap held at `SwapTimeout` whose new provider then fails once released: the provider it replaced must be shut
    * down exactly once, by this instance, since the SDK never will.
    */
  private def timedOutThenFails: UIO[(Set[String], Int, Option[ProviderStatus])] = {
    val run = for {
      probe   <- TestProbe.make(Some("SwapTimeout"))
      _       <- ZIO.addFinalizer(probe.release.succeed(()))
      initial <- provider("p0")
      ff      <- FeatureFlagsFactories
        .buildWithTracking(
          initial,
          domain = Some(s"interleaving-${java.util.UUID.randomUUID()}"),
          version = None,
          initialHooks = Nil,
          statusRef = None,
          addShutdownFinalizer = true,
          apiOverride = Some(OpenFeatureAPI.createIsolated()),
          initTimeout = InitTimeout,
          probe = probe
        )
        .orDie
      inner <- FaultyProvider.make(Map(Flag -> true, Who -> "p1"), "p1")
      _     <- inner.script(Op.Initialize, Fault.Throw(new RuntimeException("p1: initialize fails")))
      outer <- FaultyProvider.wrapScoped[FeatureProvider](inner, "p1")
      _     <- probe.armAfterBuild
      _     <- outer.script(Op.Initialize, Fault.HangUntilReleased, Fault.Succeed)
      swap  <- ff.setProvider(outer).either.fork
      _     <- probe.held.await.timeout(HoldWait)
      _     <- probe.release.succeed(())
      _     <- swap.await.timeout(Bound)
      _     <- outer.release
      // The previous provider is shut down once the timed-out swap's provider reports its failure.
      _ <- initial
        .calls(Op.Shutdown)
        .repeat(Schedule.spaced(10.millis) && Schedule.recurWhile(_.isEmpty))
        .timeout(OnceBound)
      status <- ff.providerStatus
    } yield (probe, initial, status)
    for {
      scope <- Scope.make
      ran   <- run.provideEnvironment(ZEnvironment[Scope](scope))
      _     <- scope.close(Exit.unit).timeout(Bound)
      (probe, initial, status) = ran
      calls <- initial.calls(Op.Shutdown)
    } yield (probe.reached, calls.size, Some(status))
  }

  /** An async build whose provider never finishes initializing, held at its init watchdog while the provider reports
    * READY: the READY wins, and the watchdog leaves the provider running.
    */
  private def watchdogRacedByReady: UIO[(Set[String], ProviderStatus, Int)] = {
    val run = for {
      probe <- TestProbe.make(Some("Watchdog"), armed = true)
      _     <- ZIO.addFinalizer(probe.release.succeed(()))
      p     <- provider("p0", Fault.HangUntilReleased, Fault.Succeed)
      ff    <- FeatureFlagsFactories
        .buildAsyncWithTracking(
          p,
          domain = Some(s"interleaving-${java.util.UUID.randomUUID()}"),
          version = None,
          initialHooks = Nil,
          statusRef = None,
          addShutdownFinalizer = true,
          apiOverride = Some(OpenFeatureAPI.createIsolated()),
          initTimeout = InitTimeout,
          probe = probe
        )
        .orDie
      _ <- probe.held.await.timeout(HoldWait)
      _ <- emit(p, Action.EmitReady, "competitor")
      _ <- ff.providerStatus
        .repeat(Schedule.spaced(10.millis) && Schedule.recurWhile(_ != ProviderStatus.Ready))
        .timeout(HoldWait)
      _      <- probe.release.succeed(())
      _      <- ZIO.sleep(Settle)
      status <- ff.providerStatus
      calls  <- p.calls(Op.Shutdown)
    } yield (probe, status, calls.size)
    ZIO.scoped(run).map { case (probe, status, shutdowns) => (probe.reached, status, shutdowns) }
  }

  private def grid: List[(Primary, String, Competitor)] =
    for {
      primary    <- Primary.all
      point      <- reachTable(primary).toList.sorted
      competitor <- Competitor.all
      if applies(primary, competitor)
    } yield (primary, point, competitor)

  def spec: Spec[TestEnvironment with Scope, Any] = suite("LifecycleInterleavingSpec")(
    test("every primary reaches exactly the points in the reach table") {
      ZIO.foreach(Primary.all)(p => scenario(p, None, None).map(p -> _)).map { outcomes =>
        val wrong = outcomes.collect {
          case (p, o)
              if !reachTable(p).subsetOf(o.reached) || !o.reached.subsetOf(reachTable(p) ++ mayReach(p)) ||
                o.violations.nonEmpty =>
            s"$p reached ${o.reached.toList.sorted}, the table says ${reachTable(p).toList.sorted}; " +
              o.violations.mkString("; ")
        }
        val closing = outcomes.collect { case (p, o) if !o.closed("ScopeClose") => s"$p never reached ScopeClose" }
        assertTrue(wrong.isEmpty, closing.isEmpty)
      }
    },
    test("every point every primary reaches, paired with every competitor, agrees with the model") {
      for {
        baseline <- snapshot
        found    <- ZIO
          .foreachPar(grid) { case (p, point, c) => scenario(p, Some(point), Some(c)).map(_.violations) }
          .withParallelism(4)
          .map(_.flatten)
        leaked <- awaitNoLeaks(baseline, Bound)
      } yield assertTrue(found.mkString("\n") == "", leaked.isEmpty)
    } @@ TestAspect.timeout(4.minutes),
    suite("regressions")(
      test("a shutdown during a swap held after the SDK bound the new provider waits for it, and ends NotReady") {
        scenario(Primary.SwapGood, Some("SdkBind"), Some(Competitor.Shutdown)).map { o =>
          assertTrue(o.violations.mkString("\n") == "")
        }
      },
      test("the new provider's READY during a swap held after binding leaves the status NotReady") {
        scenario(Primary.SwapGood, Some("SdkBind"), Some(Competitor.Evaluate)).map { o =>
          assertTrue(o.violations.mkString("\n") == "")
        }
      },
      test("a timed-out swap whose provider then fails shuts the previous provider down exactly once") {
        timedOutThenFails.map { case (reached, shutdowns, status) =>
          assertTrue(
            reached.contains("OrphanRecorded"),
            reached.contains("OrphanResolved"),
            shutdowns == 1,
            status.contains(ProviderStatus.Error)
          )
        }
      },
      test("a READY while the init watchdog is held wins, and the watchdog leaves the provider running") {
        watchdogRacedByReady.map { case (reached, status, shutdowns) =>
          assertTrue(reached.contains("Watchdog"), status == ProviderStatus.Ready, shutdowns == 0)
        }
      }
    )
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
}
