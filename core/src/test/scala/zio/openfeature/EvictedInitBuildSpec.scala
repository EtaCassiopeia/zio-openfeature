package zio.openfeature

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProviderAccess,
  FeatureProvider,
  Metadata,
  OpenFeatureAPI,
  Value
}
import dev.openfeature.sdk.exceptions.GeneralError
import zio._
import zio.openfeature.internal.{FeatureFlagsFactories, ManagedProvider, ProviderEvaluations, ProviderRegistration}
import zio.openfeature.testkit.ScriptedInitDeadline
import zio.test._

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

/** A build on a provider whose earlier registration was evicted while its `initialize()` still runs. */
object EvictedInitBuildSpec extends ZIOSpecDefault {

  /** `initialize()` waits for `gate`, then fails when `fails`; every call is counted, and the most running at once. */
  final class Gated(name: String, fails: Boolean) extends FeatureProvider {
    val gate                                                = new CountDownLatch(1)
    val inits                                               = new AtomicInteger(0)
    val running                                             = new AtomicInteger(0)
    val overlap                                             = new AtomicInteger(0)
    val shutdowns                                           = new AtomicInteger(0)
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = name }
    override def initialize(ctx: OFEvaluationContext): Unit = {
      inits.incrementAndGet()
      val now = running.incrementAndGet()
      overlap.accumulateAndGet(now, (a, b) => math.max(a, b))
      try {
        gate.await(60, TimeUnit.SECONDS)
        if (fails) throw new GeneralError(s"$name: initialize fails")
      } finally running.decrementAndGet(): Unit
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

  private def open(): Gated                = { val g = new Gated("open", fails = false); g.gate.countDown(); g }
  private def failing(name: String): Gated = { val g = new Gated(name, fails = true); g.gate.countDown(); g }

  def attached(p: FeatureProvider): Boolean = EventProviderAccess.attached(p)

  private def stillInitializing(e: Throwable): Boolean = e.isInstanceOf[ProviderRegistration.StillInitializing]

  // Long enough for a second initialize(), had the build started one, to have entered.
  private val settle = 500.millis

  private def sync(api: OpenFeatureAPI, p: FeatureProvider, d: String, owns: Boolean, dl: ScriptedInitDeadline) =
    FeatureFlagsFactories.build(
      p,
      domain = Some(d),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = owns,
      apiOverride = Some(api),
      initTimeout = 60.seconds,
      initDeadline = dl
    )

  private def async(api: OpenFeatureAPI, p: FeatureProvider, d: String, dl: ScriptedInitDeadline) =
    FeatureFlagsFactories.buildAsync(
      p,
      domain = Some(d),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = false,
      apiOverride = Some(api),
      initTimeout = 60.seconds,
      initDeadline = dl
    )

  // Generous: the suite runs beside the whole core suite, and these wait on SDK executor tasks.
  private def eventually(what: String, state: => String = "")(cond: => Boolean): Task[Unit] =
    ZIO
      .succeed(cond)
      .repeatUntil(identity)
      .timeout(30.seconds)
      .flatMap(r => ZIO.fail(new RuntimeException(s"$what did not happen $state")).unless(r.isDefined).unit)

  /** The owner swaps to `p`, which times out with its call still running, then to a provider that fails: the slot ends
    * on the failed one, and no slot of the API holds `p`.
    */
  private def evict(api: OpenFeatureAPI, p: Gated) =
    for {
      dl    <- ZIO.succeed(new ScriptedInitDeadline)
      owner <- sync(api, open(), "d1", owns = true, dl)
      swap  <- owner.setProvider(p).either.fork
      _     <- eventually("p's wait armed")(dl.armed.contains(p))
      _     <- ZIO.succeed(dl.fire(p))
      timed <- swap.join
      q     <- owner.setProvider(failing("q")).either
      wrapper = ManagedProvider.existing(p)
      // The owner shuts p down as the provider the failed swap replaced; that releases the wrapper's attachment.
      _ <- eventually(
        "p's shutdown releasing its attachment",
        s"(shutdowns=${p.shutdowns.get()} attached=${wrapper.map(attached)} inits=${p.inits.get()})"
      )(p.shutdowns.get() >= 1 && !wrapper.exists(attached))
      _ <- ZIO.logWarning(
        s"evicted: timed=${timed.isLeft} q=${q.isLeft} inFlight=${wrapper.map(_.initsInFlightAnywhere)} " +
          s"attached=${wrapper.map(attached)} d1=${api.getProvider("d1")} inits=${p.inits.get()}"
      )
    } yield (timed, q, wrapper)

  def spec = suite("EvictedInitBuild")(
    test("a build on a provider whose evicted registration is still initializing is refused and starts no call") {
      val api = OpenFeatureAPI.createIsolated()
      val p   = new Gated("p", fails = false)
      ZIO.scoped {
        for {
          ev <- evict(api, p)
          (timed, q, wrapper) = ev
          inFlight            = wrapper.map(_.initsInFlightAnywhere)
          build <- ZIO.scoped(sync(api, p, "d2", owns = false, new ScriptedInitDeadline)).either.fork
          _     <- ZIO.sleep(settle)
          seen = p.inits.get()
          _      <- ZIO.succeed(p.gate.countDown())
          result <- build.join
          _      <- ZIO.logWarning(s"sync build: result=$result inits=$seen overlap=${p.overlap.get()}")
        } yield assertTrue(
          timed.isLeft,
          q.isLeft,
          inFlight.contains(1),
          seen == 1,
          p.overlap.get() == 1,
          result.left.exists(stillInitializing),
          p.shutdowns.get() == 1
        )
      }
    },
    test("an async build, as a registry makes, on such a provider is refused and starts no call") {
      val api = OpenFeatureAPI.createIsolated()
      val p   = new Gated("p", fails = false)
      ZIO.scoped {
        for {
          ev <- evict(api, p)
          (timed, _, _) = ev
          build <- ZIO.scoped(async(api, p, "d2", new ScriptedInitDeadline)).either
          _     <- ZIO.sleep(settle)
          seen = p.inits.get()
          _ <- ZIO.succeed(p.gate.countDown())
          _ <- ZIO.logWarning(s"async build: result=$build inits=$seen overlap=${p.overlap.get()}")
        } yield assertTrue(
          timed.isLeft,
          seen == 1,
          p.overlap.get() == 1,
          build.left.exists(stillInitializing)
        )
      }
    },
    test("a build on a provider another slot holds while its initialize() runs shares that call") {
      val api = OpenFeatureAPI.createIsolated()
      val p   = new Gated("p", fails = false)
      val dl  = new ScriptedInitDeadline
      ZIO.scoped {
        for {
          owner  <- sync(api, open(), "d1", owns = true, dl)
          swap   <- owner.setProvider(p).either.fork
          _      <- eventually("p's wait armed")(dl.armed.contains(p))
          _      <- ZIO.succeed(dl.fire(p))
          timed  <- swap.join
          build  <- ZIO.scoped(sync(api, p, "d2", owns = false, new ScriptedInitDeadline)).either.fork
          _      <- ZIO.sleep(settle)
          _      <- ZIO.succeed(p.gate.countDown())
          result <- build.join
        } yield assertTrue(timed.isLeft, result.isRight, p.inits.get() == 1)
      }
    },
    test("once the evicted registration's initialize() returns, a build on the provider initializes it afresh") {
      val api = OpenFeatureAPI.createIsolated()
      val p   = new Gated("p", fails = false)
      ZIO.scoped {
        for {
          _       <- evict(api, p)
          refused <- ZIO.scoped(sync(api, p, "d2", owns = false, new ScriptedInitDeadline)).either
          _       <- ZIO.succeed(p.gate.countDown())
          _       <- eventually("p's call returning")(!ManagedProvider.existing(p).exists(_.initsInFlightAnywhere > 0))
          later   <- ZIO.scoped(sync(api, p, "d2", owns = false, new ScriptedInitDeadline)).either
          _ <- ZIO.logWarning(s"retry: refused=$refused later=$later inits=${p.inits.get()} shut=${p.shutdowns.get()}")
        } yield assertTrue(
          refused.left.exists(stillInitializing),
          later.isRight,
          p.inits.get() == 2,
          p.overlap.get() == 1
        )
      }
    },
    test(
      "a refused build leaves the running initialize() and the provider's shutdown to the registration that started it"
    ) {
      val api = OpenFeatureAPI.createIsolated()
      val p   = new Gated("p", fails = false)
      val dl  = new ScriptedInitDeadline
      ZIO.scoped {
        for {
          _     <- sync(api, open(), "d0", owns = true, dl)
          other <- sync(api, open(), "d1", owns = false, dl)
          swap  <- other.setProvider(p).either.fork
          _     <- eventually("p's wait armed")(dl.armed.contains(p))
          _     <- ZIO.succeed(dl.fire(p))
          timed <- swap.join
          q     <- other.setProvider(failing("q")).either
          before = p.shutdowns.get()
          refused <- ZIO.scoped(sync(api, p, "d2", owns = false, new ScriptedInitDeadline)).either
          after = p.shutdowns.get()
          _ <- ZIO.succeed(p.gate.countDown())
          _ <- ZIO.logWarning(
            s"non-owner: timed=$timed q=$q before=$before refused=$refused after=$after inits=${p.inits.get()}"
          )
        } yield assertTrue(
          timed.isLeft,
          q.isLeft,
          refused.left.exists(stillInitializing),
          before == 0,
          after == 0,
          p.inits.get() == 1
        )
      }
    },
    test("a build refused while the SDK has its provider's shutdown queued leaves that shutdown to the SDK") {
      val api = OpenFeatureAPI.createIsolated()
      val a   = open()
      val p   = new Gated("p", fails = false)
      val dl  = new ScriptedInitDeadline
      ZIO.scoped {
        for {
          owner <- sync(api, a, "d1", owns = true, dl)
          swap  <- owner.setProvider(p).either.fork
          _     <- eventually("p's wait armed")(dl.armed.contains(p))
          _     <- ZIO.succeed(dl.fire(p))
          timed <- swap.join
          // The SDK shuts a down once p initializes: until then a is still attached, and no slot holds it.
          refused <- ZIO.scoped(sync(api, a, "d2", owns = false, new ScriptedInitDeadline)).either
          after = a.shutdowns.get()
          _ <- ZIO.succeed(p.gate.countDown())
          _ <- eventually("the SDK shutting a down once p initializes")(a.shutdowns.get() >= 1)
          _ <- ZIO.sleep(settle)
        } yield assertTrue(
          timed.isLeft,
          refused.left.exists(_.isInstanceOf[ProviderRegistration.ShutdownQueued]),
          after == 0,
          a.shutdowns.get() == 1,
          a.inits.get() == 1
        )
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(3.minutes)
}
