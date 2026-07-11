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
    test("failed swap sets status to Error") {
      ZIO.scoped {
        val providerA = new SimpleProvider("A", Map("flag" -> true))
        val failingProvider = new SimpleProvider("Failing", Map.empty) {
          override def initialize(ctx: OFEvaluationContext): Unit =
            throw new RuntimeException("Initialization failed")
        }
        for {
          ff     <- buildWithDomain(providerA)
          result <- ff.setProvider(failingProvider).either
          status <- ff.providerStatus
        } yield assertTrue(result.isLeft) &&
          assertTrue(result.left.toOption.get.isInstanceOf[FeatureFlagError.ProviderInitializationFailed]) &&
          assertTrue(status == ProviderStatus.Error)
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
        } yield assertTrue(status == ProviderStatus.Error) &&
          assertTrue(v == false) &&
          assertTrue(s2 == ProviderStatus.Ready) &&
          assertTrue(m.name == "C")
      }
    }
  ) @@ TestAspect.sequential
}
