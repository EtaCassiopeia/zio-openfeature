package zio.openfeature.testkit

import zio.openfeature.testsupport.PropertyBudget
import zio.openfeature.testsupport.LeakCheck.{awaitNoLeaks, snapshot}

import zio._
import zio.test._
import zio.openfeature._
import dev.openfeature.sdk.{
  ErrorCode => OFErrorCode,
  FeatureProvider,
  ImmutableMetadata,
  OpenFeatureAPI,
  ProviderEvent => OFProviderEvent,
  ProviderEventDetails
}

/** Model-based property test of the provider lifecycle.
  *
  * A generated sequence of lifecycle actions (swaps to providers that start fine, fail, hang, hang and then fail, start
  * slowly or shut down slowly, some reusing the name of the provider they replace; events from the installed provider
  * and from one already replaced; shutdown; interrupting a swap; releasing hung initializations; handing back a
  * provider a timed-out swap replaced; and pairs of these run at once) runs against a real `FeatureFlags` on an
  * isolated `OpenFeatureAPI`, and after every step the instance is compared with [[LifecycleModel.Model]]. When the
  * case ends, every provider it created must have been shut down exactly once.
  *
  * A pair of actions run at once must agree with the model after one of the orders the two can take effect in. Two
  * swaps are serialized, and the order they finished in is the order they ran; a swap and an event can also take effect
  * with the event dropped during the swap. An evaluation run beside another action must give what the model gives
  * before it, during a swap, or after it.
  *
  * PR CI runs 25 cases; the nightly `Property tests` workflow scales that with `ZOF_PBT_SCALE`. A failing sequence is
  * checked in under `regressions` as an explicit list, never as a seed (zio-test's seeds are not stable across
  * versions).
  */
object LifecycleModelSpec extends ZIOSpecDefault {

  import LifecycleModel._

  // The system under test

  private val InitTimeout       = 2.seconds
  private val SlowInitDelay     = 300.millis
  private val SlowShutdownDelay = 200.millis
  // How long to wait for an event the step is known to produce, and for one that may never be delivered (from a
  // provider no longer bound, or after the bridge is gone).
  private val EventWait      = 2.seconds
  private val MaybeEventWait = 200.millis
  // setProvider "returns only when the swap has settled, at most about twice initTimeout".
  private val SwapBound      = 5.seconds
  private val CloseBound     = 5.seconds
  private val CloseHardBound = 30.seconds
  private val LeakBound      = 5.seconds
  // How long a provider a settled swap leaves behind may take to be shut down (the orphan watcher polls every 250 ms).
  private val OrphanBound = 3.seconds

  private val Flag   = "flag"
  private val Who    = "who"
  private val TagKey = "lifecycle-model-tag"

  type Provider = FaultyProvider[FeatureProvider]

  final private case class Sut(ff: FeatureFlagsLive, events: Queue[ProviderEvent])

  final private case class Run(
    model: Model,
    providers: Map[String, Provider], // by id
    installed: Installed,             // the provider that emits outside a parked swap
    outgoing: Installed,              // the provider a parked swap replaces: the one that emits during it
    replaced: Option[Installed],      // the provider most recently replaced
    swapFiber: Option[Fiber[Nothing, Either[FeatureFlagError, Unit]]],
    interrupters: List[Fiber[Nothing, Any]],
    violations: Chunk[String]
  )

  private def label(kind: Kind): String = kind match {
    case Kind.Good         => "good"
    case Kind.FailingInit  => "failing"
    case Kind.HangingInit  => "hanging"
    case Kind.SlowInit     => "slow"
    case Kind.HangThenFail => "hang-then-fail"
    case Kind.SlowShutdown => "slow-shutdown"
  }

