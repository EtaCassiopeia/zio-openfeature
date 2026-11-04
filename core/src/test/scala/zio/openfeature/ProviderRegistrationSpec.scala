package zio.openfeature

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  ProviderEventDetails,
  ProviderState,
  Value
}
import dev.openfeature.sdk.exceptions.{FatalError, GeneralError}
import zio._
import zio.openfeature.internal.{Bound, ManagedProvider, ProviderRegistration}
import zio.openfeature.internal.ProviderRegistration.Settled
import zio.test._

/** How a registration settles: decided by whether the provider's `initialize()` returned, not by the state it reports
  * afterwards.
  */
object ProviderRegistrationSpec extends ZIOSpecDefault {

  private class Provider(name: String, init: Provider => Unit) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata                      = new Metadata { override def getName: String = name }
    override def initialize(ctx: OFEvaluationContext): Unit = init(this)

    def reportError(message: String, fatal: Boolean = false): Unit = {
      val details = ProviderEventDetails.builder().message(message)
      val _       = emitProviderError(
        (if (fatal) details.errorCode(dev.openfeature.sdk.ErrorCode.PROVIDER_FATAL) else details).build()
      )
    }

    private def none[T](default: T): ProviderEvaluation[T] =
      zio.openfeature.internal.ProviderEvaluations.of[T](default, "DEFAULT")
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = none(d)
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext)             = none(d)
    override def getIntegerEvaluation(k: String, d: Integer, c: OFEvaluationContext)           = none(d)
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext)   = none(d)
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext)              = none(d)
  }

  private def stateOf(api: OpenFeatureAPI, domain: String): UIO[ProviderState] =
    ZIO.succeed(api.getClient(domain).getProviderState)

  private def until(api: OpenFeatureAPI, domain: String, state: ProviderState): Task[Unit] =
    stateOf(api, domain)
      .repeat(Schedule.spaced(5.millis) && Schedule.recurUntil[ProviderState](_ == state))
      .timeoutFail(new RuntimeException(s"the SDK never reported $state"))(5.seconds)
      .unit

  private def registered[A](f: (OpenFeatureAPI, String) => ZIO[Any, Any, A]): ZIO[Any, Any, A] = {
    val api    = OpenFeatureAPI.createIsolated()
    val domain = s"registration-${java.util.UUID.randomUUID()}"
    f(api, domain).ensuring(ZIO.attemptBlocking(api.shutdown()).ignore)
  }

  def spec = suite("ProviderRegistrationSpec")(
    test("a provider already bound to another domain and in ERROR is reused: Error, no second initialize()") {
      registered { (api, domain) =>
        val inits    = new java.util.concurrent.atomic.AtomicInteger(0)
        val provider = new Provider("shared", _ => { inits.incrementAndGet(); () })
        for {
          _       <- ZIO.attemptBlocking(api.setProviderAndWait(domain, ManagedProvider(provider)))
          _       <- ZIO.succeed(provider.reportError("backend down"))
          _       <- until(api, domain, ProviderState.ERROR)
          settled <- ProviderRegistration.registerAndAwait(
            api,
            Some(domain + "-b"),
            Bound.Managed(ManagedProvider(provider)),
            5.seconds
          )
          count = inits.get()
        } yield assertTrue(
          settled match {
            case Settled.Error(_, reused) => reused
            case _                        => false
          },
          count == 1
        )
      }
    },
    test("the same with FATAL settles as Fatal, reused") {
      registered { (api, domain) =>
        val provider = new Provider("shared", _ => ())
        for {
          _       <- ZIO.attemptBlocking(api.setProviderAndWait(domain, ManagedProvider(provider)))
          _       <- ZIO.succeed(provider.reportError("revoked", fatal = true))
          _       <- until(api, domain, ProviderState.FATAL)
          settled <- ProviderRegistration
            .registerAndAwait(api, Some(domain + "-b"), Bound.Managed(ManagedProvider(provider)), 5.seconds)
        } yield assertTrue(settled match {
          case Settled.Fatal(_, reused) => reused
          case _                        => false
        })
      }
    },
    suite("a provider another registration is initializing")(
      test("a registration that may not share it fails before binding anything") {
        registered { (api, domain) =>
          val inits   = new java.util.concurrent.atomic.AtomicInteger(0)
          val gate    = new java.util.concurrent.CountDownLatch(1)
          val wrapper = ManagedProvider(new Provider("busy", _ => { inits.incrementAndGet(); gate.await() }))
          val other   = domain + "-b"
          for {
            first <- ProviderRegistration.registerAndAwait(api, Some(domain), Bound.Managed(wrapper), 10.seconds).fork
            _     <- Live.live(ZIO.succeed(inits.get()).repeatUntil(_ == 1).timeout(5.seconds))
            exit  <- ProviderRegistration.register(api, Some(other), Bound.Managed(wrapper), shareInFlight = false).exit
            bound = api.getProvider(other) eq wrapper
            count = inits.get()
            _ <- ZIO.succeed(gate.countDown())
            _ <- first.join
          } yield assertTrue(
            exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[ProviderRegistration.StillInitializing]),
            !bound,
            count == 1
          )
        }
      },
      test("one that may share it binds and settles on that call's outcome") {
        registered { (api, domain) =>
          val inits   = new java.util.concurrent.atomic.AtomicInteger(0)
          val gate    = new java.util.concurrent.CountDownLatch(1)
          val wrapper = ManagedProvider(new Provider("busy", _ => { inits.incrementAndGet(); gate.await() }))
          val other   = domain + "-b"
          for {
            first  <- ProviderRegistration.registerAndAwait(api, Some(domain), Bound.Managed(wrapper), 10.seconds).fork
            _      <- Live.live(ZIO.succeed(inits.get()).repeatUntil(_ == 1).timeout(5.seconds))
            second <- ProviderRegistration.registerAndAwait(api, Some(other), Bound.Managed(wrapper), 10.seconds).fork
            _      <- Live.live(ZIO.succeed(api.getProvider(other) eq wrapper).repeatUntil(identity).timeout(5.seconds))
            _      <- ZIO.succeed(gate.countDown())
            settled <- second.join
            _       <- first.join
            count = inits.get()
          } yield assertTrue(settled == Settled.Ready, count == 1)
        }
      },
      test("a registration that may not share a call that has returned binds as usual") {
        registered { (api, domain) =>
          val wrapper = ManagedProvider(new Provider("idle", _ => ()))
          for {
            _   <- ProviderRegistration.registerAndAwait(api, Some(domain), Bound.Managed(wrapper), 5.seconds)
            reg <- ProviderRegistration.register(
              api,
              Some(domain + "-b"),
              Bound.Managed(wrapper),
              shareInFlight = false
            )
            settled <- reg.settle(5.seconds)
          } yield assertTrue(settled == Settled.Ready)
        }
      }
    ),
    suite("a provider the slot already holds")(
      test("one that is ready settles Ready with no second initialize()") {
        registered { (api, domain) =>
          val inits    = new java.util.concurrent.atomic.AtomicInteger(0)
          val provider = new Provider("held", _ => { inits.incrementAndGet(); () })
          for {
            _       <- ZIO.attemptBlocking(api.setProviderAndWait(domain, provider))
            settled <- ProviderRegistration.registerAndAwait(api, Some(domain), Bound.Foreign(provider), 5.seconds)
            count = inits.get()
          } yield assertTrue(settled == Settled.Ready, count == 1)
        }
      },
      test("one still initializing gets no PROVIDER_READY before its initialize() returns, and no second call") {
        registered { (api, domain) =>
          val inits    = new java.util.concurrent.atomic.AtomicInteger(0)
          val readies  = new java.util.concurrent.atomic.AtomicInteger(0)
          val gate     = new java.util.concurrent.CountDownLatch(1)
          val provider = new Provider("held", _ => { inits.incrementAndGet(); gate.await() })
          for {
            _     <- ZIO.succeed(api.onProviderReady { _ => readies.incrementAndGet(); () })
            _     <- ZIO.succeed(api.setProvider(domain, provider))
            _     <- Live.live(ZIO.succeed(inits.get()).repeatUntil(_ == 1).timeout(5.seconds))
            fiber <- ProviderRegistration.registerAndAwait(api, Some(domain), Bound.Foreign(provider), 10.seconds).fork
            _     <- Live.live(ZIO.sleep(300.millis))
            early <- ZIO.succeed(readies.get())
            _     <- ZIO.succeed(gate.countDown())
            settled <- fiber.join
            count = inits.get()
          } yield assertTrue(early == 0, settled == Settled.Ready, count == 1)
        }
      },
      test("the same in the default slot") {
        registered { (api, _) =>
          val inits    = new java.util.concurrent.atomic.AtomicInteger(0)
          val readies  = new java.util.concurrent.atomic.AtomicInteger(0)
          val gate     = new java.util.concurrent.CountDownLatch(1)
          val provider = new Provider("held", _ => { inits.incrementAndGet(); gate.await() })
          for {
            _       <- ZIO.succeed(api.onProviderReady { _ => readies.incrementAndGet(); () })
            _       <- ZIO.succeed(api.setProvider(provider))
            _       <- Live.live(ZIO.succeed(inits.get()).repeatUntil(_ == 1).timeout(5.seconds))
            fiber   <- ProviderRegistration.registerAndAwait(api, None, Bound.Foreign(provider), 10.seconds).fork
            _       <- Live.live(ZIO.sleep(300.millis))
            early   <- ZIO.succeed(readies.get())
            _       <- ZIO.succeed(gate.countDown())
            settled <- fiber.join
            count = inits.get()
          } yield assertTrue(early == 0, settled == Settled.Ready, count == 1)
        }
      },
      test("one the default slot holds is still bound under a domain, which keeps it when the default moves on") {
        registered { (api, domain) =>
          val provider = new Provider("default", _ => ())
          val next     = new Provider("next", _ => ())
          for {
            _ <- ZIO.attemptBlocking(api.setProviderAndWait(provider))
            _ <- ProviderRegistration.registerAndAwait(api, Some(domain), Bound.Foreign(provider), 5.seconds)
            _ <- ZIO.attemptBlocking(api.setProviderAndWait(next))
            underDomain = api.getProvider(domain) eq provider
            inDefault   = api.getProvider() eq next
          } yield assertTrue(underDomain, inDefault)
        }
      },
      test("an EventProvider an instance's slot holds is reused under another domain, and its events reach both") {
        registered { (api, _) =>
          val provider              = new Provider("shared", _ => ())
          def build(domain: String) = FeatureFlags.build(
            provider,
            domain = Some(domain),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = false,
            apiOverride = Some(api)
          )
          ZIO.scoped {
            for {
              _ <- ZIO.attemptBlocking(api.setProviderAndWait("x", provider))
              x <- build("x")
              y <- build("y")
              held = api.getProvider("y") eq provider
              _   <- ZIO.succeed(provider.reportError("down"))
              onX <- Live.live(x.providerStatus.repeatUntil(_ == ProviderStatus.Error).timeout(5.seconds))
              onY <- Live.live(y.providerStatus.repeatUntil(_ == ProviderStatus.Error).timeout(5.seconds))
            } yield assertTrue(held, onX.isDefined, onY.isDefined)
          }
        }
      },
      test("the API's own no-op provider, which nothing has initialized, still settles Ready") {
        registered { (api, _) =>
          ProviderRegistration
            .registerAndAwait(api, None, Bound.Foreign(api.getProvider()), 5.seconds)
            .map(settled => assertTrue(settled == Settled.Ready))
        }
      }
    ),
    test("a provider without a name: an initialize() that returns, then an ERROR, settles as Error") {
      registered { (api, domain) =>
        val provider = new Provider(null, _ => ())
        ProviderRegistration
          .registerThenAwait(api, Some(domain), Bound.Managed(ManagedProvider(provider)), 5.seconds)(
            until(api, domain, ProviderState.READY) *>
              ZIO.succeed(provider.reportError("lost the backend")) *>
              until(api, domain, ProviderState.ERROR)
          )
          .map(settled => assertTrue(settled == Settled.Error("lost the backend")))
      }
    },
    test("a provider without a name whose initialize() throws still fails the registration") {
      registered { (api, domain) =>
        val provider = new Provider(null, _ => throw new RuntimeException("bad config"))
        ProviderRegistration
          .registerAndAwait(api, Some(domain), Bound.Managed(ManagedProvider(provider)), 5.seconds)
          .either
          .map { result =>
            assertTrue(result.left.toOption.exists(_.getMessage.contains("bad config")))
          }
      }
    },
    test("over a slot already READY, a nameless provider whose initialize() throws is not counted as initialized") {
      // The slot's READY belongs to the provider it held: the new one's own outcome decides.
      registered { (api, domain) =>
        val before = new Provider("before", _ => ())
        val after  = new Provider(null, _ => throw new RuntimeException("bad config"))
        for {
          _      <- ZIO.attemptBlocking(api.setProviderAndWait(domain, ManagedProvider(before)))
          result <- ProviderRegistration
            .registerAndAwait(api, Some(domain), Bound.Managed(ManagedProvider(after)), 5.seconds)
            .either
        } yield assertTrue(result.isLeft)
      }
    },
    test("an initialize() that returns, then an ERROR before the wait reads it, settles as Error") {
      registered { (api, domain) =>
        val provider = new Provider("p", _ => ())
        ProviderRegistration
          .registerThenAwait(api, Some(domain), Bound.Managed(ManagedProvider(provider)), 5.seconds)(
            until(api, domain, ProviderState.READY) *>
              ZIO.succeed(provider.reportError("lost the backend")) *>
              until(api, domain, ProviderState.ERROR)
          )
          .map(settled => assertTrue(settled == Settled.Error("lost the backend")))
      }
    },
    test("the same with a FATAL settles as Fatal") {
      registered { (api, domain) =>
        val provider = new Provider("p", _ => ())
        ProviderRegistration
          .registerThenAwait(api, Some(domain), Bound.Managed(ManagedProvider(provider)), 5.seconds)(
            until(api, domain, ProviderState.READY) *>
              ZIO.succeed(provider.reportError("revoked", fatal = true)) *>
              until(api, domain, ProviderState.FATAL)
          )
          .map(settled => assertTrue(settled == Settled.Fatal("revoked")))
      }
    },
    test("an initialize() that throws fails the registration with its message") {
      registered { (api, domain) =>
        val provider = new Provider("p", _ => throw new RuntimeException("bad config"))
        ProviderRegistration
          .registerAndAwait(api, Some(domain), Bound.Managed(ManagedProvider(provider)), 5.seconds)
          .either
          .map { result =>
            assertTrue(
              result.left.toOption.exists(e => e.isInstanceOf[GeneralError] && e.getMessage.contains("bad config"))
            )
          }
      }
    },
    test("an initialize() that throws a FatalError fails the registration with a FatalError") {
      registered { (api, domain) =>
        val provider = new Provider("p", _ => throw new FatalError("bad sdk key"))
        ProviderRegistration
          .registerAndAwait(api, Some(domain), Bound.Managed(ManagedProvider(provider)), 5.seconds)
          .either
          .map { result =>
            assertTrue(
              result.left.toOption.exists(e => e.isInstanceOf[FatalError] && e.getMessage.contains("bad sdk key"))
            )
          }
      }
    },
    test("an ERROR reported from inside an initialize() that then returns settles, never fails") {
      registered { (api, domain) =>
        val provider = new Provider("p", p => p.reportError("warming up"))
        ProviderRegistration
          .registerAndAwait(api, Some(domain), Bound.Managed(ManagedProvider(provider)), 5.seconds)
          .either
          .map { result =>
            assertTrue(result.isRight)
          }
      }
    },
    test("on the default slot, an initialize() that returns, then an ERROR, settles as Error too") {
      val api                                     = OpenFeatureAPI.createIsolated()
      val provider                                = new Provider("p", _ => ())
      def until(state: ProviderState): Task[Unit] =
        ZIO
          .succeed(api.getClient().getProviderState)
          .repeat(Schedule.spaced(5.millis) && Schedule.recurUntil[ProviderState](_ == state))
          .timeoutFail(new RuntimeException(s"the SDK never reported $state"))(5.seconds)
          .unit
      ProviderRegistration
        .registerThenAwait(api, None, Bound.Managed(ManagedProvider(provider)), 5.seconds)(
          until(ProviderState.READY) *> ZIO.succeed(provider.reportError("lost the backend")) *>
            until(ProviderState.ERROR)
        )
        .map(settled => assertTrue(settled == Settled.Error("lost the backend")))
        .ensuring(ZIO.attemptBlocking(api.shutdown()).ignore)
    },
    test("a READY from a same-named provider bound before the registration does not count as initialized") {
      registered { (api, domain) =>
        val before = new Provider("same", _ => ())
        val after  = new Provider("same", _ => throw new RuntimeException("bad config"))
        for {
          _      <- ZIO.attemptBlocking(api.setProviderAndWait(domain, ManagedProvider(before)))
          _      <- ZIO.succeed(before.emitProviderReady(ProviderEventDetails.builder().build()).await())
          result <- ProviderRegistration
            .registerAndAwait(api, Some(domain), Bound.Managed(ManagedProvider(after)), 5.seconds)
            .either
        } yield assertTrue(result.isLeft)
      }
    },
    test("two domains bound to one registration both settle on the one initialize() the SDK runs for it") {
      registered { (api, domain) =>
        val gate     = new java.util.concurrent.CountDownLatch(1)
        val inits    = new java.util.concurrent.atomic.AtomicInteger(0)
        val provider = new Provider(
          "shared",
          _ => { inits.incrementAndGet(); gate.await(10, java.util.concurrent.TimeUnit.SECONDS); () }
        )
        val bound = Bound.Managed(ManagedProvider(provider))
        (for {
          a       <- ProviderRegistration.register(api, Some(domain + "-a"), bound)
          b       <- ProviderRegistration.register(api, Some(domain + "-b"), bound)
          waitA   <- a.settle(5.seconds).fork
          waitB   <- b.settle(5.seconds).fork
          _       <- ZIO.succeed(gate.countDown())
          settled <- waitA.join.zip(waitB.join)
        } yield assertTrue(settled == ((Settled.Ready, Settled.Ready)), inits.get() == 1))
          .ensuring(ZIO.succeed(gate.countDown()))
      }
    },
    test("a registration settles on the attempt it armed, though a later registration arms a fresh one") {
      registered { (api, domain) =>
        val provider = new Provider("first", _ => ())
        val bound    = Bound.Managed(ManagedProvider(provider))
        for {
          first   <- ProviderRegistration.register(api, Some(domain + "-a"), bound)
          _       <- until(api, domain + "-a", ProviderState.READY)
          _       <- ProviderRegistration.register(api, Some(domain + "-b"), bound)
          settled <- first.settle(2.seconds)
        } yield assertTrue(settled == Settled.Ready)
      }
    },
    test("a provider the API holds wins over a wrapper cached for it") {
      registered { (api, domain) =>
        val provider = new dev.openfeature.sdk.NoOpProvider()
        val cached   = ManagedProvider(provider)
        for {
          _     <- ZIO.attemptBlocking(api.setProviderAndWait(domain, provider))
          bound <- ZIO.attempt(Bound.of(api, Some(domain), provider))
        } yield assertTrue(bound == Bound.Foreign(provider), ManagedProvider.existing(provider).exists(_ eq cached))
      }
    },
    suite("a provider registered with the API outside the library")(
      test("held in the default slot, it is reused under a domain with no second initialize()") {
        registered { (api, domain) =>
          val inits    = new java.util.concurrent.atomic.AtomicInteger(0)
          val provider = new Provider("default", _ => { inits.incrementAndGet(); () })
          for {
            _       <- ZIO.attemptBlocking(api.setProviderAndWait(provider))
            bound   <- ZIO.attempt(Bound.of(api, Some(domain), provider))
            settled <- ProviderRegistration.registerAndAwait(api, Some(domain), bound, 5.seconds)
            held    <- ZIO.succeed(api.getProvider(domain) eq provider)
          } yield assertTrue(
            bound == Bound.Foreign(provider),
            settled == Settled.Ready,
            inits.get() == 1,
            held
          )
        }
      },
      test("it settles on the first read of the state the SDK holds for it: READY, STALE, ERROR") {
        def settleIn(report: Provider => Unit) = registered { (api, domain) =>
          val provider = new Provider("held", _ => ())
          for {
            _       <- ZIO.attemptBlocking(api.setProviderAndWait(domain, provider))
            _       <- ZIO.attemptBlocking(report(provider))
            settled <- ProviderRegistration.registerAndAwait(
              api,
              Some(domain),
              Bound.of(api, Some(domain), provider),
              5.seconds
            )
          } yield settled
        }
        for {
          ready <- settleIn(_ => ())
          stale <- settleIn(_.emitProviderStale(ProviderEventDetails.builder().build()).await())
          error <- settleIn(_.emitProviderError(ProviderEventDetails.builder().message("down").build()).await())
        } yield assertTrue(
          ready == Settled.Ready,
          stale == Settled.Stale,
          error match { case _: Settled.Error => true; case _ => false }
        )
      },
      test("still initializing, it settles once the SDK reports it initialized, and times out when it never does") {
        def initializing(timeout: Duration, release: Boolean) = registered { (api, domain) =>
          val gate     = new java.util.concurrent.CountDownLatch(1)
          val provider = new Provider("slow", _ => { gate.await(10, java.util.concurrent.TimeUnit.SECONDS); () })
          (for {
            _      <- ZIO.attemptBlocking(api.setProvider(domain, provider))
            bound  <- ZIO.attempt(Bound.of(api, Some(domain), provider))
            waiter <- ProviderRegistration.registerAndAwait(api, Some(domain), bound, timeout).either.fork
            _      <- ZIO.when(release)(ZIO.succeed(gate.countDown()))
            result <- waiter.join
          } yield (bound, result)).ensuring(ZIO.succeed(gate.countDown()))
        }
        for {
          released <- initializing(5.seconds, release = true)
          unbound  <- initializing(Duration.Infinity, release = true)
          never    <- initializing(300.millis, release = false)
        } yield assertTrue(
          unbound._2 == Right(Settled.Ready),
          released._1 == Bound.Foreign(released._1.sdk),
          released._2 == Right(Settled.Ready),
          never._2.left.toOption.exists(_.isInstanceOf[java.util.concurrent.TimeoutException])
        )
      },
      test("an EventProvider registered directly in another domain fails the build: the SDK holds its events") {
        registered { (api, domain) =>
          val provider = new Provider("elsewhere", _ => ())
          for {
            _     <- ZIO.attemptBlocking(api.setProviderAndWait(domain + "-direct", provider))
            built <- ZIO
              .scoped(
                zio.openfeature.internal.FeatureFlagsFactories
                  .buildAsync(
                    provider,
                    domain = Some(domain),
                    version = None,
                    initialHooks = Nil,
                    statusRef = None,
                    addShutdownFinalizer = false,
                    apiOverride = Some(api)
                  )
                  .unit
              )
              .either
          } yield assertTrue(
            built.left.toOption.exists(e =>
              e.isInstanceOf[IllegalStateException] && e.getMessage.contains("already attached")
            )
          )
        }
      }
    )
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
}
