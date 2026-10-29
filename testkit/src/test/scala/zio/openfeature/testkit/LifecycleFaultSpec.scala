package zio.openfeature.testkit

import java.util.concurrent.TimeoutException

import zio._
import zio.test._
import zio.openfeature._
import dev.openfeature.sdk.{FeatureProvider, OpenFeatureAPI}

// The stuck-initialize, interrupted-swap and teardown scenarios, re-expressed with FaultyProvider.
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

  def spec = suite("LifecycleFaultSpec")(
    suite("stuck initialize")(
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
    suite("interrupted swap")(
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
    suite("teardown")(
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
      // The shutdown waits for the swap to time out (status Error, which does not end the wait) and then ends it.
      test("a fiber parked in awaitReady() during a swap returns when the instance shuts down") {
        bounded("awaitReady during shutdown") {
          for {
            ff     <- old.flatMap(buildIsolated(_, timeout = 1.second))
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
    ),
    // A provider whose shutdown() waits on whatever its hung initialize() holds (an HTTP client mid-connect, a poller
    // joining its thread): every failure path must give up on that shutdown instead of waiting for it.
    suite("blocking provider shutdown")(
      test("a sync build whose init hangs fails near its init timeout even when shutdown() blocks") {
        bounded("sync build, blocking shutdown") {
          for {
            stuck     <- FaultyProvider.scoped(name = "stuck")
            _         <- stuck.script(Op.Initialize, Fault.HangUntilReleased)
            _         <- stuck.script(Op.Shutdown, Fault.HangUntilReleased)
            timed     <- elapsedMillis(ZIO.scoped(buildShared(stuck).either).disconnect.timeout(8.seconds))
            shutdowns <- stuck.entered(Op.Shutdown)
          } yield
            // init timeout + at most the same again for the abandoned shutdown, far below the 15s shutdown cap
            assertTrue(timed._1.exists(_.isLeft), timed._2 < 2000L, shutdowns == 1)
        }
      },
      test("a failed sync build still shuts the provider down before it returns") {
        bounded("sync build, failed init") {
          for {
            failing   <- FaultyProvider.scoped(name = "failing")
            _         <- failing.script(Op.Initialize, Fault.Throw(new RuntimeException("init failed")))
            res       <- ZIO.scoped(buildShared(failing).either)
            shutdowns <- failing.calls(Op.Shutdown)
          } yield assertTrue(res.isLeft, shutdowns.size == 1)
        }
      },
      test("a failed sync build reports the init failure even when shutdown() throws") {
        bounded("sync build, throwing shutdown") {
          for {
            failing <- FaultyProvider.scoped(name = "failing")
            _       <- failing.script(Op.Initialize, Fault.Throw(new RuntimeException("init failed")))
            _       <- failing.script(Op.Shutdown, Fault.Throw(new RuntimeException("shutdown failed")))
            res     <- ZIO.scoped(buildShared(failing).either)
          } yield assertTrue(res.left.exists(e => String.valueOf(e.getMessage).contains("init failed")))
        }
      },
      test("the init watchdog publishes Fatal, releases its latch, and lets the scope close while shutdown() blocks") {
        bounded("watchdog, blocking shutdown") {
          for {
            stuck  <- FaultyProvider.scoped(name = "stuck-async")
            _      <- stuck.script(Op.Initialize, Fault.HangUntilReleased)
            _      <- stuck.script(Op.Shutdown, Fault.HangUntilReleased)
            events <- Queue.unbounded[ProviderEvent]
            latch = new java.util.concurrent.CountDownLatch(1)
            scope <- Scope.make
            ff    <- scope.extend[Any](
              FeatureFlags.buildAsync(
                stuck,
                domain = Some(isolatedDomain),
                version = None,
                initialHooks = Nil,
                statusRef = None,
                addShutdownFinalizer = false,
                apiOverride = Some(OpenFeatureAPI.createIsolated()),
                onReady = Some(latch),
                initTimeout = initTimeout
              )
            )
            _        <- ff.on(ProviderEventType.Error, e => events.offer(e).unit)
            status   <- ff.providerStatus.repeatUntil(_ == ProviderStatus.Fatal).timeout(10.seconds)
            fatal    <- events.take.timeout(10.seconds)
            released <- ZIO.attemptBlocking(latch.await(10, java.util.concurrent.TimeUnit.SECONDS))
            _        <- stuck.awaitEntered(Op.Shutdown)
            closed   <- scope.close(Exit.unit).disconnect.timeout(10.seconds)
          } yield assertTrue(
            status.isDefined,
            fatal.exists {
              case e: ProviderEvent.Error => e.errorCode.contains(ErrorCode.ProviderFatal)
              case _                      => false
            },
            released,
            closed.isDefined
          )
        }
      },
      test("setProvider returns when rollback fails and the failed provider's shutdown() blocks") {
        bounded("rollback cleanup, blocking shutdown") {
          for {
            // The first initialize() serves the build; the second, the rollback's re-registration, fails.
            previous <- FaultyProvider.scoped(Map("x" -> false), name = "previous")
            _    <- previous.script(Op.Initialize, Fault.Succeed, Fault.Throw(new RuntimeException("re-init failed")))
            next <- FaultyProvider.scoped(Map("x" -> true), name = "next")
            _    <- next.script(Op.Initialize, Fault.Throw(new RuntimeException("init failed")))
            _    <- next.script(Op.Shutdown, Fault.HangUntilReleased)
            res  <- ZIO.scoped {
              for {
                ff        <- buildIsolated(previous)
                res       <- elapsedMillis(ff.setProvider(next).either).disconnect.timeout(8.seconds)
                reInits   <- previous.entered(Op.Initialize)
                shutdowns <- next.entered(Op.Shutdown)
                // Let the scope's API shutdown through.
                _ <- next.script(Op.Shutdown, Fault.Succeed) *> next.release
              } yield (res, reInits, shutdowns)
            }
          } yield assertTrue(
            res._1.exists(_._1.isLeft),
            res._1.exists(_._2 < 3000L),
            res._2 == 2, // the rollback re-registration ran, and failed
            res._3 == 1  // the rollback cleanup, not the scope's API shutdown, called it
          )
        }
      }
    )
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(120.seconds)

  private def isolatedDomain: String = s"lifecycle-fault-${java.util.UUID.randomUUID()}"

  // A sync build on an API this instance does not own, so no API finalizer runs: only the build's own failure path
  // shuts the provider down.
  private def buildShared(provider: FeatureProvider): ZIO[Scope, Throwable, ZEnvironment[FeatureFlags]] =
    FeatureFlags
      .fromProvider(
        provider,
        FeatureFlagsConfig()
          .withInitTimeout(initTimeout)
          .withDomain(isolatedDomain)
          .withApiOwnership(ApiOwnership.Shared),
        statusRef = None,
        apiOverride = Some(OpenFeatureAPI.createIsolated())
      )
      .build
}
