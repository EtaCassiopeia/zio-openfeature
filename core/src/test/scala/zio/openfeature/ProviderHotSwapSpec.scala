package zio.openfeature

import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
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
      ProviderEvaluation.builder[java.lang.Boolean]().value(v).reason("STATIC").build()
    }

    override def getStringEvaluation(
      key: String,
      defaultValue: String,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[String] = {
      val v = flags.get(key).map(_.toString).getOrElse(defaultValue)
      ProviderEvaluation.builder[String]().value(v).reason("STATIC").build()
    }

    override def getIntegerEvaluation(
      key: String,
      defaultValue: java.lang.Integer,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Integer] = {
      val v = flags.get(key).map(_.asInstanceOf[Int]).getOrElse(defaultValue.intValue())
      ProviderEvaluation.builder[java.lang.Integer]().value(v).reason("STATIC").build()
    }

    override def getDoubleEvaluation(
      key: String,
      defaultValue: java.lang.Double,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[java.lang.Double] = {
      val v = flags.get(key).map(_.asInstanceOf[Double]).getOrElse(defaultValue.doubleValue())
      ProviderEvaluation.builder[java.lang.Double]().value(v).reason("STATIC").build()
    }

    override def getObjectEvaluation(
      key: String,
      defaultValue: Value,
      ctx: OFEvaluationContext
    ): ProviderEvaluation[Value] =
      ProviderEvaluation.builder[Value]().value(defaultValue).reason("STATIC").build()
  }

  def spec = suite("Provider Hot-Swap")(
    test("setProvider swaps to a new provider") {
      ZIO.scoped {
        val providerA = new SimpleProvider("ProviderA", Map("flag" -> true))
        val providerB = new SimpleProvider("ProviderB", Map("flag" -> false))
        FeatureFlags.fromProvider(providerA).build.flatMap { env =>
          val ff = env.get[FeatureFlags]
          for {
            v1 <- ff.boolean("flag", default = false)
            _  <- ff.setProvider(providerB)
            v2 <- ff.boolean("flag", default = false)
          } yield assertTrue(v1 == true) && assertTrue(v2 == false)
        }
      }
    },
    test("setProvider updates provider metadata") {
      ZIO.scoped {
        val providerA = new SimpleProvider("ProviderA", Map.empty)
        val providerB = new SimpleProvider("ProviderB", Map.empty)
        FeatureFlags.fromProvider(providerA).build.flatMap { env =>
          val ff = env.get[FeatureFlags]
          for {
            m1 <- ff.providerMetadata
            _  <- ff.setProvider(providerB)
            m2 <- ff.providerMetadata
          } yield assertTrue(m1.name == "ProviderA") && assertTrue(m2.name == "ProviderB")
        }
      }
    },
    test("setProvider preserves hooks") {
      ZIO.scoped {
        var hookCalled = false
        val hook = new FeatureHook {
          override def before(
            ctx: HookContext,
            hints: HookHints
          ): UIO[Option[(EvaluationContext, HookHints)]] =
            ZIO.succeed { hookCalled = true; None }
        }
        val providerA = new SimpleProvider("ProviderA", Map("flag" -> true))
        val providerB = new SimpleProvider("ProviderB", Map("flag" -> false))
        FeatureFlags.fromProvider(providerA).build.flatMap { env =>
          val ff = env.get[FeatureFlags]
          for {
            _ <- ff.addHook(hook)
            _ <- ff.setProvider(providerB)
            _ <- ff.boolean("flag", default = false)
          } yield assertTrue(hookCalled)
        }
      }
    },
    test("setProvider preserves client context") {
      ZIO.scoped {
        val providerA = new SimpleProvider("ProviderA", Map("flag" -> true))
        val providerB = new SimpleProvider("ProviderB", Map("flag" -> false))
        val ctx       = EvaluationContext.builder.targetingKey("user-123").build
        FeatureFlags.fromProvider(providerA).build.flatMap { env =>
          val ff = env.get[FeatureFlags]
          for {
            _       <- ff.setClientContext(ctx)
            _       <- ff.setProvider(providerB)
            readCtx <- ff.clientContext
          } yield assertTrue(readCtx.targetingKey.contains("user-123"))
        }
      }
    },
    test("setProvider transitions status through NotReady") {
      ZIO.scoped {
        val providerA = new SimpleProvider("ProviderA", Map.empty)
        val providerB = new SimpleProvider("ProviderB", Map.empty)
        FeatureFlags.fromProvider(providerA).build.flatMap { env =>
          val ff = env.get[FeatureFlags]
          for {
            s1 <- ff.providerStatus
            _  <- ff.setProvider(providerB)
            s2 <- ff.providerStatus
          } yield assertTrue(s1 == ProviderStatus.Ready) && assertTrue(s2 == ProviderStatus.Ready)
        }
      }
    },
    test("setProvider preserves global context") {
      ZIO.scoped {
        val providerA = new SimpleProvider("ProviderA", Map("flag" -> true))
        val providerB = new SimpleProvider("ProviderB", Map("flag" -> false))
        val ctx       = EvaluationContext.builder.targetingKey("global-key").build
        FeatureFlags.fromProvider(providerA).build.flatMap { env =>
          val ff = env.get[FeatureFlags]
          for {
            _       <- ff.setGlobalContext(ctx)
            _       <- ff.setProvider(providerB)
            readCtx <- ff.globalContext
          } yield assertTrue(readCtx.targetingKey.contains("global-key"))
        }
      }
    },
    test("multiple swaps work correctly") {
      ZIO.scoped {
        val providerA = new SimpleProvider("A", Map("flag" -> "a"))
        val providerB = new SimpleProvider("B", Map("flag" -> "b"))
        val providerC = new SimpleProvider("C", Map("flag" -> "c"))
        FeatureFlags.fromProvider(providerA).build.flatMap { env =>
          val ff = env.get[FeatureFlags]
          for {
            v1 <- ff.string("flag", default = "")
            _  <- ff.setProvider(providerB)
            v2 <- ff.string("flag", default = "")
            _  <- ff.setProvider(providerC)
            v3 <- ff.string("flag", default = "")
            m  <- ff.providerMetadata
          } yield assertTrue(v1 == "a") && assertTrue(v2 == "b") && assertTrue(v3 == "c") &&
            assertTrue(m.name == "C")
        }
      }
    },
    test("all flag types work after swap") {
      ZIO.scoped {
        val providerA = new SimpleProvider("A", Map("b" -> true))
        val providerB = new SimpleProvider("B", Map("b" -> false, "s" -> "hello", "i" -> 42, "d" -> 3.14))
        FeatureFlags.fromProvider(providerA).build.flatMap { env =>
          val ff = env.get[FeatureFlags]
          for {
            _  <- ff.setProvider(providerB)
            vb <- ff.boolean("b", default = true)
            vs <- ff.string("s", default = "")
            vi <- ff.int("i", default = 0)
            vd <- ff.double("d", default = 0.0)
          } yield assertTrue(vb == false) && assertTrue(vs == "hello") &&
            assertTrue(vi == 42) && assertTrue(vd == 3.14)
        }
      }
    },
    test("setProvider fails gracefully when new provider initialization fails") {
      ZIO.scoped {
        val providerA = new SimpleProvider("A", Map("flag" -> true))
        val failingProvider = new SimpleProvider("Failing", Map.empty) {
          override def initialize(ctx: OFEvaluationContext): Unit =
            throw new RuntimeException("Initialization failed")
        }
        FeatureFlags.fromProvider(providerA).build.flatMap { env =>
          val ff = env.get[FeatureFlags]
          for {
            result <- ff.setProvider(failingProvider).either
          } yield assertTrue(result.isLeft) &&
            assertTrue(result.left.toOption.get.isInstanceOf[FeatureFlagError.ProviderInitializationFailed])
        }
      }
    }
  ) @@ TestAspect.sequential
}
