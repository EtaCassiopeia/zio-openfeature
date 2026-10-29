package snippets

import dev.openfeature.sdk.FeatureProvider
import rift.zio.testkit.aspects as riftAspects
import zio.*
import zio.openfeature.*
import zio.openfeature.extras.EnvVarProvider
import zio.openfeature.optimizely.{OptimizelyEvents, OptimizelyProvider, OptimizelyProviderConfig}
import zio.test.*

/** The real Optimizely provider (Apache HttpClient underneath, so it reads the JVM truststore and proxy properties at
  * first use) polling a datafile through the intercept proxy.
  */
object OptimizelyFaultSpec extends ZIOSpecDefault:

  private val host = "cdn.example.test"
  // Optimizely decides per user, so every evaluation needs a targeting key.
  private val user = EvaluationContext("user-1")

  // A datafile with one rollout flag that is on for everyone.
  private val healthy =
    """{"version":"4","accountId":"1","projectId":"1","revision":"1","anonymizeIP":false,"botFiltering":false,
      |"sendFlagDecisions":true,"experiments":[],"featureFlags":[{"id":"100","key":"lifecycle_flag","rolloutId":"200",
      |"experimentIds":[],"variables":[]}],"rollouts":[{"id":"200","experiments":[{"id":"300","key":"rollout-200",
      |"status":"Running","layerId":"200","audienceIds":[],"forcedVariations":{},"trafficAllocation":[{"entityId":"400",
      |"endOfRange":10000}],"variations":[{"id":"400","key":"on","featureEnabled":true,"variables":[]}]}]}],
      |"audiences":[],"typedAudiences":[],"groups":[],"attributes":[],"variables":[],"events":[]}""".stripMargin
      .replace("\n", "")

  private val config = OptimizelyProviderConfig(
    sdkKey = "fault-test-key",
    datafileUrl = Some(s"https://$host/datafiles/fault-test-key.json"),
    initWait = java.time.Duration.ofSeconds(2),
    blockingTimeout = Some(java.time.Duration.ofSeconds(2)),
    events = OptimizelyEvents.Disabled // datafile behaviour only; keep events off the network
  )

  // Bounds, in the live clock: the provider's own init wait is 2 s.
  private val initTimeout   = 6.seconds
  private val answerBound   = 5.seconds
  private val shutdownBound = 6.seconds

  // The fallback says `false`; the healthy datafile says `true`; the tests pass `default = true`.
  private val fallback: URIO[Scope, FeatureProvider] =
    ZIO.succeed(EnvVarProvider.withLookup(Map("FF_LIFECYCLE_FLAG" -> "false").get))

  private def causeText(t: Throwable): String =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).take(10).map(e => String.valueOf(e.getMessage)).mkString(" | ")

  private def syncTest(fault: Fault) =
    test(
      s"fromProvider, $fault: the layer ${
          if fault == Fault.Healthy then "builds" else "fails with the provider's error"
        }"
    ) {
      for
        _     <- Fault.install(fault, host, healthy)
        scope <- Scope.make
        timed <- (for
          provider <- OptimizelyProvider.scoped(config)
          built    <- FeatureFlags
            .fromProvider(
              provider,
              FeatureFlagsConfig().withDomain(s"optimizely-sync-$fault").withInitTimeout(initTimeout)
            )
            .build
            .either
        yield built).provideEnvironment(ZEnvironment(scope)).timed
        (took, built) = timed
        closed <- scope.close(Exit.unit).timed.map(_._1)
        text = built.left.toOption.map(causeText).getOrElse("")
      yield fault match
        case Fault.Healthy => assertTrue(built.isRight, closed < shutdownBound)
        case _             =>
          // The provider's own error, within its init wait: not the outer init timeout, and not a hang.
          assertTrue(built.isLeft, text.contains("datafile did not load"), took < answerBound, closed < shutdownBound)
    }

  private def fallbackTest(fault: Fault) =
    test(s"fromAcquireAsync, $fault: the fallback answers at once, then ${
        if fault == Fault.Healthy then "the datafile does"
        else "construction fails and nothing else does"
      }") {
      Fault.install(fault, host, healthy) *> ZIO.scoped {
        for
          env <- FeatureFlags
            .fromAcquireAsync(
              acquire = acquired(OptimizelyProvider.scoped(config)),
              fallback = fallback,
              constructionRetry = Schedule.stop,
              constructionTimeout = initTimeout,
              initTimeout = initTimeout,
              evaluationTimeout = 2.seconds
            )
            .build
          flags  = env.get[FeatureFlags]
          status = env.get[AcquireStatus]
          // Before the outcome: boot did not wait for the network.
          early   <- flags.boolean("lifecycle_flag", default = true, user).timed
          state   <- status.changes.filter(_ != AcquireState.Constructing).runHead.timeout(answerBound)
          settled <- flags.boolean("lifecycle_flag", default = true, user).exit
        yield
          val (earlyTook, earlyValue) = early
          val bootDidNotBlock         = earlyValue == false && earlyTook < 1.second
          fault match
            case Fault.Healthy =>
              assertTrue(bootDidNotBlock, state == Some(Some(AcquireState.Live)), settled == Exit.succeed(true))
            case _ =>
              assertTrue(
                bootDidNotBlock,
                state.flatten.exists(_.isInstanceOf[AcquireState.Failed]),
                settled == Exit.succeed(false)
              )
      }
    }

  def spec = suite("Optimizely under network faults")(
    suite("FeatureFlags.fromProvider")(Fault.values.toList.map(syncTest)*),
    suite("FeatureFlags.fromAcquireAsync")(Fault.values.toList.map(fallbackTest)*)
  ).provideShared(RiftFixture.layer) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@
    TestAspect.timeout(5.minutes) @@ riftAspects.embeddedOnly
