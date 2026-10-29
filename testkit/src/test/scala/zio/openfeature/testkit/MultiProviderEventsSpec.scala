package zio.openfeature.testkit

import zio._
import zio.test._
import zio.openfeature._
import dev.openfeature.sdk.{
  ErrorCode => OFErrorCode,
  FeatureProvider,
  OpenFeatureAPI,
  ProviderEvent => OFProviderEvent,
  ProviderEventDetails,
  ProviderState
}

// A multi-provider chain tracks each child's status, re-emits a status event only when its own aggregate status
// changes (FATAL > NOT_READY > ERROR > STALE > READY), and always re-emits a configuration change.
object MultiProviderEventsSpec extends ZIOSpecDefault {

  private val Settle = 300.millis

  private def build(chain: FeatureProvider): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      chain,
      domain = Some(s"multi-events-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      initTimeout = 5.seconds
    )

  private def emit(p: FaultyProvider[_], event: OFProviderEvent, code: Option[OFErrorCode] = None): UIO[Unit] =
    ZIO.attemptBlocking {
      val details = code match {
        case Some(c) => ProviderEventDetails.builder().message(s"$event").errorCode(c).build()
        case None    => ProviderEventDetails.builder().message(s"$event").build()
      }
      p.emit(event, details).await()
    }.orDie *> ZIO.sleep(Settle)

  /** Records the chain's status events from now on: after the build's own READY, and after the READY a handler gets at
    * once when it is added to a ready instance.
    */
  private def recorded(ff: FeatureFlags): URIO[Scope, Ref[Chunk[ProviderEvent]]] =
    ZIO.sleep(Settle) *> Ref.make(Chunk.empty[ProviderEvent]).tap { seen =>
      ZIO.foreachDiscard(
        List(ProviderEventType.Ready, ProviderEventType.Error, ProviderEventType.Stale)
      )(t => ZIO.acquireRelease(ff.on(t, e => seen.update(_ :+ e)))(cancel => cancel)) *>
        ZIO.sleep(Settle) *> seen.set(Chunk.empty)
    }

  private def kinds(events: Chunk[ProviderEvent]): Chunk[ProviderEventType] = events.map(_.eventType)

