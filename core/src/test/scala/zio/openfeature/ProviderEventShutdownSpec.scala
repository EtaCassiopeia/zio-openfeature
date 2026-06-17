package zio.openfeature

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPIFactory,
  ProviderEvaluation,
  ProviderState,
  Value
}
import zio._
import zio.test._
import zio.test.TestAspect.{withLiveClock, timeout, sequential}

/** Verifies that subscribing to provider events concurrently with scope shutdown never deadlocks.
  *
  * The event hub shuts down when the layer scope closes (via Hub.unbounded finalization and scope interrupt).
  * Subscriptions and cancellations must complete cleanly without leaking daemon fibers.
  */
object ProviderEventShutdownSpec extends ZIOSpecDefault {

  private class ReadyProvider extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "ReadyProvider" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()

    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluation.builder[java.lang.Boolean]().value(true).reason("STATIC").build()
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluation.builder[String]().value(d).reason("DEFAULT").build()
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluation.builder[java.lang.Integer]().value(d).reason("DEFAULT").build()
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluation.builder[java.lang.Double]().value(d).reason("DEFAULT").build()
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluation.builder[Value]().value(d).reason("DEFAULT").build()
  }

  private def buildFF: ZIO[Scope, Throwable, FeatureFlags] = {
    val api    = OpenFeatureAPIFactory.create()
    val domain = s"event-shutdown-${java.util.UUID.randomUUID()}"
    FeatureFlags.build(
      new ReadyProvider,
      domain = Some(domain),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(api),
      evaluationTimeout = Some(2.seconds)
    )
  }

  def spec = suite("ProviderEventShutdownSpec")(
    test("rapid subscribe+cancel cycles complete without hanging") {
      // Subscribe and immediately cancel 100 times in sequence. The daemon fiber spawned by consumeEvents
      // must be interrupted on each cancel rather than accumulating.
      ZIO.scoped {
        for {
          ff <- buildFF
          _ <- ZIO.foreach(1 to 100) { _ =>
            ff.onProviderReady(_ => ZIO.unit).flatMap(cancel => cancel)
          }
        } yield assertCompletes
      }
    } @@ withLiveClock @@ timeout(15.seconds),
    test("cancel token returned from onProviderReady does not block") {
      ZIO.scoped {
        for {
          ff     <- buildFF
          cancel <- ff.onProviderReady(_ => ZIO.unit)
          _      <- cancel
        } yield assertCompletes
      }
    } @@ withLiveClock @@ timeout(5.seconds),
    test("multiple concurrent subscriptions all receive PROVIDER_READY replay") {
      ZIO.scoped {
        for {
          ff      <- buildFF
          counter <- Ref.make(0)
          // Register 10 ready handlers; each should fire immediately (provider is already READY)
          cancels <- ZIO.foreach(1 to 10) { _ =>
            ff.onProviderReady(_ => counter.update(_ + 1))
          }
          // Poll until all 10 immediate ready-replays have fired, rather than sleeping a fixed window — robust to
          // async event-dispatch latency on a slow runner (bounded by the suite's 10s timeout below).
          count <- counter.get.repeatUntil(_ >= 10)
          // Cancel all subscriptions
          _ <- ZIO.foreachDiscard(cancels)(identity)
        } yield assertTrue(count >= 10)
      }
    } @@ withLiveClock @@ timeout(10.seconds),
    test("subscriptions on all event types complete without deadlock") {
      ZIO.scoped {
        for {
          ff      <- buildFF
          counter <- Ref.make(0)
          c1      <- ff.onProviderReady(_ => counter.update(_ + 1))
          c2      <- ff.onProviderError((_, _) => ZIO.unit)
          c3      <- ff.onProviderStale((_, _) => ZIO.unit)
          c4      <- ff.onConfigurationChanged((_, _) => ZIO.unit)
          _       <- ZIO.sleep(50.millis)
          // Cancel all
          _ <- c1 *> c2 *> c3 *> c4
        } yield assertCompletes
      }
    } @@ withLiveClock @@ timeout(10.seconds)
  ) @@ sequential
}
