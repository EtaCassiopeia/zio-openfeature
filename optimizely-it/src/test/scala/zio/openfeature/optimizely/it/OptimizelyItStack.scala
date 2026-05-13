package zio.openfeature.optimizely.it

import com.dimafeng.testcontainers.DockerComposeContainer
import com.dimafeng.testcontainers.DockerComposeContainer.ComposeFile
import com.dimafeng.testcontainers.ExposedService
import eu.rekawek.toxiproxy.model.Toxic
import eu.rekawek.toxiproxy.{Proxy, ToxiproxyClient}
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.wait.strategy.Wait
import zio.test.{TestAspect, TestAspectPoly}

import java.io.File
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.jdk.CollectionConverters._

/** docker-compose-backed stack for the Optimizely real-backend integration suite. One stack per test JVM (sbt `Test /
  * fork := true` keeps each module isolated). A JVM shutdown hook stops the stack on exit.
  *
  * Layout:
  *   - `datafile-server` (nginx) serves files from a host directory that is *populated at startup* by copying
  *     everything under `src/test/resources/datafiles/`. The runtime mount is `./datafiles` (gitignored) so tests may
  *     mutate it via `swapDatafile` without dirtying the working tree.
  *   - `toxiproxy` fronts the nginx; tests configure a single proxy named [[ProxyName]] at startup and add/remove
  *     toxics per-scenario via [[withToxic]] / [[disableProxy]].
  *
  * Tests in this module should gate themselves with `@@ ifDockerAvailable` so the suite self-skips on machines where
  * Docker isn't reachable (e.g. CI). Initialization is lazy: nothing happens until something dereferences
  * [[datafileBaseUrl]] or another accessor.
  */
object OptimizelyItStack {

  /** Name of the single Toxiproxy proxy fronting nginx. Tests refer to it via [[withToxic]]. */
  val ProxyName: String = "datafile"

  /** True when the local Docker daemon is reachable. Cached on first access. */
  lazy val isDockerAvailable: Boolean =
    try DockerClientFactory.instance().isDockerAvailable
    catch { case _: Throwable => false }

  /** `TestAspect` that flips a spec to ignored when Docker isn't reachable. Mirrors the project's preference for IT
    * suites to self-skip rather than fail noisily on machines without Docker.
    */
  val ifDockerAvailable: TestAspectPoly =
    if (isDockerAvailable) TestAspect.identity else TestAspect.ignore

  // Paths — resolved relative to the optimizely-it sub-project base directory. sbt `Test / fork := true` sets the
  // forked JVM's working directory to the sub-project base, so `new File("docker-compose.yml")` resolves correctly.

  private val baseDir: Path          = new File(".").toPath.toAbsolutePath.normalize()
  private val composeFile: File      = baseDir.resolve("docker-compose.yml").toFile
  private val sourceFixtures: Path   = baseDir.resolve("src/test/resources/datafiles")
  private val runtimeDatafiles: Path = baseDir.resolve("datafiles")

  private val DatafileServer = "datafile-server"
  private val Toxiproxy      = "toxiproxy"
  private val NginxPort      = 80
  private val ToxiproxyAdmin = 8474
  private val ToxiproxyProxy = 8666

  private lazy val container: DockerComposeContainer = {
    prepareRuntimeDatafiles()
    val c = DockerComposeContainer(
      composeFiles = ComposeFile(Left(composeFile)),
      exposedServices = Seq(
        ExposedService(DatafileServer, NginxPort, Wait.forHttp("/datafiles/health.txt").forStatusCode(200)),
        ExposedService(Toxiproxy, ToxiproxyAdmin, Wait.forHttp("/version").forStatusCode(200)),
        ExposedService(Toxiproxy, ToxiproxyProxy)
      )
    )
    c.start()
    sys.addShutdownHook { val _ = scala.util.Try(c.stop()) }
    c
  }

  private lazy val client: ToxiproxyClient = {
    val host = container.getServiceHost(Toxiproxy, ToxiproxyAdmin)
    val port = container.getServicePort(Toxiproxy, ToxiproxyAdmin)
    new ToxiproxyClient(host, port)
  }

  private lazy val proxy: Proxy = {
    // The proxy listens on 0.0.0.0:8666 inside the toxiproxy container; testcontainers maps 8666 → a random host port.
    // The upstream uses docker-compose service DNS, which only resolves inside the compose network.
    val existing = Option(client.getProxyOrNull(ProxyName))
    existing.getOrElse(client.createProxy(ProxyName, s"0.0.0.0:$ToxiproxyProxy", s"$DatafileServer:$NginxPort"))
  }

  /** Base URL (no trailing slash) for HTTP traffic to nginx via Toxiproxy. */
  def datafileBaseUrl: String = {
    val _    = proxy // force lazy init order: container → proxy creation → URL
    val host = container.getServiceHost(Toxiproxy, ToxiproxyProxy)
    val port = container.getServicePort(Toxiproxy, ToxiproxyProxy)
    s"http://$host:$port"
  }

  /** URL the Optimizely SDK would poll for the given SDK key. */
  def datafileUrl(sdkKey: String): String = s"$datafileBaseUrl/datafiles/$sdkKey.json"

  /** Run `body` with the given toxic installed; remove it on exit even if `body` throws. */
  def withToxic[A](install: Proxy => Toxic)(body: => A): A = {
    val toxic = install(proxy)
    try body
    finally { val _ = scala.util.Try(toxic.remove()) }
  }

  /** Disable the proxy listener so connections are actively refused. Returns an `AutoCloseable` that re-enables it. */
  def disableProxy(): AutoCloseable = {
    proxy.disable()
    new AutoCloseable {
      override def close(): Unit = { val _ = scala.util.Try(proxy.enable()) }
    }
  }

  /** Replace (or create) the served datafile for `sdkKey` atomically. Subsequent SDK polls will see the new content on
    * their next poll cycle.
    */
  def swapDatafile(sdkKey: String, content: String): Unit = {
    val _    = container
    val dest = runtimeDatafiles.resolve(s"$sdkKey.json")
    val tmp  = runtimeDatafiles.resolve(s".$sdkKey.json.tmp")
    Files.writeString(tmp, content)
    Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }

  /** Wipe `./datafiles/` and re-seed it from the committed fixtures under `src/test/resources/datafiles/`. */
  private def prepareRuntimeDatafiles(): Unit = {
    if (!Files.isDirectory(sourceFixtures))
      throw new IllegalStateException(s"Missing committed fixtures at $sourceFixtures")
    if (Files.exists(runtimeDatafiles)) {
      val stream = Files.walk(runtimeDatafiles)
      try
        stream
          .sorted(java.util.Comparator.reverseOrder[Path]())
          .iterator()
          .asScala
          .foreach { p =>
            if (p != runtimeDatafiles) { val _ = Files.deleteIfExists(p) }
          }
      finally stream.close()
    } else {
      Files.createDirectories(runtimeDatafiles)
    }
    val src = Files.walk(sourceFixtures)
    try
      src.iterator().asScala.foreach { p =>
        val rel = sourceFixtures.relativize(p)
        val out = runtimeDatafiles.resolve(rel.toString)
        if (Files.isDirectory(p)) {
          val _ = Files.createDirectories(out)
        } else {
          Files.createDirectories(out.getParent)
          val _ = Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING)
        }
      }
    finally src.close()
  }
}
