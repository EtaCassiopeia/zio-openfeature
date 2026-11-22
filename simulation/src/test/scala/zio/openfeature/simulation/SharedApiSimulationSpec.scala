package zio.openfeature.simulation

import dev.openfeature.sdk.{NoOpProvider, OpenFeatureAPI, FeatureProvider => OFFeatureProvider}
import zio._
import zio.openfeature._
import zio.openfeature.internal.{FeatureFlagsFactories, LifecycleProbe, ManagedProvider}
import zio.openfeature.testsupport.{LeakCheck, PropertyBudget}
import zio.test.{Spec => _, _}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference}

import scala.jdk.CollectionConverters._

/** Concurrent simulation of two or three instances on one shared API, under the schedule perturber. The first instance
  * owns the API and the others do not; each is on its own domain, and one may be built on another's provider object, so
  * one stack serves two slots; instances on one stack swap one at a time. Each instance has its own evaluation workers
  * and swapper; one mutator changes flags on every stack, and a closer shuts some non-owners down mid-run, then maybe
  * the owner. It checks:
  *   - every evaluation completes (bounded) with a value or a default plus an error code, never a defect;
  *   - each instance's successful answers against its own history ([[History]]): the global writes, and its own
  *     evaluations and swaps. An answer from a leaf after a swap away from it raised its gate is never a success, for
  *     the instance that swapped, unless a sibling's stack still holds that leaf: the sibling answers from it
  *     correctly, and the mark is the leaf's, not the instance's;
  *   - after an instance's own close returns, no evaluation it invokes from then on succeeds, and after the owner's
  *     close returns the same holds for every instance; a non-owner's close changes nothing in its siblings' checks;
  *   - a swap is refused (`ProviderNotReady`) only once the instance's close, or the owner's, has started, and always
  *     when invoked after that close returned; a refused swap's stack is never initialized or shut down;
  *   - `RegisteredNotShutDown`: while the API is open, the stack a slot holds now, read from the API itself, has no
  *     leaf shut down, after every command and at every lifecycle probe point. A stack whose `initialize()` fails is
  *     left out: a multi-provider shuts its members down itself when it fails, before the rollback binds the previous
  *     stack;
  *   - `SharedShutDownOnce`: no leaf is shut down twice, at every probe point; once the API is gone every leaf of every
  *     stack a slot ever held is shut down exactly once;
  *   - no fiber or thread outlives the run (LeakCheck).
  *
  * With `ZOF_TRACE=1` each plan's run is recorded for the registry model's trace check (`formal/registry/validate.py`),
  * with the plan beside it, so a run the model rejects can be replayed from the regression corpus
  * (`regressions/shared/`).
  */
object SharedApiSimulationSpec extends ZIOSpecDefault {

  private val name = "SharedApiSimulationSpec"

  /** An instance: built on `shareOf`'s provider object if set (an earlier instance), else on its own `stack`. With
    * `userBound`, the user binds that stack under the instance's domain first, and the build reuses it as it is (the
    * SDK owns it). A user-bound instance makes no swap and no instance shares it: a swap off a provider the user bound
    * queues the SDK's own shutdown of it, which can land after the owner's teardown already shut it down, an open gap
    * kept out of the plans; and a provider a slot the library can see holds cannot be bound under a second domain.
    */
  final case class Inst(
    shareOf: Option[Int],
    stack: Spec,
    swaps: List[Spec],
    workers: List[List[Cmd.Eval]],
    userBound: Option[Spec] = None
  )

  /** `closes`: the non-owners (by index) closed mid-run, in order; then the owner, if `ownerCloses`. */
  final case class Plan(
    home: Map[String, Kind],
    insts: List[Inst],
    closes: List[Int],
    ownerCloses: Boolean,
    muts: List[Cmd],
    scheduleSeed: Long
  )

  /** A failing `initialize()` on about a quarter of swap targets. No stall: a timed-out swap is outside this plan. */
  val swapInitFault: Gen[Any, Option[InitFault]] =
    Gen.weighted(Gen.const(None) -> 4.0, Gen.const(Some(InitFault.Fails)) -> 1.5)