  def spec = suite("multiProvider events")(
    test("a child's ERROR moves the status to Error and its READY back to Ready, under the chain's name") {
      ZIO.scoped {
        for {
          a     <- FaultyProvider.scoped(Map("flag" -> true), name = "a")
          ff    <- build(FeatureFlags.multiProvider(List(a)))
          seen  <- recorded(ff)
          _     <- emit(a, OFProviderEvent.PROVIDER_ERROR)
          erred <- ff.providerStatus
          _     <- emit(a, OFProviderEvent.PROVIDER_READY)
          ready <- ff.providerStatus
          got   <- seen.get
        } yield assertTrue(
          erred == ProviderStatus.Error,
          ready == ProviderStatus.Ready,
          kinds(got) == Chunk(ProviderEventType.Error, ProviderEventType.Ready),
          got.forall(_.metadata.name == "multiprovider")
        )
      }
    },
    test("the chain reports the worst child: STALE then ERROR, and READY on the failed one leaves it STALE") {
      ZIO.scoped {
        for {
          a <- FaultyProvider.scoped(Map("flag" -> true), name = "a")
          b <- FaultyProvider.scoped(Map("flag" -> true), name = "b")
          chain = FeatureFlags.multiProvider(List(a, b))
          ff    <- build(chain)
          seen  <- recorded(ff)
          _     <- emit(a, OFProviderEvent.PROVIDER_STALE)
          _     <- emit(b, OFProviderEvent.PROVIDER_ERROR)
          _     <- emit(b, OFProviderEvent.PROVIDER_READY)
          after <- ZIO.succeed(chainState(chain))
          got   <- seen.get
        } yield assertTrue(
          kinds(got) == Chunk(ProviderEventType.Stale, ProviderEventType.Error, ProviderEventType.Stale),
          // The re-emitted event carries the details of the child event that changed the aggregate.
          got.lastOption.collect { case e: ProviderEvent.Stale => e.reason }.contains("PROVIDER_READY"),
          after == ProviderState.STALE
        )
      }
    },
    test("a child event that leaves the aggregate unchanged is not re-emitted") {
      ZIO.scoped {
        for {
          a    <- FaultyProvider.scoped(Map("flag" -> true), name = "a")
          b    <- FaultyProvider.scoped(Map("flag" -> true), name = "b")
          ff   <- build(FeatureFlags.multiProvider(List(a, b)))
          seen <- recorded(ff)
          _    <- emit(a, OFProviderEvent.PROVIDER_ERROR)
          _    <- emit(b, OFProviderEvent.PROVIDER_ERROR)
          _    <- emit(a, OFProviderEvent.PROVIDER_READY)
          got  <- seen.get
          st   <- ff.providerStatus
        } yield assertTrue(kinds(got) == Chunk(ProviderEventType.Error), st == ProviderStatus.Error)
      }
    },
    test("a child's configuration change always reaches the chain's handlers") {
      ZIO.scoped {
        for {
          a       <- FaultyProvider.scoped(Map("flag" -> true), name = "a")
          ff      <- build(FeatureFlags.multiProvider(List(a)))
          changed <- Promise.make[Nothing, Set[String]]
          _       <- ZIO.acquireRelease(ff.onConfigurationChanged((flags, _) => changed.succeed(flags).unit))(c => c)
          _       <- ZIO
            .attemptBlocking(
              a.emit(
                OFProviderEvent.PROVIDER_CONFIGURATION_CHANGED,
                ProviderEventDetails.builder().flagsChanged(java.util.List.of("flag")).build()
              ).await()
            )
            .orDie
          flags <- changed.await.timeout(5.seconds)
        } yield assertTrue(flags.contains(Set("flag")))
      }
    },
    test("a child's FATAL makes the chain Fatal, and typed evaluations fail with ProviderFatal") {
      ZIO.scoped {
        for {
          a      <- FaultyProvider.scoped(Map("flag" -> true), name = "a")
          ff     <- build(FeatureFlags.multiProvider(List(a)))
          _      <- emit(a, OFProviderEvent.PROVIDER_ERROR, Some(OFErrorCode.PROVIDER_FATAL))
          status <- ff.providerStatus
          typed  <- ff.boolean("flag", false).either
        } yield assertTrue(status == ProviderStatus.Fatal, typed == Left(FeatureFlagError.ProviderFatal))
      }
    },
    test("the chain's getState reports the aggregate of its children") {
      ZIO.scoped {
        for {
          a <- FaultyProvider.scoped(Map("flag" -> true), name = "a")
          b <- FaultyProvider.scoped(Map("flag" -> true), name = "b")
          chain = FeatureFlags.multiProvider(List(a, b))
          _     <- build(chain)
          ready <- ZIO.succeed(chainState(chain))
          _     <- emit(a, OFProviderEvent.PROVIDER_STALE)
          stale <- ZIO.succeed(chainState(chain))
          _     <- emit(b, OFProviderEvent.PROVIDER_ERROR)
          error <- ZIO.succeed(chainState(chain))
        } yield assertTrue(ready == ProviderState.READY, stale == ProviderState.STALE, error == ProviderState.ERROR)
      }
    },
    test("a failed swap away from the chain rolls back to it, and its members' events still reach it") {
      ZIO.scoped {
        for {
          a       <- FaultyProvider.scoped(Map("flag" -> true), name = "a")
          failing <- FaultyProvider.scoped(Map("flag" -> false), name = "failing")
          _       <- failing.script(Op.Initialize, Fault.Throw(new RuntimeException("cannot start")))
          ff      <- build(FeatureFlags.multiProvider(List(a)))
          swap    <- ff.setProvider(failing).either
          back    <- ff.providerStatus
          _       <- emit(a, OFProviderEvent.PROVIDER_ERROR)
          erred   <- ff.providerStatus
        } yield assertTrue(swap.isLeft, back == ProviderStatus.Ready, erred == ProviderStatus.Error)
      }
    },
    test("initializing the chain again keeps its members attached") {
      ZIO.scoped {
        for {
          a <- FaultyProvider.scoped(Map("flag" -> true), name = "a")
          chain = FeatureFlags.multiProvider(List(a))
          _     <- ZIO.attemptBlocking(chain.initialize(new dev.openfeature.sdk.ImmutableContext()))
          again <- ZIO.attemptBlocking(chain.initialize(new dev.openfeature.sdk.ImmutableContext())).either
          _     <- emit(a, OFProviderEvent.PROVIDER_STALE)
          state <- ZIO.succeed(chainState(chain))
          _     <- ZIO.attemptBlocking(chain.shutdown())
        } yield assertTrue(again.isRight, state == ProviderState.STALE)
      }
    },
    test("a member listed twice does not fail the chain, and its events count once") {
      ZIO.scoped {
        for {
          a     <- FaultyProvider.scoped(Map("flag" -> true), name = "a")
          ff    <- build(FeatureFlags.multiProvider(List(a, a)))
          _     <- emit(a, OFProviderEvent.PROVIDER_ERROR)
          erred <- ff.providerStatus
        } yield assertTrue(erred == ProviderStatus.Error)
      }
    },
    test("after the chain shuts down, a child's event reaches nothing and each child was shut down once") {
      for {
        a <- FaultyProvider.scoped(Map("flag" -> true), name = "a")
        chain = FeatureFlags.multiProvider(List(a))
        events <- ZIO.scoped(build(chain).flatMap(recorded))
        _      <- emit(a, OFProviderEvent.PROVIDER_ERROR)
        after  <- events.get
        downs  <- a.calls(Op.Shutdown).map(_.size)
      } yield assertTrue(after.isEmpty, downs == 1, chainState(chain) == ProviderState.NOT_READY)
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(2.minutes)

  @scala.annotation.nowarn("msg=deprecated")
  private def chainState(chain: FeatureProvider): ProviderState = chain.getState
}