  private def newProvider(who: Installed, kind: Kind): URIO[Scope, Provider] = {
    val flags = Map[String, Any](Flag -> true, Who -> who.id)
    kind match {
      case Kind.HangThenFail =>
        // The outer wrapper parks its first call; every call that reaches the inner one throws.
        for {
          inner <- FaultyProvider.make(flags, who.name)
          _     <- inner.script(Op.Initialize, Fault.Throw(new RuntimeException(s"${who.id}: initialize fails")))
          outer <- FaultyProvider.wrapScoped[FeatureProvider](inner, who.name)
          _     <- outer.script(Op.Initialize, Fault.HangUntilReleased, Fault.Succeed)
        } yield outer
      case _ =>
        FaultyProvider.scoped(flags, who.name).tap { p =>
          kind match {
            case Kind.Good | Kind.HangThenFail => ZIO.unit
            case Kind.FailingInit              =>
              p.script(Op.Initialize, Fault.Throw(new RuntimeException(s"${who.id}: initialize fails")))
            case Kind.HangingInit  => p.script(Op.Initialize, Fault.HangUntilReleased, Fault.Succeed)
            case Kind.SlowInit     => p.script(Op.Initialize, Fault.Delay(SlowInitDelay))
            case Kind.SlowShutdown => p.script(Op.Shutdown, Fault.Delay(SlowShutdownDelay))
          }
        }
    }
  }

  private def awaitEvent(sut: Sut, within: Duration)(p: ProviderEvent => Boolean): UIO[Boolean] =
    sut.events.take.repeatUntil(p).timeout(within).map(_.isDefined)

  private def isReadyFrom(name: String)(e: ProviderEvent): Boolean =
    e.eventType == ProviderEventType.Ready && e.metadata.name == name

  private def tagged(tag: String)(e: ProviderEvent): Boolean = e.eventMeta.getString(TagKey).contains(tag)

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

  private def observe(sut: Sut): UIO[Observation] =
    for {
      status    <- sut.ff.providerStatus
      meta      <- sut.ff.providerMetadata
      typed     <- sut.ff.boolean(Flag, false).either
      orDefault <- sut.ff.booleanOrDefault(Flag, false).exit
      who       <- sut.ff.string(Who, "").either
    } yield Observation(status, meta.name, typed, orDefault, who)

  private def verify(sut: Sut, model: Model, where: String): UIO[Chunk[String]] =
    observe(sut).map(o => Chunk.fromIterable(violations(model, o)).map(v => s"$where: $v"))

  /** Of `candidates` (the models each serial order of a pair leads to), the first the instance agrees with; or the
    * first, with what the instance broke against it.
    */
  private def checkAny(sut: Sut, candidates: List[Model], where: String): UIO[(Model, Chunk[String])] =
    observe(sut).map { o =>
      candidates.find(violations(_, o).isEmpty) match {
        case Some(m) => (m, Chunk.empty)
        case None    =>
          val m = candidates.head
          (m, Chunk.fromIterable(violations(m, o)).map(v => s"$where (no serial order agrees): $v"))
      }
    }

  /** Wait for a parked swap to finish (it gives up at initTimeout), then step the model as if it timed out. */
  private def settle(sut: Sut, run: Run, where: String): UIO[Run] = run.swapFiber match {
    case None        => ZIO.succeed(run)
    case Some(fiber) =>
      for {
        done <- fiber.await.timeout(SwapBound)
        _    <- ZIO.foreachDiscard(run.interrupters)(_.await.timeout(SwapBound))
        model = run.model.settle
        seen <- verify(sut, model, s"$where, settling the parked swap")
        late = if (done.isEmpty) Chunk(s"$where: the parked swap did not settle within $SwapBound") else Chunk.empty
      } yield run.copy(
        model = model,
        swapFiber = None,
        interrupters = Nil,
        replaced = Some(run.outgoing),
        violations = run.violations ++ late ++ seen
      )
  }

  private def whoFor(run: Run, kind: Kind, reuseName: Boolean, id: String): Installed =
    Installed(id, if (reuseName) run.installed.name else id)

  /** Install `who`, waiting for the READY the SDK sends for whichever provider ends up initialized. */
  private def swapTo(sut: Sut, run: Run, who: Installed, kind: Kind): URIO[Scope, Run] =
    for {
      p <- newProvider(who, kind)
      withP = run.copy(providers = run.providers + (who.id -> p))
      next <- kind match {
        case Kind.HangingInit | Kind.HangThenFail =>
          for {
            fiber <- sut.ff.setProvider(p).either.fork
            _     <- p.awaitEntered(Op.Initialize).timeout(EventWait)
          } yield withP.copy(outgoing = run.installed, installed = who, swapFiber = Some(fiber))
        case _ =>
          // The SDK reports READY for whichever provider ends up initialized (the new one, or the previous one
          // registered again); wait for it so it cannot land after a later step.
          val failed = kind == Kind.FailingInit
          sut.ff.setProvider(p).either *>
            awaitEvent(sut, EventWait)(isReadyFrom(if (failed) run.installed.name else who.name)).as(
              if (failed) withP.copy(replaced = Some(who))
              else withP.copy(installed = who, replaced = Some(run.installed))
            )
      }
    } yield next

