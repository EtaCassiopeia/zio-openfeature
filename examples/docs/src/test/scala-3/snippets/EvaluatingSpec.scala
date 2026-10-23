package snippets

import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*
import zio.test.*
import snippets.Evaluating.*

object EvaluatingSpec extends ZIOSpecDefault:

  private val empty = TestFeatureProvider.scopedLayer(Map.empty[String, Any])

  def spec = suite("Evaluating flags")(
    test("every typed method reads its flag") {
      for
        provider <- ZIO.service[TestFeatureProvider]
        _ <- provider.setFlags(
          Map(
            "dark-mode"       -> true,
            "button-color"    -> "green",
            "max-items"       -> 5,
            "max-bytes"       -> 7L,
            "sample-rate"     -> 0.5,
            "checkout-config" -> Map("timeout" -> 10),
            "ratio"           -> 0.25f
          )
        )
        e <- enabled
        c <- color
        l <- limit
        q <- quota
        r <- rate
        o <- config
        f <- ratio
        u <- forUser
        d <- details
      yield assertTrue(
        e,
        c == "green",
        l == 5,
        q == 7L,
        r == 0.5,
        o.get("timeout").contains(10),
        f == 0.25f,
        u,
        d.startsWith("value=true")
      )
    }.provide(empty),
    test("a missing flag: the typed tier fails, the total tier serves the default and says why") {
      for
        typed <- enabled.either
        total <- lenient
        res   <- FeatureFlags.resolveOrDefault[Boolean]("dark-mode", default = false)
        why   <- explained
      yield assertTrue(
        typed == Left(FeatureFlagError.FlagNotFound("dark-mode")),
        !total,
        res.reason == ResolutionReason.Error,
        res.errorCode.contains(ErrorCode.FlagNotFound),
        why.startsWith("served the default because of FlagNotFound")
      )
    }.provide(empty),
    test("the fail-closed gate fails closed") {
      paymentsGate.either.map(r => assertTrue(r == Left(Misconfigured("payments-enabled is not defined"))))
    }.provide(empty),
    test("recovery turns a missing flag into false") {
      recovered.map(r => assertTrue(!r))
    }.provide(empty),
    test("options apply to one call") {
      for
        provider <- ZIO.service[TestFeatureProvider]
        _        <- provider.setFlag("dark-mode", true)
        res      <- bounded
      yield assertTrue(res.value, options.timeout == EvaluationTimeout.After(200.millis))
    }.provide(empty),
    test("classify sorts provider exceptions") {
      assertTrue(
        FeatureFlagError.classify(new java.net.ConnectException("refused")).isInstanceOf[FeatureFlagError.Unreachable],
        FeatureFlagError.classify(new RuntimeException("HTTP 401")).isInstanceOf[FeatureFlagError.Unauthorized],
        FeatureFlagError.classify(new RuntimeException("boom")).isInstanceOf[FeatureFlagError.ProviderError]
      )
    }
  )
