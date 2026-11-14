package zio.openfeature.testsupport

import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters._
import scala.util.{Try, Using}

import zio._

/** Behaviours that TLC's `-generate` wrote for a model (`formal/generate.py`), read as `ModelState`s: each state's
  * variables and the `act` that produced it. The model-specific part, the map from a state to the code's, stays with
  * the replay that uses them.
  */
object ModelBehaviours {

  // A small JSON reader for what TLC's Json module writes: objects, arrays, strings, integers, booleans and null.
  sealed trait Json
  object Json {
    final case class Str(value: String)             extends Json
    final case class Num(value: Long)               extends Json
    final case class Bool(value: Boolean)           extends Json
    case object Null                                extends Json
    final case class Arr(items: Vector[Json])       extends Json
    final case class Obj(fields: Map[String, Json]) extends Json

    def parse(text: String): Either[String, Json] = {
      val reader = new Reader(text)
      for {
        value <- reader.value()
        _     <- reader.end()
      } yield value
    }

    final private class Reader(text: String) {
      private var at = 0

      private def skip(): Unit = while (at < text.length && text.charAt(at).isWhitespace) at += 1

      private def fail(what: String): Either[String, Nothing] = Left(s"$what at offset $at")

      def end(): Either[String, Unit] = { skip(); if (at == text.length) Right(()) else fail("trailing text") }

      def value(): Either[String, Json] = {
        skip()
        if (at >= text.length) fail("unexpected end")
        else
          text.charAt(at) match {
            case '{'                        => at += 1; obj(Map.empty)
            case '['                        => at += 1; arr(Vector.empty)
            case '"'                        => str().map(Str(_))
            case 't'                        => literal("true", Bool(true))
            case 'f'                        => literal("false", Bool(false))
            case 'n'                        => literal("null", Null)
            case c if c == '-' || c.isDigit => num()
            case c                          => fail(s"unexpected '$c'")
          }
      }

      private def literal(word: String, value: Json): Either[String, Json] =
        if (text.startsWith(word, at)) { at += word.length; Right(value) }
        else fail(s"expected $word")

      private def num(): Either[String, Json] = {
        val start = at
        if (text.charAt(at) == '-') at += 1
        while (at < text.length && text.charAt(at).isDigit) at += 1
        if (at < text.length && (text.charAt(at) == '.' || text.charAt(at) == 'e' || text.charAt(at) == 'E'))
          fail("a number that is not an integer")
        else Try(text.substring(start, at).toLong).toOption.map(Num(_)).toRight(s"not an integer at offset $start")
      }

      private def str(): Either[String, String] = {
        at += 1
        val out = new StringBuilder
        var bad = Option.empty[String]
        while (bad.isEmpty && at < text.length && text.charAt(at) != '"')
          text.charAt(at) match {
            case '\\' if at + 1 < text.length =>
              text.charAt(at + 1) match {
                case '"'                         => out += '"'; at += 2
                case '\\'                        => out += '\\'; at += 2
                case '/'                         => out += '/'; at += 2
                case 'n'                         => out += '\n'; at += 2
                case 't'                         => out += '\t'; at += 2
                case 'r'                         => out += '\r'; at += 2
                case 'b'                         => out += '\b'; at += 2
                case 'f'                         => out += '\f'; at += 2
                case 'u' if at + 5 < text.length =>
                  Try(Integer.parseInt(text.substring(at + 2, at + 6), 16)).toOption match {
                    case Some(code) => out += code.toChar; at += 6
                    case None       => bad = Some(s"bad unicode escape at offset $at")
                  }
                case other => bad = Some(s"bad escape '\\$other' at offset $at")
              }
            case c => out += c; at += 1
          }
        bad match {
          case Some(why)                 => Left(why)
          case None if at >= text.length => fail("unterminated string")
          case None                      => at += 1; Right(out.toString)
        }
      }

      private def arr(items: Vector[Json]): Either[String, Json] = {
        skip()
        if (at < text.length && text.charAt(at) == ']') { at += 1; Right(Arr(items)) }
        else
          value().flatMap { v =>
            skip()
            if (at < text.length && text.charAt(at) == ',') { at += 1; arr(items :+ v) }
            else if (at < text.length && text.charAt(at) == ']') { at += 1; Right(Arr(items :+ v)) }
            else fail("expected , or ]")
          }
      }

      private def obj(fields: Map[String, Json]): Either[String, Json] = {
        skip()
        if (at < text.length && text.charAt(at) == '}') { at += 1; Right(Obj(fields)) }
        else if (at >= text.length || text.charAt(at) != '"') fail("expected a key")
        else
          str().flatMap { key =>
            skip()
            if (at >= text.length || text.charAt(at) != ':') fail("expected :")
            else {
              at += 1
              value().flatMap { v =>
                skip()
                if (at < text.length && text.charAt(at) == ',') { at += 1; obj(fields + (key -> v)) }
                else if (at < text.length && text.charAt(at) == '}') { at += 1; Right(Obj(fields + (key -> v))) }
                else fail("expected , or }")
              }
            }
          }
      }
    }
  }

