package zio.openfeature.testkit

import zio.openfeature.testsupport.PropertyBudget

import scala.jdk.CollectionConverters._

import zio._
import zio.test._
import zio.openfeature._
import dev.openfeature.sdk.{
  ErrorCode => OFErrorCode,
  ImmutableMetadata,
  OpenFeatureAPI,
  ProviderEvent => OFProviderEvent,
  ProviderEventDetails
}

/** Model-based property test of the provider lifecycle.
  *
  * A generated sequence of lifecycle actions (swaps to providers that start fine, fail, hang or start slowly; provider
  * events; shutdown; interrupting a swap; releasing hung initializations) runs against a real `FeatureFlags` on an
  * isolated `OpenFeatureAPI`, and after every step the instance is compared with [[LifecycleModelSpec.Model]].
  *
  * The model is written from the documented contract (`docs/lifecycle.md`, "What changes the status", and
  * `docs/providers.md`, "Replace the provider at runtime"), NOT from `ProviderStatusMachine`: copying the code would
  * only check the code against itself. Where the docs say nothing (which provider `providerMetadata` names while a swap
  * is parked, how an evaluation in `Error` ends), the model asserts nothing.
  *
  * PR CI runs 25 cases; the nightly `Property tests` workflow scales that with `ZOF_PBT_SCALE`. A failing sequence is
  * checked in under `regressions` as an explicit list, never as a seed (zio-test's seeds are not stable across
  * versions).
  */
object LifecycleModelSpec extends ZIOSpecDefault {

  // Actions

  /** How a provider handed to `setProvider` behaves in `initialize()`. */
  sealed trait Kind extends Product with Serializable

  object Kind {
    case object Good        extends Kind
    case object FailingInit extends Kind

    /** The first `initialize()` parks until [[Action.ReleaseHangs]] (ignoring `shutdown()`); later ones succeed. */
    case object HangingInit extends Kind

    /** `initialize()` takes [[SlowInitDelay]], well inside the init timeout. */
    case object SlowInit extends Kind
  }

  sealed trait Action extends Product with Serializable

  object Action {
    final case class SetProvider(kind: Kind) extends Action

    /** The provider reports READY: the installed provider, or during a parked swap the provider being replaced. */
    case object EmitReady                      extends Action
    final case class EmitError(fatal: Boolean) extends Action
    case object EmitStale                      extends Action
    case object Shutdown                       extends Action

    /** Eight concurrent typed evaluations, each checked against the model. */
    case object Evaluate extends Action

    /** Interrupt the fiber running a parked swap (no-op without one). */
    case object InterruptSwap extends Action

    /** Unpark every hung `initialize()`. */
    case object ReleaseHangs extends Action
  }

  // The model, from docs/lifecycle.md and docs/providers.md