  val genPlan: Gen[Any, Plan] =
    for {
      // Drawn first and never shrunk, so shrinking the rest of the plan keeps the schedule that failed.
      scheduleSeed <- Gen.long.noShrink
      home         <- SequentialSimulationSpec.genHomeKinds
      n            <- Gen.int(2, 3)
      insts        <- Gen
        .collectAll((0 until n).toList.map { k =>
          for {
            share <-
              if (k == 0) Gen.const(None)
              else Gen.weighted(Gen.const(None) -> 2.0, Gen.int(0, k - 1).map(Some(_)) -> 1.0)
            stack   <- Gens.genSpec(2, home, Gens.leafFaults(Gens.noInitFault))
            swaps   <- Gen.listOfBounded(0, 2)(Gens.genSpec(2, home, Gens.leafFaults(swapInitFault)))
            workers <- Gen.listOfBounded(1, 2)(Gen.listOfBounded(3, 15)(Gens.genEval(home)))
            user    <-
              if (share.nonEmpty) Gen.const(false) else Gen.weighted(Gen.const(false) -> 3.0, Gen.const(true) -> 1.0)
          } yield Inst(share, stack, swaps, workers, Option.when(user)(stack))
        })
        .map(userBoundAlone)
      closes      <- Gen.collectAll((1 until n).toList.map(k => Gen.boolean.map(Option.when(_)(k)))).map(_.flatten)
      ownerCloses <- Gen.weighted(Gen.const(false) -> 3.0, Gen.const(true) -> 1.0)
      muts        <- Gen.listOfBounded(0, 10)(
        Gens.genCmd(7, Nil, home).map {
          case c: Cmd.SetFlag => c
          case _              => Cmd.SetFlag(0, "k0", None, emit = true)
        }
      )
    } yield Plan(home, insts, closes, ownerCloses, muts, scheduleSeed)

  /** A user-bound instance makes no swap, and an instance that would share its stack builds on its own instead. */
  def userBoundAlone(insts: List[Inst]): List[Inst] = {
    val user = insts.zipWithIndex.collect { case (i, k) if i.userBound.nonEmpty && i.shareOf.isEmpty => k }.toSet
    insts.map {
      case i if i.userBound.nonEmpty && i.shareOf.isEmpty => i.copy(swaps = Nil)
      case i if i.shareOf.exists(user)                    => i.copy(shareOf = None)
      case i                                              => i.copy(userBound = None)
    }
  }

  /** The counters the generated cases must reach. */
  val reachKeys: List[String] = List(
    "rsim.swap.ok",
    "rsim.swap.rolledBack",
    "rsim.swap.refused",
    "rsim.close",
    "rsim.ownerClose",
    "rsim.shared",
    "rsim.evalAfterClose",
    "rsim.userBound",
    "rsim.siblingsChecked",
    "rsim.invariant.checked",
    "rsim.slot.held"
  )

  private val evalBound = 10.seconds

  /** One instance of a run: its stack at build, the stack it serves now, and when its close (or the owner's) started
    * and returned, on the perturber's clock.
    */
  final private class Live(
    val k: Int,
    val initial: Built,
    val current: Ref[Built],
    val restored: Ref[Set[String]],
    val retired: Ref[Set[String]],
    val closing: AtomicBoolean,
    val closedAt: AtomicLong,
    val userBound: Boolean
  ) {
    // Bumped as each swap starts and as it returns, so odd while one is in flight: a sibling is quiet across a close
    // when every read of it, before and after, saw one even value.
    val swapSeq: AtomicInteger = new AtomicInteger(0)
    def domain: String         = s"d$k"
  }

  private def isBound(sdk: OFFeatureProvider, b: Built): Boolean =
    (sdk eq b.provider) || ManagedProvider
      .existing(b.provider)
      .exists(m => ManagedProvider.existing(sdk).exists(_ eq m))

