package zio.openfeature
import zio.openfeature.internal.ProviderEvaluations

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderEvaluation,
  ProviderState,
  Value
}
import zio._
import zio.test._

object ProviderHotSwapSpec extends ZIOSpecDefault {

  private class SimpleProvider(name: String, flags: Map[String, Any]) extends EventProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata {
      override def getName: String = name
    }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()

    override def getBooleanEvaluation(
      key: String,
      defaultValue: java.lang.Boolean,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Boolean] = {
      val v = flags.get(key).map(_.asInstanceOf[Boolean]).getOrElse(defaultValue.booleanValue())
      ProviderEvaluations.of[java.lang.Boolean](v, "STATIC")
    }

    override def getStringEvaluation(
      key: String,
      defaultValue: String,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[String] = {
      val v = flags.get(key).map(_.toString).getOrElse(defaultValue)
      ProviderEvaluations.of[String](v, "STATIC")
    }

    override def getIntegerEvaluation(
      key: String,
      defaultValue: java.lang.Integer,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Integer] = {
      val v = flags.get(key).map(_.asInstanceOf[Int]).getOrElse(defaultValue.intValue())
      ProviderEvaluations.of[java.lang.Integer](v, "STATIC")
    }

    override def getDoubleEvaluation(
      key: String,
      defaultValue: java.lang.Double,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Double] = {
      val v = flags.get(key).map(_.asInstanceOf[Double]).getOrElse(defaultValue.doubleValue())
      ProviderEvaluations.of[java.lang.Double](v, "STATIC")
    }

    override def getObjectEvaluation(
      key: String,
      defaultValue: Value,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[Value] =
      ProviderEvaluations.of[Value](defaultValue, "STATIC")
  }

  private def buildWithDomain(provider: SimpleProvider): ZIO[Scope, Throwable, FeatureFlags] = {
    val api    = OpenFeatureAPI.createIsolated()
    val domain = s"test-swap-${java.util.UUID.randomUUID()}"
    for {
      ff <- FeatureFlags.build(
        provider,
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = false,
        apiOverride = Some(api)
      )
      // Wait for the Java SDK's initial PROVIDER_READY event to settle so it doesn't
      // race with subsequent setProvider calls in tests
      _ <- ZIO.attemptBlocking(Thread.sleep(50)).ignore
    } yield ff
  }

  private def buildNoDomain(provider: SimpleProvider): ZIO[Scope, Throwable, FeatureFlags] = {
    val api = OpenFeatureAPI.createIsolated()
    for {
      ff <- FeatureFlags.build(
        provider,
        domain = None,
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = false,
        apiOverride = Some(api)
      )
      _ <- ZIO.attemptBlocking(Thread.sleep(50)).ignore
    } yield ff
  }

  /** A provider whose `initialize()`, from its `staleFrom`-th call on, reports STALE and then waits for `release`. */
  private class StaleWhileInitializing(name: String, staleFrom: Int) extends SimpleProvider(name, Map("flag" -> true)) {
    val release                                             = new java.util.concurrent.CountDownLatch(1)
    private val calls                                       = new java.util.concurrent.atomic.AtomicInteger(0)
    override def initialize(ctx: OFEvaluationContext): Unit =
      if (calls.incrementAndGet() >= staleFrom) {
        emit(
          dev.openfeature.sdk.ProviderEvent.PROVIDER_STALE,
          dev.openfeature.sdk.ProviderEventDetails.builder().build()
        )
        release.await()
        ()
      }
  }

  /** Counts its `initialize()` calls and its `shutdown()`; `initialize()` waits for `release` while `blocking`. */
  private class Counting(name: String, blocking: Boolean) extends SimpleProvider(name, Map("flag" -> true)) {
    val release                                             = new java.util.concurrent.CountDownLatch(1)
    val inits                                               = new java.util.concurrent.atomic.AtomicInteger(0)
    val shutdowns                                           = new java.util.concurrent.atomic.AtomicInteger(0)
    override def initialize(ctx: OFEvaluationContext): Unit = {
      inits.incrementAndGet()
      if (blocking) release.await()
      ()
    }
    override def shutdown(): Unit = { shutdowns.incrementAndGet(); () }
  }

  /** [[Counting]] whose `initialize()` throws. */
  private class FailingCounting(name: String) extends Counting(name, blocking = false) {
    override def initialize(ctx: OFEvaluationContext): Unit = {
      inits.incrementAndGet()
      throw new RuntimeException(s"$name: cannot start")
    }
  }

  private def emitError(provider: EventProvider, message: String): UIO[Unit] =
    ZIO.succeed {
      val _ = provider.emitProviderError(
        dev.openfeature.sdk.ProviderEventDetails.builder().message(message).build()
      )
    }

  /** Holds every task submitted to it until released: as a fiber's blocking executor, it parks the provider call of an
    * evaluation that has already passed its readiness check.
    */
  final private class GatingExecutor(real: Executor) extends Executor {
    val held                   = new java.util.concurrent.ConcurrentLinkedQueue[Runnable]
    @volatile private var open = false
    def metrics(implicit unsafe: Unsafe): Option[zio.internal.ExecutionMetrics] = None
    def submit(r: Runnable)(implicit unsafe: Unsafe): Boolean                   =
      if (open) real.submit(r) else { held.add(r); true }
    def releaseAll(): Unit = {
      open = true
      var r = held.poll()
      while (r != null) { real.submit(r)(Unsafe.unsafe): Unit; r = held.poll() }
    }
  }

  /** A probe that holds the fiber reaching `point` until `release` completes, completing `held` first. */
  private def holdAt(
    point: zio.openfeature.internal.LifecycleProbe.Point,
    held: Promise[Nothing, Unit],
    release: Promise[Nothing, Unit]
  ): zio.openfeature.internal.LifecycleProbe =
    new zio.openfeature.internal.LifecycleProbe {
      def at(p: zio.openfeature.internal.LifecycleProbe.Point): UIO[Unit] =
        if (p == point) held.succeed(()) *> release.await else ZIO.unit
    }

  /** A build on an isolated API whose lifecycle probe is `probe`, handing back the API and domain. */
  private def buildProbed(
    provider: SimpleProvider,
    probe: zio.openfeature.internal.LifecycleProbe
  ): ZIO[Scope, Throwable, (FeatureFlags, OpenFeatureAPI, String)] = {
    val api    = OpenFeatureAPI.createIsolated()
    val domain = s"test-swap-${java.util.UUID.randomUUID()}"
    zio.openfeature.internal.FeatureFlagsFactories
      .buildWithTracking(
        provider,
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = true,
        apiOverride = Some(api),
        probe = probe
      )
      .map(ff => (ff, api, domain))
  }

  private def isStillInitializing(result: Either[FeatureFlagError, Unit]): Boolean = result match {
    case Left(FeatureFlagError.ProviderInitializationFailed(e: IllegalStateException)) =>
      e.getMessage.contains("still initializing")
    case _ => false
  }

  // Once released, the provider's first initialize() returns on the SDK's executor; until the instance reads that, a
  // reinstall is still refused, so retry the refusal for a moment.
  private def installOnceReturned(
    ff: FeatureFlags,
    provider: dev.openfeature.sdk.FeatureProvider
  ): UIO[Either[FeatureFlagError, Unit]] =
    ff.setProvider(provider)
      .retry(Schedule.spaced(50.millis) && Schedule.recurs(60) && Schedule.recurWhile[FeatureFlagError] {
        case FeatureFlagError.ProviderInitializationFailed(_: IllegalStateException) => true
        case _                                                                       => false
      })
      .either

  private def awaitTrue(cond: => Boolean): UIO[Boolean] =
    ZIO
      .succeed(cond)
      .repeat(Schedule.spaced(20.millis) && Schedule.recurWhile[Boolean](!_))
      .timeout(5.seconds)
      .map(_.isDefined)

  /** [[buildWithDomain]], also handing back the API and domain so a test can read the SDK's state for the slot. */
  private def buildExposed(
    provider: SimpleProvider,
    initTimeout: Duration = FeatureFlags.DefaultInitTimeout
  ): ZIO[Scope, Throwable, (FeatureFlags, OpenFeatureAPI, String)] = {
    val api    = OpenFeatureAPI.createIsolated()
    val domain = s"test-swap-${java.util.UUID.randomUUID()}"
    FeatureFlags
      .build(
        provider,
        domain = Some(domain),
        version = None,
        initialHooks = Nil,
        statusRef = None,
        addShutdownFinalizer = true,
        apiOverride = Some(api),
        initTimeout = initTimeout
      )
      .map(ff => (ff, api, domain))
  }

  // Build a client on a caller-provided API + domain so several clients can share one API instance (registry topology).
  private def buildOn(
    api: dev.openfeature.sdk.OpenFeatureAPI,
    domain: String,
    provider: SimpleProvider
  ): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      provider,
      domain = Some(domain),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = false,
      apiOverride = Some(api)
    )

