package zio.openfeature

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  EventProviderAccess,
  FeatureProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderStateAccess,
  Value
}
import dev.openfeature.sdk.exceptions.GeneralError
import zio._
import zio.openfeature.internal.{FeatureFlagsFactories, ManagedProvider, ProviderEvaluations}
import zio.openfeature.testkit.ScriptedInitDeadline
import zio.test._

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

/** A provider the library wrapped and bound once can be bound again, whatever ended its last registration: a failure
  * another failure replaced, a user's binding that replaced it, or the close of the instance it served. The wrapper's
  * attachment to that dead registration is reset on the way in, as a rollback already did.
  */
object ProviderReclaimSpec extends ZIOSpecDefault {

  /** `initialize()` fails while `failing` is set; every call and every shutdown is counted. */
  final class Toggled(name: String, failing: Boolean) extends FeatureProvider {
    val fails     = new AtomicBoolean(failing)
    val inits     = new AtomicInteger(0)
    val shutdowns = new AtomicInteger(0)

    override def getMetadata: Metadata                      = new Metadata { override def getName: String = name }
    override def initialize(ctx: OFEvaluationContext): Unit = {
      inits.incrementAndGet()
      if (fails.get()) throw new GeneralError(s"$name: initialize fails")
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

  /** An `EventProvider` the SDK attaches itself to when it registers it. */
  final class Emitting(name: String) extends EventProvider {
    override def getMetadata: Metadata = new Metadata { override def getName: String = name }
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

  private def attached(p: FeatureProvider): Boolean =
    scala.util
      .Try(EventProviderAccess.attach(p, (_, _) => ()))
      .fold(_ => true, _ => { EventProviderAccess.detach(p); false })

  private val initTimeout = 5.seconds

  /** `initialize()` waits for `gate`, then fails when `fails`. */
  final class Gated(name: String, fails: Boolean) extends FeatureProvider {
    val gate                                                = new CountDownLatch(1)
    val inits                                               = new AtomicInteger(0)
    val shutdowns                                           = new AtomicInteger(0)
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = name }
    override def initialize(ctx: OFEvaluationContext): Unit = {
      inits.incrementAndGet()
      gate.await(30, TimeUnit.SECONDS)
      if (fails) throw new GeneralError(s"$name fails late")
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

  private def onApi(
    api: OpenFeatureAPI,
    p: FeatureProvider,
    d: Option[String],
    owns: Boolean,
    dl: ScriptedInitDeadline
  ) =
    FeatureFlagsFactories.build(
      p,
      domain = d,
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = owns,
      apiOverride = Some(api),
      initTimeout = 30.seconds,
      initDeadline = dl
    )

  private def eventually(what: String)(cond: => Boolean): Task[Unit] =
    ZIO
      .succeed(cond)
      .repeatUntil(identity)
      .timeout(10.seconds)
      .flatMap(r => ZIO.fail(new RuntimeException(s"$what did not happen")).unless(r.isDefined).unit)

  private def registry(default: FeatureProvider): ZIO[Scope, Throwable, FeatureFlagRegistry] =
    FeatureFlagRegistry
      .fromProvider(default, FeatureFlagsConfig.default.withInitTimeout(initTimeout))
      .build
      .map(_.get[FeatureFlagRegistry])

  private def admin(ff: FeatureFlags): FeatureFlagsAdmin = ff match {
    case a: FeatureFlagsAdmin => a
    case other                => throw new IllegalStateException(s"not an admin: $other")
  }

  def spec = suite("ProviderReclaim")(
    test("a provider whose failed registration was replaced by another failure can be bound again") {
      val p3 = new Toggled("p3", failing = true)
      val p4 = new Toggled("p4", failing = true)
      ZIO.scoped {
        for {
          reg <- registry(new Toggled("p1", failing = false))
          ff  <- reg.defaultClient
          _   <- reg.setProvider("d1", p3)
          _   <- reg.getClient("d1").either
          // Held strongly: a collected wrapper would take its stale attachment with it and hide the refusal.
          wrapper = ManagedProvider.existing(p3)
          // The retry with another provider fails too: the registry releases p3, whose registration has no successor.
          _ <- reg.setProvider("d1", p4)
          _ <- reg.getClient("d1").either
          _ <- ZIO.succeed(p3.fails.set(false))
          before = p3.inits.get()
          swap <- admin(ff).setProvider(p3).either
        } yield assertTrue(wrapper.isDefined, swap.isRight, p3.inits.get() == before + 1)
      }
    },
    test("a provider bound before, whose registration a user binding replaced, can be built on again") {
      val api = OpenFeatureAPI.createIsolated()
      val p3  = new Toggled("p3", failing = true)
      val p5  = new Toggled("p5", failing = true)
      ZIO.scoped {
        for {
          owner <- SharedApiFixtures.syncBuild(api, new Toggled("p1", failing = false), None, initTimeout, owns = true)
          _     <- ZIO.scoped(SharedApiFixtures.syncBuild(api, p3, Some("d1"), initTimeout)).either
          wrapper = ManagedProvider.existing(p3)
          // The user binds a failing provider of their own over p3: nothing shuts p3's registration down for good.
          _    <- ZIO.attemptBlocking(api.setProvider("d1", p5))
          _    <- Live.live(ZIO.sleep(200.millis))
          _    <- ZIO.succeed(p3.fails.set(false))
          swap <- owner.setProvider(p3).either
        } yield assertTrue(wrapper.isDefined, swap.isRight)
      }
    },
    test("a provider an instance closed with can back a new instance at once") {
      val p1     = new Toggled("p1", failing = false)
      val config = FeatureFlagsConfig.default.withIsolatedApi.withInitTimeout(initTimeout)
      for {
        _ <- ZIO.scoped(FeatureFlags.fromProvider(p1, config).build)
        // Held strongly: a collected wrapper would take its stale attachment with it and hide the refusal.
        wrapper = ManagedProvider.existing(p1)
        again <- ZIO.scoped(FeatureFlags.fromProvider(p1, config).build).either
      } yield assertTrue(wrapper.isDefined, again.isRight)
    },
    test("a swap to a provider another slot holds in ERROR reuses that registration and runs no initialize()") {
      val p3 = new Toggled("p3", failing = true)
      ZIO.scoped {
        for {
          reg <- registry(new Toggled("p1", failing = false))
          ff  <- reg.defaultClient
          _   <- reg.setProvider("d1", p3)
          _   <- reg.getClient("d1").either
          before = p3.inits.get()
          swap <- admin(ff).setProvider(p3).either
          bound = ff match {
            case live: FeatureFlagsLive => ManagedProvider.existing(p3).exists(live.slotHolds(_))
            case _                      => false
          }
        } yield assertTrue(swap.isRight, bound, p3.inits.get() == before)
      }
    },
    test(
      "swapping back onto a provider another slot holds ready shuts down a timed-out swap's provider that failed late"
    ) {
      val api = OpenFeatureAPI.createIsolated()
      val dl  = new ScriptedInitDeadline
      val p2  = new Toggled("p2", failing = false)
      val p1  = new Gated("p1", fails = true)
      ZIO.scoped {
        for {
          owner <- onApi(api, p2, Some("d2"), owns = true, dl)
          _     <- onApi(api, p2, None, owns = false, dl)
          swap  <- owner.setProvider(p1).either.fork
          _     <- eventually("p1's wait armed")(dl.armed.contains(p1))
          _     <- ZIO.succeed(dl.fire(p1))
          timed <- swap.join
          _     <- ZIO.succeed(p1.gate.countDown())
          _     <- eventually("p1's late initialize() returning")(
            !ManagedProvider.existing(p1).exists(_.initsInFlightAnywhere > 0)
          )
          // The owner refuses p2 until it has settled the timed-out swap's entry for p1's call.
          _ <- eventually("the owner settling p1's call")(owner.lifecycleSnapshot.orphans.isEmpty)
          before = p1.shutdowns.get()
          back <- owner.setProvider(p2).either
          shut <- eventually("p1's shutdown")(p1.shutdowns.get() >= 1).either
        } yield assertTrue(timed.isLeft, before == 0, back.isRight, shut.isRight, p1.shutdowns.get() == 1)
      }
    },
    test(
      "a provider whose shutdown a timed-out swap left queued is not bound again until that swap's provider settles"
    ) {
      val api = OpenFeatureAPI.createIsolated()
      val dl  = new ScriptedInitDeadline
      val a   = new Toggled("a", failing = false)
      val p   = new Gated("p", fails = false)
      ZIO.scoped {
        for {
          owner <- onApi(api, a, Some("d1"), owns = true, dl)
          swap  <- owner.setProvider(p).either.fork
          _     <- eventually("p's wait armed")(dl.armed.contains(p))
          _     <- ZIO.succeed(dl.fire(p))
          timed <- swap.join
          // The SDK shuts `a` down once p initializes: a binding of `a` now would be closed under it.
          early <- ZIO.scoped(onApi(api, a, Some("d2"), owns = false, dl)).either
          _     <- ZIO.succeed(p.gate.countDown())
          _     <- eventually("a's queued shutdown")(a.shutdowns.get() == 1)
          _     <- eventually("the owner settling p's call")(owner.lifecycleSnapshot.orphans.isEmpty)
          later <- ZIO.scoped(onApi(api, a, Some("d2"), owns = false, dl).flatMap(_.providerStatus)).either
        } yield assertTrue(
          timed.isLeft,
          early.left.exists(_.toString.contains("cannot be installed again until")),
          later.isRight
        )
      }
    },
    test("binding a provider whose attachment outlived its registration resets it, and binds") {
      val api = OpenFeatureAPI.createIsolated()
      val p   = new Emitting("stale")
      // Attached to nothing the API knows: what a registration that ended without a successor leaves behind.
      EventProviderAccess.attach(p, (_, _) => ())
      for {
        refused <- ZIO.attemptBlocking(api.setProvider("d1", p)).either
        _       <- ZIO.attemptBlocking(ProviderStateAccess.bindReclaiming(api, "d1", p, () => ()))
      } yield assertTrue(
        refused.left.exists(_.getMessage.endsWith("is already attached.")),
        api.getProvider("d1") eq p
      )
    },
    test("any other refusal of a binding propagates, and the provider's attachment is left alone") {
      val other = OpenFeatureAPI.createIsolated()
      val api   = OpenFeatureAPI.createIsolated()
      val p     = new Emitting("live elsewhere")
      for {
        _     <- ZIO.attemptBlocking(other.setProviderAndWait("d1", p))
        bound <- ZIO.attemptBlocking(ProviderStateAccess.bindReclaiming(api, "d1", p, () => ())).either
        // Still attached to the API that holds it: the refusal did not detach it.
        still <- ZIO.succeed(attached(p))
        _     <- ZIO.attemptBlocking(other.shutdown())
      } yield assertTrue(
        bound.left.exists(e => e.isInstanceOf[IllegalStateException] && !e.getMessage.endsWith("is already attached.")),
        still
      )
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(2.minutes)
}
