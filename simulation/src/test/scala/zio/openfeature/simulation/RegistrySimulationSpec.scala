package zio.openfeature.simulation

import dev.openfeature.sdk.{FeatureProvider => OFFeatureProvider}
import zio._
import zio.openfeature._
import zio.openfeature.internal.{LifecycleProbe, ManagedProvider, ProviderRegistration}
import zio.openfeature.testsupport.{LeakCheck, PropertyBudget}
import zio.test.{Spec => _, _}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.jdk.CollectionConverters._

/** Concurrent simulation of one `FeatureFlagRegistry`, the registry model's other topology, under the schedule
  * perturber. Each of two or three domains gets its own stacks; the registry's provider map names the first, and its
  * build may fail: on its first few `initialize()` calls, so a later `getClient` retries the same provider, or on every
  * one, so the run names a replacement (`registry.setProvider` with no client yet) and a retry builds that one instead,
  * leaving the failed provider for the registry's close. Workers call `getClient` before each evaluation, so builds,
  * failures and retries race; once a domain has its client, a swapper swaps it through `registry.setProvider`. The
  * registry closes with the run's scope. It checks:
  *   - `OneInstancePerDomain`: every `getClient(d)` that succeeds returns the same instance (`eq`);
  *   - `OnlyFailedBuildsEvicted`: once a `getClient(d)` succeeded, the registry holds a promise for `d` until it closes
  *     (`replayView`);
  *   - each domain's successful answers against its own history ([[History]]): the global writes, and its client's
  *     evaluations and swaps; an answer from a leaf after a swap away from it raised its gate is never a success;
  *   - a swap to a stack whose `initialize()` fails fails with a typed error and rolls back;
  *   - `RegisteredNotShutDown`: while the registry is open, the stack each domain's slot holds, read from the API, has
  *     no leaf shut down, after every command and at every lifecycle probe point (a stack whose `initialize()` always
  *     fails, or stalls, is left out, as in [[SharedApiSimulationSpec]]: its failed build may shut it down);
  *   - `SharedShutDownOnce`: no leaf is shut down twice; once the registry closed, every stack a slot ever held is shut
  *     down exactly once, a provider a retry replaced included;
  *   - once the registry closed, no client serves (`ClosedApiServesNone`, its value side);
  *   - `OneInitializeAtATime`: no leaf ever runs two `initialize()` calls at once, at every probe point and after every
  *     command;
  *   - no fiber or thread outlives the run (LeakCheck).
  *
  * A fourth kind of domain stalls its first build: a leaf whose first `initialize()` blocks, so the build fails at the
  * init timeout (short in such a run) with that call still running, the async build's watchdog having shut the leaf
  * down, and the run names a replacement as for a stack that always fails. Once the replacement serves, no slot holds the stalled leaf
  * and its call still runs: a build of it on another domain must be refused before it binds, starting no second call,
  * and the run then lets the stalled call return.
  *
  * Each domain's stacks are its own: a provider bound in two slots is an open gap between the SDK and the library,
  * kept out of the plans as `Generate.tla` keeps it out.
  *
  * With `ZOF_TRACE=1` each plan's run is recorded for the registry model's trace check, with the plan beside it
  * (`regressions/registry/`).
  */
object RegistrySimulationSpec extends ZIOSpecDefault {

  private val name = "RegistrySimulationSpec"

  /** A domain: the stack the provider map names first, the replacement a retry builds when that one always fails, the
    * swaps of its client once it has one, and its workers' evaluations, each after a `getClient`.
    */
  final case class Dom(stack: Spec, replacement: Option[Spec], swaps: List[Spec], workers: List[List[Cmd.Eval]])

  final case class Plan(home: Map[String, Kind], domains: List[Dom], muts: List[Cmd], scheduleSeed: Long)

  /** A registry build's wait for readiness in a run with a stalled build: long enough for every other build. */
  val stalledBuildTimeout: Duration = 2.seconds

  // How long a build of a stalled stack held by no slot waits before the run lets the stalled call go: a refused one
  // ends at once, before it binds.
  private val borrowBound = 3.seconds