  private def emitter(run: Run): Installed = if (run.model.swap.nonEmpty) run.outgoing else run.installed

  /** Emit from the provider that emits now; a provider the model does not let speak (`silent`) stays quiet. */
  private def emitStep(sut: Sut, run: Run, action: Action, tag: String): UIO[Unit] = {
    val from = emitter(run)
    val wait = if (run.model.swap.nonEmpty || run.model.shutDown) MaybeEventWait else EventWait
    ZIO
      .unless(run.model.silent(from.id))(
        emit(run.providers(from.id), action, tag) *> awaitEvent(sut, wait)(tagged(tag))
      )
      .unit
  }

  /** The model after an event from the provider that emits now. */
  private def afterEmit(run: Run, model: Model, action: Action): Model =
    if (run.model.silent(emitter(run).id)) model else model.step(action, run.installed)

  /** Eight concurrent `who` evaluations. */
  private def evaluate(sut: Sut): UIO[Chunk[Either[FeatureFlagError, String]]] =
    ZIO.foreachPar(Chunk.fromIterable(1 to 8))(_ => sut.ff.string(Who, "").either)

  /** The results none of `allowed` gives. */
  private def unexpected(
    results: Chunk[Either[FeatureFlagError, String]],
    allowed: List[Model]
  ): Chunk[Either[FeatureFlagError, String]] =
    results.filterNot(r => allowed.exists(whoAllowed(_, r)))

  private def perform(sut: Sut, run: Run, action: Action, index: Int): URIO[Scope, Run] = {
    val where = s"step $index ($action)"
    action match {
      case Action.SetProvider(kind, reuse) =>
        for {
          settled <- settle(sut, run, where)
          who = whoFor(settled, kind, reuse, s"p$index-${label(kind)}")
          swapped <- swapTo(sut, settled, who, kind)
          model = settled.model.step(action, who)
          seen <- verify(sut, model, where)
        } yield swapped.copy(model = model, violations = swapped.violations ++ seen)

      case Action.Both(first, second) => both(sut, run, first, second, index, where)

      case Action.EmitReady | Action.EmitError(_) | Action.EmitStale =>
        for {
          _ <- emitStep(sut, run, action, s"step-$index")
          model = afterEmit(run, run.model, action)
          seen <- verify(sut, model, where)
        } yield run.copy(model = model, violations = run.violations ++ seen)

      // The SDK routes a provider's events only to the domains it is bound to, so a replaced provider's events never
      // reach this instance: this checks the documented outcome end to end. The bridge's own guards against such an
      // event (the provider-name check, a slot the SDK still reports NOT_READY) are exercised directly in core.
      case Action.EmitFromReplaced(event) =>
        run.replaced match {
          case Some(old) if !run.model.silent(old.id) =>
            val tag = s"step-$index"
            stepped(sut, run, action, where)(
              emit(run.providers(old.id), event, tag) *> awaitEvent(sut, MaybeEventWait)(tagged(tag)).unit
            )
          case _ => ZIO.succeed(run)
        }

      // Returns only once a parked swap has settled; the model's own step settles it.
      case Action.Shutdown =>
        for {
          _       <- sut.ff.shutdown
          settled <- ZIO.foreach(run.swapFiber.toList ++ run.interrupters)(_.await.timeout(SwapBound))
          late =
            if (settled.exists(_.isEmpty))
              Chunk(s"$where: the parked swap did not settle within $SwapBound of shutdown returning")
            else Chunk.empty
          model = run.model.step(action, run.installed)
          seen <- verify(sut, model, where)
        } yield run.copy(
          model = model,
          swapFiber = None,
          interrupters = Nil,
          violations = run.violations ++ late ++ seen
        )

      case Action.Evaluate =>
        evaluate(sut).map(unexpected(_, List(run.model))).flatMap { wrong =>
          val found = if (wrong.isEmpty) Chunk.empty else Chunk(s"$where: concurrent evaluations gave $wrong")
          verify(sut, run.model, where).map(seen => run.copy(violations = run.violations ++ found ++ seen))
        }

      case Action.InterruptSwap =>
        run.swapFiber match {
          case None => ZIO.succeed(run)
          // Forked: the interrupt returns only once the swap has settled, and the swap stays parked meanwhile.
          case Some(fiber) =>
            fiber.interrupt.fork.flatMap(i => ZIO.sleep(50.millis).as(run.copy(interrupters = i :: run.interrupters)))
        }

      case Action.ReleaseHangs =>
        // A parked swap that already reached initTimeout settles as a timeout, whatever the release does.
        ZIO
          .foreach(run.swapFiber)(_.poll)
          .flatMap(polled => if (polled.flatten.nonEmpty) settle(sut, run, where) else ZIO.succeed(run))
          .flatMap(release(sut, _, where))

      case Action.ReinstallPrevious =>
        settle(sut, run, where).flatMap(reinstall(sut, _, where))
    }
  }

