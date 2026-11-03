package zio.openfeature

import zio._
import zio.test._

/** Scala 2.13 has no union types, so `transaction` does not exist there: `transactionEither` is the one form. */
object TransactionRemovedSpec extends ZIOSpecDefault {

  def spec = suite("TransactionRemovedSpec")(
    test("ff.transaction no longer compiles on Scala 2.13") {
      assertZIO(typeCheck("""
        def f(ff: FeatureFlags) = ff.transaction()(ZIO.unit)
      """))(Assertion.isLeft)
    },
    test("FeatureFlags.transaction no longer compiles on Scala 2.13") {
      assertZIO(typeCheck("""
        val effect = FeatureFlags.transaction()(ZIO.unit)
      """))(Assertion.isLeft)
    },
    test("ff.transactionEither still compiles on Scala 2.13") {
      assertZIO(typeCheck("""
        def f(ff: FeatureFlags) = ff.transactionEither()(ZIO.unit)
      """))(Assertion.isRight)
    }
  )
}
