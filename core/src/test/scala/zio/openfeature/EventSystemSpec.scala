package zio.openfeature

import zio._
import zio.test._
import zio.test.TestAspect.{sequential, withLiveClock}
import zio.openfeature.internal.{FeatureFlagsState, ProviderEvaluations}
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  ErrorCode => JErrorCode,
  EventProvider,
  ImmutableMetadata,
  Metadata,
  OpenFeatureAPIFactory,
  ProviderEvaluation,
  ProviderEventDetails,
  ProviderState,
  Value
}
import scala.jdk.CollectionConverters._

/** Spec §5.1.2/§5.1.4/§5.2.4/§5.2.5: the generic `on` delivers the full original event payload; a handler defect does
  * not cancel its subscription; and the internal hub does not silently drop the newest event on a burst.
  */
object EventSystemSpec extends ZIOSpecDefault {

  private class EmittingProvider extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Emitting" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](true, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")

    def fireError(): Unit =
      emitProviderError(
        ProviderEventDetails
          .builder()
          .errorCode(JErrorCode.GENERAL)
          .message("boom")
          .eventMetadata(ImmutableMetadata.builder().addString("origin", "provider").build())
          .build()
      )

    def fireConfigChanged(flags: List[String]): Unit =
      emitProviderConfigurationChanged(ProviderEventDetails.builder().flagsChanged(flags.asJava).build())

    def fireStale(): Unit =
      emitProviderStale(
        ProviderEventDetails
          .builder()
          .message("stale-reason")
          .eventMetadata(ImmutableMetadata.builder().addString("origin", "provider").build())
          .build()
      )
  }

  private def buildFF(provider: EventProvider): ZIO[Scope, Throwable, FeatureFlags] = {
    val api = OpenFeatureAPIFactory.create()
    FeatureFlags.build(
      provider,
      domain = Some(s"events-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(api),
      evaluationTimeout = Some(5.seconds)
    )
  }

  def spec = suite("EventSystemSpec")(
    test("generic on(Error) delivers the full original event payload (errorCode/message/eventMetadata)") {
      ZIO.scoped {
        for {
          received <- Promise.make[Nothing, ProviderEvent]
          provider = new EmittingProvider
          ff <- buildFF(provider)
          _  <- ff.on(ProviderEventType.Error, e => received.succeed(e).unit)
          _  <- ZIO.attempt(provider.fireError())
          ev <- received.await
        } yield ev match {
          // The old generic `on` rebuilt Error as `Error(e, m)`, dropping the errorMessage field and eventMetadata; the
          // fix passes the original event, so both survive. (errorCode is not carried by the SDK's emit path, so it is
          // not asserted here.)
          case ProviderEvent.Error(err, _, _, msg, em) =>
            assertTrue(
              err.getMessage == "boom",
              msg == Some("boom"),
              em.getString("origin").contains("provider")
            )
          case _ => assertTrue(false)
        }
      }
    },
    test("a handler that defects on one event keeps its subscription and receives the next event (spec 5.2.5)") {
      ZIO.scoped {
        for {
          count <- Ref.make(0)
          provider = new EmittingProvider
          ff <- buildFF(provider)
          _ <- ff.on(
            ProviderEventType.ConfigurationChanged,
            _ => count.updateAndGet(_ + 1).flatMap(n => if (n == 1) ZIO.dieMessage("handler boom") else ZIO.unit)
          )
          _ <- ZIO.attempt(provider.fireConfigChanged(List("a")))
          _ <- ZIO.attempt(provider.fireConfigChanged(List("b")))
          _ <- Live.live(count.get.repeatUntil(_ >= 2).timeout(10.seconds))
          n <- count.get
        } yield assertTrue(n >= 2) // second event still delivered despite the first handler defect
      }
    },
    test("generic on(Stale) delivers the original eventMetadata (not a narrowed reconstruction)") {
      ZIO.scoped {
        for {
          received <- Promise.make[Nothing, ProviderEvent]
          provider = new EmittingProvider
          ff <- buildFF(provider)
          _  <- ff.on(ProviderEventType.Stale, e => received.succeed(e).unit)
          _  <- ZIO.attempt(provider.fireStale())
          ev <- received.await
        } yield ev match {
          case ProviderEvent.Stale(reason, _, em) =>
            assertTrue(reason == "stale-reason", em.getString("origin").contains("provider"))
          case _ => assertTrue(false)
        }
      }
    },
    test("a handler that throws synchronously (before returning its effect) keeps its subscription (spec 5.2.5)") {
      val sync = new java.util.concurrent.atomic.AtomicInteger(0)
      ZIO.scoped {
        for {
          provider <- ZIO.succeed(new EmittingProvider)
          ff       <- buildFF(provider)
          _ <- ff.on(
            ProviderEventType.ConfigurationChanged,
            _ => {
              val n = sync.incrementAndGet()
              if (n == 1) throw new RuntimeException("sync boom") // raw throw while producing the effect
              ZIO.unit
            }
          )
          _ <- ZIO.attempt(provider.fireConfigChanged(List("a")))
          _ <- ZIO.attempt(provider.fireConfigChanged(List("b")))
          _ <- Live.live(ZIO.succeed(sync.get()).repeatUntil(_ >= 2).timeout(10.seconds))
        } yield assertTrue(sync.get() >= 2)
      }
    },
    test("the event hub is sliding: a burst keeps the newest event and drops the oldest") {
      ZIO.scoped {
        for {
          state <- FeatureFlagsState.make
          queue <- state.eventHub.subscribe
          _ <- ZIO.foreachDiscard(0 until 300) { i =>
            state.eventHub.publish(ProviderEvent.ConfigurationChanged(Set(i.toString), ProviderMetadata("p")))
          }
          drained <- queue.takeAll
          flags = drained.toList.flatMap {
            case ProviderEvent.ConfigurationChanged(f, _, _) => f
            case _                                           => Set.empty[String]
          }.toSet
        } yield assertTrue(flags.contains("299"), !flags.contains("0"))
      }
    }
  ) @@ sequential @@ withLiveClock
}
