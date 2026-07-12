package zio.openfeature

import zio._
import zio.test._
import zio.stream.SubscriptionRef
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventDetails,
  FeatureProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Value
}
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Init-watchdog escalation (#244): the core fix — a provider that was ever Ready must NOT be Fataled/shut down on a
  * transient post-Ready error; a never-ready provider is escalated to Fatal, shut down, and reported.
  */
object WatchdogEscalationSpec extends ZIOSpecDefault {

  private def uniqueDomain(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

  private class CountingProvider(nm: String, shutdowns: AtomicInteger) extends FeatureProvider {
    private val gate = new CountDownLatch(1)
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = nm }
    override def getState: ProviderState                    = ProviderState.NOT_READY
    override def initialize(ctx: OFEvaluationContext): Unit = gate.await()
    override def shutdown(): Unit                           = { shutdowns.incrementAndGet(); gate.countDown() }
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

  private def build(
    ref: SubscriptionRef[ProviderStatus],
    provider: FeatureProvider,
    onReady: Option[CountDownLatch]
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    FeatureFlags.buildAsync(
      provider,
      domain = Some(uniqueDomain("wd")),
      version = None,
      initialHooks = Nil,
      statusRef = Some(ref),
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      onReady = onReady,
      initTimeout = 1.hour
    )

  private def details(name: String): EventDetails =
    EventDetails.builder().providerName(name).build().asInstanceOf[EventDetails]

  def spec = suite("WatchdogEscalationSpec")(
    test("ever-Ready then transient Error at the deadline: escalation is a no-op, provider left running") {
      val shutdowns = new AtomicInteger(0)
      for {
        ref <- SubscriptionRef.make[ProviderStatus](ProviderStatus.NotReady)
        out <- ZIO.scoped {
          build(ref, new CountingProvider("p", shutdowns), None).flatMap { ff =>
            for {
              _        <- ff.onReadyEvent(details("p")) // provider becomes Ready => everReady set
              _        <- ff.onErrorEvent(details("p")) // transient error => Error
              beforeWd <- ff.providerStatus
              _        <- ff.escalateInitTimeout(new CountingProvider("p", shutdowns), 1.hour)
              afterWd  <- ff.providerStatus
              count    <- ZIO.succeed(shutdowns.get())
            } yield assertTrue(
              beforeWd == ProviderStatus.Error,
              afterWd == ProviderStatus.Error, // NOT Fatal
              count == 0                       // provider was never shut down
            )
          }
        }
      } yield out
    },
    test(
      "never-ready: escalation Fatals, shuts the provider down once, publishes Error(ProviderFatal), releases latch"
    ) {
      val shutdowns = new AtomicInteger(0)
      val provider  = new CountingProvider("p", shutdowns)
      val latch     = new CountDownLatch(1)
      for {
        ref <- SubscriptionRef.make[ProviderStatus](ProviderStatus.NotReady)
        out <- ZIO.scoped {
          build(ref, provider, Some(latch)).flatMap { ff =>
            for {
              collected <- Ref.make(List.empty[ProviderEvent])
              cancel    <- ff.on(ProviderEventType.Error, e => collected.update(_ :+ e))
              _         <- ff.escalateInitTimeout(provider, 30.seconds)
              status    <- ff.providerStatus
              evs       <- Live.live(collected.get.repeatUntil(_.nonEmpty).timeout(5.seconds)).map(_.getOrElse(Nil))
              count     <- ZIO.succeed(shutdowns.get())
              latchN    <- ZIO.succeed(latch.getCount)
              _         <- cancel
            } yield assertTrue(
              status == ProviderStatus.Fatal,
              count == 1,
              latchN == 0L,
              evs.collectFirst { case e: ProviderEvent.Error => e.errorCode }.flatten.contains(ErrorCode.ProviderFatal)
            )
          }
        }
      } yield out
    }
  ) @@ TestAspect.sequential
}
