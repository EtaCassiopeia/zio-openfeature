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

object FeatureFlagRegistrySpec extends ZIOSpecDefault {

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

  private def registryLayer(defaultProvider: SimpleProvider): ZLayer[Scope, Throwable, FeatureFlagRegistry] =
    FeatureFlagRegistry.fromProvider(defaultProvider)

  def spec = suite("FeatureFlagRegistry")(
    test("getClient returns a working client using default provider") {
      ZIO.scoped {
        val provider = new SimpleProvider("Default", Map("flag" -> true))
        for {
          registry <- registryLayer(provider).build.map(_.get[FeatureFlagRegistry])
          client   <- registry.getClient("billing")
          value    <- client.boolean("flag", default = false)
        } yield assertTrue(value == true)
      }
    },
    test("setProvider then getClient uses the registered provider") {
      ZIO.scoped {
        val defaultProvider = new SimpleProvider("Default", Map("flag" -> false))
        val billingProvider = new SimpleProvider("Billing", Map("flag" -> true))
        for {
          registry <- registryLayer(defaultProvider).build.map(_.get[FeatureFlagRegistry])
          _        <- registry.setProvider("billing", billingProvider)
          client   <- registry.getClient("billing")
          value    <- client.boolean("flag", default = false)
        } yield assertTrue(value == true)
      }
    },
    test("setProvider hot-swaps an existing client") {
      ZIO.scoped {
        val providerA = new SimpleProvider("A", Map("flag" -> true))
        val providerB = new SimpleProvider("B", Map("flag" -> false))
        for {
          registry <- registryLayer(providerA).build.map(_.get[FeatureFlagRegistry])
          client   <- registry.getClient("domain1")
          v1       <- client.boolean("flag", default = false)
          _        <- registry.setProvider("domain1", providerB)
          v2       <- client.boolean("flag", default = true)
        } yield assertTrue(v1 == true, v2 == false)
      }
    },
    test("getClient caches — returns same instance") {
      ZIO.scoped {
        val provider = new SimpleProvider("Default", Map.empty)
        for {
          registry <- registryLayer(provider).build.map(_.get[FeatureFlagRegistry])
          c1       <- registry.getClient("domain1")
          c2       <- registry.getClient("domain1")
        } yield assertTrue(c1 eq c2)
      }
    },
    test("defaultClient returns a working no-domain client") {
      ZIO.scoped {
        val provider = new SimpleProvider("Default", Map("flag" -> true))
        for {
          registry <- registryLayer(provider).build.map(_.get[FeatureFlagRegistry])
          client   <- registry.defaultClient
          value    <- client.boolean("flag", default = false)
        } yield assertTrue(value == true)
      }
    },
    test("multiple domains with different providers") {
      ZIO.scoped {
        val defaultProvider = new SimpleProvider("Default", Map("flag" -> "default"))
        val billingProvider = new SimpleProvider("Billing", Map("flag" -> "billing"))
        val authProvider    = new SimpleProvider("Auth", Map("flag" -> "auth"))
        for {
          registry <- registryLayer(defaultProvider).build.map(_.get[FeatureFlagRegistry])
          _        <- registry.setProvider("billing", billingProvider)
          _        <- registry.setProvider("auth", authProvider)
          billing  <- registry.getClient("billing")
          auth     <- registry.getClient("auth")
          other    <- registry.getClient("other")
          vBilling <- billing.string("flag", default = "none")
          vAuth    <- auth.string("flag", default = "none")
          vOther   <- other.string("flag", default = "none")
        } yield assertTrue(
          vBilling == "billing",
          vAuth == "auth",
          vOther == "default"
        )
      }
    },
    test("defaultClient is cached") {
      ZIO.scoped {
        val provider = new SimpleProvider("Default", Map.empty)
        for {
          registry <- registryLayer(provider).build.map(_.get[FeatureFlagRegistry])
          c1       <- registry.defaultClient
          c2       <- registry.defaultClient
        } yield assertTrue(c1 eq c2)
      }
    },
    test("setProvider on domain without existing client registers for later use") {
      ZIO.scoped {
        val defaultProvider = new SimpleProvider("Default", Map("flag" -> "default"))
        val customProvider  = new SimpleProvider("Custom", Map("flag" -> "custom"))
        for {
          registry <- registryLayer(defaultProvider).build.map(_.get[FeatureFlagRegistry])
          _        <- registry.setProvider("late", customProvider)
          client   <- registry.getClient("late")
          value    <- client.string("flag", default = "none")
          meta     <- client.providerMetadata
        } yield assertTrue(value == "custom", meta.name == "Custom")
      }
    },
    test("failed setProvider does not update providers map") {
      ZIO.scoped {
        val defaultProvider = new SimpleProvider("Default", Map("flag" -> "default"))
        val failingProvider = new SimpleProvider("Failing", Map.empty) {
          override def initialize(ctx: OFEvaluationContext): Unit =
            throw new RuntimeException("init failed")
        }
        val recoveryProvider = new SimpleProvider("Recovery", Map("flag" -> "recovered"))
        for {
          registry <- registryLayer(defaultProvider).build.map(_.get[FeatureFlagRegistry])
          client   <- registry.getClient("domain1")
          v1       <- client.string("flag", default = "none")
          result   <- registry.setProvider("domain1", failingProvider).either
          // After failed swap, client is in Error state (old provider was shut down by Java SDK)
          status <- client.providerStatus
          // Recover with a working provider
          _  <- registry.setProvider("domain1", recoveryProvider)
          v3 <- client.string("flag", default = "none")
        } yield assertTrue(
          v1 == "default",
          result.is(_.left).isInstanceOf[FeatureFlagError],
          status == ProviderStatus.Error,
          v3 == "recovered"
        )
      }
    },
    test("provider metadata reflects the domain provider") {
      ZIO.scoped {
        val defaultProvider = new SimpleProvider("Default", Map.empty)
        val billingProvider = new SimpleProvider("Billing", Map.empty)
        for {
          registry <- registryLayer(defaultProvider).build.map(_.get[FeatureFlagRegistry])
          _        <- registry.setProvider("billing", billingProvider)
          client   <- registry.getClient("billing")
          meta     <- client.providerMetadata
        } yield assertTrue(meta.name == "Billing")
      }
    }
  )
}