  /** Unpark every hung `initialize()`. */
  private def release(sut: Sut, run: Run, where: String): UIO[Run] = {
    val action   = Action.ReleaseHangs
    val incoming = run.model.swap.map(_._1)
    val failed   = run.model.swap.exists(_._2 == Kind.HangThenFail)
    for {
      _         <- ZIO.foreachDiscard(run.providers.values)(_.release)
      afterSwap <- run.swapFiber match {
        case Some(fiber) =>
          for {
            done <- fiber.await.timeout(SwapBound)
            _    <- ZIO.foreachDiscard(run.interrupters)(_.await.timeout(SwapBound))
            readyFrom = if (failed) run.outgoing.name else incoming.fold(run.installed.name)(_.name)
            _ <- awaitEvent(sut, if (run.model.shutDown) MaybeEventWait else EventWait)(isReadyFrom(readyFrom))
            late =
              if (done.isEmpty) Chunk(s"$where: the released swap did not finish within $SwapBound")
              else Chunk.empty
          } yield
            if (failed)
              run.copy(
                installed = run.outgoing,
                replaced = incoming,
                swapFiber = None,
                interrupters = Nil,
                violations = run.violations ++ late
              )
            else
              run.copy(
                replaced = Some(run.outgoing),
                swapFiber = None,
                interrupters = Nil,
                violations = run.violations ++ late
              )
        case None =>
          val m         = run.model
          val lateReady = m.parked(run.installed.id) && !m.failing(run.installed.id) && !m.shutDown
          ZIO.when(lateReady)(awaitEvent(sut, EventWait)(isReadyFrom(run.installed.name))).as(run)
      }
      // The providers timed-out swaps replaced are shut down once those swaps' providers settle.
      orphaned <- ZIO.foreach(Chunk.fromIterable(run.model.orphans)) { id =>
        run.providers(id).calls(Op.Shutdown).repeatUntil(_.nonEmpty).timeout(OrphanBound).map(id -> _.isDefined)
      }
      gone = orphaned.collect { case (id, false) =>
        s"$where: '$id', which a timed-out swap replaced, was not shut down within $OrphanBound of its release"
      }
      model = run.model.step(action, run.installed)
      seen <- verify(sut, model, where)
    } yield afterSwap.copy(model = model, violations = afterSwap.violations ++ gone ++ seen)
  }

  /** Hand back the provider a timed-out swap replaced: refused up front while that swap is pending. */
  private def reinstall(sut: Sut, run: Run, where: String): UIO[Run] =
    run.model.orphans.headOption match {
      case None     => ZIO.succeed(run)
      case Some(id) =>
        for {
          initsBefore  <- run.providers(id).entered(Op.Initialize)
          seenStatuses <- Ref.make(Chunk.empty[ProviderStatus])
          watcher      <- sut.ff.statusChanges.foreach(s => seenStatuses.update(_ :+ s)).fork
          _            <- seenStatuses.get.repeatUntil(_.nonEmpty).timeout(EventWait)
          result       <- sut.ff.setProvider(run.providers(id)).either
          _            <- watcher.interrupt
          statuses     <- seenStatuses.get
          initsAfter   <- run.providers(id).entered(Op.Initialize)
          // Refused up front: no swap starts, so the status never moves.
          refused = result match {
            case Left(FeatureFlagError.ProviderInitializationFailed(_))
                if statuses.distinct.size == 1 && initsAfter == initsBefore =>
              Chunk.empty
            case other =>
              Chunk(
                s"$where: reinstalling '$id' while its timed-out swap is pending gave $other, statuses $statuses"
              )
          }
          seen <- verify(sut, run.model, where)
        } yield run.copy(violations = run.violations ++ refused ++ seen)
    }

