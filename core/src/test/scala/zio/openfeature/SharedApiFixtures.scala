package zio.openfeature

import zio._
import zio.stream.SubscriptionRef
import zio.test.Live
import zio.openfeature.internal.{FeatureFlagsFactories, LifecycleProbe, ManagedProvider, ProviderEvaluations}
import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, FeatureProvider, Metadata, OpenFeatureAPI, Value}
import dev.openfeature.sdk.exceptions.GeneralError
import java.util.concurrent.{ConcurrentHashMap, CountDownLatch}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

/** Providers, builds and probe holds for specs that put several instances on one API and race them. */
private[openfeature] object SharedApiFixtures {

  // initialize() waits for `gate`, then throws `failure` if there is one; shutdown() is counted and opens the gate.
  class Gated(failure: Option[String] = None, open: Boolean = false) extends FeatureProvider {
    val shutdowns = new AtomicInteger(0)
    val gate      = new CountDownLatch(if (open) 0 else 1)

    override def getMetadata: Metadata                      = new Metadata { override def getName: String = "gated" }
    override def initialize(ctx: OFEvaluationContext): Unit = {
      gate.await()
      failure.foreach(msg => throw new GeneralError(msg))
    }
    override def shutdown(): Unit = { shutdowns.incrementAndGet(); gate.countDown() }

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

  class Counting(failure: Option[String] = None, open: Boolean = false) extends Gated(failure, open) {
    val inits                                               = new AtomicInteger(0)
    override def initialize(ctx: OFEvaluationContext): Unit = { inits.incrementAndGet(); super.initialize(ctx) }
  }

  // Fails on its second initialize(): the rollback that registers it again fails too.
  class FailsWhenReinitialized extends Gated(open = true) {
    private val inits                                       = new AtomicInteger(0)
    override def initialize(ctx: OFEvaluationContext): Unit =
      if (inits.incrementAndGet() > 1) throw new GeneralError("re-init boom")
  }

  def syncBuild(
    api: OpenFeatureAPI,
    provider: FeatureProvider,
    domain: Option[String],
    initTimeout: Duration,
    owns: Boolean = false,
    statusRef: Option[SubscriptionRef[ProviderStatus]] = None
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    RegistryRecording.instanceProbe().flatMap { probe =>
      FeatureFlagsFactories.build(
        provider,
        domain = domain,
        version = None,
        initialHooks = Nil,
        statusRef = statusRef,
        addShutdownFinalizer = owns,
        apiOverride = Some(api),
        initTimeout = initTimeout,
        probe = probe
      )
    }

  def asyncBuild(
    api: OpenFeatureAPI,
    provider: FeatureProvider,
    domain: String,
    initTimeout: Duration,
    onReady: Option[CountDownLatch] = None
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    RegistryRecording.instanceProbe().flatMap { probe =>
      FeatureFlagsFactories.buildAsync(
        provider,
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = false,
        apiOverride = Some(api),
        onReady = onReady,
        initTimeout = initTimeout,
        probe = probe
      )
    }

  def probedBuild(
    api: OpenFeatureAPI,
    provider: FeatureProvider,
    domain: String,
    probe: LifecycleProbe,
    owns: Boolean = false,
    initTimeout: Duration = 10.seconds
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    RegistryRecording.instanceProbe(probe).flatMap { probe =>
      FeatureFlagsFactories.build(
        provider,
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = owns,
        apiOverride = Some(api),
        initTimeout = initTimeout,
        probe = probe
      )
    }

  // Stops every hit of `point`.
  def holdAt(point: LifecycleProbe.Point, reached: Promise[Nothing, Unit], release: Promise[Nothing, Unit]) =
    new LifecycleProbe {
      def at(p: LifecycleProbe.Point): UIO[Unit] =
        if (p == point) reached.succeed(()) *> release.await else ZIO.unit
    }

  /** One stop: the first hit matching `matches` (while `armed`) completes `reached` and waits for `release`. */
  final class Hold(
    val matches: LifecycleProbe.Point => Boolean,
    val reached: Promise[Nothing, Unit],
    val release: Promise[Nothing, Unit],
    armed: AtomicBoolean
  ) {
    private val used                           = new AtomicBoolean(false)
    def take(p: LifecycleProbe.Point): Boolean = armed.get() && matches(p) && used.compareAndSet(false, true)
    def go: UIO[Unit]                          = release.succeed(()).unit
  }

  object Hold {
    def apply(
      matches: LifecycleProbe.Point => Boolean,
      armed: AtomicBoolean = new AtomicBoolean(true)
    ): UIO[Hold] =
      Promise.make[Nothing, Unit].zipWith(Promise.make[Nothing, Unit])(new Hold(matches, _, _, armed))
  }

  /** A probe that stops at each hold's first match and counts every hit by point name. */
  final class Holds(holds: List[Hold]) extends LifecycleProbe {
    private val hits = new ConcurrentHashMap[String, AtomicInteger]()

    def count(name: String): Int = Option(hits.get(name)).fold(0)(_.get())

    def at(p: LifecycleProbe.Point): UIO[Unit] =
      ZIO.succeed(hits.computeIfAbsent(p.productPrefix, _ => new AtomicInteger(0)).incrementAndGet()) *>
        ZIO.succeed(holds.find(_.take(p))).flatMap {
          case Some(h) => h.reached.succeed(()) *> h.release.await
          case None    => ZIO.unit
        }
  }

  /** Waits (live clock, bounded) until `fiber` is suspended or done: it has reached its wait, or did not need one. */
  def suspendedOrDone(fiber: Fiber.Runtime[Any, Any]): UIO[Unit] =
    Live
      .live(
        fiber.status
          .repeatUntil {
            case _: Fiber.Status.Suspended => true
            case Fiber.Status.Done         => true
            case _                         => false
          }
          .timeout(5.seconds)
      )
      .unit

  def wrapperIn(api: OpenFeatureAPI, provider: Gated, domain: String): UIO[Unit] =
    Live
      .live(
        ZIO
          .succeed(ManagedProvider.existing(provider).exists(w => api.getProvider(domain) eq w))
          .repeatUntil(identity)
          .timeout(5.seconds)
      )
      .unit

  // Waits long enough for a shutdown on its way to land: a watchdog runs its effects after the status moved.
  def shutdownsAfterSettling(provider: Gated): UIO[Int] =
    Live.live(
      ZIO.succeed(provider.shutdowns.get()).repeatUntil(_ > 0).timeout(500.millis).map(_.getOrElse(0))
    )

  // A sibling's build in flight on `domain`, holding `provider`'s wrapper in its slot while the provider initializes.
  def siblingBuilding(
    api: OpenFeatureAPI,
    provider: Gated,
    domain: String
  ): UIO[Fiber[Throwable, ProviderStatus]] =
    for {
      fiber <- ZIO.scoped(syncBuild(api, provider, Some(domain), 30.seconds).flatMap(_.providerStatus)).fork
      _     <- wrapperIn(api, provider, domain)
    } yield fiber
}
