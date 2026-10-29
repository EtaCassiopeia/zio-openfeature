package zio.openfeature.testkit

import zio._
import zio.test._
import zio.openfeature._
import dev.openfeature.sdk.{
  FeatureProvider,
  OpenFeatureAPI,
  ProviderEvent => OFProviderEvent,
  ProviderEventDetails,
  ProviderState,
  ProviderStateAccess
}
import dev.openfeature.sdk.exceptions.FatalError

// FeatureFlagRegistry under provider faults: a failed build must leave nothing running and must not stop a later
// getClient from initializing the provider again.
object RegistryFaultSpec extends ZIOSpecDefault {

  private def registry(default: FeatureProvider) =
    FeatureFlagRegistry.fromProvider(default).build.map(_.get[FeatureFlagRegistry])

  private def bounded[R, E, A](label: String)(effect: ZIO[R, E, A]): ZIO[R, Any, A] =
    effect.timeoutFail(s"$label did not finish")(30.seconds)

  // Fibers still running among those a supervised region forked, after giving teardown a moment to land.
  private def stillRunning(tracker: Supervisor[Chunk[Fiber.Runtime[Any, Any]]]): UIO[Int] = {
    val running = tracker.value.flatMap(fs => ZIO.foreach(fs)(_.poll).map(_.count(_.isEmpty)))
    running
      .repeat(Schedule.spaced(20.millis) *> Schedule.recurWhile[Int](_ > 0))
      .timeout(3.seconds)
      .someOrElseZIO(running)
  }

