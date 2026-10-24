package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEventDetails,
  ProviderState,
  Value
}

import scala.jdk.CollectionConverters._

/** #436: an event handler must not inherit the transaction or fiber context of the fiber that registered it. */
object EventHandlerIsolationSpec extends ZIOSpecDefault {

  /** Every boolean is `true`; every string echoes the evaluation's targeting key (or "<none>"). */
  private class EchoProvider extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "echo" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(key: String, default: java.lang.Boolean, ctx: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](true, "STATIC")
    override def getStringEvaluation(key: String, default: String, ctx: OFEvaluationContext) =
      ProviderEvaluations.of[String](Option(ctx.getTargetingKey).getOrElse("<none>"), "STATIC")
    override def getIntegerEvaluation(key: String, default: java.lang.Integer, ctx: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](default, "DEFAULT")
    override def getDoubleEvaluation(key: String, default: java.lang.Double, ctx: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](default, "DEFAULT")
    override def getObjectEvaluation(key: String, default: Value, ctx: OFEvaluationContext) =
      ProviderEvaluations.of[Value](default, "DEFAULT")

    def fireConfigChanged(): Unit =
      emitProviderConfigurationChanged(ProviderEventDetails.builder().flagsChanged(List("f").asJava).build())
  }

  private def buildFF(provider: EventProvider): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      provider,
      domain = Some(s"handler-isolation-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      evaluationTimeout = Some(5.seconds)
    )

  def spec = suite("EventHandlerIsolationSpec (#436)")(
    test("a handler registered inside a transaction does not apply its overrides to later events") {
      ZIO.scoped {
        for {
          provider <- ZIO.succeed(new EchoProvider)
          ff       <- buildFF(provider)
          seen     <- Promise.make[Nothing, (Boolean, Boolean)]
          tx <- ff
            .transactionEither(overrides = Map("f" -> false)) {
              ff.onConfigurationChanged((_, _) =>
                (ff.booleanOrDefault("f", false) <*> ff.inTransaction).flatMap(seen.succeed).unit
              )
            }
          _                   <- ZIO.attempt(provider.fireConfigChanged())
          result              <- seen.await
          evaluatedAfterwards <- ZIO.succeed(tx.evaluatedFlags.keySet)
        } yield assertTrue(result._1, !result._2, evaluatedAfterwards.isEmpty)
      }
    },
    test("a handler registered inside withContext does not see that context on later events") {
      ZIO.scoped {
        for {
          provider <- ZIO.succeed(new EchoProvider)
          ff       <- buildFF(provider)
          seen     <- Promise.make[Nothing, String]
          _ <- ff.withContext(EvaluationContext("ctx-user")) {
            ff.onConfigurationChanged((_, _) => ff.stringOrDefault("who", "").flatMap(seen.succeed).unit)
          }
          _   <- ZIO.attempt(provider.fireConfigChanged())
          who <- seen.await
        } yield assertTrue(who == "<none>")
      }
    },
    // #472: acquireRelease runs its acquire uninterruptibly; the handler fiber must still be cancellable
    test("a handler registered in acquireRelease is cancelled when its scope closes") {
      ZIO.scoped {
        for {
          ff     <- buildFF(new EchoProvider)
          _      <- ZIO.scoped(ZIO.acquireRelease(ff.onProviderReady(_ => ZIO.unit))(cancel => cancel))
          closed <- ZIO.succeed(true)
        } yield assertTrue(closed)
      }
    },
    test("the immediate invocation for an already-Ready provider does not see the registering transaction") {
      ZIO.scoped {
        for {
          provider <- ZIO.succeed(new EchoProvider)
          ff       <- buildFF(provider)
          seen     <- Promise.make[Nothing, (Boolean, Boolean)]
          _ <- ff.transactionEither(overrides = Map("f" -> false)) {
            ff.onProviderReady(_ => (ff.booleanOrDefault("f", false) <*> ff.inTransaction).flatMap(seen.succeed).unit)
          }
          result <- seen.await
        } yield assertTrue(result._1, !result._2)
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(30.seconds) @@ TestAspect.withLiveClock
}
