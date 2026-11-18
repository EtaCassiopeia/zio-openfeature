package zio.openfeature

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

import scala.jdk.CollectionConverters._

import zio._
import zio.test._
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  FeatureProvider => OFFeatureProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  Value
}

/** Installing again a provider whose `shutdown()` has started and not returned: it is refused once the bounded wait for
  * that shutdown elapses, and installs once the call has returned.
  */
object ReinstallDuringShutdownSpec extends ZIOSpecDefault {

  private def dflt[T](v: T): ProviderEvaluation[T] = {
    val b = ProviderEvaluation.builder[T]()
    b.value(v)
    b.reason("STATIC")
    b.build().asInstanceOf[ProviderEvaluation[T]]
  }

  /** Records its lifecycle calls. Its first `shutdown()` blocks until released, and it fails every evaluation while
    * closed, as a provider that can be initialized again does.
    */
  private class SlowShutdown(name: String) extends OFFeatureProvider {
    val calls          = new ConcurrentLinkedQueue[String]()
    val release        = new CountDownLatch(1)
    val entered        = new CountDownLatch(1)
    val returned       = new CountDownLatch(1)
    private val closed = new AtomicBoolean(false)

    override def getMetadata: Metadata                      = new Metadata { override def getName: String = name }
    override def initialize(ctx: OFEvaluationContext): Unit = { calls.add("initialize"); closed.set(false) }
    override def shutdown(): Unit                           = {
      calls.add("shutdown")
      entered.countDown()
      release.await(10, TimeUnit.SECONDS)
      closed.set(true)
      returned.countDown()
    }
    private def serve[T](v: T): ProviderEvaluation[T] =
      if (closed.get()) throw new IllegalStateException("provider is shut down") else dflt(v)
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      serve(java.lang.Boolean.TRUE)
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext)             = serve(d)
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) = serve(d)
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext)   = serve(d)
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext)              = serve(d)
  }

  private class Plain(name: String) extends OFFeatureProvider {
    override def getMetadata: Metadata = new Metadata { override def getName: String = name }
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = dflt(d)
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext)             = dflt(d)
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) = dflt(d)
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext)   = dflt(d)
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext)              = dflt(d)
  }

  private def await(l: CountDownLatch) = ZIO.attemptBlocking(l.await(10, TimeUnit.SECONDS)).orDie

  private def stillShuttingDown(result: Either[FeatureFlagError, Unit]): Boolean = result match {
    case Left(FeatureFlagError.ProviderInitializationFailed(e: IllegalStateException)) =>
      e.getMessage.contains("still shutting down")
    case _ => false
  }

  private def instance(provider: OFFeatureProvider, api: OpenFeatureAPI = OpenFeatureAPI.createIsolated()) =
    FeatureFlags.build(
      provider,
      domain = None,
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(api)
    )

  def spec = suite("ReinstallDuringShutdownSpec")(
    test("a provider whose shutdown() outlasts the wait is refused, and installs once that call returns") {
      ZIO.scoped {
        for {
          a       <- ZIO.acquireRelease(ZIO.succeed(new SlowShutdown("A")))(a => ZIO.succeed(a.release.countDown()))
          ff      <- instance(a)
          _       <- ff.awaitReady(5.seconds)
          swapped <- ff.setProvider(new Plain("B")).either
          entered <- await(a.entered)
          refused <- ff.setProvider(a).either
          status  <- ff.providerStatus
          during = a.calls.asScala.toList
          _      <- ZIO.succeed(a.release.countDown())
          _      <- await(a.returned)
          again  <- ff.setProvider(a).either
          served <- ff.boolean("flag", default = false).either
          // Read now: the assertion runs after the scope closes, which shuts the provider down.
          calls <- ZIO.succeed(a.calls.asScala.toList)
        } yield assertTrue(
          swapped.isRight,
          entered,
          stillShuttingDown(refused),
          status == ProviderStatus.Ready,
          during == List("initialize", "shutdown"),
          again.isRight,
          served == Right(true),
          calls == List("initialize", "shutdown", "initialize")
        )
      }
    },
    test("a provider the SDK does not shut down, because another slot holds it, is installed after the wait") {
      val api = OpenFeatureAPI.createIsolated()
      ZIO.scoped {
        for {
          a  <- ZIO.acquireRelease(ZIO.succeed(new SlowShutdown("A")))(a => ZIO.succeed(a.release.countDown()))
          ff <- instance(a, api)
          _  <- ff.awaitReady(5.seconds)
          // A sibling on the same API binds the same wrapper in another slot, so the SDK skips its shutdown.
          _ <- FeatureFlags.build(
            a,
            Some("other"),
            None,
            Nil,
            None,
            addShutdownFinalizer = false,
            apiOverride = Some(api)
          )
          swapped <- ff.setProvider(new Plain("B")).either
          again   <- ff.setProvider(a).either
          served  <- ff.boolean("flag", default = false).either
          calls   <- ZIO.succeed(a.calls.asScala.toList)
        } yield assertTrue(swapped.isRight, again.isRight, served == Right(true), !calls.contains("shutdown"))
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(2.minutes)
}
