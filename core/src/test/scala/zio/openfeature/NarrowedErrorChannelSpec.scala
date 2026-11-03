package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.FeatureFlagError._

/** Each operation's error channel is the sub-trait of the cases it can raise, on both Scala versions. */
object NarrowedErrorChannelSpec extends ZIOSpecDefault {

  // Exhaustive over EvaluationError alone: no ProviderInitializationFailed, InvalidConfiguration or
  // NestedTransactionNotAllowed, which an evaluation cannot raise. Compiled with fatal warnings, so a missing case or
  // a case that is not an EvaluationError fails the build.
  private def describe(e: EvaluationError): String = e match {
    case FlagNotFound(k)               => s"not found $k"
    case TypeMismatch(k, _, _)         => s"type mismatch $k"
    case ParseError(k, _)              => s"parse $k"
    case TargetingKeyMissing(k)        => s"targeting key $k"
    case InvalidContext(r)             => s"context $r"
    case ProviderNotReady(s)           => s"not ready $s"
    case ProviderFatal                 => "fatal"
    case ProviderError(_)              => "provider error"
    case Unauthorized(r, _)            => s"unauthorized $r"
    case Unreachable(_)                => "unreachable"
    case OverrideTypeMismatch(k, _, _) => s"override $k"
  }

  def spec = suite("NarrowedErrorChannelSpec")(
    test("an evaluation's error channel is EvaluationError") {
      assertZIO(typeCheck("""
        def f(ff: FeatureFlags): IO[FeatureFlagError.EvaluationError, Boolean] = ff.boolean("k", false)
        def g(ff: FeatureFlags): IO[FeatureFlagError.EvaluationError, zio.openfeature.FlagResolution[String]] =
          ff.stringDetails("k", "d")
        def h(ff: FeatureFlags): IO[FeatureFlagError.EvaluationError, Unit] = ff.track("event")
        val viaCompanion: ZIO[FeatureFlags, FeatureFlagError.EvaluationError, Int] = FeatureFlags.int("k", 0)
      """))(Assertion.isRight)
    },
    test("an evaluation's error channel is not LifecycleError") {
      assertZIO(typeCheck("""
        def f(ff: FeatureFlags): IO[FeatureFlagError.LifecycleError, Boolean] = ff.boolean("k", false)
      """))(Assertion.isLeft)
    },
    test("setProvider's error channel is LifecycleError") {
      assertZIO(typeCheck("""
        def f(admin: FeatureFlagsAdmin, p: dev.openfeature.sdk.FeatureProvider): IO[FeatureFlagError.LifecycleError, Unit] =
          admin.setProvider(p)
        def g(p: dev.openfeature.sdk.FeatureProvider): ZIO[FeatureFlagsAdmin, FeatureFlagError.LifecycleError, Unit] =
          FeatureFlagsAdmin.setProvider(p)
      """))(Assertion.isRight)
    },
    test("setProvider's error channel is not EvaluationError") {
      assertZIO(typeCheck("""
        def f(admin: FeatureFlagsAdmin, p: dev.openfeature.sdk.FeatureProvider): IO[FeatureFlagError.EvaluationError, Unit] =
          admin.setProvider(p)
      """))(Assertion.isLeft)
    },
    test("transactionEither's error channel carries only TransactionError besides the body's own") {
      assertZIO(typeCheck("""
        def f(ff: FeatureFlags): IO[Either[String, FeatureFlagError.TransactionError], TransactionResult[Int]] =
          ff.transactionEither()(ZIO.fail("boom"): IO[String, Int])
      """))(Assertion.isRight)
    },
    test("code matching exhaustively over EvaluationError need not name ProviderInitializationFailed") {
      val failing: IO[EvaluationError, String] = ZIO.fail(FlagNotFound("k"))
      failing.catchAll(e => ZIO.succeed(describe(e))).map(r => assertTrue(r == "not found k"))
    },
    test("describe covers every EvaluationError case") {
      val boom = new RuntimeException("boom")
      assertTrue(
        describe(FlagNotFound("k")) == "not found k",
        describe(Unauthorized("r")) == "unauthorized r",
        describe(Unreachable(boom)) == "unreachable",
        describe(ProviderFatal) == "fatal"
      )
    }
  )
}
