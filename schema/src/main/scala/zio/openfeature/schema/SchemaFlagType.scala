package zio.openfeature.schema

import scala.util.Try

import zio.{Chunk, Unsafe}
import zio.openfeature.{FlagType, WireType}
import zio.schema.{Schema, StandardType}
import zio.schema.annotation.fieldDefaultValue

/** A [[zio.openfeature.FlagType]] read from a zio-schema `Schema`, on Scala 2.13 and 3.
  *
  * {{{
  * // Scala 2.13
  * implicit val planFlagType: FlagType[Plan] = SchemaFlagType[Plan](DeriveSchema.gen[Plan])
  * // Scala 3
  * given FlagType[Plan] = SchemaFlagType[Plan](using DeriveSchema.gen[Plan])
  * }}}
  *
  * Explicit on purpose, never an implicit conversion from any `Schema`: zio-schema has schemas for `Int`, `String`,
  * `Option`, `List` and the rest, which would compete with the built-in `FlagType`s.
  *
  * ==Wire mapping==
  *
  * Decoding is directed by the schema, and encoding produces exactly what decoding accepts, so the round-trip law
  * holds.
  *
  *   - A '''primitive''' (`Boolean`, `String`, `Int`, `Long`, `Float`, `Double`) is that scalar, read through the
  *     built-in instance, so an integral `Double` reads as an `Int` or `Long`. Other primitives fail to decode.
  *   - A '''case class''' is an object. Unknown keys are ignored. A missing (or `null`) key takes the field's declared
  *     default, else `None` for an `Option` field, else fails naming the field.
  *   - A '''sealed trait''' or enum whose cases all have no fields is a string holding the case name. Reading ignores
  *     case, but an exact match wins; an unknown name fails.
  *   - A sealed trait with any case that has fields is a wrapper object, `{"<CaseName>": {<fields>}}`; a case without
  *     fields is `{"<CaseName>": {}}`.
  *   - An `Option` is the value, or absent / `null` for `None`. A sequence is a list, and a `Map[String, V]` is an
  *     object. A transformed schema is its underlying shape.
  *
  * The [[zio.openfeature.FlagType.wire]] is a scalar for a primitive, `String` for a case-name enum and `Object`
  * otherwise. For an optional flag use `FlagType[Option[Plan]]`, which core builds from this instance.
  *
  * The instance's `defaultValue` is a type-level zero (a record of its fields' zeros, an enum's first case), not the
  * declared field defaults. Evaluation never serves it as a flag's value.
  */
object SchemaFlagType {

  def apply[A](implicit schema: Schema[A]): FlagType[A] = new FlagType[A] {
    def typeName: String = nameOf(schema)

    override def wire: WireType = wireOf(schema)

    def decode(value: Any): Either[String, A] = decodeWith(schema, value).map(_.asInstanceOf[A])

    override def encode(value: A): Any = encodeWith(schema, value)

    // Lazy: a recursive schema is only walked when the zero is asked for.
    lazy val defaultValue: A =
      zeroOf(schema).fold(
        reason => throw new IllegalArgumentException(s"$typeName: cannot build a default value: $reason"),
        _.asInstanceOf[A]
      )
  }

  // Schema shapes

  private def unwrap(schema: Schema[_]): Schema[_] = schema match {
    case l: Schema.Lazy[_] => unwrap(l.schema)
    case other             => other
  }

  private def isFieldless(schema: Schema[_]): Boolean = unwrap(schema) match {
    case r: Schema.Record[_] => r.fields.isEmpty
    case _                   => false
  }

  // A sum whose every case carries no fields is stored as its case name.
  private def isCaseNameEnum(e: Schema.Enum[_]): Boolean = e.cases.forall(c => isFieldless(c.schema))

