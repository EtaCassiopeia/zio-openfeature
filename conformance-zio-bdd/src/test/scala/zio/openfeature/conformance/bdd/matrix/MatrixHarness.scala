package zio.openfeature.conformance.bdd.matrix

import zio._
import zio.bdd.mock._
import zio.bdd.mock.dsl._
import zio.openfeature._
import zio.openfeature.optimizely.{OptimizelyProvider, OptimizelyProviderConfig}

/** Builds a per-scenario ZLayer[Any, Throwable, FeatureFlags] from a named datafile fixture.
  *
  * Each layer acquisition:
  *   1. Provisions a fresh Rift mock space (own loopback port) that serves the named datafile.
  *   2. Points an OptimizelyProvider at that space's `baseUri` and initialises it (blocks until the
  *      datafile is fetched).
  *   3. Builds a FeatureFlags service wired to that provider.
  *
  * The layer is scoped: the mock space is destroyed and the Optimizely client shut down when the
  * scenario ends, so each scenario is fully isolated — no shared state, no polling wait. Rift is an
  * in-process native engine, so there is no WireMock server or container to manage (#278).
  */
object MatrixHarness {

  private val SdkKey       = "test-matrix-key"
  private val DatafilePath = s"/datafiles/$SdkKey.json"

  private def loadDatafile(name: String): String = {
    val path = s"/datafiles/$name.json"
    val is   = getClass.getResourceAsStream(path)
    require(is != null, s"Datafile fixture not found on classpath: $path")
    scala.io.Source.fromInputStream(is).mkString
  }

  private def datafileRule(name: String): MockRule =
    get(DatafilePath).respondWith(ok.withHeader("Content-Type", "application/json").withBody(Body.Json(loadDatafile(name))))

  /** A scoped ZLayer that owns one Rift mock space + one Optimizely provider for the named datafile.
    *
    * Bound to a freshly-generated domain (`FeatureFlags.fromProviderWithDomain`) rather than the plain
    * `fromProvider` factory. `fromProvider` registers its provider as the *unnamed default* client on
    * the process-wide `OpenFeatureAPI` singleton — concurrent calls from different scenarios would race
    * to overwrite that single default slot, and since the OpenFeature SDK looks up the active provider
    * dynamically on every evaluation (not a frozen reference), one scenario's in-flight evaluations could
    * start hitting another scenario's datafile mid-run. A named domain gets its own slot on that same
    * singleton, so concurrently-running scenarios (`scenarioParallelism > 1`) can never collide — this is
    * the same isolation technique `ConformanceSpec` uses, expressed with the public API so it also works
    * outside this package.
    *
    * `plan`, when present, is seeded as a global-context attribute (via `setGlobalContext`) before the
    * layer is handed to the scenario. The OpenFeature spec merges global context into every invocation
    * automatically (API -> Transaction -> Client -> Invocation), so a single `@flags(datafile=X, plan=Y)`
    * tag can combine a provider swap with a context override in one `flagLayer` call — no separate
    * "with plan" step text required.
    */
  def layerForDatafile(name: String, plan: Option[String] = None): ZLayer[Any, Throwable, FeatureFlags] =
    ZLayer.scoped {
      for {
        mc     <- ZIO.succeed(RiftEngine.mockControl)
        spaces <- mc.provision(MockSpec(List(datafileRule(name))).source).mapError(e => new RuntimeException(s"mock provision failed: $e"))
        space  <- ZIO.fromOption(spaces.headOption).orElseFail(new RuntimeException("no mock space provisioned"))
        _      <- ZIO.addFinalizer(mc.destroy(space).ignore)
        dataUrl = s"${space.baseUri}$DatafilePath"
        config  = OptimizelyProviderConfig(
                    sdkKey = SdkKey,
                    datafileUrl = Some(dataUrl),
                    initWait = java.time.Duration.ofSeconds(5),
                    pollingInterval = Some(java.time.Duration.ofSeconds(3600)), // no polling in tests
                    blockingTimeout = Some(java.time.Duration.ofSeconds(2))
                  )
        provider <- OptimizelyProvider.scoped(config).mapError(e => new RuntimeException(e.message))
        domain    = s"flag-matrix-${java.util.UUID.randomUUID()}"
        env      <- FeatureFlags.fromProviderWithDomain(provider, domain).build
        ff        = env.get[FeatureFlags]
        _ <- ZIO.foreachDiscard(plan)(p =>
               ff.setGlobalContext(EvaluationContext.empty.withAttribute("plan", AttributeValue.StringValue(p)))
             )
      } yield ff
    }
}