  /** A failing `initialize()` on about a quarter of swap targets, at once. */
  val swapInitFault: Gen[Any, Option[InitFault]] =
    Gen.weighted(Gen.const(None) -> 4.0, Gen.const(Some(InitFault.Fails)) -> 1.5)

  private def genDom(home: Map[String, Kind]): Gen[Any, Dom] =
    for {
      kind <- Gen.weighted(Gen.const(0) -> 2.0, Gen.const(1) -> 2.0, Gen.const(2) -> 1.0, Gen.const(3) -> 1.0)
      stack <- kind match {
        case 0 => Gens.genSpec(2, home, Gens.leafFaults(Gens.noInitFault))
        case 1 =>
          Gen.int(1, 2).flatMap(k => Gens.genSpec(0, home, Gens.leafFaults(Gen.const(Some(InitFault.FailsFirst(k))))))
        case 2 => Gens.genSpec(0, home, Gens.leafFaults(Gen.const(Some(InitFault.Fails))))
        case _ => Gens.genSpec(0, home, Gens.leafFaults(Gen.const(Some(InitFault.StallThenOk))))
      }
      replacement <-
        if (kind >= 2) Gens.genSpec(2, home, Gens.leafFaults(Gens.noInitFault)).map(Some(_)) else Gen.const(None)
      swaps   <- Gen.listOfBounded(0, 2)(Gens.genSpec(2, home, Gens.leafFaults(swapInitFault)))
      workers <- Gen.listOfBounded(1, 3)(Gen.listOfBounded(2, 10)(Gens.genEval(home)))
    } yield Dom(stack, replacement, swaps, workers)

  val genPlan: Gen[Any, Plan] =
    for {
      // Drawn first and never shrunk, so shrinking the rest of the plan keeps the schedule that failed.
      scheduleSeed <- Gen.long.noShrink
      home         <- SequentialSimulationSpec.genHomeKinds
      n            <- Gen.int(2, 3)
      domains      <- Gen.listOfN(n)(genDom(home))
      muts         <- Gen.listOfBounded(0, 10)(
        Gens.genCmd(7, Nil, home).map {
          case c: Cmd.SetFlag => c
          case _              => Cmd.SetFlag(0, "k0", None, emit = true)
        }
      )
    } yield Plan(home, domains, muts, scheduleSeed)

  /** The counters the generated cases must reach. */
  val reachKeys: List[String] = List(
    "reg.build.failed",
    "reg.build.stalled",
    "reg.build.refused",
    "reg.retry.same",
    "reg.retry.replaced",
    "reg.getClient.concurrent",
    "reg.swap.ok",
    "reg.swap.rolledBack",
    "reg.closedServesNone"
  )

  private val evalBound = 10.seconds

  // A worker retries a failed getClient a millisecond apart until this deadline: a build fails at most twice before its
  // provider comes up, and a replaced one until the run names its replacement.
  private val retryFor = 30.seconds

  /** One domain of a run: its stacks, the stack its client serves now, and its client once a getClient returned it. */
  final private class Live(
    val k: Int,
    val first: Built,
    val replacement: Option[Built],
    val current: Ref[Built],
    val restored: Ref[Set[String]],
    val retired: Ref[Set[String]],
    val failedOnce: Promise[Nothing, Unit],
    val client: Promise[Nothing, FeatureFlags]
  ) {
    def domain: String = s"d$k"
    // The stack its successful build serves: the first, unless that one always fails.
    def initial: Built                      = replacement.getOrElse(first)
    val seen: AtomicReference[FeatureFlags] = new AtomicReference(null)
    val getting                             = new java.util.concurrent.atomic.AtomicInteger(0)
  }

  private def stalls(s: Spec): Boolean = Spec.initOutcome(s).isInstanceOf[InitOutcome.Stalls]

  private def isBound(sdk: OFFeatureProvider, b: Built): Boolean =
    (sdk eq b.provider) || ManagedProvider
      .existing(b.provider)
      .exists(m => ManagedProvider.existing(sdk).exists(_ eq m))