  /** An action with no effect on the harness's own bookkeeping, checked against the model after it. */
  private def stepped(sut: Sut, run: Run, action: Action, where: String)(act: UIO[Unit]): UIO[Run] =
    for {
      _ <- act
      model = run.model.step(action, run.installed)
      seen <- verify(sut, model, where)
    } yield run.copy(model = model, violations = run.violations ++ seen)

  /** Two actions at once. The generator pairs a swap (never a hanging one) with an event, an evaluation or another
    * swap, or two events, or an event and an evaluation.
    */
  private def both(sut: Sut, run0: Run, first: Action, second: Action, index: Int, where: String): URIO[Scope, Run] =
    for {
      run <-
        if (Seq(first, second).exists(_.isInstanceOf[Action.SetProvider])) settle(sut, run0, where)
        else ZIO.succeed(run0)
      before = run.model
      next <- (first, second) match {
        case (a @ Action.SetProvider(ka, ra), b @ Action.SetProvider(kb, rb)) =>
          val whoA = whoFor(run, ka, ra, s"p$index-a-${label(ka)}")
          val whoB = whoFor(run, kb, rb, s"p$index-b-${label(kb)}")
          for {
            pa    <- newProvider(whoA, ka)
            pb    <- newProvider(whoB, kb)
            order <- Ref.make(List.empty[String])
            fa    <- (sut.ff.setProvider(pa).either *> order.update(_ :+ "a")).fork
            fb    <- (sut.ff.setProvider(pb).either *> order.update(_ :+ "b")).fork
            _     <- fa.await.timeout(SwapBound) *> fb.await.timeout(SwapBound)
            done  <- order.get
            (firstA, steps) =
              if (done.headOption.contains("a")) (true, List((a, whoA), (b, whoB)))
              else (false, List((b, whoB), (a, whoA)))
            model     = steps.foldLeft(before) { case (m, (act, who)) => m.step(act, who) }
            installed = model.installed.getOrElse(run.installed)
            _    <- awaitEvent(sut, EventWait)(isReadyFrom(installed.name))
            seen <- verify(sut, model, s"$where, swaps finished ${if (firstA) "a then b" else "b then a"}")
          } yield run.copy(
            model = model,
            providers = run.providers + (whoA.id -> pa) + (whoB.id -> pb),
            installed = installed,
            // The provider the pair replaced; when both swaps failed, the later failed one.
            replaced = Some(if (installed.id == run.installed.id) steps.last._2 else run.installed),
            violations = run.violations ++ seen
          )

        case (s @ Action.SetProvider(kind, reuse), paired) =>
          val who     = whoFor(run, kind, reuse, s"p$index-${label(kind)}")
          val emitter = run.installed
          // A provider the model does not let speak sends nothing: the pair is then a swap beside an evaluation.
          val other = if (Action.isEmit(paired) && before.silent(emitter.id)) Action.Evaluate else paired
          val tag   = s"step-$index"
          for {
            p         <- newProvider(who, kind)
            swap      <- sut.ff.setProvider(p).either.fork
            evaluated <- other match {
              case Action.Evaluate => evaluate(sut)
              case _               => emit(run.providers(emitter.id), other, tag).as(Chunk.empty)
            }
            _ <- swap.await.timeout(SwapBound)
            readyFrom = if (kind == Kind.FailingInit) emitter.name else who.name
            _ <- awaitEvent(sut, EventWait)(isReadyFrom(readyFrom))
            _ <- ZIO.when(Action.isEmit(other))(awaitEvent(sut, MaybeEventWait)(tagged(tag)))
            swapped = before.step(s, who)
            // The event can take effect before the swap, after it (only while its sender is still installed), or be
            // dropped while the swap is in flight.
            afterEmit =
              if (swapped.installed.exists(_.id == emitter.id)) swapped.step(other, who) else swapped
            candidates =
              if (Action.isEmit(other)) List(swapped, before.step(other, who).step(s, who), afterEmit)
              else List(swapped)
            checked <- checkAny(sut, candidates, where)
            (model, seen) = checked
            installed     = if (kind == Kind.FailingInit) emitter else who
            // An evaluation sees the instance before the swap, during it (NotReady), or after it.
            wrong = unexpected(evaluated, List(before, before.copy(status = ProviderStatus.NotReady), swapped))
          } yield run.copy(
            model = model,
            providers = run.providers + (who.id -> p),
            installed = installed,
            replaced = Some(if (kind == Kind.FailingInit) who else emitter),
            violations = run.violations ++ seen ++
              (if (wrong.isEmpty) Chunk.empty else Chunk(s"$where: evaluations during the swap gave $wrong"))
          )

        case (a, b) =>
          // Two events, or an event and an evaluation, from the installed provider.
          val tagA                                                                         = s"step-$index-a"
          val tagB                                                                         = s"step-$index-b"
          def fire(act: Action, tag: String): UIO[Chunk[Either[FeatureFlagError, String]]] = act match {
            case Action.Evaluate =>
              evaluate(sut).map(unexpected(_, List(before, afterEmit(run, afterEmit(run, before, a), b))))
            case _ => emitStep(sut, run, act, tag).as(Chunk.empty)
          }
          for {
            results <- fire(a, tagA).zipPar(fire(b, tagB))
            candidates = List(
              afterEmit(run, afterEmit(run, before, a), b),
              afterEmit(run, afterEmit(run, before, b), a)
            )
            checked <- checkAny(sut, candidates, where)
            (model, seen) = checked
            wrong         = results._1 ++ results._2
          } yield run.copy(
            model = model,
            violations = run.violations ++ seen ++
              (if (wrong.isEmpty) Chunk.empty else Chunk(s"$where: concurrent evaluations gave $wrong"))
          )
      }
    } yield next