  private def builtIn(standardType: StandardType[_]): Option[FlagType[Any]] = {
    val instance: Option[FlagType[_]] = standardType match {
      case StandardType.BoolType   => Some(FlagType.booleanFlagType)
      case StandardType.StringType => Some(FlagType.stringFlagType)
      case StandardType.IntType    => Some(FlagType.intFlagType)
      case StandardType.LongType   => Some(FlagType.longFlagType)
      case StandardType.FloatType  => Some(FlagType.floatFlagType)
      case StandardType.DoubleType => Some(FlagType.doubleFlagType)
      case _                       => None
    }
    instance.map(_.asInstanceOf[FlagType[Any]])
  }

  private def wireOf(schema: Schema[_]): WireType = unwrap(schema) match {
    case p: Schema.Primitive[_]                 => builtIn(p.standardType).fold[WireType](WireType.Object)(_.wire)
    case t: Schema.Transform[_, _, _]           => wireOf(t.schema)
    case e: Schema.Enum[_] if isCaseNameEnum(e) => WireType.String
    case _                                      => WireType.Object
  }

  private def nameOf(schema: Schema[_]): String = unwrap(schema) match {
    case p: Schema.Primitive[_]       => builtIn(p.standardType).fold(p.standardType.tag)(_.typeName)
    case r: Schema.Record[_]          => r.id.name
    case e: Schema.Enum[_]            => e.id.name
    case o: Schema.Optional[_]        => s"Option[${nameOf(o.schema)}]"
    case s: Schema.Sequence[_, _, _]  => s"List[${nameOf(s.elementSchema)}]"
    case m: Schema.Map[_, _]          => s"Map[${nameOf(m.keySchema)}, ${nameOf(m.valueSchema)}]"
    case t: Schema.Transform[_, _, _] => nameOf(t.schema)
    case other                        => other.getClass.getSimpleName
  }

  // Decoding

  private def decodeWith(schema: Schema[_], value: Any): Either[String, Any] = unwrap(schema) match {
    case p: Schema.Primitive[_] =>
      builtIn(p.standardType) match {
        case Some(ft) => ft.decode(value)
        case None     => Left(s"Unsupported primitive type ${p.standardType.tag}")
      }

    case r: Schema.Record[_] => asObject(value, r.id.name).flatMap(decodeRecord(r, _))

    case e: Schema.Enum[_] if isCaseNameEnum(e) => decodeCaseName(e, value)

    case e: Schema.Enum[_] =>
      asObject(value, e.id.name).flatMap { payload =>
        payload.toList match {
          case (name, inner) :: Nil =>
            e.caseOf(name) match {
              case Some(c) =>
                decodeWith(c.schema, inner)
                  .map(c.asInstanceOf[Schema.Case[Any, Any]].construct)
                  .left
                  .map(reason => s"${e.id.name}.$name: $reason")
              case None =>
                Left(s"Unknown ${e.id.name} case '$name' (expected one of ${e.cases.map(_.caseName).mkString(", ")})")
            }
          case _ =>
            Left(s"${e.id.name}: expected an object with exactly one key naming the case, got ${payload.size} keys")
        }
      }

    case o: Schema.Optional[_] =>
      value match {
        case null    => Right(None)
        case None    => Right(None)
        case Some(v) => decodeWith(o.schema, v).map(Some(_))
        case other   => decodeWith(o.schema, other).map(Some(_))
      }

    case s: Schema.Sequence[_, _, _] =>
      val seq = s.asInstanceOf[Schema.Sequence[Any, Any, Any]]
      asList(value).flatMap { elems =>
        elems.zipWithIndex
          .foldLeft[Either[String, List[Any]]](Right(Nil)) { case (acc, (elem, i)) =>
            acc.flatMap(done => decodeWith(seq.elementSchema, elem).left.map(r => s"[$i]: $r").map(_ :: done))
          }
          .map(done => seq.fromChunk(Chunk.fromIterable(done.reverse)))
      }

    case m: Schema.Map[_, _] if isStringKey(m) =>
      asObject(value, nameOf(m)).flatMap { payload =>
        payload.toList
          .foldLeft[Either[String, List[(String, Any)]]](Right(Nil)) { case (acc, (k, v)) =>
            acc.flatMap(done => decodeWith(m.valueSchema, v).left.map(r => s"$k: $r").map(k -> _ :: done))
          }
          .map(_.toMap)
      }

    case m: Schema.Map[_, _] => Left(s"Unsupported ${nameOf(m)}: only String keys can be stored in an object")

    case t: Schema.Transform[_, _, _] =>
      val transform = t.asInstanceOf[Schema.Transform[Any, Any, Any]]
      decodeWith(transform.schema, value).flatMap(transform.f)

    case other => Left(s"Unsupported schema ${other.getClass.getSimpleName}")
  }

