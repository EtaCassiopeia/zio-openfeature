package zio.openfeature.testkit

import zio._
import zio.test._
import zio.openfeature._
import zio.openfeature.internal.FeatureFlagsFactories
import dev.openfeature.sdk.{EventDetails, FeatureProvider, OpenFeatureAPI}

/** The name an instance reports for a provider that has none: `FeatureFlags.UnknownProviderName`, never `null`, on
  * every path a name reaches a user. For each [[MetadataShape]], a provider's `getMetadata` answers `null` or a
  * metadata whose `getName` is `null`.
  */
object ProviderNameSpec extends ZIOSpecDefault {

  private val Unknown = FeatureFlags.UnknownProviderName

  private def nameless(shape: MetadataShape): URIO[Scope, FaultyProvider[TestFeatureProvider]] =
    FaultyProvider.scoped(Map("flag" -> true)).tap(_.script(Op.GetMetadata, Fault.MetadataAnswer(shape)))

  private def named(name: String): URIO[Scope, FaultyProvider[TestFeatureProvider]] =
    FaultyProvider.scoped(Map("flag" -> false), name)

  /** Builds an instance, recording into `trace` from here on when it is the run's only one. */
  private def build(
    provider: FeatureProvider,
    api: OpenFeatureAPI = OpenFeatureAPI.createIsolated(),
    domain: String = "provider-name",
    initTimeout: Duration = FeatureFlags.DefaultInitTimeout,
    trace: Option[TraceProbe] = None
  ): ZIO[Scope, Throwable, FeatureFlagsLive] =
    FeatureFlagsFactories
      .build(
        provider,
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = true,
        apiOverride = Some(api),
        initTimeout = initTimeout,
        probe = TraceRecording.probe(trace)
      )
      .tap(_ => ZIO.foreachDiscard(trace)(_.begin(provider)))

  private def forShape(shape: MetadataShape) =
    suite(shape.toString)(
      test("a swap to the provider reports the unknown name") {
        TraceRecording.recorded(s"naming-$shape-swap") { trace =>
          for {
            first  <- named("first")
            next   <- nameless(shape)
            ff     <- build(first, trace = trace)
            before <- ff.providerMetadata
            _      <- ff.setProvider(next)
            after  <- ff.providerMetadata
            status <- ff.providerStatus
          } yield assertTrue(before.name == "first", after.name == Unknown, status == ProviderStatus.Ready)
        }
      },
      test("a switch to the provider reports the unknown name") {
        ZIO.scoped {
          for {
            real     <- nameless(shape)
            fallback <- named("fallback")
            env      <- FeatureFlags
              .fromAcquireAsync(
                ZIO.succeed(real),
                ZIO.succeed(fallback),
                FeatureFlagsConfig.default.withIsolatedApi,
                AcquireConfig.default.withCompose((r, _) => r)
              )
              .build
            live <- env
              .get[AcquireStatus]
              .changes
              .filter(_ == AcquireState.Live)
              .runHead
              .timeoutFail(new RuntimeException("the switch never happened"))(10.seconds)
            after  <- env.get[FeatureFlags].providerMetadata
            served <- env.get[FeatureFlags].boolean("flag", default = false)
          } yield assertTrue(live.contains(AcquireState.Live), after.name == Unknown, served)
        }
      } @@ TestAspect.withLiveClock,
      test("a sync build of the provider has no name: an event carrying one is another provider's") {
        TraceRecording.recorded(s"naming-$shape-sync-build") { trace =>
          for {
            provider <- nameless(shape)
            ff       <- build(provider, trace = trace)
            _        <- ff.onErrorEvent(EventDetails.builder().providerName("another").build())
            status   <- ff.providerStatus
            name     <- ff.providerMetadata.map(_.name)
          } yield assertTrue(status == ProviderStatus.Ready, name == Unknown)
        }
      },
      test("a swap refused while another provider holds it names it with the unknown name") {
        TraceRecording.recorded(s"naming-$shape-refused-swap") { trace =>
          for {
            // Starts at once when built, and hangs when the rollback registers it again.
            holder <- named("holder")
            _      <- holder.script(Op.Initialize, Fault.Succeed, Fault.HangUntilReleased)
            // Fails its first start, so the swap rolls back onto the holder.
            next    <- nameless(shape)
            _       <- next.script(Op.Initialize, Fault.Throw(new RuntimeException("first start fails")), Fault.Succeed)
            ff      <- build(holder, initTimeout = 500.millis, trace = trace)
            failed  <- ff.setProvider(next).either
            refused <- ff.setProvider(next).either
            _       <- holder.release
            message = refused.left.toOption.collect { case FeatureFlagError.ProviderInitializationFailed(e) =>
              e.getMessage
            }
          } yield assertTrue(
            failed.isLeft,
            message.exists(_.startsWith(s"'$Unknown' cannot be installed again until 'holder', which replaced it"))
          )
        }
      } @@ TestAspect.withLiveClock,
      test("the warning about a slot's existing provider names it with the unknown name") {
        ZIO.scoped {
          val api = OpenFeatureAPI.createIsolated()
          for {
            first  <- nameless(shape)
            second <- named("second")
            _      <- build(first, api)
            _      <- build(second, api)
            logged <- ZTestLogger.logOutput
            warnings = logged.filter(_.logLevel == LogLevel.Warning).map(_.message())
          } yield assertTrue(warnings.exists(_.contains(s"already holds provider '$Unknown'; binding 'second'")))
        }
      }
    )

  def spec = suite("ProviderNameSpec")(MetadataShape.values.map(forShape): _*)
}