  /** @param providerName
    *   the provider `providerMetadata` must name; None where the docs do not say
    * @param swap
    *   the provider a parked swap is installing (only a [[Kind.HangingInit]] swap stays in flight across steps)
    * @param parked
    *   providers whose first `initialize()` is still parked
    * @param swapCompleted
    *   a swap has finished, with any outcome
    */
  final case class Model(
    status: ProviderStatus,
    providerName: Option[String],
    swap: Option[String],
    parked: Set[String],
    swapCompleted: Boolean,
    shutDown: Boolean
  ) {
    import ProviderStatus._

    /** `status.canEvaluate`: an evaluation runs and the provider answers. */
    def serving: Boolean = status == Ready || status == Stale

    // "Events that arrive during a swap, or after a shutdown, do not change the status."
    private def quiet: Boolean = swap.nonEmpty || shutDown

    // "The provider reports READY: NotReady, Stale or Error -> Ready"
    private def onReady: Model =
      if (quiet) this
      else
        status match {
          case NotReady | Stale | Error => copy(status = Ready)
          case _                        => this
        }

    // "ERROR: NotReady, Ready or Stale -> Error"; "ERROR with code ProviderFatal: any status except ShuttingDown -> Fatal"
    private def onError(fatal: Boolean): Model =
      if (quiet) this
      else if (fatal) { if (status == ShuttingDown) this else copy(status = Fatal) }
      else
        status match {
          case NotReady | Ready | Stale => copy(status = Error)
          case _                        => this
        }

    // "STALE: Ready -> Stale"
    private def onStale: Model = if (!quiet && status == Ready) copy(status = Stale) else this

    /** A parked swap reaches `initTimeout`: "the status becomes Error. This swap is not rolled back: the new provider
      * stays registered". After a shutdown nothing moves the status ("evaluations fail from then on").
      */
    def settle: Model = swap match {
      case None                => this
      case Some(_) if shutDown => copy(swap = None, providerName = None, swapCompleted = true)
      case Some(incoming)      => copy(status = Error, providerName = Some(incoming), swap = None, swapCompleted = true)
    }

    /** The model after `action`. `name` is the metadata name of the provider a [[Action.SetProvider]] hands in. */
    def step(action: Action, name: String): Model = action match {
      case Action.SetProvider(kind) =>
        // "Swaps are serialized": a swap waits for a parked one to settle first.
        val m = settle
        kind match {
          // "On success, the status becomes Ready and the new provider serves."
          case Kind.Good | Kind.SlowInit => m.copy(status = Ready, providerName = Some(name), swapCompleted = true)
          // "If the new provider's initialize() throws ... the previous provider is registered again, and the status
          // returns to Ready."
          case Kind.FailingInit => m.copy(status = Ready, swapCompleted = true)
          // "The status becomes NotReady" while the SDK initializes the new provider.
          case Kind.HangingInit =>
            m.copy(status = NotReady, providerName = None, swap = Some(name), parked = m.parked + name)
        }
      case Action.EmitReady        => onReady
      case Action.EmitError(fatal) => onError(fatal)
      case Action.EmitStale        => onStale
      // "Shutdown moves the status to ShuttingDown and then NotReady, and evaluations fail from then on."
      case Action.Shutdown => copy(status = NotReady, shutDown = true)
      case Action.Evaluate => this
      // "Once a swap has started it finishes, with one of the outcomes above, even if the calling fiber is interrupted"
      case Action.InterruptSwap => this
      case Action.ReleaseHangs  =>
        val afterSwap = swap match {
          case None                => this
          case Some(_) if shutDown => copy(swap = None, providerName = None, swapCompleted = true)
          // initialize() finishes within initTimeout: "On success, the status becomes Ready and the new provider serves."
          case Some(incoming) =>
            copy(status = Ready, providerName = Some(incoming), swap = None, swapCompleted = true)
        }
        // A timed-out provider that is still installed: "the status becomes Ready on its own if it finishes later"
        // (the SDK reports READY for it).
        val lateReady = afterSwap.providerName.exists(n => parked(n) && !swap.contains(n))
        (if (lateReady) afterSwap.onReady else afterSwap).copy(parked = Set.empty)
    }
  }

  object Model {
    def initial(name: String): Model =
      Model(ProviderStatus.Ready, Some(name), swap = None, parked = Set.empty, swapCompleted = false, shutDown = false)
  }