  private def isStringKey(m: Schema.Map[_, _]): Boolean = unwrap(m.keySchema) match {
    case p: Schema.Primitive[_] => p.standardType == StandardType.StringType
    case _                      => false
  }

  private def decodeRecord(record: Schema.Record[_], payload: Map[String, Any]): Either[String, Any] = {
    val name = record.id.name
    record.fields.toList
      .foldLeft[Either[String, List[Any]]](Right(Nil)) { (acc, field) =>
        acc.flatMap(done => fieldValue(name, field, payload).map(_ :: done))
      }
      .flatMap { values =>
        // Guarded: a constructor check, or a value of the wrong runtime type, must stay inside the `Either` contract.
        Try(Unsafe.unsafe(u => record.construct(Chunk.fromIterable(values.reverse))(u))).toEither.left
          .map(e => s"$name: cannot construct from the decoded fields: $e")
          .flatMap(_.left.map(reason => s"$name: $reason"))
      }
  }

  private def fieldValue(record: String, field: Schema.Field[_, _], payload: Map[String, Any]): Either[String, Any] = {
    val label = field.name
    payload.get(label) match {
      case Some(raw) if raw != null => decodeWith(field.schema, raw).left.map(reason => s"$record.$label: $reason")
      case _                        =>
        // Absent, or `null` (which the SDK drops anyway): the declared default, else `None` for an optional field.
        val declared = field.annotations.collectFirst { case d: fieldDefaultValue[_] => d.value }
        declared match {
          case Some(default) => Right(default)
          case None          =>
            unwrap(field.schema) match {
              case _: Schema.Optional[_] => Right(None)
              case _                     => Left(s"$record.$label: missing key '$label'")
            }
        }
    }
  }

  private def decodeCaseName(e: Schema.Enum[_], value: Any): Either[String, Any] = {
    val name   = e.id.name
    val labels = e.cases.map(_.caseName)
    value match {
      case str: String =>
        // Exact first, so two names that differ only by case each decode back to themselves.
        val exact = labels.indexOf(str)
        (if (exact >= 0) exact else labels.indexWhere(_.equalsIgnoreCase(str))) match {
          case -1 => Left(s"Unknown $name: '$str' (expected one of ${labels.mkString(", ")})")
          case i  =>
            val c = e.cases(i).asInstanceOf[Schema.Case[Any, Any]]
            zeroOf(c.schema).map(c.construct)
        }
      case null  => Left(s"Cannot convert null to $name")
      case other => Left(s"Cannot convert ${other.getClass.getSimpleName} to $name")
    }
  }

  private def asObject(value: Any, name: String): Either[String, Map[String, Any]] = value match {
    case m: Map[_, _]           => Right(m.asInstanceOf[Map[String, Any]])
    case m: java.util.Map[_, _] =>
      import scala.jdk.CollectionConverters._
      Right(m.asScala.toMap.asInstanceOf[Map[String, Any]])
    case null  => Left(s"Cannot convert null to $name")
    case other => Left(s"Cannot convert ${other.getClass.getSimpleName} to $name")
  }