  def spec = suite("RegistryFaultSpec")(
    test("getClient retries a domain whose provider failed to initialize, without shutting it down") {
      bounded("domain retry") {
        for {
          flaky <- FaultyProvider.scoped(Map("x" -> true), name = "flaky")
          _     <- flaky.script(Op.Initialize, Fault.Throw(new RuntimeException("transient")), Fault.Succeed)
          dflt  <- TestFeatureProvider.makeNamed("default", Map.empty)
          out   <- ZIO.scoped {
            registry(dflt).flatMap { reg =>
              for {
                _      <- reg.setProvider("d", flaky)
                first  <- reg.getClient("d").either
                second <- reg.getClient("d").either
                value  <- ZIO.foreach(second.toOption)(_.boolean("x", false))
                inits  <- flaky.entered(Op.Initialize)
                downs  <- flaky.entered(Op.Shutdown)
              } yield (first, value, inits, downs)
            }
          }
        } yield assertTrue(
          out._1.left.exists(_.isInstanceOf[FeatureFlagError.ProviderInitializationFailed]),
          out._2.contains(true),
          out._3 == 2,
          out._4 == 0 // a provider that owns its client (Optimizely) cannot come back from a shutdown
        )
      }
    },
    test("defaultClient retries after the default provider failed to initialize") {
      bounded("default retry") {
        for {
          flaky <- FaultyProvider.scoped(Map("x" -> true), name = "flaky-default")
          _     <- flaky.script(Op.Initialize, Fault.Throw(new RuntimeException("transient")), Fault.Succeed)
          out   <- ZIO.scoped {
            registry(flaky).flatMap { reg =>
              for {
                first  <- reg.defaultClient.either
                second <- reg.defaultClient.either
                value  <- ZIO.foreach(second.toOption)(_.boolean("x", false))
                inits  <- flaky.entered(Op.Initialize)
              } yield (first.isLeft, value, inits)
            }
          }
        } yield assertTrue(out._1, out._2.contains(true), out._3 == 2)
      }
    },
    test("each failed getClient initializes the provider again and leaves none of its fibers running") {
      bounded("failed build teardown") {
        for {
          broken  <- FaultyProvider.scoped(Map("x" -> true), name = "broken")
          _       <- broken.script(Op.Initialize, Fault.Throw(new RuntimeException("down")))
          dflt    <- TestFeatureProvider.makeNamed("default", Map.empty)
          tracker <- Supervisor.track(weak = false)
          out     <- ZIO.scoped {
            registry(dflt).flatMap { reg =>
              for {
                _       <- reg.setProvider("d", broken)
                results <- ZIO.foreach(1 to 3)(_ => reg.getClient("d").either).supervised(tracker)
                running <- stillRunning(tracker)
                inits   <- broken.entered(Op.Initialize)
              } yield (results.forall(_.isLeft), running, inits)
            }
          }
        } yield assertTrue(out._1, out._2 == 0, out._3 == 3) // a leaked build left its init watchdog running
      }
    },
    test("domains retrying a failed default provider at the same time all get a working client") {
      bounded("concurrent retry") {
        for {
          flaky <- FaultyProvider.scoped(Map("x" -> true), name = "flaky-shared")
          _     <- flaky.script(Op.Initialize, Fault.Throw(new RuntimeException("transient")), Fault.Delay(500.millis))
          out   <- ZIO.scoped {
            registry(flaky).flatMap { reg =>
              for {
                first <- reg.defaultClient.either
                both  <- reg.getClient("a").either.zipPar(reg.getClient("b").either)
                inits <- flaky.entered(Op.Initialize)
              } yield (first.isLeft, both._1.isRight, both._2.isRight, inits)
            }
          }
        } yield assertTrue(out._1, out._2, out._3, out._4 == 2)
      }
    },
    suite("retrying a registration")(
      test("re-runs a failed initialization") {
        withRegistered(Fault.Throw(new RuntimeException("transient")), Fault.Succeed) { (api, p) =>
          for {
            _     <- ZIO.attemptBlocking(ProviderStateAccess.retryFailedInit(api, "d", p))
            state <- ZIO.attemptBlocking(api.getClient("d").getProviderState)
            inits <- p.entered(Op.Initialize)
            downs <- p.entered(Op.Shutdown)
          } yield assertTrue(state == ProviderState.READY, inits == 2, downs == 0)
        }
      },
      test("fails at once, without initializing again, after a fatal initialization") {
        withRegistered(Fault.Throw(new FatalError("bad key")), Fault.Succeed) { (api, p) =>
          for {
            res   <- ZIO.attemptBlocking(ProviderStateAccess.retryFailedInit(api, "d", p)).either
            inits <- p.entered(Op.Initialize)
          } yield assertTrue(res.left.exists(_.isInstanceOf[FatalError]), inits == 1)
        }
      },
      test("leaves a provider that initialized and later reported an error to recover on its own") {
        withRegistered(Fault.Succeed) { (api, p) =>
          for {
            _     <- ZIO.attempt(p.emit(OFProviderEvent.PROVIDER_ERROR, ProviderEventDetails.builder().build()))
            _     <- ZIO.attemptBlocking(api.getClient("d").getProviderState).repeatUntil(_ == ProviderState.ERROR)
            _     <- ZIO.attemptBlocking(ProviderStateAccess.retryFailedInit(api, "d", p))
            inits <- p.entered(Op.Initialize)
            downs <- p.entered(Op.Shutdown)
          } yield assertTrue(inits == 1, downs == 0)
        }
      },
      test("leaves a ready provider alone") {
        withRegistered(Fault.Succeed) { (api, p) =>
          for {
            _     <- ZIO.attemptBlocking(ProviderStateAccess.retryFailedInit(api, "d", p))
            state <- ZIO.attemptBlocking(api.getClient("d").getProviderState)
            inits <- p.entered(Op.Initialize)
          } yield assertTrue(state == ProviderState.READY, inits == 1)
        }
      },
      test("leaves a slot that holds another provider alone") {
        withRegistered(Fault.Throw(new RuntimeException("down"))) { (api, p) =>
          for {
            other <- FaultyProvider.scoped(name = "other")
            _     <- ZIO.attemptBlocking(ProviderStateAccess.retryFailedInit(api, "d", other))
            inits <- p.entered(Op.Initialize)
            mine  <- other.entered(Op.Initialize)
          } yield assertTrue(inits == 1, mine == 0)
        }
      }
    )
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(120.seconds)

  // A provider registered for domain "d" on an isolated API, its initialize() scripted, after that first
  // initialization has settled.
  private def withRegistered[E](init: Fault*)(
    body: (OpenFeatureAPI, FaultyProvider[TestFeatureProvider]) => ZIO[Scope, E, TestResult]
  ): ZIO[Scope, Any, TestResult] =
    bounded("retry") {
      ZIO
        .acquireRelease(ZIO.succeed(OpenFeatureAPI.createIsolated()))(api => ZIO.attemptBlocking(api.shutdown()).ignore)
        .flatMap { api =>
          for {
            p <- FaultyProvider.scoped(Map("x" -> true), name = "registered")
            _ <- p.script(Op.Initialize, init: _*)
            _ <- ZIO.attemptBlocking(api.setProviderAndWait("d", p)).ignore
            r <- body(api, p)
          } yield r
        }
    }
}
