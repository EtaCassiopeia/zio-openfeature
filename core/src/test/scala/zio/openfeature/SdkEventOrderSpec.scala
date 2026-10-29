package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventDetails,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvent => OFProviderEvent,
  ProviderEventDetails,
  ProviderState,
  Value
}

/** The SDK runs a provider's event handlers with no ordering between them, so the status follows the SDK's own state
  * for the slot as of each event rather than the event type. Handlers are driven directly through `onSdkEvent`, as the
  * SDK would call them, with the slot in a known state.
  */
object SdkEventOrderSpec extends ZIOSpecDefault {

  final private class Emitting(name: String) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = name }
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.TRUE, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
  }

  private def build(provider: Emitting): ZIO[Scope, Throwable, (FeatureFlagsLive, OpenFeatureAPI, String)] = {
    val api    = OpenFeatureAPI.createIsolated()
    val domain = s"sdk-event-order-${java.util.UUID.randomUUID()}"
    FeatureFlags
      .build(
        provider,
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = true,
        apiOverride = Some(api),
        initTimeout = 5.seconds
      )
      .map(ff => (ff, api, domain))
  }

  private def details(name: String): EventDetails =
    EventDetails.builder().providerName(name).message("from the test").build().asInstanceOf[EventDetails]

  private def slotState(api: OpenFeatureAPI, domain: String): UIO[ProviderState] =
    ZIO.succeed(api.getClient(domain).getProviderState)

  private def awaitStatus(ff: FeatureFlags, status: ProviderStatus): UIO[Boolean] =
    ff.providerStatus.repeatUntil(_ == status).timeout(5.seconds).map(_.isDefined)

  private def published(ff: FeatureFlags, eventType: ProviderEventType): URIO[Scope, Ref[Int]] =
    Ref.make(0).tap(count => ZIO.acquireRelease(ff.on(eventType, _ => count.update(_ + 1)))(cancel => cancel))

  private def matches(observed: (ProviderStatus, ProviderState)): Boolean = observed match {
    case (ProviderStatus.Ready, ProviderState.READY) => true
    case (ProviderStatus.Error, ProviderState.ERROR) => true
    case _                                           => false
  }

  def spec = suite("SdkEventOrderSpec")(
    test("an ERROR handled after the provider is READY again leaves the status Ready, and is still published") {
      ZIO.scoped {
        for {
          built <- build(new Emitting("current"))
          (ff, api, domain) = built
          errors <- published(ff, ProviderEventType.Error)
          slot   <- slotState(api, domain)
          _      <- ff.onSdkEvent(OFProviderEvent.PROVIDER_ERROR, details("current"))
          status <- ff.providerStatus
          seen   <- errors.get.repeatUntil(_ > 0).timeout(2.seconds)
        } yield assertTrue(slot == ProviderState.READY, status == ProviderStatus.Ready, seen.contains(1))
      }
    },
    test("a STALE handled while the provider reports READY leaves the status Ready") {
      ZIO.scoped {
        for {
          built <- build(new Emitting("current"))
          (ff, _, _) = built
          _      <- ff.onSdkEvent(OFProviderEvent.PROVIDER_STALE, details("current"))
          status <- ff.providerStatus
        } yield assertTrue(status == ProviderStatus.Ready)
      }
    },
    test("a READY handled while the provider reports ERROR leaves the status Error, and is still published") {
      ZIO.scoped {
        for {
          provider <- ZIO.succeed(new Emitting("current"))
          built    <- build(provider)
          (ff, api, domain) = built
          readies <- published(ff, ProviderEventType.Ready)
          _       <- ZIO.succeed(provider.emit(OFProviderEvent.PROVIDER_ERROR, ProviderEventDetails.builder().build()))
          inErr   <- awaitStatus(ff, ProviderStatus.Error)
          slot    <- slotState(api, domain)
          before  <- readies.get
          _       <- ff.onSdkEvent(OFProviderEvent.PROVIDER_READY, details("current"))
          status  <- ff.providerStatus
          seen    <- readies.get.repeatUntil(_ > before).timeout(2.seconds)
        } yield assertTrue(inErr, slot == ProviderState.ERROR, status == ProviderStatus.Error, seen.isDefined)
      }
    },
    test("an ERROR handled while the provider reports ERROR moves the status to Error") {
      ZIO.scoped {
        for {
          provider <- ZIO.succeed(new Emitting("current"))
          built    <- build(provider)
          (ff, _, _) = built
          _      <- ZIO.succeed(provider.emit(OFProviderEvent.PROVIDER_ERROR, ProviderEventDetails.builder().build()))
          inErr  <- awaitStatus(ff, ProviderStatus.Error)
          _      <- ff.onSdkEvent(OFProviderEvent.PROVIDER_ERROR, details("current"))
          status <- ff.providerStatus
        } yield assertTrue(inErr, status == ProviderStatus.Error)
      }
    },
    test("a fatal ERROR handled after the provider reports READY again still makes the status Fatal") {
      ZIO.scoped {
        for {
          built <- build(new Emitting("current"))
          (ff, _, _) = built
          fatal      = EventDetails
            .builder()
            .providerName("current")
            .errorCode(dev.openfeature.sdk.ErrorCode.PROVIDER_FATAL)
            .build()
            .asInstanceOf[EventDetails]
          _      <- ff.onSdkEvent(OFProviderEvent.PROVIDER_ERROR, fatal)
          status <- ff.providerStatus
        } yield assertTrue(status == ProviderStatus.Fatal)
      }
    },
    test("ERROR then READY in quick succession settles on the state the SDK reports, every time") {
      ZIO.scoped {
        for {
          provider <- ZIO.succeed(new Emitting("current"))
          built    <- build(provider)
          (ff, api, domain) = built
          rounds <- ZIO.foreach(1 to 30) { _ =>
            for {
              _ <- ZIO.succeed {
                provider.emit(OFProviderEvent.PROVIDER_ERROR, ProviderEventDetails.builder().build())
                provider.emit(OFProviderEvent.PROVIDER_READY, ProviderEventDetails.builder().build())
              }
              settled <- (ff.providerStatus <*> slotState(api, domain)).repeatUntil(matches).timeout(2.seconds)
              last    <- ff.providerStatus <*> slotState(api, domain)
            } yield settled.getOrElse(last)
          }
          mismatched = rounds.filterNot(matches)
        } yield assertTrue(mismatched.isEmpty)
      }
    } @@ TestAspect.withLiveClock
  ) @@ TestAspect.sequential @@ TestAspect.timeout(2.minutes)
}
