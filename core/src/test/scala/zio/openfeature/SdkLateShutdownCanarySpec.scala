package zio.openfeature

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider => OFFeatureProvider,
  Metadata,
  OpenFeatureAPI,
  SdkExecutorAccess,
  Value
}
import zio._
import zio.openfeature.internal.{FeatureFlagsFactories, ProviderEvaluations}
import zio.test._

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/** Pins an SDK behaviour (OpenFeature Java SDK 1.23.0, `ProviderRepository`): a swap's `shutDownOld` runs on the SDK's
  * executor and decides from the slots it sees then. Run after `OpenFeatureAPI.shutdown()` has cleared the domain slots
  * (`prepareShutdown`), it finds the replaced registration unregistered and shuts its provider down, although the API's
  * shutdown already did. A provider the user bound gets that second `shutdown()`; the library's wrapper absorbs it.
  *
  * When these fail, the SDK has fixed it: remove the registry replay's drain before its closes
  * (`RegistryReplaySpec.drainSdk`) and the caveat in docs/providers.md ("Sharing the SDK").
  */
object SdkLateShutdownCanarySpec extends ZIOSpecDefault {

  final class Counting(name: String, initGate: Option[CountDownLatch] = None, onShutdown: () => Unit = () => ())
      extends OFFeatureProvider {
    val shutdowns                                           = new AtomicInteger(0)
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = name }
    override def initialize(ctx: OFEvaluationContext): Unit = initGate.foreach(_.await(30, TimeUnit.SECONDS): Unit)
    override def shutdown(): Unit                           = { shutdowns.incrementAndGet(); onShutdown() }
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

  private val fixed =
    "the SDK no longer shuts the provider down twice: remove RegistryReplaySpec.drainSdk and the docs/providers.md caveat"

  // The API's shutdown waits up to 3 s for the SDK's executor, so the late shutDownOld has usually run when it returns;
  // the pause covers one the shutdown ran inline after its executor refused it.
  private def settled(c: Counting): UIO[Int] =
    ZIO.attemptBlocking { LockSupport.parkNanos(300000000L); c.shutdowns.get() }.orDie

  private def build(p: OFFeatureProvider, d: String, own: Boolean, api: OpenFeatureAPI, scope: Scope, t: Duration) =
    scope.extend[Any](
      FeatureFlagsFactories.build(
        p,
        domain = Some(d),
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = own,
        apiOverride = Some(api),
        initTimeout = t
      )
    )

  def spec = suite("SdkLateShutdownCanary")(
    test(
      "the SDK shuts a provider another slot holds down a second time when the API shuts down before a swap's queued " +
        "shutdown decision runs"
    ) {
      val gate = new CountDownLatch(1)
      val p1   = new Counting("p1")
      // Its first shutdown, the API's, lets the swap's initialize() return: its shutDownOld then runs late.
      val p2  = new Counting("p2", onShutdown = () => gate.countDown())
      val g   = new Counting("g", initGate = Some(gate))
      val api = OpenFeatureAPI.createIsolated()
      for {
        _ <- ZIO.attemptBlocking {
          api.setProviderAndWait(p1)
          api.setProviderAndWait("d1", p2)
          api.setProviderAndWait("d2", p2)
          api.setProvider("d2", g) // d1 still holds p2: on time, its shutDownOld leaves p2 alone
          api.shutdown()
        }
        n <- settled(p2)
      } yield assertTrue(n == 2) ?? s"p2 shut down $n times; $fixed"
    },
    test(
      "an owner's close after a timed-out swap off a shared user-bound provider returns late shuts that provider " +
        "down twice, as the SDK does"
    ) {
      val gate = new CountDownLatch(1)
      val p1   = new Counting("p1")
      val p2   = new Counting("p2", onShutdown = () => gate.countDown())
      val g    = new Counting("g", initGate = Some(gate))
      val api  = OpenFeatureAPI.createIsolated()
      for {
        _      <- ZIO.attemptBlocking { api.setProviderAndWait(p1); api.setProviderAndWait("d2", p2) }
        scopeA <- Scope.make
        scopeB <- Scope.make
        // The d2 instance first, so the library sees d2's slot and binds the user's p2 itself in d1 too.
        b      <- build(p2, "d2", own = false, api, scopeB, 300.millis)
        _      <- build(p2, "d1", own = true, api, scopeA, 5.seconds)
        swap   <- b.setProvider(g).either
        before <- settled(p2)
        _      <- scopeA.close(Exit.unit)
        after  <- settled(p2)
        _      <- scopeB.close(Exit.unit)
      } yield assertTrue(swap.isLeft, before == 0, after == 2) ?? s"swap=$swap before=$before after=$after; $fixed"
    },
    test("the SDK executor reads as drained only once a queued initialize() has returned") {
      val gate                             = new CountDownLatch(1)
      val g                                = new Counting("g", initGate = Some(gate))
      val api                              = OpenFeatureAPI.createIsolated()
      def pendingIs(n: Long): UIO[Boolean] =
        ZIO
          .succeed(SdkExecutorAccess.pending(api))
          .repeat(Schedule.recurUntil[Long](_ == n) && Schedule.spaced(1.milli))
          .timeout(10.seconds)
          .map(_.isDefined)
      for {
        idle    <- ZIO.succeed(SdkExecutorAccess.pending(api))
        _       <- ZIO.attemptBlocking(api.setProvider("d1", g))
        held    <- pendingIs(1)
        _       <- ZIO.succeed(gate.countDown())
        drained <- pendingIs(0)
        _       <- ZIO.attemptBlocking(api.shutdown())
      } yield assertTrue(idle == 0L, held, drained)
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(2.minutes)
}
