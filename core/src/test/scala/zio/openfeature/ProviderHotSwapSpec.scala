package zio.openfeature
import zio.openfeature.internal.ProviderEvaluations

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPIFactory,
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
    val api    = OpenFeatureAPIFactory.create()
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
    val api = OpenFeatureAPIFactory.create()
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
    test("failed swap rolls back routing and status to the previous provider (#282)") {
      // The core regression: after a failed swap, evaluations must route back to provider A (not the failed
      // provider), status must be Ready, and metadata must name A — the SDK client routing is reconciled, not just
      // the internal providerRef.
      ZIO.scoped {
        val providerA = new SimpleProvider("A", Map("flag" -> true))
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
    test("stale PROVIDER_READY from the old provider does not mark status Ready mid-swap (#181)") {
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
        val providerA = new SimpleProvider("A", Map("flag" -> true))
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
    test("no-domain failed swap rolls back routing and status (#282)") {
      // The rollback branches on `domain`; exercise the default-slot (no-domain) path.
      ZIO.scoped {
        val providerA = new SimpleProvider("A", Map("flag" -> true))
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
    test("failed swap re-initializes the previous provider exactly once more (#282)") {
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
    test("failed swap re-attaches the previous EventProvider so its events still flow (#282)") {
      // The detach->re-register round-trip must not sever event propagation: after rollback, A can still emit a
      // configuration-changed event that reaches subscribers.
      ZIO.scoped {
        class EmittingProvider extends SimpleProvider("A", Map("flag" -> true)) {
          def fireConfigChanged(): Unit =
            emitProviderConfigurationChanged(dev.openfeature.sdk.ProviderEventDetails.builder().build())
        }
        val providerA = new EmittingProvider
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
    test("failed swap eventually shuts the failed provider down (#282)") {
      // On a successful rollback the SDK's shutDownOld tears down the failed provider (async).
      ZIO.scoped {
        val failShutdowns = new java.util.concurrent.atomic.AtomicInteger(0)
        val providerA     = new SimpleProvider("A", Map("flag" -> true))
        val failingProvider = new SimpleProvider("Failing", Map.empty) {
          override def initialize(ctx: OFEvaluationContext): Unit =
            throw new RuntimeException("Initialization failed")
          override def shutdown(): Unit = { failShutdowns.incrementAndGet(); () }
        }
        for {
          ff <- buildWithDomain(providerA)
          _  <- ff.setProvider(failingProvider).either
          _ <- ZIO
            .succeed(failShutdowns.get())
            .repeatUntil(_ >= 1)
            .timeoutFail(new RuntimeException("failed provider never shut down"))(5.seconds)
        } yield assertTrue(failShutdowns.get() >= 1)
      }
    },
    test("rollback failure: old provider re-init throws leaves status Error, surfaces original error, no hang (#282)") {
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
          ff <- buildWithDomain(providerA)
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
    test("shared provider bound to two domains: failed swap on one leaves the shared provider intact (#282)") {
      // Decision-2 guard: one provider instance bound to two domains of the same API. A failed swap on domain 1 must
      // NOT detach or re-initialize the shared provider (its READY manager on domain 2 is reused), so its events on
      // domain 2 keep flowing and its init count does not grow.
      ZIO.scoped {
        val sharedInit = new java.util.concurrent.atomic.AtomicInteger(0)
        class SharedProvider extends SimpleProvider("Shared", Map("flag" -> true)) {
          override def initialize(ctx: OFEvaluationContext): Unit = { sharedInit.incrementAndGet(); () }
          def fireConfigChanged(): Unit =
            emitProviderConfigurationChanged(dev.openfeature.sdk.ProviderEventDetails.builder().build())
        }
        val shared = new SharedProvider
        val failingProvider = new SimpleProvider("Failing", Map.empty) {
          override def initialize(ctx: OFEvaluationContext): Unit =
            throw new RuntimeException("Initialization failed")
        }
        val api = OpenFeatureAPIFactory.create()
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
          ev <- received.take.timeoutFail(new RuntimeException("domain-2 events severed by domain-1 rollback"))(
            5.seconds
          )
          initAfter = sharedInit.get()
        } yield assertTrue(ev.eventType == ProviderEventType.ConfigurationChanged) &&
          assertTrue(initAfter == initBefore)
      }
    }
  ) @@ TestAspect.sequential
}