  /** What the instance reported after a step. */
  final case class Observation(
    status: ProviderStatus,
    providerName: String,
    typed: Either[FeatureFlagError, Boolean],
    orDefault: Exit[Nothing, Boolean],
    who: Either[FeatureFlagError, String]
  )

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
      model.providerName.exists(_ != o.providerName) ->
        s"providerMetadata names '${o.providerName}', the model says '${model.providerName.getOrElse("")}'",
      (model.shutDown && o.status != NotReady) -> s"providerStatus is ${o.status} after shutdown",
      (o.status == NotReady && model.swap.isEmpty && model.swapCompleted && !model.shutDown) ->
        "NotReady with no swap in flight, after a swap completed",
      (model.serving && o.typed != Right(true)) -> s"typed evaluation gave ${o.typed} while ${model.status}",
      (model.serving && model.providerName.exists(n => o.who != Right(n))) ->
        s"evaluation was answered by ${o.who}, the model says '${model.providerName.getOrElse("")}'",
      expectedRejection.exists(e => o.typed != Left(e)) ->
        s"typed evaluation gave ${o.typed} while ${model.status}, expected Left(${expectedRejection.getOrElse("")})",
      (o.orDefault match {
        case Exit.Success(v) => (model.serving && !v) || (expectedRejection.nonEmpty && v)
        case Exit.Failure(_) => true
      }) -> s"booleanOrDefault gave ${o.orDefault} while ${model.status}"
    ).collect { case (true, message) => message }
  }

  // The system under test

  private val InitTimeout   = 2.seconds
  private val SlowInitDelay = 300.millis
  // How long to wait for an event the step is known to produce, and for one that may never be delivered (from a
  // provider no longer bound, or after the bridge is gone).
  private val EventWait      = 2.seconds
  private val MaybeEventWait = 200.millis
  // setProvider "returns only when the swap has settled, at most about twice initTimeout".
  private val SwapBound      = 5.seconds
  private val CloseBound     = 5.seconds
  private val CloseHardBound = 30.seconds
  private val LeakBound      = 5.seconds

  private val Flag   = "flag"
  private val Who    = "who"
  private val TagKey = "lifecycle-model-tag"

  type Provider = FaultyProvider[TestFeatureProvider]

  final private case class Sut(ff: FeatureFlags, events: Queue[ProviderEvent])

  final private case class Run(
    model: Model,
    providers: Map[String, Provider],
    installed: String, // the provider that emits outside a parked swap
    outgoing: String,  // the provider a parked swap replaces: the one that emits during it
    swapFiber: Option[Fiber[Nothing, Either[FeatureFlagError, Unit]]],
    interrupters: List[Fiber[Nothing, Any]],
    violations: Chunk[String]
  )

  private def label(kind: Kind): String = kind match {
    case Kind.Good        => "good"
    case Kind.FailingInit => "failing"
    case Kind.HangingInit => "hanging"
    case Kind.SlowInit    => "slow"
  }

  private def newProvider(name: String, kind: Kind): URIO[Scope, Provider] =
    FaultyProvider.scoped(Map(Flag -> true, Who -> name), name).tap { p =>
      kind match {
        case Kind.Good        => ZIO.unit
        case Kind.FailingInit => p.script(Op.Initialize, Fault.Throw(new RuntimeException(s"$name: initialize fails")))
        case Kind.HangingInit => p.script(Op.Initialize, Fault.HangUntilReleased, Fault.Succeed)
        case Kind.SlowInit    => p.script(Op.Initialize, Fault.Delay(SlowInitDelay))
      }
    }

  private def awaitEvent(sut: Sut, within: Duration)(p: ProviderEvent => Boolean): UIO[Boolean] =
    sut.events.take.repeatUntil(p).timeout(within).map(_.isDefined)

  private def isReadyFrom(name: String)(e: ProviderEvent): Boolean =
    e.eventType == ProviderEventType.Ready && e.metadata.name == name

  private def emit(p: Provider, action: Action, tag: String): UIO[Unit] = ZIO.succeed {
    val meta = ImmutableMetadata.builder().addString(TagKey, tag).build()
    // Chained without a val: the builder's self type is an existential Scala 2.13 cannot name.
    def details(code: Option[OFErrorCode]): ProviderEventDetails = code match {
      case Some(c) => ProviderEventDetails.builder().message(tag).eventMetadata(meta).errorCode(c).build()
      case None    => ProviderEventDetails.builder().message(tag).eventMetadata(meta).build()
    }
    val (event, payload) = action match {
      case Action.EmitError(fatal) =>
        val code = if (fatal) OFErrorCode.PROVIDER_FATAL else OFErrorCode.GENERAL
        (OFProviderEvent.PROVIDER_ERROR, details(Some(code)))
      case Action.EmitStale => (OFProviderEvent.PROVIDER_STALE, details(None))
      case _                => (OFProviderEvent.PROVIDER_READY, details(None))
    }
    p.emit(event, payload)
    ()
  }

  private def observe(sut: Sut, model: Model, where: String): UIO[Chunk[String]] =
    for {
      status    <- sut.ff.providerStatus
      meta      <- sut.ff.providerMetadata
      typed     <- sut.ff.boolean(Flag, false).either
      orDefault <- sut.ff.booleanOrDefault(Flag, false).exit
      who       <- sut.ff.string(Who, "").either
    } yield Chunk
      .fromIterable(violations(model, Observation(status, meta.name, typed, orDefault, who)))
      .map(v => s"$where: $v")

  /** Wait for a parked swap to finish (it gives up at initTimeout), then step the model as if it timed out. */
  private def settle(sut: Sut, run: Run, where: String): UIO[Run] = run.swapFiber match {
    case None        => ZIO.succeed(run)
    case Some(fiber) =>
      for {
        done <- fiber.await.timeout(SwapBound)
        _    <- ZIO.foreachDiscard(run.interrupters)(_.await.timeout(SwapBound))
        model = run.model.settle
        seen <- observe(sut, model, s"$where, settling the parked swap")
        late = if (done.isEmpty) Chunk(s"$where: the parked swap did not settle within $SwapBound") else Chunk.empty
      } yield run.copy(model = model, swapFiber = None, interrupters = Nil, violations = run.violations ++ late ++ seen)
  }

  private def perform(sut: Sut, run: Run, action: Action, index: Int): URIO[Scope, Run] = {
    val where = s"step $index ($action)"
    val name  = action match {
      case Action.SetProvider(kind) => s"p$index-${label(kind)}"
      case _                        => ""
    }
    val acted: URIO[Scope, Run] = action match {
      case Action.SetProvider(kind) =>
        for {
          settled <- settle(sut, run, where)
          p       <- newProvider(name, kind)
          withP = settled.copy(providers = settled.providers + (name -> p))
          next <- kind match {
            case Kind.HangingInit =>
              for {
                fiber <- sut.ff.setProvider(p).either.fork
                _     <- p.awaitEntered(Op.Initialize).timeout(EventWait)
              } yield withP.copy(outgoing = settled.installed, installed = name, swapFiber = Some(fiber))
            case _ =>
              // The SDK reports READY for whichever provider ends up initialized (the new one, or the previous one
              // registered again); wait for it so it cannot land after a later step.
              val (installed, readyFrom) =
                if (kind == Kind.FailingInit) (settled.installed, settled.installed) else (name, name)
              sut.ff.setProvider(p).either *>
                awaitEvent(sut, EventWait)(isReadyFrom(readyFrom)).as(withP.copy(installed = installed))
          }
        } yield next
      case Action.EmitReady | Action.EmitError(_) | Action.EmitStale =>
        val emitter = if (run.model.swap.nonEmpty) run.outgoing else run.installed
        val tag     = s"step-$index"
        val wait    = if (run.model.swap.nonEmpty || run.model.shutDown) MaybeEventWait else EventWait
        emit(run.providers(emitter), action, tag) *>
          awaitEvent(sut, wait)(_.eventMeta.getString(TagKey).contains(tag)).as(run)
      case Action.Shutdown => sut.ff.shutdown.as(run)
      case Action.Evaluate =>
        val expected = run.model.serving
        ZIO
          .foreachPar(1 to 8)(_ => sut.ff.boolean(Flag, false).either)
          .map { results =>
            val wrong = results.filter(r => r.isRight != expected || (expected && r != Right(true)))
            if (wrong.isEmpty || run.model.status == ProviderStatus.Error) run
            else run.copy(violations = run.violations :+ s"$where: concurrent evaluations gave $wrong")
          }
      case Action.InterruptSwap =>
        run.swapFiber match {
          case None => ZIO.succeed(run)
          // Forked: the interrupt returns only once the swap has settled, and the swap stays parked meanwhile.
          case Some(fiber) =>
            fiber.interrupt.fork.flatMap(i => ZIO.sleep(50.millis).as(run.copy(interrupters = i :: run.interrupters)))
        }
      case Action.ReleaseHangs =>
        ZIO.foreachDiscard(run.providers.values)(_.release) *> (run.swapFiber match {
          case Some(fiber) =>
            val incoming = run.installed
            for {
              done <- fiber.await.timeout(SwapBound)
              _    <- ZIO.foreachDiscard(run.interrupters)(_.await.timeout(SwapBound))
              _    <- awaitEvent(sut, if (run.model.shutDown) MaybeEventWait else EventWait)(isReadyFrom(incoming))
              late =
                if (done.isEmpty) Chunk(s"$where: the released swap did not finish within $SwapBound") else Chunk.empty
            } yield run.copy(swapFiber = None, interrupters = Nil, violations = run.violations ++ late)
          case None =>
            val lateReady = run.model.parked.contains(run.installed) && !run.model.shutDown
            ZIO.when(lateReady)(awaitEvent(sut, EventWait)(isReadyFrom(run.installed))).as(run)
        })
    }
    for {
      next <- acted
      model = run.model.step(action, name)
      seen <- observe(sut, model, where)
    } yield next.copy(model = model, violations = next.violations ++ seen)
  }

  private def runSteps(actions: List[Action]): URIO[Scope, Chunk[String]] =
    (for {
      initial <- newProvider("p0-initial", Kind.Good)
      ff      <- FeatureFlags.build(
        initial,
        domain = Some(s"lifecycle-model-${java.util.UUID.randomUUID()}"),
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = true,
        apiOverride = Some(OpenFeatureAPI.createIsolated()),
        initTimeout = InitTimeout
      )
      queue <- Queue.unbounded[ProviderEvent]
      // Handlers tied to the scope, as docs/lifecycle.md "Scoping a handler" says: they run until cancelled.
      _ <- ZIO.foreachDiscard(List(ProviderEventType.Ready, ProviderEventType.Error, ProviderEventType.Stale)) { t =>
        ZIO.acquireRelease(ff.on(t, e => queue.offer(e).unit))(cancel => cancel)
      }
      sut   = Sut(ff, queue)
      start = Run(
        Model.initial("p0-initial"),
        Map("p0-initial" -> initial),
        "p0-initial",
        "p0-initial",
        None,
        Nil,
        Chunk.empty
      )
      first <- observe(sut, start.model, "initial")
      last  <- ZIO.foldLeft(actions.zipWithIndex)(start.copy(violations = first)) { case (run, (action, i)) =>
        perform(sut, run, action, i + 1)
      }
      settled <- settle(sut, last, "end of sequence")
    } yield settled.violations).catchAllCause(c => ZIO.succeed(Chunk(s"the harness failed: ${c.prettyPrint}")))

  // Leaks: root fibers and live non-daemon threads that were not there before the case

  final case class Baseline(fibers: Set[FiberId], threads: Set[Thread])

  private def liveNonDaemonThreads: UIO[Set[Thread]] =
    ZIO.succeed(Thread.getAllStackTraces.keySet.asScala.filter(t => t.isAlive && !t.isDaemon).toSet)

  private def liveRoots: UIO[Chunk[Fiber.Runtime[_, _]]] =
    Fiber.roots.flatMap(ZIO.filter(_)(_.status.map(_ != Fiber.Status.Done)))

  def snapshot: UIO[Baseline] =
    for {
      roots   <- liveRoots
      threads <- liveNonDaemonThreads
    } yield Baseline(roots.map(_.id).toSet, threads)

  private def leaks(baseline: Baseline): UIO[List[String]] =
    for {
      roots   <- liveRoots
      threads <- liveNonDaemonThreads
    } yield roots.filterNot(f => baseline.fibers(f.id)).map(f => s"leaked fiber ${f.id.threadName}").toList ++
      (threads -- baseline.threads).map(t => s"leaked non-daemon thread '${t.getName}'").toList

  /** The leaks still present once `within` has passed, or none as soon as there are none. */
  def awaitNoLeaks(baseline: Baseline, within: Duration): UIO[List[String]] = {
    // A plain deadline loop: `timeout` races in forked root fibers, which the check itself would report.
    def loop(deadline: Long): UIO[List[String]] =
      leaks(baseline).flatMap { found =>
        if (found.isEmpty) ZIO.succeed(Nil)
        else Clock.nanoTime.flatMap(now => if (now >= deadline) ZIO.succeed(found) else loop(deadline).delay(50.millis))
      }
    Clock.nanoTime.flatMap(now => loop(now + within.toNanos))
  }

  /** Run one sequence; returns every violation, including a slow scope close and leaks after it. */
  def runCase(actions: List[Action]): UIO[Chunk[String]] =
    for {
      baseline <- snapshot
      scope    <- Scope.make
      steps    <- runSteps(actions).provideEnvironment(ZEnvironment[Scope](scope))
      closed   <- scope.close(Exit.unit).timed.timeout(CloseHardBound)
      closing = closed match {
        case None                                 => Chunk(s"scope close did not complete within $CloseHardBound")
        case Some((took, _)) if took > CloseBound => Chunk(s"scope close took ${took.toMillis} ms, over $CloseBound")
        case Some(_)                              => Chunk.empty
      }
      leaked <- awaitNoLeaks(baseline, LeakBound)
    } yield steps ++ closing ++ Chunk.fromIterable(leaked).map(l => s"after scope close: $l")

  private def report(actions: List[Action], found: Chunk[String]): String =
    if (found.isEmpty) ""
    else (s"sequence: ${actions.mkString("List(", ", ", ")")}" +: found).mkString("\n")

  // The generator

  private val genKind: Gen[Any, Kind] =
    Gen.weighted(
      Gen.const(Kind.Good)        -> 12.0,
      Gen.const(Kind.SlowInit)    -> 5.0,
      Gen.const(Kind.FailingInit) -> 7.0,
      Gen.const(Kind.HangingInit) -> 6.0
    )

  private val genAction: Gen[Any, Action] =
    Gen.weighted(
      genKind.map(Action.SetProvider(_): Action) -> 30.0,
      Gen.const(Action.Evaluate)                 -> 20.0,
      Gen.const(Action.EmitReady)                -> 8.0,
      Gen.const(Action.EmitError(fatal = false)) -> 5.0,
      Gen.const(Action.EmitError(fatal = true))  -> 3.0,
      Gen.const(Action.EmitStale)                -> 6.0,
      Gen.const(Action.InterruptSwap)            -> 6.0,
      Gen.const(Action.ReleaseHangs)             -> 6.0,
      Gen.const(Action.Shutdown)                 -> 3.0
    )

  /** After a shutdown the instance is not used again ("evaluations fail from then on"), so a later swap becomes an
    * evaluation. A map keeps shrinking intact.
    */
  private def afterShutdownNoSwaps(actions: List[Action]): List[Action] = {
    val (before, rest) = actions.span(_ != Action.Shutdown)
    before ++ rest.map {
      case Action.SetProvider(_) => Action.Evaluate
      case other                 => other
    }
  }

  val genActions: Gen[Any, List[Action]] = Gen.listOfBounded(1, 12)(genAction).map(afterShutdownNoSwaps)

  private val baseSamples = 25

  private def regression(name: String, actions: List[Action]) =
    test(name) {
      runCase(actions).map(found => assertTrue(report(actions, found) == ""))
    }

  import Action._

  def spec: Spec[TestEnvironment with Scope, Any] = suite("LifecycleModelSpec")(
    test("every generated lifecycle sequence agrees with the documented contract") {
      check(genActions) { actions =>
        runCase(actions).map(found => assertTrue(report(actions, found) == ""))
      }
    } @@ PropertyBudget(baseSamples) @@ TestAspect.shrinks(50) @@ PropertyBudget.timeout((baseSamples * 30).seconds),
    suite("regressions")(
      // Found by the generated test with `swap.uninterruptible` (in FeatureFlagsLive.setProvider)
      // reverted: the interrupt killed the swap mid-registration, so it never reached its timeout outcome and the
      // instance stayed NotReady with no swap in flight.
      regression(
        "an interrupted parked swap still settles to Error at initTimeout",
        List(SetProvider(Kind.HangingInit), InterruptSwap)
      ),
      regression(
        "a swap timed out at initTimeout leaves Error, and a later swap recovers",
        List(SetProvider(Kind.HangingInit), SetProvider(Kind.Good), Evaluate)
      ),
      regression(
        "a shutdown during a parked swap stays NotReady when the swap settles",
        List(SetProvider(Kind.HangingInit), Shutdown, Evaluate)
      ),
      regression(
        "a failed swap from Fatal rolls back to the previous provider, Ready",
        List(EmitError(fatal = true), SetProvider(Kind.FailingInit))
      ),
      regression(
        "a fatal error from the outgoing provider during a swap is ignored",
        List(SetProvider(Kind.HangingInit), EmitError(fatal = true), ReleaseHangs)
      ),
      regression(
        "a superseded timed-out provider that finishes late does not disturb the installed one",
        List(SetProvider(Kind.HangingInit), SetProvider(Kind.Good), ReleaseHangs, EmitError(fatal = false))
      )
    ),
    suite("harness")(
      test("the model refuses an EmitReady after Shutdown becoming Ready, and the checker flags a Ready instance") {
        val shutDown  = Model.initial("p0").step(Shutdown, "")
        val afterThat = shutDown.step(EmitReady, "")
        val observed  = Observation(
          ProviderStatus.Ready,
          "p0",
          Right(true),
          Exit.succeed(true),
          Right("p0")
        )
        val flagged = violations(afterThat, observed)
        assertTrue(
          afterThat.status == ProviderStatus.NotReady,
          flagged.contains("providerStatus is Ready after shutdown")
        )
      },
      test("the leak check detects a deliberately leaked fiber") {
        for {
          baseline <- snapshot
          leaked   <- ZIO.never.forkDaemon
          found    <- awaitNoLeaks(baseline, 300.millis)
          _        <- leaked.interrupt
          after    <- awaitNoLeaks(baseline, 2.seconds)
        } yield assertTrue(found.exists(_.startsWith("leaked fiber")), after.isEmpty)
      }
    )
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
}
