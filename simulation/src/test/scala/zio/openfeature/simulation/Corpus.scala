package zio.openfeature.simulation

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters._

import zio._

/** The regression corpus: plans checked in under `resources/regressions/<dir>/`, replayed before the generated cases,
  * and the place a failing run writes its plan for CI to upload.
  */
object Corpus {

  /** The (file name, text) of every `.json` entry of a corpus directory on the test classpath. An empty or missing
    * directory is an error, so a broken resource path cannot pass vacuously.
    */
  def load(dir: String): Either[String, List[(String, String)]] =
    Option(getClass.getResource(s"/regressions/$dir")).toRight(s"corpus directory regressions/$dir not found").flatMap {
      url =>
        val files = scala.util.Using.resource(Files.list(Paths.get(url.toURI)))(_.iterator.asScala.toList)
        val jsons = files.filter(_.getFileName.toString.endsWith(".json")).sortBy(_.getFileName.toString)
        if (jsons.isEmpty) Left(s"corpus directory regressions/$dir holds no .json entry")
        else
          jsons.foldRight[Either[String, List[(String, String)]]](Right(Nil)) { (p, acc) =>
            for {
              rest <- acc
              text <- scala.util
                .Try(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
                .toEither
                .left
                .map(e => s"cannot read ${p.getFileName}: $e")
            } yield (p.getFileName.toString -> text.trim) :: rest
          }
    }

  /** Where a failing run writes its plan: the `zof.simulation.failures` system property, set by the build. */
  def failuresDir: Option[Path] = sys.props.get("zof.simulation.failures").map(Paths.get(_))

  /** Overwrites `<failuresDir>/<spec>.json`; the last write of a failing check is the shrunk plan. Best effort: the
    * failure label already carries the plan, so a write failure is logged and never fails the property.
    */
  def record(spec: String, json: String): UIO[Unit] =
    ZIO.foreachDiscard(failuresDir) { dir =>
      ZIO
        .attemptBlocking {
          Files.createDirectories(dir)
          Files.write(dir.resolve(s"$spec.json"), (json + "\n").getBytes(StandardCharsets.UTF_8)): Unit
        }
        .catchAll(e => ZIO.logWarning(s"could not record the failing plan to $dir: $e"))
    }

  /** Runs every entry of a corpus directory through `run`; the result lists the failing entries with their traces (and
    * a directory that cannot be loaded), so an empty list means every entry passed.
    */
  def replay[A: PlanCodec](dir: String)(run: A => ZIO[Any, String, Unit]): UIO[List[String]] =
    load(dir) match {
      case Left(e)        => ZIO.succeed(List(e))
      case Right(entries) =>
        ZIO
          .foreach(entries) { case (name, text) =>
            PlanCodec.read[A](text) match {
              case Left(e)     => ZIO.succeed(Some(s"$name: cannot decode: $e"))
              case Right(plan) => run(plan).either.map(_.left.toOption.map(t => s"$name:\n$t"))
            }
          }
          .map(_.flatten)
    }
}
