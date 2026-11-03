package zio.openfeature

import zio.*
import zio.test.*
import zio.test.TestAspect.{sequential, withLiveClock}

/** On Scala 3 `transaction` is `transactionEither` with the source tag dropped: its error channel is the union `E |
  * TransactionError`.
  */
object TransactionUnionChannelSpec extends ZIOSpecDefault:

  def spec = suite("TransactionUnionChannelSpec")(
    test("transaction surfaces the caller's own error raw, not Either-wrapped") {
      ZIO.scoped {
        TransactionErrorChannelSpec.buildFF.flatMap { ff =>
          for out <- ff.transaction()(ZIO.fail("boom"): IO[String, Int]).either
          yield assertTrue(out == Left("boom"))
        }
      }
    },
    test("transaction surfaces a nested-transaction failure as the TransactionError itself") {
      ZIO.scoped {
        TransactionErrorChannelSpec.buildFF.flatMap { ff =>
          for out <- ff.transaction()(ff.transaction()(ZIO.unit)).either
          yield assertTrue(out == Left(FeatureFlagError.NestedTransactionNotAllowed))
        }
      }
    },
    test("Reuse composes across the two forms: transaction outer, transactionEither inner") {
      ZIO.scoped {
        TransactionErrorChannelSpec.buildFF.flatMap { ff =>
          for out <- ff.transaction(overrides = Map("k" -> true)) {
              ff.transactionEither(nested = NestedPolicy.Reuse)(ff.boolean("k", false))
            }
          yield assertTrue(out.result.result == true)
        }
      }
    },
    test("Reuse composes across the two forms: transactionEither outer, transaction inner") {
      ZIO.scoped {
        TransactionErrorChannelSpec.buildFF.flatMap { ff =>
          for out <- ff.transactionEither(overrides = Map("k" -> true)) {
              ff.transaction(nested = NestedPolicy.Reuse)(ff.boolean("k", false))
            }
          yield assertTrue(out.result.result == true)
        }
      }
    },
    test("companion accessors: the default is still Fail, nested FeatureFlags.transaction without a policy fails") {
      ZIO.scoped {
        TransactionErrorChannelSpec.buildFF.flatMap { ff =>
          FeatureFlags
            .transactionEither() {
              FeatureFlags.transaction()(ZIO.unit).either
            }
            .provideEnvironment(ZEnvironment(ff))
            .map(out => assertTrue(out.result.left.exists(_ == FeatureFlagError.NestedTransactionNotAllowed)))
        }
      }
    },
    test("the channel is statically E | TransactionError") {
      ZIO.scoped {
        TransactionErrorChannelSpec.buildFF.map { ff =>
          val tx: ZIO[Any, String | FeatureFlagError.TransactionError, TransactionResult[Int]] =
            ff.transaction()(ZIO.fail("boom"): IO[String, Int])
          val viaCompanion: ZIO[FeatureFlags, String | FeatureFlagError.TransactionError, TransactionResult[Int]] =
            FeatureFlags.transaction()(ZIO.succeed(1): IO[String, Int])
          assertTrue(tx != null, viaCompanion != null)
        }
      }
    },
    test("ff.transaction compiles on Scala 3") {
      assertZIO(typeCheck("""
        def f(ff: FeatureFlags) = ff.transaction()(ZIO.unit)
      """))(Assertion.isRight)
    }
  ) @@ sequential @@ withLiveClock