  /** One state of a behaviour: the model's variables after `act`. */
  final case class ModelState(act: Act, vars: Map[String, Json]) {
    def field(name: String): Json = vars.getOrElse(name, sys.error(s"no variable $name"))

    def asStr(j: Json): String = j match {
      case Json.Str(s) => s
      case other       => sys.error(s"not a string: $other")
    }
    def asNum(j: Json): Long = j match {
      case Json.Num(n) => n
      case other       => sys.error(s"not a number: $other")
    }
    def asBool(j: Json): Boolean = j match {
      case Json.Bool(b) => b
      case other        => sys.error(s"not a boolean: $other")
    }
    def str(name: String): String   = asStr(field(name))
    def num(name: String): Long     = asNum(field(name))
    def bool(name: String): Boolean = asBool(field(name))

    /** A function variable. TLC writes a function whose domain is 1..n as an array, and any other as an object. */
    def fun(name: String): Map[String, Json] = field(name) match {
      case Json.Obj(fields) => fields
      case Json.Arr(items)  => items.zipWithIndex.map { case (item, i) => (i + 1).toString -> item }.toMap
      case other            => sys.error(s"$name is not a function: $other")
    }
    def record(name: String, fields: Map[String, Json], key: String): String =
      asStr(fields.getOrElse(key, sys.error(s"$name has no field $key")))
  }

  /** The action that produced a state, with its parameters. */
  final case class Act(name: String, params: Map[String, Json]) {
    def str(name: String): String = params.get(name) match {
      case Some(Json.Str(s)) => s
      case other             => sys.error(s"$this has no string $name: $other")
    }
    def bool(name: String): Boolean = params.get(name) match {
      case Some(Json.Bool(b)) => b
      case other              => sys.error(s"$this has no boolean $name: $other")
    }
  }

  object Act {
    def apply(name: String, params: (String, Json)*): Act = Act(name, params.toMap)
  }

  final case class Behaviour(name: String, states: List[ModelState])

  def parseBehaviour(name: String, lines: List[String]): Either[String, Behaviour] = {
    val parsed = lines.zipWithIndex.filter(_._1.trim.nonEmpty).map { case (line, i) =>
      Json.parse(line).left.map(e => s"$name line ${i + 1}: $e").flatMap {
        case Json.Obj(fields) =>
          fields.get("act") match {
            case Some(Json.Obj(act)) =>
              act.get("name") match {
                case Some(Json.Str(actName)) =>
                  Right(ModelState(Act(actName, act - "name"), fields - "act" - "_action"))
                case _ => Left(s"$name line ${i + 1}: act has no name")
              }
            case _ => Left(s"$name line ${i + 1}: no act")
          }
        case _ => Left(s"$name line ${i + 1}: not an object")
      }
    }
    parsed
      .foldLeft[Either[String, Vector[ModelState]]](Right(Vector.empty)) { (acc, next) =>
        acc.flatMap(states => next.map(states :+ _))
      }
      .flatMap { states =>
        if (states.size < 2) Left(s"$name: fewer than two states")
        else if (states.head.act.name != "Init") Left(s"$name: does not start with Init")
        else Right(Behaviour(name, states.toList))
      }
  }

  def files(path: Path): List[Path] =
    if (Files.isDirectory(path))
      Using.resource(Files.list(path))(
        _.iterator().asScala.filter(_.toString.endsWith(".ndjson")).toList.sortBy(_.getFileName.toString)
      )
    else if (Files.exists(path)) List(path)
    else sys.error(s"$path does not exist")

  def location(property: String, environment: String): Option[String] =
    sys.props.get(property).orElse(sys.env.get(environment)).map(_.trim).filter(_.nonEmpty)

  def load(location: String): Task[List[Behaviour]] =
    ZIO.attempt(location.split(',').toList.map(_.trim).filter(_.nonEmpty).map(Paths.get(_)).flatMap(files)).flatMap {
      paths =>
        ZIO.foreach(paths) { p =>
          ZIO
            .attempt(Files.readAllLines(p).asScala.toList)
            .flatMap(lines =>
              ZIO.fromEither(parseBehaviour(p.getFileName.toString, lines)).mapError(new IllegalArgumentException(_))
            )
        }
    }

  def loadRequired(location: Option[String], setting: String): Task[List[Behaviour]] =
    location.fold[Task[List[Behaviour]]](ZIO.fail(new IllegalStateException(s"$setting is not set")))(load)
}
