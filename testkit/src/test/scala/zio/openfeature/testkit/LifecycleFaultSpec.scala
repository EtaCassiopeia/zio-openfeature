package zio.openfeature.testkit

import java.util.concurrent.TimeoutException

import zio._
import zio.test._
import zio.openfeature._
import dev.openfeature.sdk.{FeatureProvider, OpenFeatureAPI}

// The #433 (stuck initialize), #434 (interrupted swap) and #435 (teardown) scenarios, re-expressed with FaultyProvider.
// core's regression specs keep their hand-rolled providers (core cannot depend on testkit); these pin the same
// behaviour through the public fault-injection surface, including an initialize() that ignores shutdown().
object LifecycleFaultSpec extends ZIOSpecDefault {

  private val initTimeout = 300.millis
  private val testBound   = 20.seconds

  private def buildIsolated(
    provider: FeatureProvider,
    timeout: Duration = initTimeout
  ): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      provider,
      domain = Some(s"lifecycle-fault-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      initTimeout = timeout
    )

  private def old: UIO[TestFeatureProvider] = TestFeatureProvider.makeNamed("old", Map("x" -> false))

  private def initFailure(e: FeatureFlagError): Option[Throwable] = e match {
    case FeatureFlagError.ProviderInitializationFailed(t) => Some(t)
    case _                                                => None
  }

  private def elapsedMillis[R, E, A](effect: ZIO[R, E, A]): ZIO[R, E, (A, Long)] =
    for {
      start <- Clock.nanoTime
      a     <- effect
      end   <- Clock.nanoTime
    } yield (a, (end - start) / 1000000L)

  private def bounded[E, A](label: String)(effect: ZIO[Scope, E, A]): ZIO[Any, Any, A] =
    ZIO.scoped(effect).timeoutFail(s"$label did not complete within $testBound")(testBound)