  def run(plan: Plan): ZIO[Any, String, Unit] =
    for {
      baseline <- LeakCheck.snapshot
      problems <- Ref.make(Chunk.empty[String])
      report = (p: String) => problems.update(_ :+ p)
      // One counter for every stack of the run, so a leaf id names one leaf across instances.
      nextId <- Ref.make(0)
      number = (s: Spec) =>
        nextId.modify { from =>
          val ns = Spec.number(s, from); (ns, Spec.maxId(ns) + 1)
        }
      insts = userBoundAlone(plan.insts)
      builts <- ZIO.foldLeft(insts.zipWithIndex)(Vector.empty[Built]) { case (acc, (i, k)) =>
        i.shareOf.filter(j => j >= 0 && j < k) match {
          case Some(j) => ZIO.succeed(acc :+ acc(j))
          case None    => number(i.userBound.getOrElse(i.stack)).flatMap(Harness.build(_, timed = false)).map(acc :+ _)
        }
      }
      lives <- ZIO.foreach(builts.zipWithIndex.toList) { case (b, k) =>
        (Ref.make(b) <*> Ref.make(Set.empty[String]) <*> Ref.make(Set.empty[String])).map { case (c, r, t) =>
          new Live(k, b, c, r, t, new AtomicBoolean(false), new AtomicLong(Long.MaxValue), insts(k).userBound.nonEmpty)
        }
      }
      // Instances built on one stack swap one at a time. A swap off a provider no other slot holds makes the SDK queue
      // its shutdown at the binding, which runs even if a failed sibling swap's rollback binds that provider again
      // meanwhile: an open gap between the SDK and the library, kept out of the plans as Generate.tla keeps it out.
      groupLocks <- ZIO.foreach(builts.toList.distinctBy(java.lang.System.identityHashCode))(b =>
        Semaphore.make(1).map(b -> _)
      )
      lockOf = (l: Live) => groupLocks.collectFirst { case (b, s) if b eq l.initial => s }.get
      // Every stack built for the run, so a slot's provider can be matched to its stack before its swap returns.
      stacks = new java.util.concurrent.ConcurrentLinkedQueue[Built](builts.distinct.asJava)
      bound   <- Ref.make(builts.toList.distinct) // stacks a slot held at some point
      refused <- Ref.make(List.empty[Built])      // stacks a refused swap never bound
      history <- Ref.make(Chunk.empty[(Int, Op)]) // instance -1: a write, seen by every instance
      api     = OpenFeatureAPI.createIsolated()
      apiOpen = new AtomicBoolean(true)
      // RegisteredNotShutDown: read each slot from the API, and report only if the slot still holds that provider after
      // the leaves were read, so a swap that rebinds the slot meanwhile is not mistaken for a shutdown of a bound stack.
      registeredNotShutDown = (where: String) =>
        ZIO.foreachDiscard(lives) { l =>
          ZIO.suspendSucceed {
            val held = api.getProvider(l.domain)
            stacks.asScala.find(isBound(held, _)) match {
              case Some(b) if apiOpen.get() && Spec.initOutcome(b.spec) != InitOutcome.Fails =>
                val shut = b.leaves.values.filter(_.shutdowns.get() > 0).map(_.name)
                ZIO.when(shut.nonEmpty && apiOpen.get() && (api.getProvider(l.domain) eq held))(
                  report(s"RegisteredNotShutDown ($where): ${shut.mkString(",")} shut down, still bound to ${l.domain}")
                )
              case _ => ZIO.unit
            }
          }
        }
      sharedShutDownOnce = (where: String) =>
        ZIO
          .succeed(stacks.asScala.flatMap(_.leaves.values).filter(_.shutdowns.get() > 1).toList)
          .flatMap(twice =>
            ZIO.foreachDiscard(twice)(l =>
              report(s"SharedShutDownOnce ($where): ${l.name} shut down ${l.shutdowns.get()} times")
            )
          )
      // The registry model's invariants on live state (RegistryInvariants), once every instance is built: each
      // violation is kept with where it was first seen, and reported at the end.
      instances = new AtomicReference(Vector.empty[FeatureFlagsLive])
      apiGone   = new AtomicBoolean(false)
      found     = new java.util.concurrent.ConcurrentHashMap[String, String]()
      leavesOf  = (b: Built) =>
        b.leaves.values.toList
          .sortBy(_.name)
          .map(l => RegistryInvariants.Leaf(l.name, l.shutdownThreads.asScala.toList))
      userStacks = lives.filter(_.userBound).map(_.initial)
      // A slot as the API holds it, read again after its leaves so a swap that moved it meanwhile reads as Unknown.
      slotOf = (l: Live, ff: FeatureFlagsLive) => {
        val held = api.getProvider(l.domain)
        if (held.isInstanceOf[NoOpProvider]) RegistryInvariants.Slot.Empty
        else
          stacks.asScala.find(isBound(held, _)) match {
            case Some(b) if Spec.initOutcome(b.spec) != InitOutcome.Fails =>
              val view = RegistryInvariants.Slot.Held(leavesOf(b), ff.replayView._2.exists(isBound(_, b)))
              if (api.getProvider(l.domain) eq held) view else RegistryInvariants.Slot.Unknown
            case _ => RegistryInvariants.Slot.Unknown
          }
      }
      registryInvariants = (where: String) =>
        ZIO.suspendSucceed {
          val ffs = instances.get()
          // Read before the instances: the owner's close returning meanwhile must not make their earlier status late.
          val gone = apiGone.get()
          ZIO.when(ffs.size == lives.size) {
            ZIO
              .foreach(lives) { l =>
                val ff = ffs(l.k)
                ff.providerStatus.map { status =>
                  RegistryInvariants.Instance(
                    l.k,
                    ff.replayView._1,
                    live = !l.closing.get(),
                    closed = l.closedAt.get() != Long.MaxValue,
                    slotOf(l, ff),
                    status
                  )
                }
              }
              .map { is =>
                val state = RegistryInvariants.State(apiOpen.get(), gone, is, userStacks.flatMap(leavesOf))
                RegistryInvariants.always(state).foreach(v => found.putIfAbsent(v, where): Unit)
                Stats.hit("rsim.invariant.checked")
                if (is.exists(_.slot.isInstanceOf[RegistryInvariants.Slot.Held])) Stats.hit("rsim.slot.held")
              }
          }
        }
      checks = (where: String) => registeredNotShutDown(where) *> registryInvariants(where)
      // A swap raises its gate before it binds the new provider: from then on the leaves of the stack it leaves are
      // retiring for that instance (`retired`), which a late answer of that instance is judged against. A sibling still
      // serving the stack is not, and its answers from those leaves are not late for it.
      callback = (l: Live) =>
        (p: LifecycleProbe.Point) =>
          (p match {
            case LifecycleProbe.Point.SwapBind =>
              // The late mark is on the leaf, and the gate is the instance's: a leaf another instance's stack still holds
              // is not marked (instances on one stack swap one at a time, so that instance's stack is current here).
              (l.current.get <*> ZIO.foreach(lives.filter(_.k != l.k))(_.current.get)).flatMap { case (b, others) =>
                val held = others.flatMap(_.leaves.values).toSet
                b.leaves.values.filterNot(held.contains).foreach(_.retiring.set(true))
                l.retired.update(_ ++ b.leaves.values.map(_.name))
              }
            case LifecycleProbe.Point.RollbackBind =>
              l.current.get.flatMap { b =>
                b.leaves.values.foreach(_.retiring.set(false))
                l.retired
                  .update(_ -- b.leaves.values.map(_.name)) *> l.restored.update(_ ++ b.leaves.values.map(_.name))
              }
            case _ => ZIO.unit
          }) *> checks(s"i${l.k} at ${p.name}") *> sharedShutDownOnce(s"i${l.k} at ${p.name}")
      perturber = new SchedulePerturber(plan.scheduleSeed, _ => ZIO.unit)
      _ <- ZIO.succeed(Stats.declare(SchedulePerturber.declaredCounters ++ reachKeys))
      _ <- RegistryRecording.recorded("simulation-shared", Some(PlanCodec.write(plan))) {
        ZIO.scoped {
          for {
            ffs <- ZIO.foreach(lives) { l =>
              ZIO.when(l.userBound)(
                ZIO
                  .attemptBlocking(api.setProviderAndWait(l.domain, l.initial.provider))
                  .mapError(e => s"the user's bind of i${l.k}'s stack failed: $e") *>
                  RegistryRecording.environment("UserBind", Some(l.domain), Some(l.initial.provider)) *>
                  Stats.generated("rsim.userBound")
              ) *> RegistryRecording
                .instanceProbe(new LifecycleProbe {
                  def at(p: LifecycleProbe.Point): UIO[Unit] = callback(l)(p) *> perturber.lifecycle.at(p)
                })
                .flatMap { probe =>
                  FeatureFlagsFactories.build(
                    l.initial.provider,
                    domain = Some(l.domain),
                    version = None,
                    initialHooks = Nil,
                    statusRef = None,
                    addShutdownFinalizer = l.k == 0,
                    apiOverride = Some(api),
                    probe = probe,
                    evaluationProbe = perturber.evaluation
                  )
                }
                .mapError(e => s"install i${l.k} failed: $e")
            }
            byK = ffs.toVector
            _ <- ZIO.succeed(instances.set(byK))

            worker = (l: Live, cmds: List[Cmd.Eval]) =>
              ZIO.foreachDiscard(cmds) { case Cmd.Eval(key, kind, inv, scoped) =>
                val ff  = byK(l.k)
                val eff = Harness.read(ff, key, kind, inv.toEvaluationContext)
                val fp  = Ctx.rendered(Runner.mergedFor(Ctx.empty, Ctx.empty, None, scoped, inv))
                for {
                  t0   <- ZIO.succeed(perturber.tick())
                  exit <- scoped.fold(eff)(c => ff.withContext(c.toEvaluationContext)(eff)).timeout(evalBound).exit
                  t1   <- ZIO.succeed(perturber.tick())
                  _    <- exit match {
                    case Exit.Success(None)    => report(s"i${l.k} eval $key:$kind did not complete within $evalBound")
                    case Exit.Failure(c)       => report(s"i${l.k} eval $key:$kind failed: ${c.prettyPrint.take(400)}")
                    case Exit.Success(Some(o)) =>
                      val closed = l.closedAt.get()
                      ZIO.when(t0 > closed)(Stats.generated("rsim.evalAfterClose")) *>
                        ZIO.when(o.isSuccess && t0 > closed)(
                          report(
                            s"i${l.k} eval $key:$kind got $o, invoked at t$t0 after its close returned at t$closed"
                          )
                        ) *>
                        ZIO.when(o.isSuccess)(history.update(_ :+ (l.k -> Op.Eval(key, kind, fp, t0, t1, o)))) *>
                        (l.restored.get <*> l.retired.get).flatMap { case (back, gone) =>
                          val late = o.variant
                            .filter(_.endsWith(SimLeaf.LateSuffix))
                            .filter(v => gone.contains(v.takeWhile(_ != ':')))
                          val kept = late.exists(v => o.reason == "CACHED" && back.contains(v.takeWhile(_ != ':')))
                          ZIO.when(o.isSuccess && late.nonEmpty && !kept)(
                            report(s"i${l.k} eval $key:$kind got $o, answered after a swap away from its leaf started")
                          )
                        }
                  }
                  _ <- checks(s"after an evaluation of i${l.k}")
                } yield ()
              }

            swapper = (l: Live, swaps: List[Spec]) =>
              ZIO.foreachDiscard(swaps) { s =>
                for {
                  ns <- number(s)
                  nb <- Harness.build(ns, timed = false)
                  _  <- ZIO.succeed(stacks.add(nb))
                  fails = Spec.initOutcome(ns) == InitOutcome.Fails
                  _  <- perturber.note(s"i${l.k} swap to ${Spec.show(ns)} starts")
                  t0 <- ZIO.succeed(perturber.tick())
                  _  <- ZIO.succeed(l.swapSeq.incrementAndGet())
                  r  <- lockOf(l).withPermit(byK(l.k).setProvider(nb.provider)).sandbox.either
                  _  <- ZIO.succeed(l.swapSeq.incrementAndGet())
                  t1 <- ZIO.succeed(perturber.tick())
                  // Invoked once this instance's close, or the owner's, had returned: it must have been refused.
                  closed = t0 > l.closedAt.get()
                  _ <- ZIO.when(
                    closed && !r.left.exists(_.failureOption.exists(_.isInstanceOf[FeatureFlagError.ProviderNotReady]))
                  )(
                    report(
                      s"i${l.k} setProvider to ${Spec.show(ns)}, invoked at t$t0 after its close returned, was not refused"
                    )
                  )
                  _ <- r match {
                    case Left(c) if c.failureOption.exists(_.isInstanceOf[FeatureFlagError.ProviderNotReady]) =>
                      ZIO.when(!l.closing.get())(
                        report(s"i${l.k} setProvider to ${Spec.show(ns)} refused before any close started")
                      ) *> Stats.generated("rsim.swap.refused") *> refused.update(nb :: _)
                    case Left(c)
                        if fails && c.defects.isEmpty &&
                          c.failureOption.exists(_.isInstanceOf[FeatureFlagError.ProviderInitializationFailed]) =>
                      Stats.generated("rsim.swap.rolledBack") *> bound.update(nb :: _)
                    case Left(c) =>
                      report(s"i${l.k} setProvider to ${Spec.show(ns)} failed: ${c.prettyPrint.take(400)}")
                    case Right(_) if fails =>
                      report(s"i${l.k} setProvider to ${Spec.show(ns)}, whose initialize() fails, succeeded")
                    case Right(_) =>
                      Stats.generated("rsim.swap.ok") *> bound.update(nb :: _) *>
                        history.update(_ :+ (l.k -> Op.Swap(ns, t0, t1))) *> l.current.set(nb)
                  }
                  _ <- checks(s"after i${l.k}'s swap to ${Spec.show(ns)}")
                  _ <- ZIO.yieldNow
                } yield ()
              }

            mutator = ZIO.foreachDiscard(plan.muts) {
              case Cmd.SetFlag(idx, key, value, emit) =>
                ZIO.foreach(lives)(_.current.get).flatMap { bs =>
                  val leaves     = bs.flatMap(_.leaves.toList).distinctBy(_._1).sortBy(_._1)
                  val (id, leaf) = leaves(idx % leaves.size)
                  for {
                    t0          <- ZIO.succeed(perturber.tick())
                    invalidated <- ZIO
                      .attemptBlocking {
                        value.fold(leaf.flags.remove(key): Unit)(v => leaf.flags.put(key, v): Unit)
                        if (emit) leaf.configurationChanged()
                      }
                      .as(emit)
                      .catchAll {
                        case _: java.util.concurrent.RejectedExecutionException if leaf.shutdowns.get() > 0 =>
                          ZIO.succeed(false)
                        case e => report(s"mutation failed: $e").as(false)
                      }
                    t1 <- ZIO.succeed(perturber.tick())
                    _  <- history.update(_ :+ (-1 -> Op.Write(id, key, value, invalidated, t0, t1)))
                    _  <- checks("after a flag change")
                  } yield ()
                } *> ZIO.yieldNow
              case _ => ZIO.unit
            }

            // Each instance's slot provider and status, between two reads of its swap sequence.
            sample = ZIO.foreach(lives) { j =>
              val seq0 = j.swapSeq.get()
              val held = api.getProvider(j.domain): AnyRef
              byK(j.k).providerStatus.map(st => (j.k, (held, st, seq0, j.swapSeq.get())))
            }
            close = (l: Live) =>
              for {
                _      <- perturber.note(s"i${l.k} shutdown")
                before <- sample
                _      <- ZIO.succeed(l.closing.set(true))
                _      <- byK(l.k).shutdown
                _      <- ZIO.succeed(l.closedAt.set(perturber.tick()))
                after  <- sample
                _      <- Stats.generated("rsim.close")
                // SiblingsUnaffected: a non-owner's close moves no quiet sibling.
                _ <- ZIO.when(l.k != 0 && apiOpen.get()) {
                  val quiet = before
                    .zip(after)
                    .collect {
                      case ((k, (_, _, a, b)), (_, (_, _, c, e))) if a % 2 == 0 && a == b && b == c && c == e => k
                    }
                    .toSet
                  val view = (xs: List[(Int, (AnyRef, ProviderStatus, Int, Int))]) =>
                    xs.map { case (k, (p, st, _, _)) => k -> (p, st) }.toMap
                  ZIO.succeed {
                    RegistryInvariants
                      .siblingsUnaffected(l.k, quiet, view(before), view(after))
                      .foreach(v => found.putIfAbsent(v, s"i${l.k}'s close"): Unit)
                    if (quiet.exists(_ != l.k)) Stats.hit("rsim.siblingsChecked")
                  }
                }
                _ <- checks(s"after i${l.k}'s close")
              } yield ()

            closer =
              ZIO.foreachDiscard(plan.closes.filter(k => k > 0 && k < lives.size).distinct)(k =>
                ZIO.yieldNow.repeatN(20) *> close(lives(k))
              ) *>
                ZIO.when(plan.ownerCloses)(
                  ZIO.yieldNow.repeatN(20) *> ZIO.succeed {
                    apiOpen.set(false)
                    lives.foreach(_.closing.set(true))
                  } *> close(lives.head) *>
                    ZIO.succeed(lives.foreach(_.closedAt.compareAndSet(Long.MaxValue, perturber.tick()))) *>
                    ZIO.succeed(apiGone.set(true)) *> checks("after the owner's close") *>
                    Stats.generated("rsim.ownerClose")
                )

            _ <- ZIO.collectAllParDiscard(
              closer :: mutator :: lives.zip(insts).flatMap { case (l, i) =>
                swapper(l, i.swaps) :: i.workers.map(worker(l, _))
              }
            )
            _ <- checks("at the end, before the scope closes")
            // The scope's close tears the API down with the owner's finalizer: from here it is not open.
            _ <- ZIO.succeed(apiOpen.set(false))
          } yield ()
        }
      }
      _ <- ZIO.succeed(apiOpen.set(false))
      _ <- ZIO.foreachDiscard(found.asScala.toList.sortBy(_._1).take(5)) { case (v, where) =>
        report(s"registry invariant: $v (at $where)")
      }
      ops <- history.get
      _   <- ZIO.foreachDiscard(lives) { l =>
        val mine    = ops.collect { case (k, op) if k == l.k || k == -1 => op }
        val checked = History.check(l.initial.spec, mine)
        ZIO.succeed(Stats.add("history.checked", checked.checked)) *>
          ZIO.foreachDiscard(checked.violations.take(3))(v => report(s"i${l.k} ${v.render}"))
      }
      everBound <- bound.get
      _         <- ZIO.foreachDiscard(everBound)(b =>
        Runner.awaitShutdown(b).flatMap(ZIO.foreachDiscard(_)(p => report(s"SharedShutDownOnce at the end: $p")))
      )
      // A stack a refused swap never bound: the library must not have touched it. The run then shuts it down itself.
      never <- refused.get
      _     <- ZIO.foreachDiscard(never.flatMap(_.leaves.values))(l =>
        ZIO.when(l.shutdowns.get() != 0 || l.inits.get() != 0)(
          report(s"${l.name}, of a refused swap: inits=${l.inits.get()} shutdowns=${l.shutdowns.get()}")
        )
      )
      _ <- ZIO.foreachDiscard(never)(b => ZIO.attemptBlocking(b.provider.shutdown()).ignore)
      _ <- ZIO.succeed(everBound.flatMap(_.leaves.values).flatMap(_.shutdownThreads.asScala).foreach { t =>
        Stats.hit(if (t.startsWith("openfeature-provider-thread-")) "rsim.shutBy.sdk" else "rsim.shutBy.library")
      })
      leaks <- LeakCheck.awaitNoLeaks(baseline, 5.seconds).withClock(Clock.ClockLive)
      _     <- ZIO.foreachDiscard(leaks)(l => report(s"leak: $l"))
      _     <- ZIO.when(insts.exists(_.shareOf.nonEmpty))(Stats.generated("rsim.shared"))
      _     <- ZIO.succeed(Stats.hit("rsim"))
      ps    <- problems.get
      _     <- ZIO.when(ps.nonEmpty)(
        ZIO.fail(
          (s"instances ${insts.zipWithIndex
              .map { case (i, k) =>
                s"i$k " + i.shareOf
                  .fold(Spec.show(i.userBound.getOrElse(i.stack)) + i.userBound.fold("")(_ => " (user-bound)"))(j =>
                    s"on i$j's stack"
                  )
              }
              .mkString(", ")}" +: ps.take(10)).mkString("\n") +
            (if (ps.size > 10) s"\n${ps.size - 10} more problems" else "") +
            "\nharness events:\n" + perturber.events + "\n" + perturber.render(150)
        )
      )
    } yield ()