  def spec = suite("Provider Hot-Swap")(
    test("setProvider of a provider already bound to another domain and in ERROR succeeds with status Error") {
      ZIO.scoped {
        val api = OpenFeatureAPI.createIsolated()
        val p   = new Counting("P", blocking = false)
        val q   = new Counting("Q", blocking = false)
        for {
          ffA     <- buildOn(api, "shared-a", p)
          ffB     <- buildOn(api, "shared-b", q)
          _       <- emitError(p, "P: backend down")
          _       <- awaitTrue(api.getClient("shared-a").getProviderState == ProviderState.ERROR)
          swap    <- ffB.setProvider(p).either
          status  <- ffB.providerStatus
          bound   <- ZIO.succeed(zio.openfeature.internal.ManagedProvider.unwrap(api.getProvider("shared-b")))
          qDown   <- awaitTrue(q.shutdowns.get() >= 1)
          _       <- ZIO.sleep(200.millis)
          servedA <- ffA.booleanDetails("flag", default = false).either
          counts = (p.inits.get(), p.shutdowns.get(), q.inits.get(), q.shutdowns.get())
        } yield assertTrue(
          swap.isRight,
          status == ProviderStatus.Error,
          bound eq p,
          qDown,
          counts == ((1, 0, 1, 1)),
          servedA.isRight
        )
      }
    } @@ TestAspect.withLiveClock,
    test("a sync build over a provider bound elsewhere and in ERROR fails without shutting that provider down") {
      ZIO.scoped {
        val api = OpenFeatureAPI.createIsolated()
        val p   = new Counting("P", blocking = false)
        for {
          _     <- buildOn(api, "shared-a", p)
          _     <- emitError(p, "P: backend down")
          _     <- awaitTrue(api.getClient("shared-a").getProviderState == ProviderState.ERROR)
          built <- ZIO.scoped(buildOn(api, "shared-b", p)).either
          _     <- ZIO.sleep(200.millis)
          shuts = p.shutdowns.get()
        } yield assertTrue(built.isLeft, shuts == 0)
      }
    } @@ TestAspect.withLiveClock,
    test("setProvider swaps to a new provider") {
      ZIO.scoped {
        val providerA = new SimpleProvider("ProviderA", Map("flag" -> true))
        val providerB = new SimpleProvider("ProviderB", Map("flag" -> false))
        for {
          ff <- buildWithDomain(providerA)
          v1 <- ff.boolean("flag", default = false)
          _  <- ff.setProvider(providerB)
          v2 <- ff.boolean("flag", default = false)
        } yield assertTrue(v1 == true) && assertTrue(v2 == false)
      }
    },
    test("setProvider updates provider metadata") {
      ZIO.scoped {
        val providerA = new SimpleProvider("ProviderA", Map.empty)
        val providerB = new SimpleProvider("ProviderB", Map.empty)
        for {
          ff <- buildWithDomain(providerA)
          m1 <- ff.providerMetadata
          _  <- ff.setProvider(providerB)
          m2 <- ff.providerMetadata
        } yield assertTrue(m1.name == "ProviderA") && assertTrue(m2.name == "ProviderB")
      }
    },
    test("setProvider preserves hooks") {
      ZIO.scoped {
        val providerA = new SimpleProvider("ProviderA", Map("flag" -> true))
        val providerB = new SimpleProvider("ProviderB", Map("flag" -> false))
        for {
          hookCalled <- Ref.make(false)
          hook = new FeatureHook {
            override def before(
              ctx: HookContext,
              hints: HookHints
            ): UIO[Option[EvaluationContext]] =
              hookCalled.set(true).as(None)
          }
          ff     <- buildWithDomain(providerA)
          _      <- ff.addHook(hook)
          _      <- ff.setProvider(providerB)
          _      <- ff.boolean("flag", default = false)
          called <- hookCalled.get
        } yield assertTrue(called)
      }
    },
    test("setProvider preserves client context") {
      ZIO.scoped {
        val providerA = new SimpleProvider("ProviderA", Map("flag" -> true))
        val providerB = new SimpleProvider("ProviderB", Map("flag" -> false))
        val ctx       = EvaluationContext.builder.targetingKey("user-123").build
        for {
          ff      <- buildWithDomain(providerA)
          _       <- ff.setClientContext(ctx)
          _       <- ff.setProvider(providerB)
          readCtx <- ff.clientContext
        } yield assertTrue(readCtx.targetingKey.contains("user-123"))
      }
    },
    test("a swap whose new provider reports STALE from inside initialize() ends Stale, as the SDK reports") {
      ZIO.scoped {
        for {
          built <- buildExposed(new SimpleProvider("A", Map("flag" -> true)))
          (ff, api, domain) = built
          next <- ZIO.acquireRelease(ZIO.succeed(new StaleWhileInitializing("B", staleFrom = 1)))(p =>
            ZIO.succeed(p.release.countDown())
          )
          swap   <- ff.setProvider(next).either
          status <- ff.providerStatus
          slot   <- ZIO.succeed(api.getClient(domain).getProviderState)
          value  <- ff.boolean("flag", default = false)
          // Once initialize() returns, the SDK reports READY, and so does the status.
          _     <- ZIO.succeed(next.release.countDown())
          ready <- ff.providerStatus.repeatUntil(_ == ProviderStatus.Ready).timeout(5.seconds)
        } yield assertTrue(
          swap.isRight,
          status == ProviderStatus.Stale,
          slot == ProviderState.STALE,
          value,
          ready.contains(ProviderStatus.Ready)
        )
      }
    } @@ TestAspect.withLiveClock,
    test("a rollback onto a previous provider that reports STALE while it initializes again ends Stale") {
      ZIO.scoped {
        for {
          previous <- ZIO.acquireRelease(ZIO.succeed(new StaleWhileInitializing("A", staleFrom = 2)))(p =>
            ZIO.succeed(p.release.countDown())
          )
          built <- buildExposed(previous)
          (ff, api, domain) = built
          failing           = new SimpleProvider("Failing", Map.empty) {
            override def initialize(ctx: OFEvaluationContext): Unit = throw new RuntimeException("cannot start")
          }
          swap   <- ff.setProvider(failing).either
          status <- ff.providerStatus
          slot   <- ZIO.succeed(api.getClient(domain).getProviderState)
          m      <- ff.providerMetadata
        } yield assertTrue(
          swap.left.toOption.exists(_.isInstanceOf[FeatureFlagError.ProviderInitializationFailed]),
          status == ProviderStatus.Stale,
          slot == ProviderState.STALE,
          m.name == "A"
        )
      }
    } @@ TestAspect.withLiveClock,
    test("a state the SDK reaches while the swap's gate is up is applied once the gate drops") {
      ZIO.scoped {
        for {
          // Hold the swap just before it applies its outcome: the gate is still up there.
          held    <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          _       <- ZIO.addFinalizer(release.succeed(()))
          probe = new zio.openfeature.internal.LifecycleProbe {
            def at(point: zio.openfeature.internal.LifecycleProbe.Point): UIO[Unit] = point match {
              case zio.openfeature.internal.LifecycleProbe.Point
                    .ApplySignal(zio.openfeature.internal.ProviderStatusMachine.Signal.SwapSucceeded(_)) =>
                held.succeed(()) *> release.await
              case _ => ZIO.unit
            }
          }
          api    = OpenFeatureAPI.createIsolated()
          domain = s"test-swap-${java.util.UUID.randomUUID()}"
          ff <- zio.openfeature.internal.FeatureFlagsFactories.buildWithTracking(
            new SimpleProvider("A", Map("flag" -> true)),
            domain = Some(domain),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = true,
            apiOverride = Some(api),
            probe = probe
          )
          next <- ZIO.acquireRelease(ZIO.succeed(new StaleWhileInitializing("B", staleFrom = 1)))(p =>
            ZIO.succeed(p.release.countDown())
          )
          swap <- ff.setProvider(next).either.fork
          _    <- held.await.timeout(5.seconds)
          // initialize() returns while the swap is held: the SDK now reports READY. The bridge publishes that READY
          // right after its status write, which the swap's gate turned into a no-op; once it is published, the drop
          // has happened.
          fromB <- Promise.make[Nothing, Unit]
          _     <- ZIO.acquireRelease(
            ff.on(ProviderEventType.Ready, e => ZIO.when(e.metadata.name == "B")(fromB.succeed(())).unit)
          )(cancel => cancel)
          _         <- ZIO.succeed(next.release.countDown())
          delivered <- fromB.await.timeout(5.seconds)
          ready     <- ZIO.succeed(api.getClient(domain).getProviderState)
          _         <- release.succeed(())
          result    <- swap.join
          status    <- ff.providerStatus
        } yield assertTrue(
          result.isRight,
          delivered.isDefined,
          ready == ProviderState.READY,
          status == ProviderStatus.Ready
        )
      }
    } @@ TestAspect.withLiveClock,
    test("an ERROR the new provider reports right after initializing is its status, not a failed swap") {
      ZIO.scoped {
        val a = new Counting("A", blocking = false)
        val b = new Counting("B", blocking = false)
        for {
          held    <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          _       <- ZIO.addFinalizer(release.succeed(()))
          built   <- buildProbed(a, holdAt(zio.openfeature.internal.LifecycleProbe.Point.SdkBind, held, release))
          (ff, api, domain) = built
          swap <- ff.setProvider(b).either.fork
          // Hold the swap between its registration and its wait: B initializes, the SDK reports READY and shuts A down,
          // and B reports an error before the wait reads anything.
          _      <- held.await.timeout(5.seconds)
          ready  <- awaitTrue(api.getClient(domain).getProviderState == ProviderState.READY)
          _      <- emitError(b, "B: backend lost")
          errord <- awaitTrue(api.getClient(domain).getProviderState == ProviderState.ERROR)
          _      <- release.succeed(())
          result <- swap.join
          status <- ff.providerStatus
          m      <- ff.providerMetadata
          bound  <- ZIO.succeed(zio.openfeature.internal.ManagedProvider.unwrap(api.getProvider(domain)))
          aDown  <- awaitTrue(a.shutdowns.get() >= 1)
          _      <- ZIO.sleep(300.millis)
          served <- ff.boolean("flag", default = false)
          // Read now: the scope's close shuts the serving provider down before a lazy assertion would read it.
          counts = (a.inits.get(), a.shutdowns.get(), b.shutdowns.get())
        } yield assertTrue(
          ready,
          errord,
          result.isRight,
          status == ProviderStatus.Error,
          m.name == "B",
          bound eq b,
          aDown,
          counts == ((1, 1, 0)),
          served
        )
      }
    } @@ TestAspect.withLiveClock,
    test("an ERROR a provider without a name reports right after initializing is its status, not a failed swap") {
      ZIO.scoped {
        val a = new Counting("A", blocking = false)
        val b = new Counting(null, blocking = false)
        for {
          held    <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          _       <- ZIO.addFinalizer(release.succeed(()))
          built   <- buildProbed(a, holdAt(zio.openfeature.internal.LifecycleProbe.Point.SdkBind, held, release))
          (ff, api, domain) = built
          swap   <- ff.setProvider(b).either.fork
          _      <- held.await.timeout(5.seconds)
          ready  <- awaitTrue(api.getClient(domain).getProviderState == ProviderState.READY)
          _      <- emitError(b, "B: backend lost")
          errord <- awaitTrue(api.getClient(domain).getProviderState == ProviderState.ERROR)
          _      <- release.succeed(())
          result <- swap.join
          status <- ff.providerStatus
          bound  <- ZIO.succeed(zio.openfeature.internal.ManagedProvider.unwrap(api.getProvider(domain)))
          aDown  <- awaitTrue(a.shutdowns.get() >= 1)
          _      <- ZIO.sleep(300.millis)
          // Read now: the scope's close shuts the serving provider down before a lazy assertion would read it.
          counts = (a.inits.get(), a.shutdowns.get(), b.shutdowns.get())
        } yield assertTrue(
          ready,
          errord,
          result.isRight,
          status == ProviderStatus.Error,
          bound eq b,
          aDown,
          counts == ((1, 1, 0))
        )
      }
    } @@ TestAspect.withLiveClock,
    test("an ERROR the restored provider reports right after initializing leaves the rollback successful") {
      ZIO.scoped {
        val a = new Counting("A", blocking = false)
        val c = new FailingCounting("C")
        for {
          held    <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          _       <- ZIO.addFinalizer(release.succeed(()))
          built   <- buildProbed(a, holdAt(zio.openfeature.internal.LifecycleProbe.Point.RollbackBind, held, release))
          (ff, api, domain) = built
          swap <- ff.setProvider(c).either.fork
          // C fails; the rollback registers A again. Hold it there: A initializes again, the SDK shuts C down, and A
          // reports an error before the rollback's wait reads anything.
          _      <- held.await.timeout(5.seconds)
          ready  <- awaitTrue(api.getClient(domain).getProviderState == ProviderState.READY)
          _      <- emitError(a, "A: backend lost")
          errord <- awaitTrue(api.getClient(domain).getProviderState == ProviderState.ERROR)
          _      <- release.succeed(())
          result <- swap.join
          status <- ff.providerStatus
          m      <- ff.providerMetadata
          bound  <- ZIO.succeed(zio.openfeature.internal.ManagedProvider.unwrap(api.getProvider(domain)))
          cDown  <- awaitTrue(c.shutdowns.get() >= 1)
          _      <- ZIO.sleep(300.millis)
          // Read now: the scope's close shuts the serving provider down before a lazy assertion would read it.
          counts = (a.inits.get(), a.shutdowns.get(), c.shutdowns.get())
        } yield assertTrue(
          ready,
          errord,
          result.left.toOption.exists(_.isInstanceOf[FeatureFlagError.ProviderInitializationFailed]),
          status == ProviderStatus.Error,
          m.name == "A",
          bound eq a,
          cDown,
          counts == ((2, 0, 1))
        )
      }
    } @@ TestAspect.withLiveClock,
    test("an ERROR a timed-out swap's provider reports right after initializing leaves its predecessor to the SDK") {
      ZIO.scoped {
        val a = new Counting("A", blocking = false)
        for {
          b <- ZIO.acquireRelease(ZIO.succeed(new Counting("B", blocking = true)))(p =>
            ZIO.succeed(p.release.countDown())
          )
          built <- buildExposed(a, initTimeout = 300.millis)
          (ff, api, domain) = built
          timedOut <- ff.setProvider(b).either
          // B initializes late, the SDK shuts A down, and B reports an error before the watcher's next look.
          _      <- ZIO.succeed(b.release.countDown())
          ready  <- awaitTrue(api.getClient(domain).getProviderState == ProviderState.READY)
          _      <- emitError(b, "B: backend lost")
          aDown  <- awaitTrue(a.shutdowns.get() >= 1)
          _      <- ZIO.sleep(1.second)
          status <- ff.providerStatus
          shuts = a.shutdowns.get()
        } yield assertTrue(
          timedOut.isLeft,
          ready,
          aDown,
          shuts == 1,
          status == ProviderStatus.Error
        )
      }
    } @@ TestAspect.withLiveClock,
    test("an evaluation that passed its check before a failing swap is not served by the failed provider") {
      ZIO.scoped {
        val a    = new SimpleProvider("A", Map("who" -> "A"))
        val gate = new GatingExecutor(Runtime.defaultBlockingExecutor)
        for {
          held    <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          _       <- ZIO.addFinalizer(release.succeed(()) *> ZIO.succeed(gate.releaseAll()))
          built   <- buildProbed(a, holdAt(zio.openfeature.internal.LifecycleProbe.Point.SwapRollback, held, release))
          (ff, _, _) = built
          // The evaluation passes its readiness check, then its provider call is parked.
          eval   <- FiberRef.currentBlockingExecutor.locally(gate)(ff.string("who", "default")).either.fork
          parked <- awaitTrue(!gate.held.isEmpty)
          // The swap registers B, whose initialize() throws, and is held before it rolls back: B is bound and failed.
          swap <- ff
            .setProvider(new SimpleProvider("B", Map("who" -> "B")) {
              override def initialize(ctx: OFEvaluationContext): Unit = throw new RuntimeException("B: cannot start")
            })
            .either
            .fork
          reached <- held.await.timeout(5.seconds)
          // An evaluation that starts now, while the rollback has not yet put A back, passes the check (the status
          // is Error) and must not be answered by B either.
          fresh  <- ff.string("who", "default").either
          _      <- ZIO.succeed(gate.releaseAll())
          result <- eval.join
          _      <- release.succeed(())
          _      <- swap.join
          after  <- ff.string("who", "default")
        } yield assertTrue(
          parked,
          reached.isDefined,
          fresh == Left(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady)),
          result == Left(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady)),
          after == "A"
        )
      }
    } @@ TestAspect.withLiveClock,
    test("the async build's provider is refused while its initialize() runs, after a failed swap retired it") {
      ZIO.scoped {
        val api    = OpenFeatureAPI.createIsolated()
        val domain = s"test-swap-${java.util.UUID.randomUUID()}"
        val c      = new FailingCounting("C")
        for {
          a <- ZIO.acquireRelease(ZIO.succeed(new Counting("A", blocking = true)))(p =>
            ZIO.succeed(p.release.countDown())
          )
          ff <- FeatureFlags.buildAsync(
            a,
            domain = Some(domain),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = true,
            apiOverride = Some(api),
            initTimeout = 20.seconds
          )
          _       <- awaitTrue(a.inits.get() == 1)
          failed  <- ff.setProvider(c).either
          retired <- awaitTrue(a.shutdowns.get() >= 1)
          refused <- ff.setProvider(a).either
          during = a.inits.get()
          _     <- ZIO.succeed(a.release.countDown())
          again <- installOnceReturned(ff, a)
        } yield assertTrue(
          failed.isLeft,
          retired,
          isStillInitializing(refused),
          during == 1,
          again.isRight,
          a.inits.get() == 2
        )
      }
    } @@ TestAspect.withLiveClock,
    test("a timed-out swap's provider is refused while its initialize() runs, after a later swap succeeded") {
      ZIO.scoped {
        val a = new Counting("A", blocking = false)
        for {
          b <- ZIO.acquireRelease(ZIO.succeed(new Counting("B", blocking = true)))(p =>
            ZIO.succeed(p.release.countDown())
          )
          built <- buildExposed(a, initTimeout = 500.millis)
          (ff, _, _) = built
          timedOut <- ff.setProvider(b).either
          next     <- ff.setProvider(new SimpleProvider("D", Map("flag" -> true))).either
          status   <- ff.providerStatus
          refused  <- ff.setProvider(b).either
          during = b.inits.get()
          _     <- ZIO.succeed(b.release.countDown())
          again <- installOnceReturned(ff, b)
        } yield assertTrue(
          timedOut.isLeft,
          next.isRight,
          status == ProviderStatus.Ready,
          isStillInitializing(refused),
          during == 1,
          again.isRight,
          b.inits.get() == 2
        )
      }
    } @@ TestAspect.withLiveClock,
    test("the provider a timed-out swap replaced is refused until the swap's provider settles") {
      ZIO.scoped {
        val a = new Counting("A", blocking = false)
        for {
          b <- ZIO.acquireRelease(ZIO.succeed(new Counting("B", blocking = true)))(p =>
            ZIO.succeed(p.release.countDown())
          )
          built <- buildExposed(a, initTimeout = 500.millis)
          (ff, _, _) = built
          timedOut <- ff.setProvider(b).either
          refused  <- ff.setProvider(a).either
          during = a.inits.get()
          _     <- ZIO.succeed(b.release.countDown())
          again <- installOnceReturned(ff, a)
          bDown <- awaitTrue(b.shutdowns.get() >= 1)
          shut = (a.shutdowns.get(), b.shutdowns.get())
        } yield assertTrue(
          timedOut.isLeft,
          bDown,
          shut == ((1, 1)), // A once, by the SDK when B became ready; B once, replaced by A again
          refused match {
            case Left(FeatureFlagError.ProviderInitializationFailed(e: IllegalStateException)) =>
              e.getMessage.contains("cannot be installed again until 'B'")
            case _ => false
          },
          during == 1,
          again.isRight,
          a.inits.get() == 2
        )
      }
    } @@ TestAspect.withLiveClock,
    test(
      "an instance that shares its API refuses the provider a timed-out swap replaced until that swap's provider returns"
    ) {
      ZIO.scoped {
        val api = OpenFeatureAPI.createIsolated()
        val a   = new Counting("A", blocking = false)
        for {
          _ <- ZIO.addFinalizer(ZIO.attemptBlocking(api.shutdown()).ignore)
          b <- ZIO.acquireRelease(ZIO.succeed(new Counting("B", blocking = true)))(p =>
            ZIO.succeed(p.release.countDown())
          )
          ff <- FeatureFlags.build(
            a,
            domain = Some(s"shared-${java.util.UUID.randomUUID()}"),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = false,
            apiOverride = Some(api),
            initTimeout = 500.millis
          )
          timedOut <- ff.setProvider(b).either
          refused  <- ff.setProvider(a).either
          during = (a.inits.get(), a.shutdowns.get())
          _     <- ZIO.succeed(b.release.countDown())
          again <- installOnceReturned(ff, a)
          bDown <- awaitTrue(b.shutdowns.get() >= 1)
          // The SDK shuts A's old registration down on its executor once B returns: wait for it as for B's.
          aDown <- awaitTrue(a.shutdowns.get() >= 1)
          _     <- ZIO.sleep(200.millis)
          shut = (a.shutdowns.get(), b.shutdowns.get())
        } yield assertTrue(
          timedOut.isLeft,
          refused match {
            case Left(FeatureFlagError.ProviderInitializationFailed(e: IllegalStateException)) =>
              e.getMessage.contains("cannot be installed again until 'B', which replaced it")
            case _ => false
          },
          during == ((1, 0)),
          again.isRight,
          a.inits.get() == 2,
          bDown,
          aDown,
          shut == ((1, 1)) // A once, by the SDK when B returned; B once, replaced by A again
        )
      }
    } @@ TestAspect.withLiveClock,
    test("after a rollback that timed out, the failed provider is refused until the restored provider returns") {
      ZIO.scoped {
        val reinit = new java.util.concurrent.CountDownLatch(1)
        val aInits = new java.util.concurrent.atomic.AtomicInteger(0)
        // A starts at once when built, and blocks when the rollback registers it again.
        val a = new SimpleProvider("A", Map("flag" -> true)) {
          override def initialize(ctx: OFEvaluationContext): Unit =
            if (aInits.incrementAndGet() >= 2) { reinit.await(10, java.util.concurrent.TimeUnit.SECONDS); () }
            else ()
        }
        val fInits     = new java.util.concurrent.atomic.AtomicInteger(0)
        val fShutdowns = new java.util.concurrent.atomic.AtomicInteger(0)
        // F fails its first start, so the swap rolls back onto A, and starts on later ones.
        val f = new SimpleProvider("F", Map("flag" -> true)) {
          override def initialize(ctx: OFEvaluationContext): Unit =
            if (fInits.incrementAndGet() == 1) throw new RuntimeException("first start fails") else ()
          override def shutdown(): Unit = { fShutdowns.incrementAndGet(); () }
        }
        for {
          _     <- ZIO.addFinalizer(ZIO.succeed(reinit.countDown()))
          built <- buildExposed(a, initTimeout = 500.millis)
          (ff, _, _) = built
          failed  <- ff.setProvider(f).either
          refused <- ff.setProvider(f).either
          _       <- ZIO.succeed(reinit.countDown())
          again   <- installOnceReturned(ff, f)
        } yield assertTrue(
          failed.isLeft,
          refused match {
            case Left(FeatureFlagError.ProviderInitializationFailed(e: IllegalStateException)) =>
              e.getMessage.contains("cannot be installed again until 'A', which replaced it")
            case _ => false
          },
          again.isRight,
          fInits.get() == 2
        )
      }
    } @@ TestAspect.withLiveClock,
    test("a timed-out swap's provider is refused while its initialize() runs, after a later swap failed") {
      ZIO.scoped {
        val a = new Counting("A", blocking = false)
        for {
          b <- ZIO.acquireRelease(ZIO.succeed(new Counting("B", blocking = true)))(p =>
            ZIO.succeed(p.release.countDown())
          )
          built <- buildExposed(a, initTimeout = 500.millis)
          (ff, _, _) = built
          timedOut <- ff.setProvider(b).either
          failed   <- ff.setProvider(new FailingCounting("C")).either
          // C's failure leaves B, still initializing, an orphan of C's registration: on C's error B is shut down and
          // deregistered, which is when it used to become installable again.
          settled <- awaitTrue(b.shutdowns.get() >= 1)
          refused <- ff.setProvider(b).either
          during = b.inits.get()
        } yield assertTrue(timedOut.isLeft, failed.isLeft, settled, isStillInitializing(refused), during == 1)
      }
    } @@ TestAspect.withLiveClock,
    test("a failed swap that never bound its provider leaves a still-initializing previous provider NotReady") {
      ZIO.scoped {
        val api    = OpenFeatureAPI.createIsolated()
        val domain = s"test-swap-${java.util.UUID.randomUUID()}"
        val b      = new Counting("B", blocking = false)
        for {
          a <- ZIO.acquireRelease(ZIO.succeed(new Counting("A", blocking = true)))(p =>
            ZIO.succeed(p.release.countDown())
          )
          ff <- FeatureFlags.buildAsync(
            a,
            domain = Some(domain),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = true,
            apiOverride = Some(api),
            initTimeout = 1.second
          )
          started <- awaitTrue(a.inits.get() == 1)
          // Attached elsewhere already: the SDK's registration throws before it binds B, so the slot keeps A.
          _      <- ZIO.succeed(dev.openfeature.sdk.EventProviderAccess.attach(b, (_, _) => ()))
          failed <- ff.setProvider(b).either
          status <- ff.providerStatus
          eval   <- ff.boolean("flag", default = false).either
          bound  = zio.openfeature.internal.ManagedProvider.unwrap(api.getProvider(domain))
          binits = b.inits.get()
          // The init watchdog is still armed: A, still initializing past initTimeout, goes Fatal and is shut down.
          fatal <- ff.providerStatus
            .repeat(Schedule.spaced(20.millis) && Schedule.recurWhile[ProviderStatus](_ != ProviderStatus.Fatal))
            .timeout(5.seconds)
            .map(_.isDefined)
          down <- awaitTrue(a.shutdowns.get() >= 1)
        } yield assertTrue(
          started,
          failed.isLeft,
          status == ProviderStatus.NotReady,
          eval == Left(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady)),
          bound eq a,
          binits == 0,
          fatal,
          down
        )
      }
    } @@ TestAspect.withLiveClock,
    test("a swap refused before binding, after a timed-out swap, keeps the timed-out provider and its Error status") {
      ZIO.scoped {
        val a = new Counting("A", blocking = false)
        val c = new Counting("C", blocking = false)
        for {
          b <- ZIO.acquireRelease(ZIO.succeed(new Counting("B", blocking = true)))(p =>
            ZIO.succeed(p.release.countDown())
          )
          built <- buildExposed(a, initTimeout = 300.millis)
          (ff, api, domain) = built
          timedOut <- ff.setProvider(b).either
          _        <- ZIO.succeed(dev.openfeature.sdk.EventProviderAccess.attach(c, (_, _) => ()))
          refused  <- ff.setProvider(c).either
          status   <- ff.providerStatus
          meta     <- ff.providerMetadata
          bound  = zio.openfeature.internal.ManagedProvider.unwrap(api.getProvider(domain))
          counts = (b.inits.get(), c.inits.get())
        } yield assertTrue(
          timedOut.isLeft,
          refused.isLeft,
          status == ProviderStatus.Error,
          meta.name == "B",
          bound eq b,
          counts == ((1, 0))
        )
      }
    } @@ TestAspect.withLiveClock,
    test("a swap refused before binding leaves an async provider whose initialize() threw to the init watchdog") {
      ZIO.scoped {
        val api    = OpenFeatureAPI.createIsolated()
        val domain = s"test-swap-${java.util.UUID.randomUUID()}"
        val a      = new FailingCounting("A")
        val b      = new Counting("B", blocking = false)
        for {
          ff <- FeatureFlags.buildAsync(
            a,
            domain = Some(domain),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = true,
            apiOverride = Some(api),
            initTimeout = 1.second
          )
          erred <- ff.providerStatus
            .repeat(Schedule.spaced(20.millis) && Schedule.recurWhile[ProviderStatus](_ != ProviderStatus.Error))
            .timeout(5.seconds)
            .map(_.isDefined)
          _      <- ZIO.succeed(dev.openfeature.sdk.EventProviderAccess.attach(b, (_, _) => ()))
          failed <- ff.setProvider(b).either
          status <- ff.providerStatus
          // Never ready, so the watchdog still makes it Fatal at initTimeout.
          fatal <- ff.providerStatus
            .repeat(Schedule.spaced(20.millis) && Schedule.recurWhile[ProviderStatus](_ != ProviderStatus.Fatal))
            .timeout(5.seconds)
            .map(_.isDefined)
        } yield assertTrue(erred, failed.isLeft, status == ProviderStatus.Error, fatal)
      }
    } @@ TestAspect.withLiveClock,
    test("an init-watchdog deadline that falls inside a refused swap still makes a hung provider Fatal") {
      ZIO.scoped {
        val api    = OpenFeatureAPI.createIsolated()
        val domain = s"test-swap-${java.util.UUID.randomUUID()}"
        val b      = new Counting("B", blocking = false)
        for {
          a <- ZIO.acquireRelease(ZIO.succeed(new Counting("A", blocking = true)))(p =>
            ZIO.succeed(p.release.countDown())
          )
          held    <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          _       <- ZIO.addFinalizer(release.succeed(()))
          ff      <- zio.openfeature.internal.FeatureFlagsFactories.buildAsyncWithTracking(
            a,
            domain = Some(domain),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = true,
            apiOverride = Some(api),
            initTimeout = 300.millis,
            probe = holdAt(zio.openfeature.internal.LifecycleProbe.Point.SwapRollback, held, release)
          )
          // The SDK refuses to register a wrapper attached elsewhere: the swap fails at its registration.
          _ <- ZIO.succeed(
            dev.openfeature.sdk.EventProviderAccess.attach(zio.openfeature.internal.ManagedProvider(b), (_, _) => ())
          )
          swap <- ff.setProvider(b).either.fork
          // Held with its gate up past the watchdog's deadline, which the gate drops.
          reached <- held.await.timeout(5.seconds)
          _       <- ZIO.sleep(600.millis)
          _       <- release.succeed(())
          _       <- swap.join
          fatal   <- ff.providerStatus
            .repeat(Schedule.spaced(20.millis) && Schedule.recurWhile[ProviderStatus](_ != ProviderStatus.Fatal))
            .timeout(5.seconds)
            .map(_.isDefined)
          down <- awaitTrue(a.shutdowns.get() >= 1)
        } yield assertTrue(reached.isDefined, fatal, down)
      }
    } @@ TestAspect.withLiveClock,
    test("a failed swap on an async-built instance does not initialize again a provider still initializing") {
      ZIO.scoped {
        val api    = OpenFeatureAPI.createIsolated()
        val domain = s"test-swap-${java.util.UUID.randomUUID()}"
        val c      = new FailingCounting("C")
        for {
          a <- ZIO.acquireRelease(ZIO.succeed(new Counting("A", blocking = true)))(p =>
            ZIO.succeed(p.release.countDown())
          )
          ff <- FeatureFlags.buildAsync(
            a,
            domain = Some(domain),
            version = None,
            initialHooks = Nil,
            statusRef = None,
            addShutdownFinalizer = true,
            apiOverride = Some(api),
            initTimeout = 10.seconds
          )
          started <- awaitTrue(a.inits.get() == 1)
          failed  <- ff.setProvider(c).either
          status  <- ff.providerStatus
          bound = zio.openfeature.internal.ManagedProvider.unwrap(api.getProvider(domain))
          inits = a.inits.get()
          // The watcher of the provider C replaced sees C fail and shuts A down; A is never initialized again.
          aDown <- awaitTrue(a.shutdowns.get() >= 1)
          _     <- ZIO.succeed(a.release.countDown())
          _     <- ZIO.sleep(200.millis)
          initsAfter = a.inits.get()
          recovered <- ff.setProvider(new SimpleProvider("D", Map("flag" -> true))).either
          served    <- ff.boolean("flag", default = false)
        } yield assertTrue(
          started,
          failed.isLeft,
          inits == 1,
          status == ProviderStatus.Error,
          bound eq c,
          aDown,
          initsAfter == 1,
          recovered.isRight,
          served
        )
      }
    } @@ TestAspect.withLiveClock,
    test("a failed swap does not initialize again a provider whose initialize() has not returned") {
      ZIO.scoped {
        val a = new Counting("A", blocking = false)
        val c = new SimpleProvider("C", Map.empty) {
          override def initialize(ctx: OFEvaluationContext): Unit = throw new RuntimeException("cannot start")
        }
        for {
          b <- ZIO.acquireRelease(ZIO.succeed(new Counting("B", blocking = true)))(p =>
            ZIO.succeed(p.release.countDown())
          )
          built <- buildExposed(a, initTimeout = 300.millis)
          (ff, api, domain) = built
          timedOut <- ff.setProvider(b).either
          failed   <- ff.setProvider(c).either
          status   <- ff.providerStatus
          m        <- ff.providerMetadata
          bound    <- ZIO.succeed(zio.openfeature.internal.ManagedProvider.unwrap(api.getProvider(domain)))
          bDown    <- awaitTrue(b.shutdowns.get() >= 1)
          // B's first initialize() returning later changes nothing, and the provider B replaced is then shut down.
          _     <- ZIO.succeed(b.release.countDown())
          aDown <- awaitTrue(a.shutdowns.get() >= 1)
          after <- ff.providerStatus
          d = new SimpleProvider("D", Map("flag" -> true))
          next   <- ff.setProvider(d).either
          final_ <- ff.providerStatus
          served <- ff.boolean("flag", default = false)
        } yield assertTrue(
          timedOut.left.toOption.exists {
            case FeatureFlagError.ProviderInitializationFailed(_: java.util.concurrent.TimeoutException) => true
            case _                                                                                       => false
          },
          failed.left.toOption.exists(_.isInstanceOf[FeatureFlagError.ProviderInitializationFailed]),
          b.inits.get() == 1,
          status == ProviderStatus.Error,
          m.name == "C",
          bound eq c,
          bDown,
          aDown,
          after == ProviderStatus.Error,
          next.isRight,
          final_ == ProviderStatus.Ready,
          served
        )
      }
    } @@ TestAspect.withLiveClock,
    test("a timed-out provider installed again later is rolled back onto, not mistaken for a pending one") {
      ZIO.scoped {
        val a = new Counting("A", blocking = false)
        val d = new SimpleProvider("D", Map("flag" -> true))
        val c = new SimpleProvider("C", Map.empty) {
          override def initialize(ctx: OFEvaluationContext): Unit = throw new RuntimeException("cannot start")
        }
        for {
          b <- ZIO.acquireRelease(ZIO.succeed(new Counting("B", blocking = true)))(p =>
            ZIO.succeed(p.release.countDown())
          )
          built <- buildExposed(a, initTimeout = 300.millis)
          (ff, _, _) = built
          _ <- ff.setProvider(b).either
          // B finishes late; a swap to D then gets B's registration shut down by the SDK.
          _      <- ZIO.succeed(b.release.countDown())
          _      <- ff.providerStatus.repeatUntil(_ == ProviderStatus.Ready).timeout(5.seconds)
          _      <- ff.setProvider(d)
          bDown  <- awaitTrue(b.shutdowns.get() >= 1)
          again  <- ff.setProvider(b).either
          failed <- ff.setProvider(c).either
          status <- ff.providerStatus
          m      <- ff.providerMetadata
          // Read now: the owning API shuts the serving B down once more when the scope closes.
          bShutdowns <- ZIO.succeed(b.shutdowns.get())
        } yield assertTrue(
          bDown,
          again.isRight,
          failed.isLeft,
          status == ProviderStatus.Ready,
          m.name == "B",
          bShutdowns == 1
        )
      }
    } @@ TestAspect.withLiveClock,
    test("a failed swap still rolls back onto a timed-out provider whose initialization has settled") {
      ZIO.scoped {
        val a = new Counting("A", blocking = false)
        val c = new SimpleProvider("C", Map.empty) {
          override def initialize(ctx: OFEvaluationContext): Unit = throw new RuntimeException("cannot start")
        }
        for {
          b <- ZIO.acquireRelease(ZIO.succeed(new Counting("B", blocking = true)))(p =>
            ZIO.succeed(p.release.countDown())
          )
          built <- buildExposed(a, initTimeout = 300.millis)
          (ff, _, _) = built
          _      <- ff.setProvider(b).either
          _      <- ZIO.succeed(b.release.countDown())
          ready  <- ff.providerStatus.repeatUntil(_ == ProviderStatus.Ready).timeout(5.seconds)
          failed <- ff.setProvider(c).either
          status <- ff.providerStatus
          m      <- ff.providerMetadata
          served <- ff.boolean("flag", default = false)
        } yield assertTrue(
          ready.contains(ProviderStatus.Ready),
          failed.isLeft,
          status == ProviderStatus.Ready,
          m.name == "B",
          served,
          // The rollback initializes B again, after its first initialize() returned.
          b.inits.get() == 2
        )
      }
    } @@ TestAspect.withLiveClock,
    test("setProvider transitions status to Ready after successful swap") {
      ZIO.scoped {
        val providerA = new SimpleProvider("ProviderA", Map.empty)
        val providerB = new SimpleProvider("ProviderB", Map.empty)
        for {
          ff <- buildWithDomain(providerA)
          s1 <- ff.providerStatus
          _  <- ff.setProvider(providerB)
          s2 <- ff.providerStatus
        } yield assertTrue(s1 == ProviderStatus.Ready) && assertTrue(s2 == ProviderStatus.Ready)
      }
    },
    test("setProvider preserves global context") {
      ZIO.scoped {
        val providerA = new SimpleProvider("ProviderA", Map("flag" -> true))
        val providerB = new SimpleProvider("ProviderB", Map("flag" -> false))
        val ctx       = EvaluationContext.builder.targetingKey("global-key").build
        for {
          ff      <- buildWithDomain(providerA)
          _       <- ff.setGlobalContext(ctx)
          _       <- ff.setProvider(providerB)
          readCtx <- ff.globalContext
        } yield assertTrue(readCtx.targetingKey.contains("global-key"))
      }
    },
    test("multiple swaps work correctly") {
      ZIO.scoped {
        val providerA = new SimpleProvider("A", Map("flag" -> "a"))
        val providerB = new SimpleProvider("B", Map("flag" -> "b"))
        val providerC = new SimpleProvider("C", Map("flag" -> "c"))
        for {
          ff <- buildWithDomain(providerA)
          v1 <- ff.string("flag", default = "")
          _  <- ff.setProvider(providerB)
          v2 <- ff.string("flag", default = "")
          _  <- ff.setProvider(providerC)
          v3 <- ff.string("flag", default = "")
          m  <- ff.providerMetadata
        } yield assertTrue(v1 == "a") && assertTrue(v2 == "b") && assertTrue(v3 == "c") &&
          assertTrue(m.name == "C")
      }
    },
    test("all flag types work after swap") {
      ZIO.scoped {
        val providerA = new SimpleProvider("A", Map("b" -> true))
        val providerB = new SimpleProvider("B", Map("b" -> false, "s" -> "hello", "i" -> 42, "d" -> 3.14))
        for {
          ff <- buildWithDomain(providerA)
          _  <- ff.setProvider(providerB)
          vb <- ff.boolean("b", default = true)
          vs <- ff.string("s", default = "")
          vi <- ff.int("i", default = 0)
          vd <- ff.double("d", default = 0.0)
        } yield assertTrue(vb == false) && assertTrue(vs == "hello") &&
          assertTrue(vi == 42) && assertTrue(vd == 3.14)
      }
    },
    test("failed swap rolls back routing and status to the previous provider") {
      // The core regression: after a failed swap, evaluations must route back to provider A (not the failed
      // provider), status must be Ready, and metadata must name A — the SDK client routing is reconciled, not just
      // the internal providerRef.
      ZIO.scoped {
        val providerA       = new SimpleProvider("A", Map("flag" -> true))
        val failingProvider = new SimpleProvider("Failing", Map("flag" -> false)) {
          override def initialize(ctx: OFEvaluationContext): Unit =
            throw new RuntimeException("Initialization failed")
        }
        for {
          ff     <- buildWithDomain(providerA)
          result <- ff.setProvider(failingProvider).either
          v      <- ff.boolean("flag", default = false)
          status <- ff.providerStatus
          m      <- ff.providerMetadata
        } yield assertTrue(result.isLeft) &&
          assertTrue(result.left.toOption.get.isInstanceOf[FeatureFlagError.ProviderInitializationFailed]) &&
          assertTrue(v == true) &&
          assertTrue(status == ProviderStatus.Ready) &&
          assertTrue(m.name == "A")
      }
    },
    test("stale PROVIDER_READY from the old provider does not mark status Ready mid-swap") {
      // The old provider emits READY while the new provider's initialize() is still blocked. Without the
      // swap-in-progress guard, the event bridge would flip NotReady => Ready while the swap is in flight.
      ZIO.scoped {
        val initGate = new java.util.concurrent.CountDownLatch(1)
        class EmittingProvider extends SimpleProvider("A", Map("flag" -> true)) {
          def fireReady(): Unit =
            emitProviderReady(dev.openfeature.sdk.ProviderEventDetails.builder().build())
        }
        val providerA = new EmittingProvider
        val providerB = new SimpleProvider("B", Map("flag" -> false)) {
          override def initialize(ctx: OFEvaluationContext): Unit = initGate.await()
          override def shutdown(): Unit                           = initGate.countDown()
        }
        for {
          ff        <- buildWithDomain(providerA)
          swapFiber <- ff.setProvider(providerB).fork
          // Wait until the swap has flipped status to NotReady (swap started, B's init blocked)
          _ <- ff.providerStatus.repeatUntil(_ == ProviderStatus.NotReady)
          // Old provider's emitter delivers a stale READY mid-swap
          _      <- ZIO.attemptBlocking(providerA.fireReady()).orDie
          _      <- ZIO.attemptBlocking(Thread.sleep(300)).orDie // allow the SDK's async event dispatch to run
          during <- ff.providerStatus
          _      <- ZIO.succeed(initGate.countDown())
          _      <- swapFiber.join
          after  <- ff.providerStatus
        } yield assertTrue(during == ProviderStatus.NotReady, after == ProviderStatus.Ready)
      }
    },
    test("recovery after failed swap") {
      ZIO.scoped {
        val providerA       = new SimpleProvider("A", Map("flag" -> true))
        val failingProvider = new SimpleProvider("Failing", Map.empty) {
          override def initialize(ctx: OFEvaluationContext): Unit =
            throw new RuntimeException("fail")
        }
        val providerC = new SimpleProvider("C", Map("flag" -> false))
        for {
          ff     <- buildWithDomain(providerA)
          _      <- ff.setProvider(failingProvider).either
          status <- ff.providerStatus
          _      <- ff.setProvider(providerC)
          v      <- ff.boolean("flag", default = true)
          s2     <- ff.providerStatus
          m      <- ff.providerMetadata
        } yield assertTrue(status == ProviderStatus.Ready) &&
          assertTrue(v == false) &&
          assertTrue(s2 == ProviderStatus.Ready) &&
          assertTrue(m.name == "C")
      }
    },
    test("no-domain failed swap rolls back routing and status") {
      // The rollback branches on `domain`; exercise the default-slot (no-domain) path.
      ZIO.scoped {
        val providerA       = new SimpleProvider("A", Map("flag" -> true))
        val failingProvider = new SimpleProvider("Failing", Map("flag" -> false)) {
          override def initialize(ctx: OFEvaluationContext): Unit =
            throw new RuntimeException("Initialization failed")
        }
        for {
          ff     <- buildNoDomain(providerA)
          result <- ff.setProvider(failingProvider).either
          v      <- ff.boolean("flag", default = false)
          status <- ff.providerStatus
          m      <- ff.providerMetadata
        } yield assertTrue(result.isLeft) &&
          assertTrue(v == true) &&
          assertTrue(status == ProviderStatus.Ready) &&
          assertTrue(m.name == "A")
      }
    },
    test("failed swap re-initializes the previous provider exactly once more") {
      // Pins the documented re-init caveat: rollback re-registers the old provider, so its initialize() runs a second
      // time (once at build, once at rollback).
      ZIO.scoped {
        val initCount = new java.util.concurrent.atomic.AtomicInteger(0)
        val providerA = new SimpleProvider("A", Map("flag" -> true)) {
          override def initialize(ctx: OFEvaluationContext): Unit = { initCount.incrementAndGet(); () }
        }
        val failingProvider = new SimpleProvider("Failing", Map.empty) {
          override def initialize(ctx: OFEvaluationContext): Unit =
            throw new RuntimeException("Initialization failed")
        }
        for {
          ff <- buildWithDomain(providerA)
          _  <- ff.setProvider(failingProvider).either
          n  <- ZIO.succeed(initCount.get())
        } yield assertTrue(n == 2)
      }
    },
    test("failed swap re-attaches the previous EventProvider so its events still flow") {
      // The detach->re-register round-trip must not sever event propagation: after rollback, A can still emit a
      // configuration-changed event that reaches subscribers.
      ZIO.scoped {
        class EmittingProvider extends SimpleProvider("A", Map("flag" -> true)) {
          def fireConfigChanged(): Unit =
            emitProviderConfigurationChanged(dev.openfeature.sdk.ProviderEventDetails.builder().build())
        }
        val providerA       = new EmittingProvider
        val failingProvider = new SimpleProvider("Failing", Map.empty) {
          override def initialize(ctx: OFEvaluationContext): Unit =
            throw new RuntimeException("Initialization failed")
        }
        for {
          ff       <- buildWithDomain(providerA)
          received <- Queue.unbounded[ProviderEvent]
          _        <- ff.on(ProviderEventType.ConfigurationChanged, e => received.offer(e).unit)
          _        <- ff.setProvider(failingProvider).either
          _        <- ZIO.attemptBlocking(providerA.fireConfigChanged()).orDie
          ev <- received.take.timeoutFail(new RuntimeException("no config-changed event after rollback"))(5.seconds)
        } yield assertTrue(ev.eventType == ProviderEventType.ConfigurationChanged)
      }
    },
    test("failed swap eventually shuts the failed provider down") {
      // On a successful rollback the SDK's shutDownOld tears down the failed provider (async).
      ZIO.scoped {
        val failShutdowns   = new java.util.concurrent.atomic.AtomicInteger(0)
        val providerA       = new SimpleProvider("A", Map("flag" -> true))
        val failingProvider = new SimpleProvider("Failing", Map.empty) {
          override def initialize(ctx: OFEvaluationContext): Unit =
            throw new RuntimeException("Initialization failed")
          override def shutdown(): Unit = { failShutdowns.incrementAndGet(); () }
        }
        for {
          ff <- buildWithDomain(providerA)
          _  <- ff.setProvider(failingProvider).either
          _  <- ZIO
            .succeed(failShutdowns.get())
            .repeatUntil(_ >= 1)
            .timeoutFail(new RuntimeException("failed provider never shut down"))(5.seconds)
        } yield assertTrue(failShutdowns.get() >= 1)
      }
    },
    test("a rollback whose re-initialization fails with a fatal error leaves the status Fatal, as the SDK reports") {
      ZIO.scoped {
        val initCount = new java.util.concurrent.atomic.AtomicInteger(0)
        // A succeeds on the first init (the build) and fails fatally on the second (the rollback).
        val providerA = new SimpleProvider("A", Map("flag" -> true)) {
          override def initialize(ctx: OFEvaluationContext): Unit =
            if (initCount.incrementAndGet() >= 2) throw new dev.openfeature.sdk.exceptions.FatalError("gone for good")
            else ()
        }
        val failing = new SimpleProvider("Failing", Map.empty) {
          override def initialize(ctx: OFEvaluationContext): Unit = throw new RuntimeException("cannot start")
        }
        for {
          built <- buildExposed(providerA)
          (ff, api, domain) = built
          result <- ff.setProvider(failing).either
          slot   <- ZIO.succeed(api.getClient(domain).getProviderState)
          status <- ff.providerStatus
        } yield assertTrue(result.isLeft, slot == ProviderState.FATAL, status == ProviderStatus.Fatal)
      }
    },
    test("rollback failure: old provider re-init throws leaves status Error, surfaces original error, no hang") {
      // When the old provider's re-initialize() throws, rollback fails: setProvider still reports the ORIGINAL swap
      // failure, status stays Error, and the call completes (no hang). The SDK slot was rebound to the old provider
      // before its failing init ran, so evaluations still route to it.
      ZIO.scoped {
        val initCount = new java.util.concurrent.atomic.AtomicInteger(0)
        // A succeeds on the first init (at build), throws on the second (rollback re-register).
        val providerA = new SimpleProvider("A", Map("flag" -> true)) {
          override def initialize(ctx: OFEvaluationContext): Unit =
            if (initCount.incrementAndGet() >= 2) throw new RuntimeException("re-init boom") else ()
        }
        val failingProvider = new SimpleProvider("Failing", Map.empty) {
          override def initialize(ctx: OFEvaluationContext): Unit =
            throw new RuntimeException("original swap failure")
        }
        for {
          ff     <- buildWithDomain(providerA)
          result <- ff
            .setProvider(failingProvider)
            .either
            .timeoutFail(new RuntimeException("setProvider hung on rollback failure"))(10.seconds)
          status <- ff.providerStatus
          m      <- ff.providerMetadata
        } yield assertTrue(result.isLeft) &&
          assertTrue(result.left.toOption.get.isInstanceOf[FeatureFlagError.ProviderInitializationFailed]) &&
          assertTrue(
            result.left.toOption.get
              .asInstanceOf[FeatureFlagError.ProviderInitializationFailed]
              .underlying
              .getMessage
              .contains("original swap failure")
          ) &&
          assertTrue(status == ProviderStatus.Error) &&
          assertTrue(m.name == "A")
      }
    },
    test("shared provider bound to two domains: failed swap on one leaves the shared provider intact") {
      // Decision-2 guard: one provider instance bound to two domains of the same API. A failed swap on domain 1 must
      // NOT detach or re-initialize the shared provider (its READY manager on domain 2 is reused), so its events on
      // domain 2 keep flowing and its init count does not grow.
      ZIO.scoped {
        val sharedInit = new java.util.concurrent.atomic.AtomicInteger(0)
        class SharedProvider extends SimpleProvider("Shared", Map("flag" -> true)) {
          override def initialize(ctx: OFEvaluationContext): Unit = { sharedInit.incrementAndGet(); () }
          def fireConfigChanged(): Unit                           =
            emitProviderConfigurationChanged(dev.openfeature.sdk.ProviderEventDetails.builder().build())
        }
        val shared          = new SharedProvider
        val failingProvider = new SimpleProvider("Failing", Map.empty) {
          override def initialize(ctx: OFEvaluationContext): Unit =
            throw new RuntimeException("Initialization failed")
        }
        val api = OpenFeatureAPI.createIsolated()
        val d1  = s"d1-${java.util.UUID.randomUUID()}"
        val d2  = s"d2-${java.util.UUID.randomUUID()}"
        for {
          ff1 <- buildOn(api, d1, shared)
          ff2 <- buildOn(api, d2, shared)
          _   <- ZIO.attemptBlocking(Thread.sleep(50)).ignore
          initBefore = sharedInit.get()
          // Failed swap on domain 1; shared stays bound to domain 2, so rollback reuses its manager (no detach/re-init).
          _        <- ff1.setProvider(failingProvider).either
          received <- Queue.unbounded[ProviderEvent]
          _        <- ff2.on(ProviderEventType.ConfigurationChanged, e => received.offer(e).unit)
          _        <- ZIO.attemptBlocking(shared.fireConfigChanged()).orDie
          ev       <- received.take.timeoutFail(new RuntimeException("domain-2 events severed by domain-1 rollback"))(
            5.seconds
          )
          initAfter = sharedInit.get()
        } yield assertTrue(ev.eventType == ProviderEventType.ConfigurationChanged) &&
          assertTrue(initAfter == initBefore)
      }
    }
  ) @@ TestAspect.sequential
}
