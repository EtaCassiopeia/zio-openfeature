package zio.openfeature

import zio._
import zio.test._

import java.io.File
import scala.io.Source
import scala.util.Using

// Lifecycle probes cost an evaluation nothing only while none sits on the evaluation path. This scans the main sources
// for every probe call, so one cannot be added to an evaluation, a hook or a provider call unnoticed.
object LifecycleProbeGuardSpec extends ZIOSpecDefault {

  private val probeCall = """\bprobe\.at\(""".r

  // The lifecycle code: the swap and shutdown, the event bridge, the timed-out swap's previous providers, and the
  // builds' watchdog and scope finalizer.
  private val allowed = Set(
    "FeatureFlagsLive.scala",
    "EventBridge.scala",
    "OrphanedProviders.scala",
    "FeatureFlagsFactories.scala"
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

  /** Every line that calls a probe, as `file:line: code`. */
  private def probeCalls(files: List[File]): List[(String, String)] =
    files.flatMap { f =>
      Using.resource(Source.fromFile(f, "UTF-8")) { src =>
        src
          .getLines()
          .zipWithIndex
          .collect {
            case (line, i) if probeCall.findFirstIn(code(line)).isDefined => f.getName -> s"${i + 1}: ${line.trim}"
          }
          .toList
      }
    }

  def spec = suite("LifecycleProbeGuardSpec")(
    test("lifecycle probes are called only from the lifecycle code, never on the evaluation path") {
      for {
        root  <- ZIO.fromOption(repoRoot).orElseFail(new IllegalStateException("repository root not found"))
        files <- ZIO.attemptBlocking(scalaFiles(new File(root, "core/src/main")))
        found <- ZIO.attemptBlocking(probeCalls(files))
        outside = found.collect { case (file, at) if !allowed(file) => s"$file:$at" }
      } yield assertTrue(found.size >= 14, outside == Nil)
    },
    test("the guard finds a probe call and skips comments") {
      assertTrue(
        probeCall.findFirstIn(code("    _ <- probe.at(Point.SwapStart)")).isDefined,
        probeCall.findFirstIn(code("  // probe.at(Point.SwapStart) runs here")).isEmpty,
        probeCall.findFirstIn(code("    * calls `probe.at(...)`")).isEmpty
      )
    }
  )
}
