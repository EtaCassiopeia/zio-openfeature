package zio.openfeature.ofrep

import zio._
import zio.test._

import java.time.{Duration => JDuration}

object OFREPConfigShapeSpec extends ZIOSpecDefault {

  def spec = suite("OFREPProviderConfig")(
    test("apply takes the base URL and leaves the rest unset") {
      val c = OFREPProviderConfig("https://flags.example.com")
      assertTrue(
        c.baseUrl == "https://flags.example.com",
        c.requestTimeout.isEmpty,
        c.connectTimeout.isEmpty,
        c.headers.isEmpty,
        c.proxy.isEmpty
      )
    },
    test("each with* returns a copy and leaves the receiver as it was") {
      val base    = OFREPProviderConfig("https://flags.example.com")
      val changed = base
        .withBaseUrl("https://other.example.com")
        .withRequestTimeout(JDuration.ofSeconds(2))
        .withConnectTimeout(JDuration.ofSeconds(1))
        .withHeaders(Map("X-Env" -> List("test")))
      assertTrue(
        changed.baseUrl == "https://other.example.com",
        changed.requestTimeout.contains(JDuration.ofSeconds(2)),
        changed.connectTimeout.contains(JDuration.ofSeconds(1)),
        changed.headers == Map("X-Env" -> List("test")),
        base.baseUrl == "https://flags.example.com",
        base.requestTimeout.isEmpty,
        base.headers.isEmpty
      )
    },
    test("a base URL set through withBaseUrl is validated when the provider is built") {
      OFREPProvider
        .make(OFREPProviderConfig("https://flags.example.com").withBaseUrl("ftp://flags.example.com"))
        .exit
        .map { exit =>
          assertTrue(exit.isFailure)
        }
    },
    test("toString names the fields and keeps header values out") {
      val text = OFREPProviderConfig("https://flags.example.com")
        .withRequestTimeout(JDuration.ofSeconds(2))
        .withHeaders(Map("Authorization" -> List("Bearer s3cret-token")))
        .toString
      assertTrue(
        text.startsWith("OFREPProviderConfig(baseUrl=https://flags.example.com"),
        text.contains("requestTimeout=Some(PT2S)"),
        text.contains("Authorization"),
        !text.contains("s3cret-token")
      )
    }
  )
}
