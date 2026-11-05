package zio.openfeature.internal

import java.util.concurrent.{CountDownLatch, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.AtomicInteger

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  ProviderState,
  Value
}
import zio._
import zio.openfeature.{FeatureFlagError, ProviderStatus}
import zio.test._

/** The window between a wrapper seeing `initialize()` return and the SDK writing READY. It is microseconds unless the
  * host is starved, so these open it by hand: `initialize()` parks on a latch and the armed attempt's outcome is
  * completed, which is what the library sees once `initialize()` returned.
  */
object RegistrationWriteWaitSpec extends ZIOSpecDefault {

  final private class Parked(name: String, parkFrom: Int) extends EventProvider {
    val inits   = new AtomicInteger(0)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)

    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = name }
    override def initialize(ctx: OFEvaluationContext): Unit = {
      val n = inits.incrementAndGet()
      if (n >= parkFrom) {
        entered.countDown()
        release.await(30, TimeUnit.SECONDS)
      }
      ()
    }
    private def answer[T](v: T): ProviderEvaluation[T] = ProviderEvaluations.of[T](v, "STATIC")
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      answer[java.lang.Boolean](java.lang.Boolean.TRUE)
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext)             = answer(d)
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) = answer(d)
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext)   = answer(d)
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext)              = answer(d)
  }

  private def enteredIn(p: Parked): UIO[Boolean] =
    ZIO.succeedBlocking(p.entered.await(10, TimeUnit.SECONDS))

  // What the library sees once `initialize()` returned, with the SDK's write still to come.
  private def returnWithoutTheWrite(m: ManagedProvider): UIO[Unit] =
    ZIO.succeed { m.arm().attempt.outcome.complete(()); () }

  private def untilSlot(api: OpenFeatureAPI, domain: String, state: ProviderState): UIO[Boolean] =
    ZIO
      .succeed(api.getClient(domain).getProviderState)
      .repeatUntil(_ == state)
      .timeout(10.seconds)
      .map(_.isDefined)

  def spec: Spec[TestEnvironment with Scope, Any] = suite("The wait for the SDK's write after initialize()")(
    test("a slot still NOT_READY at the deadline fails the settle, and the SDK's write still lands") {
      val api    = OpenFeatureAPI.createIsolated()
      val domain = s"write-wait-${java.util.UUID.randomUUID()}"
      val p      = new Parked("parked", parkFrom = 1)
      val bound  = Bound.Managed(ManagedProvider(p))
      for {
        registration <- ProviderRegistration.register(api, Some(domain), bound)
        entered      <- enteredIn(p)
        _            <- returnWithoutTheWrite(bound.provider)
        settled      <- registration.settle(300.millis).either
        slotDuring = api.getClient(domain).getProviderState
        _       <- ZIO.succeed(p.release.countDown())
        written <- untilSlot(api, domain, ProviderState.READY)
        _       <- ZIO.attemptBlocking(api.shutdown()).ignore
      } yield assertTrue(
        entered,
        settled.left.toOption.exists(_.isInstanceOf[TimeoutException]),
        settled.left.toOption.exists(_.getMessage.contains("has not published the registration")),
        slotDuring == ProviderState.NOT_READY,
        written,
        p.inits.get() == 1
      )
    } @@ TestAspect.withLiveClock,
    test("the write landing inside the deadline settles Ready, however long after initialize() returned") {
      val api    = OpenFeatureAPI.createIsolated()
      val domain = s"write-wait-${java.util.UUID.randomUUID()}"
      val p      = new Parked("parked", parkFrom = 1)
      val bound  = Bound.Managed(ManagedProvider(p))
      for {
        registration <- ProviderRegistration.register(api, Some(domain), bound)
        entered      <- enteredIn(p)
        _            <- returnWithoutTheWrite(bound.provider)
        settling     <- registration.settle(30.seconds).either.fork
        // Past the fixed second this wait used to be cut off at, when a slot still NOT_READY counted as ready.
        _       <- ZIO.sleep(1200.millis)
        waiting <- settling.poll.map(_.isEmpty)
        _       <- ZIO.succeed(p.release.countDown())
        settled <- settling.join
        slotNow = api.getClient(domain).getProviderState
        _ <- ZIO.attemptBlocking(api.shutdown()).ignore
      } yield assertTrue(
        entered,
        waiting,
        settled == Right(ProviderRegistration.Settled.Ready),
        slotNow == ProviderState.READY
      )
    } @@ TestAspect.withLiveClock,
    test("a rebind ends the wait: the slot's state is no longer this registration's") {
      val api    = OpenFeatureAPI.createIsolated()
      val domain = s"write-wait-${java.util.UUID.randomUUID()}"
      val p      = new Parked("parked", parkFrom = 1)
      val other  = new Parked("other", parkFrom = Int.MaxValue)
      val bound  = Bound.Managed(ManagedProvider(p))
      for {
        registration <- ProviderRegistration.register(api, Some(domain), bound)
        entered      <- enteredIn(p)
        _            <- returnWithoutTheWrite(bound.provider)
        settling     <- registration.settle(30.seconds).either.fork
        _            <- ZIO.attemptBlocking(api.setProviderAndWait(domain, other))
        settled      <- settling.join.timeout(10.seconds)
        _            <- ZIO.succeed(p.release.countDown())
        _            <- ZIO.attemptBlocking(api.shutdown()).ignore
      } yield assertTrue(
        entered,
        settled.exists(_.left.toOption.exists(_.isInstanceOf[ProviderRegistration.RegistrationSuperseded]))
      )
      // Repeated: the rebind can land anywhere in the wait, including between its check and its read of the slot.
    } @@ TestAspect.withLiveClock @@ TestAspect.nonFlaky(2000),
    test("through setProvider: it times out with status Error, and the SDK's late READY recovers it") {
      val first = new Parked("first", parkFrom = Int.MaxValue)
      val p     = new Parked("second", parkFrom = 1)
      ZIO.scoped {
        for {
          ff <- FeatureFlagsFactories.build(
            first,
            domain = Some(s"write-wait-${java.util.UUID.randomUUID()}"),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = true,
            apiOverride = Some(OpenFeatureAPI.createIsolated()),
            initTimeout = 300.millis
          )
          swap    <- ff.setProvider(p).either.fork
          entered <- enteredIn(p)
          m = ManagedProvider.existing(p).get
          _      <- returnWithoutTheWrite(m)
          result <- swap.join
          during <- ff.providerStatus
          _      <- ZIO.succeed(p.release.countDown())
          after  <- ff.providerStatus.repeatUntil(_ == ProviderStatus.Ready).timeout(10.seconds)
          value  <- ff.boolean("flag", default = false).either
        } yield assertTrue(
          entered,
          result match {
            case Left(FeatureFlagError.ProviderInitializationFailed(_: TimeoutException)) => true
            case _                                                                        => false
          },
          during == ProviderStatus.Error,
          after.contains(ProviderStatus.Ready),
          value == Right(true)
        )
      }
    } @@ TestAspect.withLiveClock
  ) @@ TestAspect.sequential
}