  def spec = suite("LifecycleFaultSpec (#463)")(
    suite("stuck initialize (#433)")(
      test("a swap whose initialize() ignores shutdown fails at the init timeout with status Error") {
        bounded("timed-out swap") {
          for {
            ff     <- old.flatMap(buildIsolated(_))
            stuck  <- FaultyProvider.scoped(name = "stuck")
            _      <- stuck.script(Op.Initialize, Fault.HangUntilReleased)
            timed  <- elapsedMillis(ff.setProvider(stuck).either)
            status <- ff.providerStatus
            meta   <- ff.providerMetadata
            next   <- FaultyProvider.scoped(Map("x" -> true), name = "next")
            swap   <- ff.setProvider(next).either
            value  <- ff.boolean("x", false)
          } yield assertTrue(
            timed._1.left.toOption.flatMap(initFailure).exists(_.isInstanceOf[TimeoutException]),
            timed._2 < 3000L,
            status == ProviderStatus.Error,
            meta.name == "stuck",
            swap.isRight,
            value
          )
        }
      },
      test("a timed-out swap never gets the previous provider shut down when the new one finishes later") {
        bounded("timed-out swap") {
          for {
            previous <- FaultyProvider.scoped(Map("x" -> false), name = "old")
            ff       <- buildIsolated(previous)
            slow     <- FaultyProvider.scoped(name = "slow")
            _        <- slow.script(Op.Initialize, Fault.HangUntilShutdown)
            result   <- ff.setProvider(slow).either
            _        <- ZIO.sleep(1.second)
            // Read inside the scope: closing it legitimately shuts `old` down.
            shutdowns <- previous.calls(Op.Shutdown)
          } yield assertTrue(result.isLeft, shutdowns.isEmpty)
        }
      },
      test("a timed-out swap recovers to Ready once the new provider finishes initializing") {
        bounded("late-ready swap") {
          for {
            ff     <- old.flatMap(buildIsolated(_))
            slow   <- FaultyProvider.scoped(Map("x" -> true), name = "slow")
            _      <- slow.script(Op.Initialize, Fault.Delay(800.millis))
            result <- ff.setProvider(slow).either
            status <- ff.providerStatus.repeatUntil(_ == ProviderStatus.Ready).timeout(5.seconds)
            value  <- ff.boolean("x", false)
          } yield assertTrue(
            result.left.toOption.flatMap(initFailure).exists(_.isInstanceOf[TimeoutException]),
            status.contains(ProviderStatus.Ready),
            value
          )
        }
      },
      test("sync build with a stuck initialize() fails at the init timeout and the scope close does not wait for it") {
        bounded("sync build") {
          for {
            stuck <- FaultyProvider.scoped(name = "stuck")
            _     <- stuck.script(Op.Initialize, Fault.HangUntilReleased)
            timed <- elapsedMillis(
              ZIO
                .scoped(
                  FeatureFlags
                    .fromProvider(
                      stuck,
                      FeatureFlagsConfig().withInitTimeout(initTimeout),
                      statusRef = None,
                      apiOverride = Some(OpenFeatureAPI.createIsolated())
                    )
                    .build
                )
                .either
            )
          } yield
            // The SDK's own shutdown waits a few seconds for its executor before interrupting the stuck init thread.
            assertTrue(timed._1.left.toOption.exists(_.isInstanceOf[TimeoutException]), timed._2 < 10000L)
        }
      },
      test("a failed init rolls back to the previous provider, and the next attempt succeeds") {
        val error = new RuntimeException("first init fails")
        bounded("retried swap") {
          for {
            ff         <- old.flatMap(buildIsolated(_, timeout = 10.seconds))
            next       <- FaultyProvider.scoped(Map("x" -> true), name = "next")
            _          <- next.script(Op.Initialize, Fault.Throw(error), Fault.Succeed)
            first      <- ff.setProvider(next).either
            rolledBack <- ff.providerMetadata
            midStatus  <- ff.providerStatus
            midValue   <- ff.boolean("x", true)
            second     <- ff.setProvider(next).either
            meta       <- ff.providerMetadata
            value      <- ff.boolean("x", false)
          } yield assertTrue(
            first.isLeft,
            rolledBack.name == "old",
            midStatus == ProviderStatus.Ready,
            !midValue,
            second.isRight,
            meta.name == "next",
            value
          )
        }
      }
    ),
    suite("interrupted swap (#434)")(
      test("interrupted while the new provider initializes, then it succeeds: Ready on the new provider") {
        bounded("interrupted swap") {
          for {
            ff          <- old.flatMap(buildIsolated(_, timeout = 10.seconds))
            next        <- FaultyProvider.scoped(Map("x" -> true), name = "next")
            _           <- next.script(Op.Initialize, Fault.HangUntilReleased)
            fiber       <- ff.setProvider(next).either.fork
            _           <- next.awaitEntered(Op.Initialize)
            interrupter <- fiber.interrupt.fork // the swap is mid-initialize; the interrupt waits for its outcome
            _           <- ZIO.sleep(100.millis)
            _           <- next.release
            _           <- interrupter.join
            status      <- ff.providerStatus
            meta        <- ff.providerMetadata
            value       <- ff.boolean("x", false)
          } yield assertTrue(status == ProviderStatus.Ready, meta.name == "next", value)
        }
      },
      test("interrupted while the new provider initializes, then it fails: rolled back to the previous provider") {
        bounded("interrupted swap") {
          for {
            ff     <- old.flatMap(buildIsolated(_, timeout = 10.seconds))
            next   <- FaultyProvider.scoped(Map("x" -> true), name = "next")
            _      <- next.script(Op.Initialize, Fault.Delay(500.millis, Fault.Throw(new RuntimeException("late"))))
            fiber  <- ff.setProvider(next).either.fork
            _      <- next.awaitEntered(Op.Initialize)
            _      <- fiber.interrupt
            status <- ff.providerStatus
            meta   <- ff.providerMetadata
            value  <- ff.boolean("x", true)
          } yield assertTrue(status == ProviderStatus.Ready, meta.name == "old", !value)
        }
      },
      test("an interrupt during a never-finishing init completes within the init timeout bound") {
        bounded("interrupted stuck swap") {
          for {
            ff     <- old.flatMap(buildIsolated(_))
            stuck  <- FaultyProvider.scoped(name = "stuck")
            _      <- stuck.script(Op.Initialize, Fault.HangUntilReleased)
            fiber  <- ff.setProvider(stuck).either.fork
            _      <- stuck.awaitEntered(Op.Initialize)
            timed  <- elapsedMillis(fiber.interrupt)
            status <- ff.providerStatus
          } yield assertTrue(timed._2 < 3000L, status == ProviderStatus.Error)
        }
      }
    ),
    suite("teardown (#435)")(
      test("closing the scope completes within the SDK's bound even though the provider's shutdown() hangs") {
        for {
          hanging <- FaultyProvider.make(name = "hanging")
          _       <- hanging.script(Op.Shutdown, Fault.HangUntilReleased)
          timed   <- elapsedMillis(ZIO.scoped(buildIsolated(hanging))).timeoutFail("scope close hung")(testBound)
          _       <- hanging.release
          // The SDK interrupts the stuck shutdown() once its executor's wait expires.
          calls <- hanging.calls(Op.Shutdown).repeatUntil(_.nonEmpty).timeout(5.seconds)
          interrupted = calls.exists(_.exists(_.outcome.isInstanceOf[FaultyProvider.Outcome.Threw]))
        } yield assertTrue(timed._2 < 10000L, interrupted)
      },
      test("a fiber parked in awaitReady() returns when the instance shuts down mid-swap") {
        bounded("awaitReady during shutdown") {
          for {
            ff     <- old.flatMap(buildIsolated(_, timeout = 10.seconds))
            gated  <- FaultyProvider.scoped(name = "gated")
            _      <- gated.script(Op.Initialize, Fault.HangUntilReleased)
            swap   <- ff.setProvider(gated).either.fork // holds the instance NotReady while `gated` initializes
            _      <- gated.awaitEntered(Op.Initialize)
            waiter <- ff.awaitReady().fork
            _      <- ZIO.sleep(100.millis)
            parked <- waiter.poll
            _      <- ff.shutdown
            status <- waiter.join.timeout(5.seconds)
            _      <- gated.release
            _      <- swap.join
          } yield assertTrue(
            parked.isEmpty,
            status.exists(s => s == ProviderStatus.NotReady || s == ProviderStatus.ShuttingDown)
          )
        }
      },
      test("an interrupted shutdown still completes to NotReady") {
        bounded("interrupted shutdown") {
          for {
            provider <- FaultyProvider.scoped(name = "slow-shutdown")
            _        <- provider.script(Op.Shutdown, Fault.HangUntilReleased)
            ff       <- buildIsolated(provider)
            fiber    <- ff.shutdown.fork
            _        <- provider.awaitEntered(Op.Shutdown)
            killer   <- fiber.interrupt.fork
            _        <- ZIO.sleep(100.millis)
            _        <- provider.release
            _        <- killer.join
            status   <- ff.providerStatus
          } yield assertTrue(status == ProviderStatus.NotReady)
        }
      }
    )
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(120.seconds)
}
