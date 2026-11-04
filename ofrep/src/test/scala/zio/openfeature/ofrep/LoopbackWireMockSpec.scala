package zio.openfeature.ofrep

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import zio._
import zio.openfeature.testsupport.LoopbackWireMock
import zio.test._

import java.net.{InetAddress, ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

object LoopbackWireMockSpec extends ZIOSpecDefault {

  private def stopped(server: WireMockServer): URIO[Scope, Any] =
    ZIO.addFinalizer(ZIO.attemptBlocking(server.stop()).ignore)

  private def started: ZIO[Scope, Throwable, WireMockServer] =
    for {
      server <- ZIO.succeed(LoopbackWireMock.server())
      _      <- stopped(server)
      _      <- ZIO.attemptBlocking(server.start())
    } yield server

  private def causedByBindFailure(error: Throwable): Boolean =
    Iterator.iterate(error)(_.getCause).takeWhile(_ != null).exists(_.isInstanceOf[java.net.BindException])

  def spec = suite("LoopbackWireMock")(
    // On macOS a wildcard bind starts on this port and loses every connection to the other listener.
    test("a server refuses a port another listener holds on the loopback address") {
      ZIO.scoped {
        for {
          held <- ZIO.acquireRelease(
            ZIO.attemptBlocking(new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")))
          )(socket => ZIO.attemptBlocking(socket.close()).ignore)
          server = new WireMockServer(LoopbackWireMock.options.port(held.getLocalPort))
          _      <- stopped(server)
          result <- ZIO.attemptBlocking(server.start()).either
        } yield assertTrue(result.left.exists(causedByBindFailure))
      }
    },
    test("each server gets a free port of its own, not WireMock's default") {
      ZIO.scoped {
        for {
          first  <- started.map(_.port())
          second <- started.map(_.port())
        } yield assertTrue(first != second, first != 8080, second != 8080)
      }
    },
    test("a server answers on the URL the helper gives") {
      ZIO.scoped {
        for {
          server <- started
          _      <- ZIO.attemptBlocking(server.stubFor(get(urlEqualTo("/ping")).willReturn(ok("pong"))))
          body   <- ZIO.attemptBlockingInterrupt {
            val request = HttpRequest.newBuilder(URI.create(LoopbackWireMock.baseUrl(server) + "/ping")).build()
            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body()
          }
        } yield assertTrue(body == "pong")
      }
    },
    test("the URL names the loopback address, so it does not depend on how localhost resolves") {
      ZIO.scoped {
        started.map { server =>
          // Read here: assertTrue evaluates its expression later, when the scope has stopped the server.
          val url  = LoopbackWireMock.baseUrl(server)
          val port = server.port()
          assertTrue(url == s"http://127.0.0.1:$port")
        }
      }
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(1.minute)
}