  def spec = suite("SharedApiSimulation")(
    test("replays every regression corpus entry") {
      Corpus
        .replay[Any, Plan]("shared")(run)
        .map(failures => assertTrue(failures.isEmpty) ?? failures.mkString("\n"))
    },
    test("instances on one API keep their answers and the ownership invariants under concurrency") {
      check(genPlan) { plan =>
        run(plan).either.flatMap { r =>
          val json = PlanCodec.write(plan)
          ZIO.when(r.isLeft)(Corpus.record(name, json)).as(assertTrue(r == Right(())) ?? s"corpus entry ($name): $json")
        }
      }
    } @@ PropertyBudget(40) @@ TestAspect.after(Console.printLine(Stats.render).orDie),
    // Last, after the generated cases: deterministic under the PR seed; retune the weights if an upgrade moves it.
    test("the generated cases reach every swap outcome, close and sharing") {
      val counts = reachKeys.map(k => k -> Stats.get(k))
      Console.printLine(s"shared-API reach: ${counts.map { case (k, n) => s"$k=$n" }.mkString(" ")}").orDie.as {
        assertTrue(counts.forall(_._2 > 0)) ?? counts.filter(_._2 == 0).map(_._1).mkString("never reached: ", ", ", "")
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ PropertyBudget.timeout(10.minutes)
}
