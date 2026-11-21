package zio.openfeature

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider => OFFeatureProvider,
  Metadata,
  NoOpProvider,
  OpenFeatureAPI,
  Value
}
import dev.openfeature.sdk.exceptions.GeneralError
import zio._
import zio.openfeature.internal.{FeatureFlagsFactories, LifecycleProbe, ManagedProvider, ProviderEvaluations}
import zio.openfeature.testsupport.ModelBehaviours
import zio.openfeature.testsupport.ModelBehaviours.{Behaviour, Json, ModelState}
import zio.test._

import java.nio.file.{Path, Paths}
import java.util.concurrent.{CompletableFuture, ConcurrentLinkedQueue, CopyOnWriteArrayList, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.locks.LockSupport
import scala.jdk.CollectionConverters._
import scala.util.chaining._

/** Replays behaviours TLC generates from `formal/registry/Generate.tla` through the real registry, instances and SDK,
  * and compares the code's state with the model's at every settled state (no swap, decision or semaphore in flight).
  *
  * Every environment step is a release the replay makes: a scripted provider's `initialize()` returns or throws when
  * the replay says, and a user binds a provider directly. Every split the model makes inside one code path is a hold at
  * an existing probe point: `RegistryInsert` keeps the semaphore held as the model's `lock` does, and
  * `FailedRegistration` separates a failure's mark from its decision. Shutdowns land on SDK threads, so a comparison is
  * retried until it holds, and fails if it never does within [[WaitMillis]]. `formal/registry/replay-map.md` maps each
  * action to the step that drives it.
  */
object RegistryReplaySpec extends ZIOSpecDefault {

  /** How long a step waits for the code to reach the point it drives to; a correct run never waits this long. */
  val WaitMillis = 5000L

  /** Long enough that no deadline ever fires: every outcome is the replay's release. */
  val InitTimeout: Duration = 30.seconds

  private def within(cond: => Boolean): Boolean = {
    val deadline = java.lang.System.nanoTime() + WaitMillis * 1000000L
    while (!cond && java.lang.System.nanoTime() < deadline) LockSupport.parkNanos(200000L)
    cond
  }

  /** A provider whose every `initialize()` waits for the replay to settle it, ok or failing; `shutdown()` is counted.
    */
  final class Scripted(val name: String) extends OFFeatureProvider {
    val shutdowns     = new AtomicInteger(0)
    val inits         = new AtomicInteger(0)
    private val calls = new ConcurrentLinkedQueue[CompletableFuture[java.lang.Boolean]]()

    /** A call has started that the replay has not settled. */
    def initializing: Boolean = calls.asScala.exists(!_.isDone)

    /** Settles the call running, waiting for one to start; false when none did within [[WaitMillis]]. */
    def settle(ok: Boolean): Boolean =
      within(initializing) && calls.asScala.find(!_.isDone).exists(_.complete(java.lang.Boolean.valueOf(ok)))

    /** Fails every call still running, so a behaviour that stopped early leaves no thread blocked. */
    def abandon(): Unit = calls.asScala.foreach(_.complete(java.lang.Boolean.FALSE): Unit)

    override def getMetadata: Metadata                      = new Metadata { override def getName: String = name }
    override def initialize(ctx: OFEvaluationContext): Unit = {
      inits.incrementAndGet()
      val call = new CompletableFuture[java.lang.Boolean]()
      calls.add(call)
      val ok =
        try call.get(InitTimeout.toMillis, TimeUnit.MILLISECONDS).booleanValue()
        finally calls.remove(call): Unit
      if (!ok) throw new GeneralError(s"$name: initialize fails")
    }
    override def shutdown(): Unit = shutdowns.incrementAndGet(): Unit

    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
  }

  /** A hold at one probe point: the code waits there until the replay lets it go. */
  final class Hold(val matches: LifecycleProbe.Point => Boolean) {
    val reached          = new CountDownLatch(1)
    private val released = new CountDownLatch(1)
    def go(): Unit       = released.countDown()
    def await(): Unit    = released.await(InitTimeout.toMillis, TimeUnit.MILLISECONDS): Unit
  }

  /** Records every point it sees, and holds at the one armed. */
  final class ReplayProbe extends LifecycleProbe {
    val points                                                       = new CopyOnWriteArrayList[LifecycleProbe.Point]()
    private val armed                                                = new AtomicReference[Option[Hold]](None)
    def mark: Int                                                    = points.size
    def seen(from: Int)(m: LifecycleProbe.Point => Boolean): Boolean = points.asScala.drop(from).exists(m)
    def arm(m: LifecycleProbe.Point => Boolean): Hold                = { val h = new Hold(m); armed.set(Some(h)); h }

    def at(point: LifecycleProbe.Point): UIO[Unit] =
      ZIO.succeed(points.add(point)) *> {
        // Compared by identity, so the hold is taken once: the reference read here is the one swapped out.
        val current = armed.get()
        current.filter(_.matches(point)) match {
          case Some(h) if armed.compareAndSet(current, None) =>
            ZIO.succeed(h.reached.countDown()) *> ZIO.attemptBlocking(h.await()).orDie
          case _ => ZIO.unit
        }
      }
  }

  private def domainOf(d: String): Option[String] = if (d == "default") None else Some(d)

  /** The model's name for what a slot holds: the scripted provider behind the library's wrapper, or `None`. */
  private def nameOf(p: OFFeatureProvider): String = p match {
    case m: ManagedProvider => nameOf(m.delegate)
    case s: Scripted        => s.name
    case null               => "None"
    case _: NoOpProvider    => "None"
    case other              => s"?${other.getClass.getSimpleName}"
  }

  type Outcome = Either[String, FeatureFlagsLive]

  /** A forked step of the code, and whether it has ended. */
  final case class Running[A](fiber: Fiber[Nothing, A], ended: java.util.concurrent.atomic.AtomicBoolean) {
    def done: Boolean = ended.get()
  }

  /** What a behaviour's steps forked, armed or opened, so cleanup reaches what a step that failed left behind. */
  final class Leftovers {
    val holds  = new ConcurrentLinkedQueue[Hold]()
    val fibers = new ConcurrentLinkedQueue[Fiber[Nothing, Any]]()
    val scopes = new ConcurrentLinkedQueue[Scope.Closeable]()
  }

  private def track[A](env: Env)(effect: UIO[A]): UIO[Running[A]] = {
    val ended = new java.util.concurrent.atomic.AtomicBoolean(false)
    effect.ensuring(ZIO.succeed(ended.set(true))).fork.map { f => env.leftovers.fibers.add(f); Running(f, ended) }
  }

  private def arm(env: Env, probe: ReplayProbe)(m: LifecycleProbe.Point => Boolean): Hold = {
    val h = probe.arm(m); env.leftovers.holds.add(h); h
  }

  /** One model instance, as the replay drives it. */
  final case class Inst(
    domain: Option[String],
    probe: ReplayProbe,
    scope: Option[Scope.Closeable] = None,
    build: Option[Running[Outcome]] = None,
    handle: Option[FeatureFlagsLive] = None,
    swap: Option[Running[Either[String, Unit]]] = None,
    hold: Option[Hold] = None,
    // The provider a build or swap of this instance bound, its initialize() count then, and how many calls the model
    // says that binding starts: checked when the outcome is taken.
    started: Option[(Scripted, Int, Int)] = None
  )

  /** The environment one behaviour runs in: a registry over its private API, or one shared API. */
  final case class Env(
    topology: String,
    providers: Map[String, Scripted],
    api: OpenFeatureAPI,
    registry: Option[FeatureFlagRegistryLive],
    registryScope: Option[Scope.Closeable],
    domainProbes: Map[Option[String], ReplayProbe],
    leftovers: Leftovers = new Leftovers,
    // The provider the registry's map holds for each domain: a domain it holds none for builds from the default.
    registryMap: AtomicReference[Map[String, String]] = new AtomicReference(Map.empty)
  ) {
    def registryTopology: Boolean = topology == "registry"
  }

  /** What the code holds, in the model's terms. */
  final case class CodeView(
    bound: Map[String, String],
    total: Map[String, Long],
    inFlight: Set[String],
    owner: Set[String],
    failedOn: Map[String, String],
    closed: Map[String, Boolean],
    promised: Set[String]
  )

  /** A distortion of the code's view, which a planted bug uses to show a comparison can fail. */
  type Plant = CodeView => CodeView

  final case class Mismatch(behaviour: String, step: Int, action: String, field: String, what: String) {
    override def toString = s"$behaviour step $step ($action): $field: $what"
  }

  private val domains = List("default", "d1", "d2")

  private def view(env: Env, insts: Map[String, Inst], model: ModelState): UIO[CodeView] =
    for {
      promised <- ZIO.foreach(env.registry)(_.replayView.map(_._2)).map(_.getOrElse(Set.empty))
    } yield {
      val modelDomains = model.fun("bound").keySet
      val bound        = domains
        .filter(modelDomains.contains)
        .map { d =>
          val p = domainOf(d).fold(env.api.getProvider())(env.api.getProvider)
          // The SDK answers a domain nothing is bound to with the default slot's provider, and keeps no public record
          // of which domains are bound: one that resolves to the default's provider reads as unbound when the model
          // has it so.
          val unbound = d != "default" && (p eq env.api.getProvider()) && model.asStr(model.fun("bound")(d)) == "None"
          d -> (if (unbound) "None" else nameOf(p))
        }
        .toMap
      val handles = insts.flatMap { case (i, in) => in.handle.map(i -> _) }
      CodeView(
        bound = bound,
        total = env.providers.map { case (n, p) => n -> p.shutdowns.get().toLong },
        inFlight = env.providers.collect { case (n, p) if p.initializing => n }.toSet,
        owner = handles.collect { case (i, h) if h.replayView._1 => i }.toSet,
        failedOn = handles.map { case (i, h) => i -> h.replayView._2.fold("None")(nameOf) },
        closed = handles.map { case (i, h) => i -> (h.lifecycleSnapshot.closed || h.lifecycleSnapshot.shutDown) },
        promised = promised.map(_.getOrElse("default"))
      )
    }

  private def strs(j: Json): Set[String] = j match {
    case Json.Arr(items) => items.collect { case Json.Str(s) => s }.toSet
    case Json.Obj(fs)    => fs.keySet
    case _               => Set.empty
  }

  /** The fields the model and the code disagree on, at a settled state. */
  def compare(model: ModelState, code: CodeView, registry: Boolean): List[(String, String)] = {
    val phase = model.fun("phase").map { case (i, j) => i -> model.asStr(j) }
    val bound = model.fun("bound").map { case (d, j) => d -> model.asStr(j) }
    val total = model.fun("total").map { case (p, j) => p -> model.asNum(j) }
    val open  = model.bool("apiOpen")
    def diff[A](field: String, m: A, c: A): List[(String, String)] =
      if (m == c) Nil else List(field -> s"model=$m code=$c")
    val failedOn = model.fun("failedOn").collect {
      case (i, j) if phase(i) == "open" && code.failedOn.contains(i) => i -> model.asStr(j)
    }
    val closedNow = code.closed.collect { case (i, c) if Set("open", "closed").contains(phase(i)) => i -> c }
    diff("bound", bound, code.bound) ++
      diff("total", total, code.total) ++
      diff("inFlight", strs(model.field("inFlight")), code.inFlight) ++
      // The owner is known once its build has handed the instance out.
      (if (!registry && open && (model.str("owner") == "None" || code.closed.contains(model.str("owner"))))
         diff("owner", Set(model.str("owner")).filter(_ != "None"), code.owner)
       else Nil) ++
      diff("failedOn", failedOn, code.failedOn.filter { case (i, _) => failedOn.contains(i) }) ++
      diff("phase", closedNow.map { case (i, _) => i -> (phase(i) == "closed") }, closedNow) ++
      (if (registry && open) {
         val held = model
           .fun("domainOf")
           .collect {
             case (i, d) if model.asStr(d) != "None" && !Set("absent", "evicted", "checking").contains(phase(i)) =>
               model.asStr(d)
           }
           .toSet
         diff("promise", held, code.promised)
       } else Nil)
  }

  private def settled(m: ModelState): Boolean =
    strs(m.field("swapping")).isEmpty && strs(m.field("deciding")).isEmpty && m.str("lock") == "None" &&
      m.field("then") == Json.Str("None")

  private def bounded[A](what: String)(effect: UIO[A]): IO[String, A] =
    effect
      .timeout(WaitMillis.millis)
      .withClock(Clock.ClockLive)
      .someOrFail(s"$what did not happen within $WaitMillis ms")

  private def check(ok: => Boolean, what: String): IO[String, Unit] =
    ZIO.attemptBlocking(within(ok)).orDie.flatMap(b => ZIO.unless(b)(ZIO.fail(s"$what did not happen")).unit)

  private def makeEnv(topology: String): ZIO[Any, Throwable, Env] = {
    val providers = List("p1", "p2", "p3").map(n => n -> new Scripted(n)).toMap
    if (topology == "registry") {
      val probes = domains.map(domainOf).map(_ -> new ReplayProbe).toMap
      for {
        scope <- Scope.make
        reg   <- FeatureFlagRegistry
          .fromProvider(providers("p1"), FeatureFlagsConfig.default.withInitTimeout(InitTimeout), probes(_))
          .build
          .provideEnvironment(ZEnvironment(scope: Scope))
          .map(_.get[FeatureFlagRegistry].asInstanceOf[FeatureFlagRegistryLive])
        api <- reg.replayView.map(_._1)
      } yield Env(topology, providers, api, Some(reg), Some(scope), probes)
    } else ZIO.succeed(Env(topology, providers, OpenFeatureAPI.createIsolated(), None, None, Map.empty))
  }

  /** Lets every call and hold go, interrupts what is left and closes everything, so nothing leaks into the next run. */
  private def cleanUp(env: Env): UIO[Unit] =
    ZIO.succeed {
      env.leftovers.holds.asScala.foreach(_.go())
      env.providers.values.foreach(_.abandon())
    } *>
      ZIO.foreachDiscard(env.leftovers.fibers.asScala.toList)(_.interrupt) *>
      ZIO.foreachDiscard(env.leftovers.scopes.asScala.toList)(_.close(Exit.unit)) *>
      env.registryScope.fold(ZIO.attemptBlocking(env.api.shutdown()).ignore)(_.close(Exit.unit))

  /** Runs `fiber` to its end, letting any `initialize()` of `provider` it starts return. */
  private def drive[A](what: String, run: Running[A], provider: Scripted): IO[String, A] =
    ZIO.attemptBlocking {
      val deadline = java.lang.System.nanoTime() + WaitMillis * 1000000L
      while (!run.done && java.lang.System.nanoTime() < deadline) {
        if (provider.initializing) provider.settle(true): Unit
        LockSupport.parkNanos(200000L)
      }
    }.orDie *> bounded(what)(run.fiber.join)

  private def joined[A](what: String, run: Running[A]): IO[String, A] =
    bounded(what)(run.fiber.join)

  // One step of the behaviour: drive the code to the model's next state.
  private def step(
    env: Env,
    insts: Map[String, Inst],
    prev: ModelState,
    next: ModelState,
    lookahead: Option[ModelState]
  ): IO[String, Map[String, Inst]] = {
    val act                           = next.act
    def inst(i: String): Inst         = insts.getOrElse(i, sys.error(s"no instance $i"))
    def provider(p: String): Scripted = env.providers(p)
    def boundOf(i: String): String    = prev.asStr(prev.fun("bound")(prev.asStr(prev.fun("domainOf")(i))))
    def put(i: String, f: Inst => Inst): Map[String, Inst] = insts.updated(i, f(inst(i)))
    // The calls the binding of p this step takes starts, as the model has it: one when p's call is in flight after it
    // and was not before.
    def startsCall(p: Scripted): Int =
      if (strs(next.field("inFlight")).contains(p.name) && !strs(prev.field("inFlight")).contains(p.name)) 1 else 0
    def started(p: Scripted): Option[(Scripted, Int, Int)] = Some((p, p.inits.get(), startsCall(p)))
    // At the binding's outcome: the code started exactly the calls the model says it did.
    def calls(i: String): IO[String, Unit] =
      ZIO.foreachDiscard(inst(i).started) { case (p, before, expected) =>
        val got = p.inits.get() - before
        ZIO.unless(got == expected)(
          ZIO.fail(s"${act.name}($i): ${p.name}'s binding started $got initialize() calls, the model $expected")
        )
      }
    def getClient(d: Option[String]): UIO[Outcome] = {
      val r = env.registry.getOrElse(sys.error("getClient outside the registry topology"))
      d.fold(r.defaultClient)(r.getClient)
        .either
        .map(_.left.map(_.toString).map {
          case live: FeatureFlagsLive => live
          case other                  => sys.error(s"not a FeatureFlagsLive: $other")
        })
    }
    act.name match {
      case "Init" | "Done" => ZIO.succeed(insts)

      case "Lookup" =>
        val d     = act.str("d")
        val phase = prev.fun("phase").map { case (j, v) => j -> prev.asStr(v) }
        val owner = prev.fun("domainOf").collectFirst {
          case (j, v) if prev.asStr(v) == d && !Set("absent", "evicted").contains(phase(j)) => j
        }
        owner.filter(j => Set("open", "closed").contains(phase(j))) match {
          case Some(j) =>
            bounded("Lookup's getClient")(getClient(domainOf(d))).flatMap {
              case Right(h) if insts.get(j).flatMap(_.handle).exists(_ eq h) => ZIO.succeed(insts)
              case other => ZIO.fail(s"Lookup($d) got $other, not instance $j")
            }
          case None => ZIO.succeed(insts) // the build is still running: the lookup would wait on it
        }

      case "Check" =>
        val i = act.str("i"); val d = domainOf(act.str("d"))
        if (env.registryTopology) {
          val probe = env.domainProbes(d)
          val hold  = arm(env, probe)(_ == LifecycleProbe.Point.RegistryInsert(d))
          // Create, held next, names the provider the build binds: the registry's map gives it.
          val p = lookahead.filter(_.act.name == "Create").map(_.act.str("p"))
          for {
            // A domain the map holds nothing for builds from the default provider: leave the map alone then, so that
            // fallback is driven too.
            _ <- ZIO.foreachDiscard(d.zip(p)) { case (dn, pn) =>
              val fallback = pn == "p1" && env.registryMap.get().get(dn).forall(_ == "p1")
              ZIO.unless(fallback)(
                env.registry.get.setProvider(dn, provider(pn)).mapError(e => s"registry.setProvider: $e") *>
                  ZIO.succeed(env.registryMap.updateAndGet(_.updated(dn, pn)))
              )
            }
            f <- track(env)(getClient(d))
            _ <- check(hold.reached.getCount == 0, s"getClient($d) reaching RegistryInsert (saw ${probe.points})")
          } yield insts.updated(i, Inst(d, probe, build = Some(f), hold = Some(hold)))
        } else ZIO.succeed(insts.updated(i, Inst(d, new ReplayProbe)))

      case "Create" =>
        val i    = act.str("i"); val p = provider(act.str("p")); val own = act.bool("own"); val in = inst(i)
        val mark = in.probe.mark
        def bound(f: Option[Running[Outcome]]) =
          check(
            in.probe.seen(mark)(_.isInstanceOf[LifecycleProbe.Point.BuildBind]) || p.initializing ||
              f.exists(_.done),
            s"Create($i) binding ${p.name}"
          )
        if (env.registryTopology)
          ZIO
            .succeed(started(p))
            .flatMap(begun =>
              ZIO.succeed(in.hold.foreach(_.go())) *> bound(in.build).as(put(i, _.copy(hold = None, started = begun)))
            )
        else
          for {
            scope <- Scope.make.tap(sc => ZIO.succeed(env.leftovers.scopes.add(sc)))
            begun = started(p)
            f <- scope
              .extend[Any](
                FeatureFlagsFactories.build(
                  p,
                  domain = in.domain,
                  version = None,
                  initialHooks = Nil,
                  statusRef = None,
                  addShutdownFinalizer = own,
                  apiOverride = Some(env.api),
                  initTimeout = InitTimeout,
                  probe = in.probe
                )
              )
              .either
              .map(_.left.map(_.toString))
              .flatMap(r => ZIO.succeed(r))
              .pipe(track(env))
            _ <- bound(Some(f))
          } yield put(i, _.copy(scope = Some(scope), build = Some(f), started = begun))

      case "Complete" =>
        val i = act.str("i"); val p = provider(boundOf(i))
        for {
          r <- drive(s"Complete($i)", inst(i).build.get, p)
          h <- ZIO.fromEither(r).mapError(e => s"Complete($i): the build failed: $e")
          _ <- calls(i)
        } yield put(i, _.copy(build = None, handle = Some(h), started = None))

      case "BuildFail" =>
        val i = act.str("i"); val p = provider(boundOf(i)); val in = inst(i); val mark = in.probe.mark
        // Right after the release of the provider a retry replaced, which already let this call fail; otherwise its
        // call may not have started yet (an async build), so settling it waits for it.
        val settled = prev.act.name == "ReleaseReplaced"
        ZIO
          .unless(settled)(
            ZIO
              .attemptBlocking(p.settle(false))
              .orDie
              .flatMap(ok => ZIO.unless(ok)(ZIO.fail(s"BuildFail($i): no initialize() of ${p.name} to fail")))
          ) *>
          check(
            in.probe.seen(mark)(_ == LifecycleProbe.Point.ScopeClose) ||
              in.build.exists(_.done),
            s"BuildFail($i) closing the failed client"
          ) *> calls(i).as(put(i, _.copy(started = None)))

      case "Evict" =>
        val i = act.str("i")
        joined(s"Evict($i)", inst(i).build.get).flatMap {
          case Left(_)  => ZIO.succeed(put(i, _.copy(build = None)))
          case Right(h) => ZIO.fail(s"Evict($i): the build succeeded")
        }

      case "ReleaseReplaced" =>
        val i          = act.str("i")
        val phase      = prev.fun("phase").map { case (j, v) => j -> prev.asStr(v) }
        val builtOn    = prev.fun("builtOn").map { case (j, v) => j -> prev.asStr(v) }
        val replacedBy = prev.asStr(prev.fun("replaced")(builtOn(i)))
        val retry      = prev.fun("domainOf").collectFirst {
          case (j, d)
              if prev.asStr(d) == prev.asStr(prev.fun("domainOf")(i)) && phase(j) == "building" &&
                builtOn(j) == replacedBy =>
            j
        }
        val probe = inst(i).probe
        val mark  = probe.mark
        retry match {
          case Some(j) =>
            ZIO
              .attemptBlocking(provider(builtOn(j)).settle(false))
              .orDie
              .flatMap(ok => ZIO.unless(ok)(ZIO.fail(s"ReleaseReplaced($i): the retry's initialize() never ran"))) *>
              check(probe.seen(mark)(_.isInstanceOf[LifecycleProbe.Point.FailDecided]), s"ReleaseReplaced($i)")
                .as(insts)
          case None => ZIO.fail(s"ReleaseReplaced($i): no retry is building")
        }

      case "SwapStart" =>
        val i = act.str("i"); val p = provider(act.str("p")); val in = inst(i); val mark = in.probe.mark
        val h = in.handle.getOrElse(sys.error(s"SwapStart($i): no instance"))
        val swap: IO[String, Unit] =
          (env.registry, in.domain) match {
            case (Some(r), Some(d)) =>
              r.setProvider(d, p).mapError(_.toString) <* ZIO.succeed(
                env.registryMap.updateAndGet(_.updated(d, p.name))
              )
            case _ => h.setProvider(p).mapError(_.toString)
          }
        val begun = started(p)
        for {
          f <- track(env)(swap.either)
          _ <- check(
            in.probe.seen(mark)(_ == LifecycleProbe.Point.SdkBind) || p.initializing || f.done,
            s"SwapStart($i) binding ${p.name}"
          )
        } yield put(i, _.copy(swap = Some(f), started = begun))

      case "SwapDone" =>
        val i = act.str("i")
        drive(s"SwapDone($i)", inst(i).swap.get, provider(boundOf(i))).flatMap {
          case Right(_) => calls(i).as(put(i, _.copy(swap = None, started = None)))
          case Left(e)  => ZIO.fail(s"SwapDone($i): the swap failed: $e")
        }

      case "SwapOldShut" =>
        val i   = act.str("i")
        val old = prev.asStr(prev.fun("prior")(i))
        val n   = next.asNum(next.fun("total")(old))
        check(provider(old).shutdowns.get() >= n, s"SwapOldShut($i) shutting $old down").as(insts)

      case "SwapFailMark" | "FailMark" =>
        val i    = act.str("i"); val in = inst(i)
        val hold = arm(env, in.probe)(_.isInstanceOf[LifecycleProbe.Point.FailedRegistration])
        val p    = provider(boundOf(i))
        ZIO
          .attemptBlocking(p.settle(false))
          .orDie
          .flatMap(ok => ZIO.unless(ok)(ZIO.fail(s"${act.name}($i): no initialize() of ${p.name} to fail"))) *>
          check(hold.reached.getCount == 0, s"${act.name}($i) reaching FailedRegistration")
            .catchAll { e =>
              val r = in.swap.orElse(in.build).map(r => if (r.done) "ended" else "running")
              in.swap
                .filter(_.done)
                .fold(ZIO.none)(r => r.fiber.join.asSome)
                .flatMap(out =>
                  ZIO.fail(
                    s"$e (${p.name}: ${p.inits.get()} inits, initializing=${p.initializing}; step $r $out; saw ${in.probe.points})"
                  )
                )
            }
            .zipRight(calls(i))
            .as(put(i, _.copy(hold = Some(hold), started = None)))

      case "SwapFail" =>
        // The rollback registers the previous provider again, which the SDK initializes again: the model takes that
        // call's success, so the replay lets it return.
        val i   = act.str("i")
        val old = provider(prev.asStr(prev.fun("prior")(i)))
        ZIO.succeed(inst(i).hold.foreach(_.go())) *>
          drive(s"SwapFail($i)", inst(i).swap.get, old).flatMap {
            case Left(_)  => ZIO.succeed(put(i, _.copy(swap = None, hold = None)))
            case Right(_) => ZIO.fail(s"SwapFail($i): the swap succeeded")
          }

      case "FailDecide" =>
        val i = act.str("i"); val in = inst(i)
        ZIO.succeed(in.hold.foreach(_.go())) *> {
          if (prev.asStr(prev.fun("phase")(i)) == "building")
            joined(s"FailDecide($i)", in.build.get).flatMap {
              case Left(_)  => ZIO.succeed(put(i, _.copy(build = None, hold = None)))
              case Right(_) => ZIO.fail(s"FailDecide($i): the build succeeded")
            }
          else ZIO.succeed(put(i, _.copy(hold = None)))
        }

      case "UserBind" =>
        val d = domainOf(act.str("d")); val p = provider(act.str("p"))
        for {
          f <- track(env)(
            ZIO.attemptBlocking(d.fold(env.api.setProviderAndWait(p))(env.api.setProviderAndWait(_, p))).either
          )
          r <- drive(s"UserBind(${act.str("d")})", f, p)
          _ <- ZIO.fromEither(r).mapError(e => s"UserBind: $e")
        } yield insts

      case "CloseInstance" =>
        val i = act.str("i"); val in = inst(i)
        bounded(s"CloseInstance($i)")(
          in.scope.fold(in.handle.fold(ZIO.unit)(_.shutdown))(_.close(Exit.unit))
        ).as(insts)

      case "OwnerClose" =>
        val o = prev.str("owner")
        bounded("OwnerClose")(inst(o).scope.fold(ZIO.unit)(_.close(Exit.unit))).as(insts)

      case "RegistryClose" =>
        bounded("RegistryClose")(env.registryScope.fold(ZIO.unit)(_.close(Exit.unit))).as(insts)

      case other => ZIO.fail(s"no step for action $other")
    }
  }

  /** Replays one behaviour, returning its mismatches (a step that cannot be driven is one, named `step`). */
  def replay(b: Behaviour, topology: String, plant: Plant = identity): UIO[List[Mismatch]] =
    ZIO.scoped {
      for {
        env   <- ZIO.acquireRelease(makeEnv(topology).orDie)(_ => ZIO.unit)
        state <- Ref.make(Map.empty[String, Inst])
        _     <- ZIO.addFinalizer(cleanUp(env))
        found <- ZIO
          .foldLeft(b.states.zip(b.states.drop(1)).zipWithIndex)(List.empty[Mismatch]) {
            case (acc, ((prev, next), n)) if acc.isEmpty =>
              val lookahead = b.states.lift(n + 2)
              (for {
                insts <- state.get
                after <- step(env, insts, prev, next, lookahead)
                _     <- state.set(after)
                diffs <- ZIO.when(settled(next)) {
                  def diffNow = view(env, after, next).map(v => compare(next, plant(v), env.registryTopology))
                  // Shutdowns land on SDK threads: retry until the code agrees, or report what still differs.
                  diffNow
                    .repeat(Schedule.recurUntil[List[(String, String)]](_.isEmpty) && Schedule.spaced(20.millis))
                    .map(_._1)
                    .timeout(WaitMillis.millis)
                    .flatMap(_.fold(diffNow)(ZIO.succeed(_)))
                    .withClock(Clock.ClockLive)
                }
              } yield diffs.getOrElse(Nil).map { case (f, w) => Mismatch(b.name, n + 1, next.act.name, f, w) })
                .catchAll(e => ZIO.succeed(List(Mismatch(b.name, n + 1, next.act.name, "step", e))))
                .catchAllDefect(t => ZIO.succeed(List(Mismatch(b.name, n + 1, next.act.name, "step", t.toString))))
            case (acc, _) => ZIO.succeed(acc)
          }
        // After the last state, which its own comparison already found settled: a late shutdown on an SDK thread
        // shows in one more comparison a moment later.
        last = b.states.last
        end <- ZIO.when(found.isEmpty && settled(last)) {
          (ZIO.sleep(50.millis) *> state.get.flatMap(view(env, _, last)))
            .map(v => compare(last, plant(v), env.registryTopology))
            .withClock(Clock.ClockLive)
        }
      } yield found ++ end.getOrElse(Nil).map { case (f, w) => Mismatch(b.name, b.states.size - 1, "end", f, w) }
    }

  // Behaviours: comma-separated files or directories; each one's topology is the nearest path segment naming it.
  private val location = ModelBehaviours.location("zof.registry.behaviours", "ZOF_REGISTRY_BEHAVIOURS")
  private val required = sys.env.get("ZOF_REGISTRY_BEHAVIOURS_REQUIRED").exists(_.nonEmpty)

  // At most this many behaviours from each location (in file-name order), so a nightly run's 20000 fit its budget.
  private val perLocation = sys.env.get("ZOF_REGISTRY_BEHAVIOURS_MAX").flatMap(_.toIntOption).getOrElse(Int.MaxValue)

  def topologyOf(path: Path): Either[String, String] =
    path.iterator.asScala
      .map(_.toString)
      .toList
      .reverse
      .collectFirst { case t @ ("registry" | "shared") =>
        t
      }
      .toRight(s"$path: no path segment names its topology (registry or shared)")

  def load(path: Path): Either[String, Behaviour] =
    scala.util
      .Try(java.nio.file.Files.readAllLines(path).asScala.toList)
      .toEither
      .left
      .map(_.toString)
      .flatMap(ModelBehaviours.parseBehaviour(path.toString, _))

  private def resource(name: String): Path = Paths.get(getClass.getResource(s"/registry-replay/$name").toURI)

  private def fixture(topology: String): (Behaviour, String) =
    load(resource(s"$topology/behaviour.ndjson")).fold(e => sys.error(e), _ -> topology)

  private def byField(ms: List[Mismatch]): String =
    ms.groupBy(_.field).map { case (k, v) => s"$k=${v.size}" }.toList.sorted.mkString("[", " ", "]")

  def spec = suite("RegistryReplaySpec")(
    suite("generated behaviours")(
      test("replay through the registry, its instances and the SDK with no mismatch") {
        for {
          files <- ZIO
            .attempt(
              location.toList
                .flatMap(_.split(',').toList.map(s => Paths.get(s.trim)))
                .flatMap(path => ModelBehaviours.files(path).take(perLocation))
            )
            .orDie
          runs <- ZIO.foreach(files) { f =>
            (for {
              t <- ZIO.fromEither(topologyOf(f))
              b <- ZIO.fromEither(load(f))
            } yield (b, t)).orDieWith(new IllegalStateException(_)).flatMap { case (b, t) => replay(b, t) }
          }
          found = runs.flatten
          // Every mismatch, one per line, for a run that wants them all: the failure message shows ten.
          _ <- ZIO.foreachDiscard(sys.env.get("ZOF_REGISTRY_MISMATCHES").filter(_.nonEmpty))(out =>
            ZIO.attemptBlocking(java.nio.file.Files.write(Paths.get(out), found.map(_.toString).asJava)).orDie
          )
          _ <- ZIO.logWarning(s"registry replay: ${files.size} behaviours, ${found.size} mismatches ${byField(found)}")
        } yield assertTrue(files.nonEmpty, found.isEmpty) ?? found.take(10).mkString("\n")
      }
    ) @@ (if (location.isEmpty && !required) TestAspect.ignore else TestAspect.identity),
    suite("the replay's own checks")(
      test("a behaviour TLC wrote reads as states") {
        val (b, _) = fixture("registry")
        assertTrue(b.states.head.act.name == "Init", b.states.size > 2, b.states.exists(_.act.name == "Create"))
      },
      test("a behaviour of each topology replays clean") {
        for {
          r <- replay(fixture("registry")._1, "registry")
          s <- replay(fixture("shared")._1, "shared")
        } yield assertTrue(r.isEmpty, s.isEmpty) ?? (r ++ s).mkString("\n")
      },
      test("an action the replay has no step for fails the behaviour") {
        val (b, t) = fixture("shared")
        val bogus  = b.copy(states = b.states.take(1) :+ b.states(1).copy(act = ModelBehaviours.Act("Teleport")))
        replay(bogus, t).map(ms => assertTrue(ms.exists(m => m.field == "step" && m.what.contains("Teleport"))))
      },
      test("each comparison can fail: a planted bug per field fails a behaviour") {
        val plants: List[(String, String, Plant)] = List(
          (
            "registry",
            "bound",
            v => v.copy(bound = v.bound.map { case (d, p) => d -> (if (p == "None") "p3" else "None") })
          ),
          ("registry", "total", v => v.copy(total = v.total.updated("p1", v.total("p1") + 1))),
          ("registry", "promise", v => v.copy(promised = Set.empty)),
          ("registry", "inFlight", v => v.copy(inFlight = v.inFlight + "p2")),
          ("shared", "owner", v => v.copy(owner = Set("i3"))),
          ("shared", "phase", v => v.copy(closed = v.closed.map { case (i, c) => i -> !c })),
          ("shared", "failedOn", v => v.copy(failedOn = v.failedOn.map { case (i, _) => i -> "p3" }))
        )
        ZIO
          .foreach(plants) { case (t, field, plant) =>
            replay(fixture(t)._1, t, plant).map(ms => field -> ms.exists(_.field == field))
          }
          .map(rs => assertTrue(rs.forall(_._2)) ?? rs.filterNot(_._2).map(_._1).mkString("never failed: ", ", ", ""))
      }
    )
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.minutes)
}
