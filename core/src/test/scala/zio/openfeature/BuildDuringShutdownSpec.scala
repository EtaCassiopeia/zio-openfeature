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

/** Building an instance, on another API, with a provider whose `shutdown()` has started and not returned. */
object BuildDuringShutdownSpec extends ZIOSpecDefault {

  private def evaluation[T](v: T): ProviderEvaluation[T] = {
    val b = ProviderEvaluation.builder[T]()
    b.value(v)
    b.reason("STATIC")
    b.build().asInstanceOf[ProviderEvaluation[T]]
  }

  // A provider whose shutdown() blocks until released, and which refuses to serve once that call has returned.
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
      calls.add("shutdown-returned")
      returned.countDown()
    }
    private def serve[T](v: T): ProviderEvaluation[T] =
      if (closed.get()) throw new IllegalStateException("provider is shut down") else evaluation(v)
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      serve(java.lang.Boolean.TRUE)
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext)             = serve(d)
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) = serve(d)
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext)   = serve(d)
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext)              = serve(d)
  }

  private class Plain(name: String) extends OFFeatureProvider {
    override def getMetadata: Metadata = new Metadata { override def getName: String = name }
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = evaluation(d)
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext)             = evaluation(d)
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) = evaluation(d)
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext)   = evaluation(d)
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext)              = evaluation(d)
  }

  private def await(l: CountDownLatch) =
    ZIO.attemptBlocking(l.await(10, TimeUnit.SECONDS)).orDie.filterOrDieMessage(identity)("latch not reached in 10s")

  private def instance(provider: OFFeatureProvider, api: OpenFeatureAPI) =
    FeatureFlags.build(
      provider,
      domain = None,
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(api)
    )

  private def asyncInstance(provider: OFFeatureProvider, api: OpenFeatureAPI) =
    FeatureFlags.buildAsync(
      provider,
      domain = None,
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(api)
    )

  // A first instance on its own API whose swap away from `a` leaves the SDK's shutdown() of `a` blocked.
  private def shuttingDown(a: SlowShutdown) =
    for {
      ff <- instance(a, OpenFeatureAPI.createIsolated())
      _  <- ff.awaitReady(5.seconds)
      _  <- ff.setProvider(new Plain("B")).either
      _  <- await(a.entered)
    } yield ()

  private def slow(name: String) =
    ZIO.acquireRelease(ZIO.succeed(new SlowShutdown(name)))(a => ZIO.succeed(a.release.countDown()))

  private def stillShuttingDown(e: Throwable): Boolean =
    e.isInstanceOf[IllegalStateException] && Option(e.getMessage).exists(_.contains("still shutting down"))

  def spec = suite("BuildDuringShutdownSpec")(
    test("a build with a provider whose shutdown() outlasts the wait fails, and builds once that call returns") {
      ZIO.scoped {
        for {
          a       <- slow("A")
          _       <- shuttingDown(a)
          refused <- ZIO.scoped(instance(a, OpenFeatureAPI.createIsolated()).unit).either
          during  <- ZIO.succeed(a.calls.asScala.toList)
          _       <- ZIO.succeed(a.release.countDown())
          _       <- await(a.returned)
          served  <- ZIO.scoped(
            instance(a, OpenFeatureAPI.createIsolated()).flatMap(_.boolean("flag", default = false).either)
          )
        } yield assertTrue(
          refused.left.exists(stillShuttingDown),
          during == List("initialize", "shutdown"),
          served == Right(true)
        )
      }
    },
    test("a build waits for a shutdown() that returns within the wait") {
      ZIO.scoped {
        for {
          a       <- slow("A")
          _       <- shuttingDown(a)
          _       <- (ZIO.sleep(500.millis) *> ZIO.succeed(a.release.countDown())).fork
          outcome <- ZIO.scoped(
            instance(a, OpenFeatureAPI.createIsolated()).flatMap { ff2 =>
              ff2.boolean("flag", default = false).either.map((_, a.calls.asScala.toList))
            }
          )
          (served, calls) = outcome
        } yield assertTrue(
          served == Right(true),
          calls == List("initialize", "shutdown", "shutdown-returned", "initialize")
        )
      }
    },
    test("an async build is refused the same way while the provider's shutdown() runs") {
      ZIO.scoped {
        for {
          a       <- slow("A")
          _       <- shuttingDown(a)
          refused <- ZIO.scoped(asyncInstance(a, OpenFeatureAPI.createIsolated()).unit).either
          during  <- ZIO.succeed(a.calls.asScala.toList)
        } yield assertTrue(refused.left.exists(stillShuttingDown), during == List("initialize", "shutdown"))
      }
    },
    test("a registry client is refused while the provider's shutdown() runs, and is built on a later getClient") {
      ZIO.scoped {
        for {
          a        <- slow("A")
          _        <- shuttingDown(a)
          registry <- FeatureFlagRegistry.fromProvider(new Plain("default")).build.map(_.get[FeatureFlagRegistry])
          _        <- registry.setProvider("tenant", a).either
          first    <- registry.getClient("tenant").either
          _        <- ZIO.succeed(a.release.countDown())
          _        <- await(a.returned)
          client   <- registry.getClient("tenant")
          served   <- client.boolean("flag", default = false).either
        } yield assertTrue(
          first match {
            case Left(FeatureFlagError.ProviderInitializationFailed(cause)) => stillShuttingDown(cause)
            case _                                                          => false
          },
          served == Right(true)
        )
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(2.minutes) @@ TestAspect.sequential
}
