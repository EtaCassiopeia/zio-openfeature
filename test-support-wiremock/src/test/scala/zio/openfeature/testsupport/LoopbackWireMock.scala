package zio.openfeature.testsupport

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration

/** WireMock servers for specs, bound to the loopback address.
  *
  * A wildcard bind on port 0 can be given a port another process already listens on at 127.0.0.1 (macOS does this). A
  * client that connects to that port on the loopback address then reaches the other process, not WireMock. A bind on
  * 127.0.0.1 is never given such a port.
  */
object LoopbackWireMock {
  val Host = "127.0.0.1"

  /** Options for a server on a free loopback port. */
  def options: WireMockConfiguration = WireMockConfiguration.options().bindAddress(Host).dynamicPort()

  /** An unstarted server on a free loopback port. */
  def server(): WireMockServer = new WireMockServer(options)

  /** The URL clients use to reach `server`, with no trailing slash. */
  def baseUrl(server: WireMockServer): String = s"http://$Host:${server.port()}"
}