  private def runSteps(actions: List[Action]): URIO[Scope, (Chunk[String], Map[String, Provider])] = {
    val first = Installed("p0-initial", "p0-initial")
    for {
      initial <- newProvider(first, Kind.Good)
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
      start = Run(Model.initial(first), Map(first.id -> initial), first, first, None, None, Nil, Chunk.empty)
      seen <- verify(sut, start.model, "initial")
      last <- ZIO.foldLeft(actions.zipWithIndex)(start.copy(violations = seen)) { case (run, (action, i)) =>
        perform(sut, run, action, i + 1)
      }
      settled <- settle(sut, last, "end of sequence")
    } yield (settled.violations, settled.providers)
  }.catchAllCause(c => ZIO.succeed((Chunk(s"the harness failed: ${c.prettyPrint}"), Map.empty[String, Provider])))

  /** Run one sequence; returns every violation, including a slow scope close, a provider not shut down exactly once,
    * and leaks after it.
    */
  def runCase(actions: List[Action]): UIO[Chunk[String]] =
    for {
      baseline <- snapshot
      scope    <- Scope.make
      ran      <- runSteps(actions).provideEnvironment(ZEnvironment[Scope](scope))
      (steps, providers) = ran
      closed <- scope.close(Exit.unit).timed.timeout(CloseHardBound)
      closing = closed match {
        case None                                 => Chunk(s"scope close did not complete within $CloseHardBound")
        case Some((took, _)) if took > CloseBound => Chunk(s"scope close took ${took.toMillis} ms, over $CloseBound")
        case Some(_)                              => Chunk.empty
      }
      // A shutdown abandoned at its timeout is recorded when it returns, so give slow ones a moment.
      shutdowns <- ZIO.foreach(Chunk.fromIterable(providers.toList.sortBy(_._1))) { case (id, p) =>
        p.calls(Op.Shutdown).repeatUntil(_.nonEmpty).timeout(OrphanBound).as(id) *>
          p.calls(Op.Shutdown).map(calls => id -> calls)
      }
      shutdownOnce = shutdowns.collect {
        case (id, calls) if calls.size != 1 =>
          val by = calls.map(c => s"${c.thread} at ${c.startedAt}").mkString(", ")
          s"after scope close: '$id' was shut down ${calls.size} times, not once ($by)"
      }
      leaked <- awaitNoLeaks(baseline, LeakBound)
    } yield steps ++ closing ++ shutdownOnce ++ Chunk.fromIterable(leaked).map(l => s"after scope close: $l")

  private def report(actions: List[Action], found: Chunk[String]): String =
    if (found.isEmpty) ""
    else (s"sequence: ${actions.mkString("List(", ", ", ")")}" +: found).mkString("\n")

  // The generator

