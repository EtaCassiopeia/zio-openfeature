package zio.openfeature

import zio._
import zio.test._
import zio.test.TestAspect.{sequential, withLiveClock}
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, EventProvider, Metadata, ProviderState, Value}
import dev.openfeature.sdk.OpenFeatureAPIFactory

/** #255: on Scala 2.13 `transaction`'s error channel erased to `Any` (no union types), disabling typed recovery.
  * `transactionEither` gives a uniform, cross-version typed channel `Either[E, FeatureFlagError]` — `Left` is the
  * caller's own error, `Right` is a transaction-machinery error — while `transaction`'s legacy behavior is preserved.
  *
  * The first test is the key gate: it recovers `Left(msg)` and calls `msg.length`, which compiles ONLY if the left side
  * is statically `String`. On the pre-fix `Any` channel there was no typed `Left` at all, so this would not build.
  */
object TransactionErrorChannelSpec extends ZIOSpecDefault {

  private class NoopProvider extends EventProvider {
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Noop" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  private def buildFF: ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      new NoopProvider,
      domain = Some(s"txec-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPIFactory.create()),
      evaluationTimeout = Some(5.seconds)
    )

  def spec = suite("TransactionErrorChannelSpec")(
    test("transactionEither exposes the caller's own error as a typed Left (statically String, not Any)") {
      ZIO.scoped {
        buildFF.flatMap { ff =>
          for {
            out <- ff.transactionEither()(ZIO.fail("boom"): IO[String, Int]).catchAll {
              case Left(msg) => ZIO.succeed(msg.length) // msg: String — would not compile on the old Any channel
              case Right(_)  => ZIO.succeed(-1)
            }
          } yield assertTrue(out == 4)
        }
      }
    },
    test("transactionEither surfaces a transaction-machinery error as a typed Right(FeatureFlagError)") {
      ZIO.scoped {
        buildFF.flatMap { ff =>
          for {
            // Establish an outer transaction so the inner transactionEither hits the nesting guard.
            out <- ff
              .transaction() {
                ff.transactionEither()(ZIO.unit).map(_ => Option.empty[FeatureFlagError]).catchAll {
                  case Right(ffe) => ZIO.succeed(Some(ffe)) // ffe: FeatureFlagError — typed
                  case Left(_)    => ZIO.succeed(Option.empty[FeatureFlagError])
                }
              }
              .either
          } yield assertTrue(out.toOption.flatMap(_.result).contains(FeatureFlagError.NestedTransactionNotAllowed))
        }
      }
    },
    test("transaction still surfaces the caller's own error raw (not Either-wrapped) — legacy behavior preserved") {
      ZIO.scoped {
        buildFF.flatMap { ff =>
          for {
            out <- ff.transaction()(ZIO.fail("boom"): IO[String, Int]).either
          } yield assertTrue(out == Left("boom"))
        }
      }
    }
  ) @@ sequential @@ withLiveClock
}
