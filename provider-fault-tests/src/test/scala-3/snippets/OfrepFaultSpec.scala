package snippets

import dev.openfeature.sdk.FeatureProvider
import rift.zio.testkit.aspects as riftAspects
import zio.*
import zio.openfeature.*
import zio.openfeature.extras.EnvVarProvider
import zio.openfeature.ofrep.{OFREPProvider, OFREPProviderConfig}
import zio.test.*

/** The real OFREP contrib provider, over TLS, through the intercept proxy. OFREP fetches nothing at start-up: every
  * evaluation is one HTTP call, so that is where the fault lands.
  */
object OfrepFaultSpec extends ZIOSpecDefault:

  // Not `.test`: the OFREP provider validates its base URL and rejects that TLD.
  private val host    = "flags.example.com"
  private val healthy = """{"key":"my-flag","value":true,"variant":"on","reason":"STATIC"}"""

  private val config = OFREPProviderConfig(
    baseUrl = s"https://$host",
    requestTimeout = Some(java.time.Duration.ofSeconds(10)),
    connectTimeout = Some(java.time.Duration.ofSeconds(2))
  )

  // Bounds, in the live clock. The evaluation timeout is what ends a stalled call.
  private val evaluationTimeout = 2.seconds
  private val answerBound       = 4.seconds
  private val shutdownBound     = 6.seconds

  // The fallback says `false`; the healthy server says `true`; the tests pass `default = true`. Each outcome is
  // therefore distinguishable from the other two.
  private val fallback: URIO[Scope, FeatureProvider] =
    ZIO.succeed(EnvVarProvider.withLookup(Map("FF_MY_FLAG" -> "false").get))

  /** How the library classifies each fault on the typed tier. A 401/403 is `Unauthorized`: the contrib provider reports
    * it as a `GENERAL` evaluation error whose message core recognizes (#498). Any other HTTP rejection or a timeout is
    * a provider error; a payload that cannot be read (truncated, not JSON, or a connection reset before any bytes) is a
    * `ParseError`.
    */
  private def classifiedAs(fault: Fault)(error: FeatureFlagError): Boolean =
    fault match
      case Fault.Unauthorized | Fault.Forbidden => error.isInstanceOf[FeatureFlagError.Unauthorized]
      case Fault.Truncated | Fault.NotJson      => error.isInstanceOf[FeatureFlagError.ParseError]
      case Fault.Reset => error.isInstanceOf[FeatureFlagError.ParseError] || FeatureFlagError.isProviderError(error)
      case _           => FeatureFlagError.isProviderError(error)

  private def isTimeout(exit: Exit[FeatureFlagError, Boolean]): Boolean =
    exit.causeOption.flatMap(_.failureOption) match
      case Some(FeatureFlagError.ProviderError(_: java.util.concurrent.TimeoutException)) => true
      case _                                                                              => false

  /** Closes `scope` and reports how long that took. */
  private def closeTimed(scope: Scope.Closeable): UIO[Duration] =
    scope.close(Exit.unit).timed.map(_._1)

  private def directTest(fault: Fault) =
    test(s"direct, $fault: ${
        if fault == Fault.Healthy then "the provider answers" else "the call fails with a typed error, quickly"
      }") {
      for
        _     <- Fault.install(fault, host, healthy)
        scope <- Scope.make
        flags <- (for
          provider <- OFREPProvider.scoped(config)
          env      <- FeatureFlags
            .fromProvider(
              provider,
              FeatureFlagsConfig().withDomain(s"ofrep-direct-$fault").withEvaluationTimeout(evaluationTimeout)
            )
            .build
        yield env.get[FeatureFlags]).provideEnvironment(ZEnvironment(scope))
        timed <- flags.booleanDetails("my-flag", default = true).either.timed
        (took, typed) = timed
        total  <- flags.booleanOrDefault("my-flag", default = true)
        closed <- closeTimed(scope)
      yield fault match
        case Fault.Healthy =>
          assertTrue(typed.exists(r => r.value && r.errorCode.isEmpty), total, closed < shutdownBound)
        case _ =>
          // No fault may turn into a silent success: the typed tier fails, the total tier answers the caller's default.
          assertTrue(typed.left.exists(classifiedAs(fault)), total, took < answerBound, closed < shutdownBound)
    }

  private def fallbackTest(fault: Fault) =
    test(s"with a fallback, $fault: ${
        if fault == Fault.Stalled then "the evaluation timeout ends the call" else "the fallback answers"
      }") {
      Fault.install(fault, host, healthy) *> ZIO.scoped {
        for
          env <- FeatureFlags
            .fromAcquireAsync(
              acquire = acquired(OFREPProvider.scoped(config)),
              fallback = fallback,
              constructionRetry = Schedule.stop,
              evaluationTimeout = evaluationTimeout
            )
            .build
          flags = env.get[FeatureFlags]
          _     <- env.get[AcquireStatus].changes.filter(_ != AcquireState.Constructing).runHead
          timed <- flags.boolean("my-flag", default = true).exit.timed
          (took, exit) = timed
          total <- flags.booleanOrDefault("my-flag", default = true)
        yield fault match
          // Healthy: the real provider is swapped in and answers.
          case Fault.Healthy => assertTrue(exit == Exit.succeed(true))
          // OFREP fetches nothing at start-up, so the swap is instant and a stalled call is a live evaluation. The
          // first-successful chain only falls through on an error answer, so nothing answers until the evaluation
          // timeout ends the call: a typed timeout, in bounded time. The total tier still answers the default.
          case Fault.Stalled =>
            assertTrue(isTimeout(exit), took < answerBound, total)
          // Every other fault is an error answer, so the chain falls through to the fallback.
          case _ => assertTrue(exit == Exit.succeed(false), took < answerBound, !total)
      }
    }

  def spec = suite("OFREP under network faults")(
    suite("FeatureFlags.fromProvider")(Fault.values.toList.map(directTest)*),
    suite("FeatureFlags.fromAcquireAsync")(Fault.values.toList.map(fallbackTest)*)
  ).provideShared(RiftFixture.layer) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@
    TestAspect.timeout(5.minutes) @@ riftAspects.embeddedOnly
