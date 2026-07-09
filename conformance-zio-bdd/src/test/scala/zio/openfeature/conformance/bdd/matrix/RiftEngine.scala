package zio.openfeature.conformance.bdd.matrix

import zio._
import zio.bdd.mock._
import zio.bdd.mock.rift.embedded.EmbeddedRift

/** One in-process Rift mock engine for the whole test JVM. Rift is a native, in-JVM HTTP mock
  * (no Docker, no separate process), so the flag-matrix suites can stand up an Optimizely-CDN stub
  * by pointing the provider straight at a mock space's `baseUri` — replacing the old
  * WireMock-server + mitmproxy-container setup (and the JVM-global `http.proxy*` properties it
  * leaked across suites, #278).
  *
  * Built once and never explicitly closed: the native engine lives for the JVM and is reclaimed at
  * process exit. That is safe here precisely because the module now forks its test JVM (see
  * build.sbt), so sbt tears the whole process down on completion — a harness singleton, not a leak.
  */
object RiftEngine {

  val mockControl: MockControl =
    try
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe
          .run(
            for {
              scope <- Scope.make
              mc <- (Provisioning.live >>> EmbeddedRift.layer).build
                      .provideEnvironment(ZEnvironment[Scope](scope))
                      .map(_.get[MockControl])
            } yield mc
          )
          .getOrThrowFiberFailure()
      }
    catch {
      // The zio-bdd runner reports a failed static init only as "Could not initialize class",
      // swallowing the real cause. Surface it (native-load / FFM issues show up here) — TEMP #278.
      case t: Throwable =>
        System.err.println(s"[RiftEngine] embedded engine init FAILED: ${t.getClass.getName}: ${t.getMessage}")
        t.printStackTrace()
        var c = t.getCause
        while (c != null) {
          System.err.println(s"[RiftEngine] caused by: ${c.getClass.getName}: ${c.getMessage}")
          c = c.getCause
        }
        throw t
    }
}