  private def asList(value: Any): Either[String, List[Any]] = value match {
    case list: List[_]            => Right(list)
    case seq: Seq[_]              => Right(seq.toList)
    case arr: Array[_]            => Right(arr.toList)
    case jlist: java.util.List[_] =>
      import scala.jdk.CollectionConverters._
      Right(jlist.asScala.toList)
    case null  => Left("Cannot convert null to List")
    case other => Left(s"Cannot convert ${other.getClass.getSimpleName} to List")
  }

  // Encoding

  private def encodeWith(schema: Schema[_], value: Any): Any = unwrap(schema) match {
    case p: Schema.Primitive[_] => builtIn(p.standardType).fold(value)(_.encode(value))

    case r: Schema.Record[_] =>
      r.fields.map { field =>
        val f = field.asInstanceOf[Schema.Field[Any, Any]]
        f.name -> encodeWith(f.schema, f.get(value))
      }.toMap

    case e: Schema.Enum[_] =>
      val enumSchema = e.asInstanceOf[Schema.Enum[Any]]
      enumSchema.caseOf(value) match {
        case Some(c) if isCaseNameEnum(e) => c.caseName
        case Some(c)                      =>
          val typed = c.asInstanceOf[Schema.Case[Any, Any]]
          Map(c.caseName -> encodeWith(typed.schema, typed.deconstruct(value)))
        case None =>
          throw new IllegalArgumentException(s"${e.id.name}: no case of the schema matches $value")
      }

    case o: Schema.Optional[_] =>
      value match {
        case Some(v) => encodeWith(o.schema, v)
        case _       => null
      }

    case s: Schema.Sequence[_, _, _] =>
      val seq = s.asInstanceOf[Schema.Sequence[Any, Any, Any]]
      seq.toChunk(value).map(encodeWith(seq.elementSchema, _)).toList

    case m: Schema.Map[_, _] =>
      value.asInstanceOf[Map[Any, Any]].map { case (k, v) => k -> encodeWith(m.valueSchema, v) }

    case t: Schema.Transform[_, _, _] =>
      val transform = t.asInstanceOf[Schema.Transform[Any, Any, Any]]
      transform.g(value) match {
        case Right(underlying) => encodeWith(transform.schema, underlying)
        // `encode` has no error channel; a value the schema itself cannot map back is a programming error.
        case Left(reason) => throw new IllegalArgumentException(s"${nameOf(t)}: cannot encode $value: $reason")
      }

    case _ => value
  }

  // The type-level zero

  private def zeroOf(schema: Schema[_]): Either[String, Any] = unwrap(schema) match {
    case p: Schema.Primitive[_] =>
      builtIn(p.standardType).fold[Either[String, Any]](p.standardType.defaultValue)(ft => Right(ft.defaultValue))

    case r: Schema.Record[_] =>
      r.fields.toList
        .foldLeft[Either[String, List[Any]]](Right(Nil)) { (acc, field) =>
          acc.flatMap(done => zeroOf(field.schema).map(_ :: done))
        }
        .flatMap { values =>
          Try(Unsafe.unsafe(u => r.construct(Chunk.fromIterable(values.reverse))(u))).toEither.left
            .map(_.toString)
            .flatMap(identity)
        }

    case e: Schema.Enum[_] =>
      e.cases.headOption match {
        case Some(c) => zeroOf(c.schema).map(c.asInstanceOf[Schema.Case[Any, Any]].construct)
        case None    => Left(s"${e.id.name} has no cases")
      }

    case _: Schema.Optional[_] => Right(None)

    case s: Schema.Sequence[_, _, _] => Right(s.asInstanceOf[Schema.Sequence[Any, Any, Any]].empty)

    case _: Schema.Map[_, _] => Right(Map.empty[String, Any])

    case t: Schema.Transform[_, _, _] =>
      val transform = t.asInstanceOf[Schema.Transform[Any, Any, Any]]
      zeroOf(transform.schema).flatMap(transform.f)

    case other => other.defaultValue
  }
}
