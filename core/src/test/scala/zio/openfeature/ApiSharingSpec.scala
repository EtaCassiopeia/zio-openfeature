package zio.openfeature

import zio._
import zio.test._
import zio.test.TestAspect.withLiveClock
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{EvaluationContext => OFEvaluationContext, FeatureProvider, Metadata, OpenFeatureAPI, Value}

/** Instances sharing one `OpenFeatureAPI`: a slot bound twice is warned about, an owner's teardown leaves no sibling
  * serving the SDK's No-op answers, and `withIsolatedApi` keeps an instance off the process-global API.
  *
  * Shared (cross-compiled) test source dir: braces only.
  */
object ApiSharingSpec extends ZIOSpecDefault {

  private class Named(name: String) extends FeatureProvider {
    @scala.annotation.nowarn("msg=deprecated")
    override def getMetadata: Metadata = new Metadata { override def getName: String = name }
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.TRUE, "STATIC")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of(d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = ProviderEvaluations.of(d, "DEFAULT")
  }

  private def owner(api: OpenFeatureAPI, provider: FeatureProvider, domain: Option[String] = None) =
    FeatureFlags.build(
      provider,
      domain = domain,
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(api)
    )

  private def warningsSince(since: Int): UIO[List[String]] =
    ZTestLogger.logOutput.map(_.drop(since).filter(_.logLevel == LogLevel.Warning).map(_.message()).toList)

  def spec = suite("ApiSharingSpec")(
    test("binding a slot that already holds another provider logs a warning naming both") {
      val api = OpenFeatureAPI.createIsolated()
      ZIO.scoped {
        for {
          _     <- owner(api, new Named("first"))
          since <- ZTestLogger.logOutput.map(_.length)
          _     <- owner(api, new Named("second"))
          warns <- warningsSince(since)
        } yield assertTrue(warns.exists(w => w.contains("first") && w.contains("second") && w.contains("default")))
      }
    },
    test("a domain already bound is warned about too, and an unbound slot or the same provider is not") {
      val api      = OpenFeatureAPI.createIsolated()
      val provider = new Named("same")
      ZIO.scoped {
        for {
          since0 <- ZTestLogger.logOutput.map(_.length)
          _      <- owner(api, new Named("d1"), domain = Some("checkout"))
          _      <- owner(api, provider)
          quiet  <- warningsSince(since0)
          since1 <- ZTestLogger.logOutput.map(_.length)
          _      <- owner(api, new Named("d2"), domain = Some("checkout"))
          warned <- warningsSince(since1)
        } yield assertTrue(
          !quiet.exists(_.contains("already holds")),
          warned.exists(w => w.contains("domain 'checkout'") && w.contains("d1") && w.contains("d2"))
        )
      }
    },
    test("withIsolatedApi keeps the instance off the process-global API, and the instance owns its own") {
      val provider = new Named("isolated")
      ZIO.scoped {
        for {
          ff <- FeatureFlags.fromProvider(provider, FeatureFlagsConfig().withIsolatedApi).build.map(_.get[FeatureFlags])
          read     <- ff.boolean("f", default = false)
          onGlobal <- ZIO.succeed(
            zio.openfeature.internal.ManagedProvider.unwrap(OpenFeatureAPI.getInstance().getProvider()) eq provider
          )
        } yield assertTrue(read, !onGlobal)
      }
    },
    test("a registry's clients report NotReady once the registry's scope has closed") {
      for {
        client <- ZIO.scoped(
          FeatureFlagRegistry
            .fromProvider(new Named("shared"))
            .build
            .flatMap(_.get[FeatureFlagRegistry].getClient("checkout"))
            .tap(_.awaitReady(5.seconds))
        )
        status <- client.providerStatus
        read   <- client.boolean("f", default = false).either
      } yield assertTrue(
        status == ProviderStatus.NotReady,
        read == Left(FeatureFlagError.ProviderNotReady(ProviderStatus.NotReady))
      )
    }
  ) @@ withLiveClock @@ TestAspect.sequential
}
