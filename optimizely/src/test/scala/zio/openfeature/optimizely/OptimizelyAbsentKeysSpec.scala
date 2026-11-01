package zio.openfeature.optimizely

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import dev.openfeature.sdk.{FeatureProvider, ImmutableContext}
import zio._
import zio.openfeature.testkit.DifferentialLaws
import zio.test._

// A ready Optimizely provider answers FLAG_NOT_FOUND for every adversarial key its datafile does not hold.
object OptimizelyAbsentKeysSpec extends ZIOSpecDefault {

  private val DatafilePath = "/datafiles/typed-variables-key.json"

  private def readResource(path: String): String =
    scala.util.Using.resource(scala.io.Source.fromInputStream(getClass.getResourceAsStream(path)))(_.mkString)

  private val ready: URIO[Scope, FeatureProvider] =
    for {
      server <- ZIO.acquireRelease(ZIO.succeed {
        val s = new WireMockServer(WireMockConfiguration.options().dynamicPort())
        s.start()
        s
      })(s => ZIO.succeed(s.stop()))
      client <- ZIO
        .attemptBlocking(
          OptimizelyTestSupport.readyClient(
            server,
            DatafilePath,
            readResource("/datafiles/typed-variables.json"),
            "typed-variables-key"
          )
        )
        .orDie
      provider <- ZIO.acquireRelease(
        ZIO.succeed(new OptimizelyFeatureProvider(client, OptimizelyTestSupport.InitWait, closeOnShutdown = true))
      )(p => ZIO.attemptBlocking(p.shutdown()).ignore)
      _ <- ZIO.attemptBlocking(provider.initialize(new ImmutableContext())).orDie
    } yield provider

  def spec = suite("OptimizelyAbsentKeysSpec")(
    // Optimizely checks the targeting key before the flag key, so the reads carry one.
    DifferentialLaws.absentKeysAreNotFound("optimizely")(_ => ready, ctx = zio.openfeature.EvaluationContext("user-1"))
  ) @@ TestAspect.withLiveClock
}
