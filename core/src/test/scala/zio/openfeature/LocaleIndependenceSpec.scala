package zio.openfeature

import java.io.File
import java.util.Locale

import scala.io.Source
import scala.util.Using

import zio._
import zio.test._
import zio.openfeature.internal.Evaluator

// Case conversion that matches against fixed words must not depend on the JVM's default locale: under a Turkish or
// Azeri locale `i` upper-cases to `İ` and `I` lower-cases to `ı`, so "is-enabled", "disabled" and "FORBIDDEN" stop
// matching their own spellings.
object LocaleIndependenceSpec extends ZIOSpecDefault {

  private val turkish = Locale.forLanguageTag("tr-TR")

  // Switches the JVM-wide default only around a synchronous call, under one lock: tests run in parallel, and two
  // interleaved save/restore pairs could otherwise leave the JVM on the Turkish locale.
  private[openfeature] def underLocale[A](locale: Locale)(thunk: => A): A = LocaleIndependenceSpec.synchronized {
    val previous = Locale.getDefault
    Locale.setDefault(locale)
    try thunk
    finally Locale.setDefault(previous)
  }

  private val unlocalized = """\.(toUpperCase|toLowerCase)\b(?!\(\s*(java\.util\.)?Locale\.ROOT\s*\))""".r

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

  private def sources(dir: File): List[File] =
    Option(dir.listFiles).toList.flatten.flatMap { f =>
      if (f.isDirectory) sources(f)
      else if (f.getName.endsWith(".scala") || f.getName.endsWith(".java")) List(f)
      else Nil
    }

  private val modules = List("core", "extras", "ofrep", "optimizely", "testkit")

  def spec = suite("LocaleIndependenceSpec")(
    test("no main source case-converts without Locale.ROOT") {
      for {
        root  <- ZIO.fromOption(repoRoot).orElseFail(new IllegalStateException("repository root not found"))
        files <- ZIO.attemptBlocking(modules.flatMap(m => sources(new File(root, s"$m/src/main"))))
        found <- ZIO.attemptBlocking(files.flatMap { f =>
          Using.resource(Source.fromFile(f, "UTF-8")) { src =>
            src
              .getLines()
              .zipWithIndex
              .collect {
                case (line, i) if unlocalized.findFirstIn(code(line)).isDefined =>
                  s"${f.getName}:${i + 1}: ${line.trim}"
              }
              .toList
          }
        })
      } yield assertTrue(files.size > 50, found == Nil)
    },
    test("the guard flags a bare conversion and accepts Locale.ROOT") {
      assertTrue(
        unlocalized.findFirstIn(code("""val k = key.toUpperCase.replace("-", "_")""")).isDefined,
        unlocalized.findFirstIn(code("""msg.toLowerCase().contains("x")""")).isDefined,
        unlocalized.findFirstIn(code("""key.toUpperCase(Locale.ROOT)""")).isEmpty,
        unlocalized.findFirstIn(code("""key.toLowerCase(java.util.Locale.ROOT)""")).isEmpty,
        unlocalized.findFirstIn(code("""  // reason.toUpperCase is avoided here""")).isEmpty
      )
    },
    test("a provider reason maps the same under a Turkish default locale") {
      val reason = underLocale(turkish)(Evaluator.toResolutionReason("disabled"))
      assertTrue(reason == ResolutionReason.Disabled)
    },
    test("an auth failure is recognised the same under a Turkish default locale") {
      val upper = underLocale(turkish)(FeatureFlagError.looksLikeAuthFailure("FORBIDDEN"))
      val mixed = underLocale(turkish)(FeatureFlagError.looksLikeAuthFailure("Request UNAUTHORIZED"))
      assertTrue(upper, mixed)
    }
  )
}
