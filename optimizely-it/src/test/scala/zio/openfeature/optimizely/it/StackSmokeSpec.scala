package zio.openfeature.optimizely.it

import zio._
import zio.test._
import zio.test.Assertion._

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

/** Foundation smoke test: brings the docker-compose stack up, asserts that the Toxiproxy-fronted nginx serves the
  * committed `health.txt`, and tears the stack down on JVM exit. No Optimizely SDK calls — that surface is covered by
  * the per-scenario specs added in follow-up PRs.
  */
object StackSmokeSpec extends ZIOSpecDefault {

  private val http: HttpClient =
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  private def fetch(url: String): HttpResponse[String] = {
    val req = HttpRequest
      .newBuilder()
      .uri(URI.create(url))
      .timeout(Duration.ofSeconds(5))
      .GET()
      .build()
    http.send(req, HttpResponse.BodyHandlers.ofString())
  }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Optimizely IT stack smoke")(
    test("compose stack is up and Toxiproxy proxies nginx /datafiles/health.txt") {
      val res = fetch(s"${OptimizelyItStack.datafileBaseUrl}/datafiles/health.txt")
      assert(res.statusCode())(equalTo(200)) &&
      assert(res.body().trim)(equalTo("ok"))
    },
    test("unknown SDK-key path returns 404 from nginx through Toxiproxy") {
      val res = fetch(OptimizelyItStack.datafileUrl("does_not_exist"))
      assert(res.statusCode())(equalTo(404))
    }
  ) @@ OptimizelyItStack.ifDockerAvailable @@ TestAspect.sequential @@ TestAspect.timeout(
    120.seconds
  ) @@ TestAspect.withLiveClock
}