  private val genKind: Gen[Any, Kind] =
    Gen.weighted(
      Gen.const(Kind.Good)         -> 10.0,
      Gen.const(Kind.SlowInit)     -> 4.0,
      Gen.const(Kind.SlowShutdown) -> 3.0,
      Gen.const(Kind.FailingInit)  -> 6.0,
      Gen.const(Kind.HangingInit)  -> 5.0,
      Gen.const(Kind.HangThenFail) -> 3.0
    )

  // A swap that settles on its own, so a pair of actions never leaves one parked.
  private val genSettlingKind: Gen[Any, Kind] =
    Gen.elements(Kind.Good, Kind.SlowInit, Kind.FailingInit, Kind.SlowShutdown)

  private val genSetProvider: Gen[Any, Action] =
    (genKind <*> Gen.weighted(Gen.const(true) -> 1.0, Gen.const(false) -> 2.0)).map { case (k, r) =>
      Action.SetProvider(k, r)
    }

  private val genEmit: Gen[Any, Action] =
    Gen.weighted(
      Gen.const(Action.EmitReady)                -> 8.0,
      Gen.const(Action.EmitError(fatal = false)) -> 5.0,
      Gen.const(Action.EmitError(fatal = true))  -> 3.0,
      Gen.const(Action.EmitStale)                -> 6.0
    )

  private val genNonFatalEmit: Gen[Any, Action] =
    Gen.elements(Action.EmitReady, Action.EmitError(fatal = false), Action.EmitStale)

  private val genBoth: Gen[Any, Action] = {
    val settlingSwap = (genSettlingKind <*> Gen.boolean).map { case (k, r) => Action.SetProvider(k, r): Action }
    Gen.oneOf(
      (settlingSwap <*> Gen.oneOf(genNonFatalEmit, Gen.const(Action.Evaluate), settlingSwap)).map { case (a, b) =>
        Action.Both(a, b): Action
      },
      (genNonFatalEmit <*> Gen.oneOf(genNonFatalEmit, Gen.const(Action.Evaluate))).map { case (a, b) =>
        Action.Both(a, b): Action
      }
    )
  }

  private val genAction: Gen[Any, Action] =
    Gen.weighted(
      genSetProvider                                          -> 26.0,
      Gen.const(Action.Evaluate)                              -> 14.0,
      genEmit                                                 -> 20.0,
      genNonFatalEmit.map(Action.EmitFromReplaced(_): Action) -> 5.0,
      genBoth                                                 -> 15.0,
      Gen.const(Action.InterruptSwap)                         -> 5.0,
      Gen.const(Action.ReleaseHangs)                          -> 6.0,
      Gen.const(Action.ReinstallPrevious)                     -> 3.0,
      Gen.const(Action.Shutdown)                              -> 3.0
    )

