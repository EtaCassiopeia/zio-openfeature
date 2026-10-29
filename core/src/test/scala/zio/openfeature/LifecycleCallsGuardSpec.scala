package zio.openfeature

import java.io.File

import scala.io.Source
import scala.util.Using

import zio._
import zio.test._
import zio.openfeature.internal.ProviderRegistration

// A provider's shutdown() or initialize() can block for as long as its backend is unreachable, so lifecycle code must
// only call them through ProviderRegistration's bounded helpers. This scans the main sources for any other call, in
// whatever wrapper, so the unbounded shape cannot come back unnoticed.
object LifecycleCallsGuardSpec extends ZIOSpecDefault {

  private val providerCall = """\.(shutdown\(\)|initialize\()""".r

  // ProviderRegistration holds the bounded helpers. SwitchableProvider is itself a provider: its initialize() and
  // shutdown() forward to its delegate, and are only ever invoked by the SDK or by those helpers. ProviderStateAccess is
  // an SDK shim that re-runs a failed initialize() through the SDK's state manager; only ProviderRegistration's bounded
  // helper calls it. LongAwareMultiProvider is a provider too: its initialize() and shutdown() wrap the SDK
  // MultiProvider's own, which they call through `super`.
  private val allowed = Set(
    "ProviderRegistration.scala",
    "SwitchableProvider.scala",
    "ProviderStateAccess.scala",
    "LongAwareMultiProvider.scala"
  )

  private def code(line: String): String = {
    val trimmed = line.trim
    if (trimmed.startsWith("*") || trimmed.startsWith("/*")) ""
    else
      line.indexOf("//") match {
        case -1 => line
        case i  => line.substring(0, i)
      }
  }

  private def calls(line: String): Boolean = providerCall.findFirstIn(code(line)).isDefined

  private def repoRoot: Option[File] =
    Iterator
      .iterate(Option(new File(sys.props("user.dir")).getAbsoluteFile))(_.flatMap(d => Option(d.getParentFile)))
      .takeWhile(_.isDefined)
      .flatten
      .find(d => new File(d, "core/src/main/scala").isDirectory)

  private def scalaFiles(dir: File): List[File] =
    Option(dir.listFiles).toList.flatten.flatMap { f =>
      if (f.isDirectory) scalaFiles(f) else if (f.getName.endsWith(".scala")) List(f) else Nil
    }

  private def offenders(files: List[File]): List[String] =
    files.filterNot(f => allowed(f.getName)).flatMap { f =>
      Using.resource(Source.fromFile(f, "UTF-8")) { src =>
        src
          .getLines()
          .zipWithIndex
          .collect {
            case (line, i) if calls(line) => s"${f.getName}:${i + 1}: ${line.trim}"
          }
          .toList
      }
    }

  def spec = suite("LifecycleCallsGuardSpec")(
    test("lifecycle code calls a provider's shutdown() and initialize() only through ProviderRegistration") {
      for {
        root  <- ZIO.fromOption(repoRoot).orElseFail(new IllegalStateException("repository root not found"))
        files <- ZIO.attemptBlocking(scalaFiles(new File(root, "core/src/main")))
        found <- ZIO.attemptBlocking(offenders(files))
      } yield assertTrue(files.size > 20, found == Nil)
    },
    test("the guard flags a direct call in any wrapper, and skips comments") {
      assertTrue(
        calls("ZIO.attemptBlocking(provider.shutdown()).ignore"),
        calls("ZIO.attempt(p.shutdown())"),
        calls("ZIO.succeed(current.get().shutdown())"),
        calls("  provider.initialize(ctx)"),
        calls("Try(composed.initialize(ctx)) // retry"),
        !calls("ProviderRegistration.boundedProviderShutdown(provider, label)"),
        !calls("  // the SDK calls `provider.shutdown()` here"),
        !calls("    * shuts it down with `provider.shutdown()`"),
        !calls("state.eventHub.shutdown *>")
      )
    },
    test("a failure path waits for a failed provider's shutdown at most its init timeout, capped") {
      assertTrue(
        ProviderRegistration.failedProviderShutdownTimeout(300.millis) == 300.millis,
        ProviderRegistration.failedProviderShutdownTimeout(15.seconds) == 15.seconds,
        ProviderRegistration.failedProviderShutdownTimeout(60.seconds) == 15.seconds
      )
    }
  )
}