  /** `recorded`'s probe for a domain, then the run's: the recorded step comes first, the perturber's delay after it. */
  private def compose(recorded: LifecycleProbe, run: LifecycleProbe.Point => UIO[Unit]): LifecycleProbe =
    new LifecycleProbe {
      def at(point: LifecycleProbe.Point): UIO[Unit]                                 = recorded.at(point) *> run(point)
      override def step[A](effect: UIO[A])(point: A => LifecycleProbe.Point): UIO[A] =
        recorded.step(effect)(point).tap(a => run(point(a)))
    }

  def run(plan: Plan): ZIO[Any, String, Unit] =
    for {
      baseline <- LeakCheck.snapshot
      problems <- Ref.make(Chunk.empty[String])
      report = (p: String) => problems.update(_ :+ p)
      nextId <- Ref.make(0)
      number = (s: Spec) =>
        nextId.modify { from =>
          val ns = Spec.number(s, from); (ns, Spec.maxId(ns) + 1)
        }
      build = (s: Spec) => number(s).flatMap(Harness.build(_, timed = false))
      // The registry's default provider: no plan uses the default client, so it is never bound.
      unused <- build(Spec.Leaf(-1, Map.empty))
      lives  <- ZIO.foreach(plan.domains.zipWithIndex) { case (d, k) =>
        for {
          first       <- build(d.stack)
          replacement <- ZIO.foreach(d.replacement)(build)
          live        <- (Ref.make(replacement.getOrElse(first)) <*> Ref.make(Set.empty[String]) <*>
            Ref.make(Set.empty[String]) <*> Promise.make[Nothing, Unit] <*> Promise.make[Nothing, FeatureFlags]).map {
            case (c, r, t, f, cl) => new Live(k, first, replacement, c, r, t, f, cl)
          }
        } yield live
      }
      // Every stack built for the run, so a slot's provider can be matched to its stack before its swap returns.
      stacks = new java.util.concurrent.ConcurrentLinkedQueue[Built](
        lives.flatMap(l => l.first :: l.replacement.toList).asJava
      )
      bound   <- Ref.make(lives.map(_.first))     // stacks a slot held at some point
      history <- Ref.make(Chunk.empty[(Int, Op)]) // domain -1: a write, seen by every domain
      open                  = new AtomicBoolean(true)
      apiRef                = new AtomicReference[dev.openfeature.sdk.OpenFeatureAPI](null)
      registry              = new AtomicReference[FeatureFlagRegistryLive](null)
      evicted               = new ConcurrentHashMap[String, String]()
      perturber             = new SchedulePerturber(plan.scheduleSeed, _ => ZIO.unit)
      registeredNotShutDown = (where: String) =>
        ZIO.foreachDiscard(Option(apiRef.get()).toList) { api =>
          ZIO.foreachDiscard(lives) { l =>
            ZIO.suspendSucceed {
              val held = api.getProvider(l.domain)
              stacks.asScala.find(isBound(held, _)) match {
                // A stack whose initialize() fails, or stalls past the init timeout, fails its build, which may shut it
                // down in its slot.
                case Some(b) if open.get() && Spec.initOutcome(b.spec) == InitOutcome.Ok =>
                  val shut = b.leaves.values.filter(_.shutdowns.get() > 0).map(_.name)
                  ZIO.when(shut.nonEmpty && open.get() && (api.getProvider(l.domain) eq held))(
                    report(
                      s"RegisteredNotShutDown ($where): ${shut.mkString(",")} shut down, still bound to ${l.domain}"
                    )
                  )
                case _ => ZIO.unit
              }
            }
          }
        }
      sharedShutDownOnce = (where: String) =>
        ZIO
          .succeed(stacks.asScala.flatMap(_.leaves.values).filter(_.shutdowns.get() > 1).toList)
          .flatMap(
            ZIO.foreachDiscard(_)(l =>
              report(s"SharedShutDownOnce ($where): ${l.name} shut down ${l.shutdowns.get()} times")
            )
          )
      // OnlyFailedBuildsEvicted: a domain a getClient succeeded on keeps its promise until the registry closes.
      onlyFailedBuildsEvicted = (where: String) =>
        ZIO.foreachDiscard(Option(registry.get()).toList) { reg =>
          // The domains with a client, read before the promises: a build that completes in between is then not judged
          // against a snapshot taken before its promise existed.
          val got = lives.filter(_.seen.get() != null)
          reg.replayView.map { case (_, promised) =>
            got.foreach { l =>
              if (open.get() && !promised.contains(Some(l.domain))) evicted.putIfAbsent(l.domain, where): Unit
            }
          }
        }
      // OneInitializeAtATime: a leaf never runs two initialize() calls at once.
      oneInitializeAtATime = (where: String) =>
        ZIO.foreachDiscard(stacks.asScala.flatMap(_.leaves.values).filter(_.concurrentInits.get() > 0).toList)(l =>
          report(s"OneInitializeAtATime ($where): ${l.name} ran two initialize() calls at once")
        )
      checks = (where: String) =>
        registeredNotShutDown(where) *> onlyFailedBuildsEvicted(where) *> oneInitializeAtATime(where)
      // Lets a domain's stalled initialize() return: after the build of it that must be refused.
      releaseStall = (l: Live) => ZIO.succeed(l.first.leaves.values.foreach(_.initGate.release(abandon = false)))
      // Every domain's, at the end of the run's commands.
      releaseStalls = ZIO.foreachDiscard(lives)(releaseStall)
      callback = (l: Live) =>
        (p: LifecycleProbe.Point) =>
          (p match {
            case LifecycleProbe.Point.SwapBind =>
              l.current.get.flatMap { b =>
                b.leaves.values.foreach(_.retiring.set(true))
                l.retired.update(_ ++ b.leaves.values.map(_.name))
              }
            case LifecycleProbe.Point.RollbackBind =>
              l.current.get.flatMap { b =>
                b.leaves.values.foreach(_.retiring.set(false))
                l.retired.update(_ -- b.leaves.values.map(_.name)) *>
                  l.restored.update(_ ++ b.leaves.values.map(_.name))
              }
            case _ => ZIO.unit
          }) *> checks(s"${l.domain} at ${p.name}") *> sharedShutDownOnce(s"${l.domain} at ${p.name}") *>
            perturber.lifecycle.at(p)
      _       <- ZIO.succeed(Stats.declare(SchedulePerturber.declaredCounters ++ reachKeys))
      clients <- RegistryRecording.recorded("simulation-registry", Some(PlanCodec.write(plan))) {
        ZIO.scoped {
          for {
            tags <- RegistryRecording.registryProbe
            probes = (d: Option[String]) =>
              d.flatMap(name => lives.find(_.domain == name)) match {
                case Some(l) => compose(tags(d), callback(l))
                case None    => compose(tags(d), perturber.lifecycle.at)
              }
            config =
              if (plan.domains.exists(d => stalls(d.stack)))
                FeatureFlagsConfig.default.withInitTimeout(stalledBuildTimeout)
              else FeatureFlagsConfig.default
            reg <- FeatureFlagRegistry
              .fromProvider(unused.provider, config, probes)
              .build
              .map(_.get[FeatureFlagRegistry].asInstanceOf[FeatureFlagRegistryLive])
              .mapError(e => s"the registry did not build: $e")
            api <- reg.replayView.map(_._1)
            _   <- ZIO.succeed { registry.set(reg); apiRef.set(api) }
            // The provider map names each domain's first stack before any client exists.
            _ <- ZIO.foreachDiscard(lives)(l =>
              reg.setProvider(l.domain, l.first.provider).mapError(e => s"${l.domain}: setProvider before a client: $e")
            )

            getClient = (l: Live) =>
              ZIO.suspendSucceed {
                if (l.getting.incrementAndGet() > 1) Stats.hit("reg.getClient.concurrent")
                reg.getClient(l.domain).either.ensuring(ZIO.succeed(l.getting.decrementAndGet()))
              }

            // A getClient until one succeeds, bounded: a failure is a failed build, which a later call retries.
            obtain = (l: Live) => {
              val deadline = java.lang.System.nanoTime() + retryFor.toNanos
              def go(attempt: Int, failed: Boolean): IO[String, FeatureFlags] =
                getClient(l).flatMap {
                  case Right(c) =>
                    ZIO
                      .succeed {
                        if (failed) Stats.hit(if (l.replacement.isDefined) "reg.retry.replaced" else "reg.retry.same")
                        l.seen.compareAndSet(null, c)
                        l.seen.get()
                      }
                      .flatMap { first =>
                        ZIO.unless(first eq c)(
                          report(s"OneInstancePerDomain: two getClient(${l.domain}) calls returned different instances")
                        ) *> l.client.succeed(c).as(c)
                      }
                  case Left(_: FeatureFlagError.ProviderInitializationFailed) if java.lang.System.nanoTime() < deadline =>
                    ZIO.when(l.first.leaves.values.exists(f => f.initGate.entered && !f.initGate.exited))(
                      Stats.generated("reg.build.stalled")
                    ) *> Stats.generated("reg.build.failed") *> l.failedOnce.succeed(()) *>
                      ZIO.sleep(1.millis) *> go(attempt + 1, failed = true)
                  case Left(e) => ZIO.fail(s"getClient(${l.domain}) failed after $attempt attempts: $e")
                }
              go(1, failed = false)
            }

            worker = (l: Live, cmds: List[Cmd.Eval]) =>
              ZIO.foreachDiscard(cmds) { case Cmd.Eval(key, kind, inv, scoped) =>
                for {
                  ff <- obtain(l).tapError(report).option
                  _  <- ZIO.foreachDiscard(ff) { ff =>
                    val eff = Harness.read(ff, key, kind, inv.toEvaluationContext)
                    val fp  = Ctx.rendered(Runner.mergedFor(Ctx.empty, Ctx.empty, None, scoped, inv))
                    for {
                      t0   <- ZIO.succeed(perturber.tick())
                      exit <- scoped.fold(eff)(c => ff.withContext(c.toEvaluationContext)(eff)).timeout(evalBound).exit
                      t1   <- ZIO.succeed(perturber.tick())
                      _    <- exit match {
                        case Exit.Success(None) =>
                          report(s"${l.domain} eval $key:$kind did not complete within $evalBound")
                        case Exit.Failure(c) =>
                          report(s"${l.domain} eval $key:$kind failed: ${c.prettyPrint.take(400)}")
                        case Exit.Success(Some(o)) =>
                          ZIO.when(o.isSuccess)(history.update(_ :+ (l.k -> Op.Eval(key, kind, fp, t0, t1, o)))) *>
                            (l.restored.get <*> l.retired.get).flatMap { case (back, gone) =>
                              val late = o.variant
                                .filter(_.endsWith(SimLeaf.LateSuffix))
                                .filter(v => gone.contains(v.takeWhile(_ != ':')))
                              val kept = late.exists(v => o.reason == "CACHED" && back.contains(v.takeWhile(_ != ':')))
                              ZIO.when(o.isSuccess && late.nonEmpty && !kept)(
                                report(s"${l.domain} eval $key:$kind got $o, answered after a swap away from its leaf")
                              )
                            }
                      }
                    } yield ()
                  }
                  _ <- checks(s"after an evaluation on ${l.domain}")
                } yield ()
              }

            // Once a stalled build's replacement serves, no slot holds the stalled leaf, and its initialize() still
            // runs: a build of it on another domain is refused before it binds, and starts no second call.
            borrow = (l: Live) =>
              ZIO.when(stalls(l.first.spec) && l.first.leaves.values.exists(f => f.initGate.entered && !f.initGate.exited)) {
                val other = s"${l.domain}x"
                for {
                  _ <- reg.setProvider(other, l.first.provider).mapError(e => s"$other: setProvider: $e")
                  // Refused at once. A build let through waits on the stalled call, which is let go after borrowBound so
                  // that such a build ends and is reported.
                  letGo <- (ZIO.sleep(borrowBound) *> releaseStall(l)).fork
                  r     <- reg.getClient(other).either
                  _     <- letGo.interrupt
                  _     <- r match {
                    case Left(FeatureFlagError.ProviderInitializationFailed(_: ProviderRegistration.StillInitializing)) =>
                      Stats.generated("reg.build.refused")
                    case ended =>
                      report(s"a build of ${l.domain}'s stalled stack, held by no slot, ended $ended, not refused")
                  }
                  _ <- checks(s"after a build of ${l.domain}'s stalled stack")
                  _ <- releaseStall(l)
                } yield ()
              }

            // Names the replacement once a build of the first stack failed, then swaps the client once there is one.
            swapper = (l: Live, swaps: List[Spec]) =>
              ZIO.foreachDiscard(l.replacement) { b =>
                l.failedOnce.await.timeout(10.seconds).flatMap {
                  case None    => report(s"${l.domain}: no build of its failing stack failed within 10s")
                  case Some(_) =>
                    // A build of the failing stack may be in flight: the call then waits for it and reports its failure.
                    reg.setProvider(l.domain, b.provider).sandbox.either.flatMap {
                      case Right(_) => ZIO.unit
                      case Left(c)
                          if c.defects.isEmpty &&
                            c.failureOption.exists(_.isInstanceOf[FeatureFlagError.ProviderInitializationFailed]) =>
                        ZIO.unit
                      case Left(c) =>
                        report(s"${l.domain}: naming its replacement failed: ${c.prettyPrint.take(400)}")
                    } *> bound.update(b :: _)
                }
              } *> l.client.await.timeout(20.seconds).flatMap {
                case None    => report(s"${l.domain}: no getClient succeeded within 20s")
                case Some(_) =>
                  borrow(l) *> ZIO.foreachDiscard(swaps) { s =>
                    for {
                      ns <- number(s)
                      nb <- Harness.build(ns, timed = false)
                      _  <- ZIO.succeed(stacks.add(nb))
                      fails = Spec.initOutcome(ns) == InitOutcome.Fails
                      _  <- perturber.note(s"${l.domain} swap to ${Spec.show(ns)} starts")
                      t0 <- ZIO.succeed(perturber.tick())
                      r  <- reg.setProvider(l.domain, nb.provider).sandbox.either
                      t1 <- ZIO.succeed(perturber.tick())
                      _  <- r match {
                        case Left(c)
                            if fails && c.defects.isEmpty &&
                              c.failureOption.exists(_.isInstanceOf[FeatureFlagError.ProviderInitializationFailed]) =>
                          Stats.generated("reg.swap.rolledBack") *> bound.update(nb :: _)
                        case Left(c) =>
                          report(s"${l.domain} setProvider to ${Spec.show(ns)} failed: ${c.prettyPrint.take(400)}")
                        case Right(_) if fails =>
                          report(s"${l.domain} setProvider to ${Spec.show(ns)}, whose initialize() fails, succeeded")
                        case Right(_) =>
                          Stats.generated("reg.swap.ok") *> bound.update(nb :: _) *>
                            history.update(_ :+ (l.k -> Op.Swap(ns, t0, t1))) *> l.current.set(nb)
                      }
                      _ <- checks(s"after ${l.domain}'s swap to ${Spec.show(ns)}")
                      _ <- ZIO.yieldNow
                    } yield ()
                  }
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

            _ <- ZIO
              .collectAllParDiscard(
                mutator :: lives.zip(plan.domains).flatMap { case (l, d) =>
                  swapper(l, d.swaps) :: d.workers.map(worker(l, _))
                }
              )
              .ensuring(releaseStalls)
            _       <- checks("at the end, before the registry closes")
            clients <- ZIO.foreach(lives)(l => l.client.poll.flatMap(ZIO.foreach(_)(_.map(l -> _))))
            // The registry's close tears its API down with the scope: from here it is not open.
            _ <- ZIO.succeed(open.set(false))
          } yield clients.flatten
        }
      }
      // ClosedApiServesNone, its value side: once the registry closed, no client serves.
      _ <- ZIO.foreachDiscard(clients) { case (l, ff) =>
        Harness.read(ff, "k0", Kind.KStr, EvaluationContext.empty).flatMap { o =>
          Stats.generated("reg.closedServesNone") *>
            ZIO.when(o.isSuccess)(report(s"${l.domain}'s client served $o once the registry closed"))
        }
      }
      _ <- ZIO.foreachDiscard(evicted.asScala.toList.sortBy(_._1)) { case (d, where) =>
        report(s"OnlyFailedBuildsEvicted: $d's promise was gone at $where, after a getClient on it succeeded")
      }
      ops <- history.get
      _   <- ZIO.foreachDiscard(lives) { l =>
        val mine    = ops.collect { case (k, op) if k == l.k || k == -1 => op }
        val checked = History.check(l.initial.spec, mine)
        ZIO.succeed(Stats.add("history.checked", checked.checked)) *>
          ZIO.foreachDiscard(checked.violations.take(3))(v => report(s"${l.domain} ${v.render}"))
      }
      everBound <- bound.get
      _         <- ZIO.foreachDiscard(everBound.distinct)(b =>
        Runner.awaitShutdown(b).flatMap(ZIO.foreachDiscard(_)(p => report(s"SharedShutDownOnce at the end: $p")))
      )
      _ <- ZIO.foreachDiscard(unused.leaves.values.toList)(l =>
        ZIO.when(l.inits.get() != 0 || l.shutdowns.get() != 0)(
          report(s"the default provider, never bound: inits=${l.inits.get()} shutdowns=${l.shutdowns.get()}")
        )
      )
      _     <- ZIO.attemptBlocking(unused.provider.shutdown()).ignore
      leaks <- LeakCheck.awaitNoLeaks(baseline, 5.seconds).withClock(Clock.ClockLive)
      _     <- ZIO.foreachDiscard(leaks)(l => report(s"leak: $l"))
      _     <- ZIO.succeed(Stats.hit("reg"))
      ps    <- problems.get
      _     <- ZIO.when(ps.nonEmpty)(
        ZIO.fail(
          (s"domains ${lives.map(l => s"${l.domain} ${Spec.show(l.first.spec)}" + l.replacement.fold("")(r => s" then ${Spec.show(r.spec)}")).mkString(", ")}" +:
            ps.take(10)).mkString("\n") + (if (ps.size > 10) s"\n${ps.size - 10} more problems" else "") +
            "\nharness events:\n" + perturber.events + "\n" + perturber.render(150)
        )
      )
    } yield ()

  def spec = suite("RegistrySimulation")(
    test("replays every regression corpus entry") {
      Corpus
        .replay[Any, Plan]("registry")(run)
        .map(failures => assertTrue(failures.isEmpty) ?? failures.mkString("\n"))
    },
    test("one registry's clients, builds and retries keep their answers and the registry invariants under concurrency") {
      check(genPlan) { plan =>
        run(plan).either.flatMap { r =>
          val json = PlanCodec.write(plan)
          ZIO.when(r.isLeft)(Corpus.record(name, json)).as(assertTrue(r == Right(())) ?? s"corpus entry ($name): $json")
        }
      }
    } @@ PropertyBudget(40) @@ TestAspect.after(Console.printLine(Stats.render).orDie),
    // Last, after the generated cases: deterministic under the PR seed; retune the weights if an upgrade moves it.
    test("the generated cases reach failed builds, both retries, concurrent getClient calls and swaps") {
      val counts = reachKeys.map(k => k -> Stats.get(k))
      Console.printLine(s"registry reach: ${counts.map { case (k, n) => s"$k=$n" }.mkString(" ")}").orDie.as {
        assertTrue(counts.forall(_._2 > 0)) ?? counts.filter(_._2 == 0).map(_._1).mkString("never reached: ", ", ", "")
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ PropertyBudget.timeout(10.minutes)
}