  /** After a shutdown the instance is not used again ("evaluations fail from then on"), so a later swap becomes an
    * evaluation. A map keeps shrinking intact.
    */
  private def afterShutdownNoSwaps(actions: List[Action]): List[Action] = {
    def swaps(a: Action): Boolean = a match {
      case Action.SetProvider(_, _) | Action.ReinstallPrevious => true
      case Action.Both(x, y)                                   => swaps(x) || swaps(y)
      case _                                                   => false
    }
    val (before, rest) = actions.span(_ != Action.Shutdown)
    before ++ rest.map(a => if (swaps(a)) Action.Evaluate else a)
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
    } @@ PropertyBudget(baseSamples) @@ TestAspect.shrinks(50) @@ PropertyBudget.timeout((baseSamples * 40).seconds),
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
      ),
      regression(
        "a timed-out swap whose provider then fails leaves Error and shuts the replaced provider down once",
        List(SetProvider(Kind.HangThenFail), ReinstallPrevious, ReleaseHangs, Evaluate)
      ),
      regression(
        "a superseded timed-out provider that then fails shuts the provider it replaced down once",
        List(SetProvider(Kind.HangThenFail), SetProvider(Kind.Good, reuseName = true), ReleaseHangs)
      ),
      regression(
        "an Error status answers from the installed provider, which a reused name does not hide",
        List(SetProvider(Kind.HangingInit, reuseName = true), ReinstallPrevious, Evaluate, EmitError(fatal = false))
      ),
      regression(
        "a parked swap whose provider fails once released rolls back to the previous provider",
        List(SetProvider(Kind.HangThenFail), ReleaseHangs, Evaluate)
      ),
      regression(
        "the provider a timed-out swap replaced is refused until that swap settles",
        List(SetProvider(Kind.HangingInit), Evaluate, ReinstallPrevious, ReleaseHangs)
      ),
      regression(
        "an event from a replaced provider with the same name does not change the status",
        List(
          SetProvider(Kind.Good, reuseName = true),
          EmitFromReplaced(EmitError(fatal = false)),
          EmitFromReplaced(EmitStale),
          Evaluate
        )
      ),
      regression(
        "after a timed-out swap, an event from the replaced provider with the same name does not change the status",
        List(
          SetProvider(Kind.HangingInit, reuseName = true),
          ReinstallPrevious,
          EmitFromReplaced(EmitStale),
          ReleaseHangs
        )
      ),
      regression(
        "evaluations during a swap see the old provider, NotReady or the new provider",
        List(Both(SetProvider(Kind.SlowInit), Evaluate), Both(SetProvider(Kind.FailingInit), Evaluate))
      ),
      regression(
        "an event during a swap takes effect before it, after it, or not at all",
        List(Both(SetProvider(Kind.SlowInit), EmitError(fatal = false)), Both(SetProvider(Kind.FailingInit), EmitStale))
      ),
      regression(
        "two swaps at once install the one that finished last",
        List(Both(SetProvider(Kind.SlowInit), SetProvider(Kind.Good)), Evaluate)
      ),
      regression(
        "two events at once settle on one of their orders",
        List(Both(EmitError(fatal = false), EmitStale), Both(EmitReady, Evaluate))
      ),
      regression(
        "a failed swap stays Error when the provider it rolls back to fails to initialize again",
        List(
          SetProvider(Kind.HangThenFail),
          SetProvider(Kind.HangThenFail),
          SetProvider(Kind.FailingInit),
          SetProvider(Kind.FailingInit, reuseName = true)
        )
      ),
      regression(
        "a failed swap rolls back to a timed-out provider by initializing it again, Ready",
        List(SetProvider(Kind.HangingInit), SetProvider(Kind.FailingInit), Evaluate)
      ),
      regression(
        "a provider parked in an initialize() that will fail is replaced, and every provider is shut down once",
        List(
          SetProvider(Kind.HangingInit),
          SetProvider(Kind.SlowInit),
          SetProvider(Kind.HangThenFail),
          SetProvider(Kind.HangThenFail),
          EmitReady
        )
      ),
      regression(
        "a provider that shuts down slowly is still shut down once, inside the close bound",
        List(SetProvider(Kind.SlowShutdown), SetProvider(Kind.SlowShutdown), Shutdown)
      )
    ),
    suite("harness")(
      test("the model refuses an EmitReady after Shutdown becoming Ready, and the checker flags a Ready instance") {
        val p0        = Installed("p0", "p0")
        val shutDown  = Model.initial(p0).step(Shutdown, p0)
        val afterThat = shutDown.step(EmitReady, p0)
        val observed  = Observation(ProviderStatus.Ready, "p0", Right(true), Exit.succeed(true), Right("p0"))
        val flagged   = violations(afterThat, observed)
        assertTrue(
          afterThat.status == ProviderStatus.NotReady,
          flagged.contains("providerStatus is Ready after shutdown")
        )
      },
      test("the checker tells two providers with the same name apart by who answered") {
        val old      = Installed("p0", "shared")
        val next     = Installed("p1", "shared")
        val model    = Model.initial(old).step(SetProvider(Kind.Good, reuseName = true), next)
        val observed = Observation(ProviderStatus.Ready, "shared", Right(true), Exit.succeed(true), Right("p0"))
        assertTrue(violations(model, observed) == List("evaluation was answered by Right(p0), the model says 'p1'"))
      },
      test("a timed-out swap leaves the replaced provider pending until its hangs are released, or shutdown") {
        val p0       = Installed("p0", "p0")
        val p1       = Installed("p1", "p1")
        val timedOut = Model.initial(p0).step(SetProvider(Kind.HangingInit), p1).settle
        assertTrue(
          timedOut.orphans == Set("p0"),
          timedOut.status == ProviderStatus.Error,
          timedOut.step(ReleaseHangs, p0).orphans.isEmpty,
          timedOut.step(Shutdown, p0).orphans.isEmpty
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
