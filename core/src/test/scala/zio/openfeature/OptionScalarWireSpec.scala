package zio.openfeature

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import zio._
import zio.test._
import zio.test.TestAspect.{sequential, withLiveClock}
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  ErrorCode => OFErrorCode,
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  MutableStructure,
  OpenFeatureAPI,
  ProviderState,
  Value
}

/** #439: evaluation dispatches on the typed [[WireType]] instead of a free-form string.
  *
  *   - `Option[A]` over a scalar `A` resolves through `A`'s resolver. A `None` default is sent as `A`'s zero and read
  *     back as `None` when the provider fell back to it (an error code, or reason `DEFAULT`/`DISABLED`/`ERROR` with the
  *     zero itself);
  *   - a legacy `wireType` override is matched case-insensitively;
  *   - only the built-in `Map[String, Any]` instance takes the raw-map shortcut on the object path, so a custom
  *     instance named `"Object"` has its `decode` run.
  *
  * Shared (cross-compiled) test source dir: must compile on 2.13 and 3, so braces only, no `given`/`using`.
  */
object OptionScalarWireSpec extends ZIOSpecDefault {

  // A string-backed type via `mapped`, the 2.13-compatible stand-in for a derived enum (that case is in
  // FlagTypeDerivedSpec).
  sealed trait Phase extends Product with Serializable
  object Phase {
    case object Off       extends Phase
    case object DualWrite extends Phase
  }
  implicit val phaseFlagType: FlagType[Phase] =
    FlagType.mapped[Phase, String]("Phase", Phase.Off)(
      s => if (s == "dual_write") Phase.DualWrite else Phase.Off,
      p => if (p == Phase.DualWrite) "dual_write" else "off"
    )

  // A legacy instance that spells its wire type in lower case. Before #439 that silently meant "object".
  final case class Code(s: String)
  implicit val codeFlagType: FlagType[Code] = new FlagType[Code] {
    def typeName: String                         = "Code"
    override def wireType: String                = "string"
    def defaultValue: Code                       = Code("")
    def decode(value: Any): Either[String, Code] = FlagType[String].decode(value).map(Code(_))
    override def encode(value: Code): Any        = value.s
  }

  // An object-backed custom type named "Object" whose default is not a `Map`. The old name test sent it down the
  // built-in branch, which cast the default to `Map[String, Any]`.
  final case class Bag(m: Map[String, Any])
  implicit val bagFlagType: FlagType[Bag] =
    FlagType.from[Bag]("Object", Bag(Map.empty), v => FlagType.objectFlagType.decode(v).map(Bag(_)), _.m)

  /** Answers by key, and records which getter ran and the default it was handed.
    *
    * Keys: `*.hit` serves a real value with `TARGETING_MATCH`; `*.default` echoes the default with `DEFAULT`;
    * `*.missing` answers `FLAG_NOT_FOUND`; `b.false-targeted` serves `false` with `TARGETING_MATCH`; `b.disabled`
    * echoes the default with `DISABLED`; `i.seven-default` serves `7` with `DEFAULT`. Any object key gets `{a: "x"}`.
    */
  private class ScriptedProvider extends EventProvider {
    val lastCall    = new AtomicReference[String]("")
    val lastDefault = new AtomicReference[Any](null)

    private def record(getter: String, key: String, default: Any): Unit = {
      lastCall.set(s"$getter:$key")
      lastDefault.set(default)
    }

    private def script[T](key: String, default: T, hit: T): dev.openfeature.sdk.ProviderEvaluation[T] =
      if (key.endsWith(".missing")) ProviderEvaluations.error[T](default, OFErrorCode.FLAG_NOT_FOUND, "no such flag")
      else if (key.endsWith(".default")) ProviderEvaluations.of[T](default, "DEFAULT")
      else ProviderEvaluations.of[T](hit, "TARGETING_MATCH")

    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Scripted" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()

    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = {
      record("boolean", k, d)
      k match {
        case "b.false-targeted" => ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.FALSE, "TARGETING_MATCH")
        case "b.disabled"       => ProviderEvaluations.of[java.lang.Boolean](d, "DISABLED")
        case _                  => script[java.lang.Boolean](k, d, java.lang.Boolean.TRUE)
      }
    }
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) = {
      record("string", k, d)
      script[String](k, d, if (k.startsWith("p.")) "dual_write" else "hello")
    }
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) = {
      record("integer", k, d)
      if (k == "i.seven-default") ProviderEvaluations.of[java.lang.Integer](Int.box(7), "DEFAULT")
      else script[java.lang.Integer](k, d, Int.box(42))
    }
    override def getLongEvaluation(k: String, d: java.lang.Long, c: OFEvaluationContext) = {
      record("long", k, d)
      script[java.lang.Long](k, d, java.lang.Long.valueOf(9000000000L))
    }
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) = {
      record("double", k, d)
      script[java.lang.Double](k, d, Double.box(2.5))
    }
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = {
      record("object", k, d)
      val structure = new MutableStructure()
      structure.add("a", "x")
      ProviderEvaluations.of[Value](new Value(structure), "TARGETING_MATCH")
    }
  }

  private def build(p: EventProvider, tag: String): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      p,
      domain = Some(s"opt-wire-$tag-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      evaluationTimeout = Some(5.seconds)
    )

  private def withFlags(tag: String)(f: (ScriptedProvider, FeatureFlags) => ZIO[Scope, Any, TestResult]) =
    ZIO.scoped {
      val provider = new ScriptedProvider
      build(provider, tag).flatMap(ff => f(provider, ff))
    }

  def spec = suite("OptionScalarWireSpec")(
    suite("WireType.fromName")(
      test("matches the scalar names ignoring case") {
        assertTrue(
          WireType.fromName("string") == WireType.String,
          WireType.fromName("String") == WireType.String,
          WireType.fromName("BOOLEAN") == WireType.Boolean,
          WireType.fromName("int") == WireType.Int,
          WireType.fromName("Long") == WireType.Long,
          WireType.fromName("float") == WireType.Float,
          WireType.fromName("dOuBlE") == WireType.Double,
          WireType.fromName("object") == WireType.Object
        )
      },
      test("reads any other name, or null, as Object") {
        assertTrue(
          WireType.fromName("Str") == WireType.Object,
          WireType.fromName("Rollout") == WireType.Object,
          WireType.fromName("Option[Int]") == WireType.Object,
          WireType.fromName("") == WireType.Object,
          WireType.fromName(null) == WireType.Object
        )
      },
      test("every case's name reads back as itself, and scalars is every case but Object") {
        val all = List(
          WireType.Boolean,
          WireType.String,
          WireType.Int,
          WireType.Long,
          WireType.Float,
          WireType.Double,
          WireType.Object
        )
        assertTrue(
          all.map(_.name) == List("Boolean", "String", "Int", "Long", "Float", "Double", "Object"),
          all.forall(w => WireType.fromName(w.name) == w),
          WireType.scalars == all.filterNot(_ == WireType.Object).toSet
        )
      }
    ),
    suite("Option over a scalar resolves through the scalar resolver")(
      test("Option[String] against a string flag is Some, with no error code") {
        withFlags("opt-str") { (p, ff) =>
          ff.valueDetails[Option[String]]("s.hit", None).map { r =>
            val call = p.lastCall.get
            assertTrue(r.value == Some("hello"), r.errorCode.isEmpty, call == "string:s.hit")
          }
        }
      },
      test("Option[Int] against an integer flag is Some") {
        withFlags("opt-int") { (p, ff) =>
          ff.value[Option[Int]]("i.hit", None).map { v =>
            val call = p.lastCall.get
            assertTrue(v == Some(42), call == "integer:i.hit")
          }
        }
      },
      test("Option[Boolean] against a boolean flag is Some") {
        withFlags("opt-bool") { (p, ff) =>
          ff.value[Option[Boolean]]("b.hit", None).map { v =>
            val call = p.lastCall.get
            assertTrue(v == Some(true), call == "boolean:b.hit")
          }
        }
      },
      test("Option over a mapped string type is Some of the decoded domain value") {
        withFlags("opt-phase") { (p, ff) =>
          ff.value[Option[Phase]]("p.hit", None).map { v =>
            val call = p.lastCall.get
            assertTrue(v == Some(Phase.DualWrite), call == "string:p.hit")
          }
        }
      },
      test("Option[Long] and Option[Double] reach their own resolvers") {
        withFlags("opt-num") { (p, ff) =>
          for {
            l     <- ff.value[Option[Long]]("l.hit", None)
            lCall <- ZIO.succeed(p.lastCall.get)
            d     <- ff.value[Option[Double]]("d.hit", None)
            dCall <- ZIO.succeed(p.lastCall.get)
          } yield assertTrue(l == Some(9000000000L), lCall == "long:l.hit", d == Some(2.5), dCall == "double:d.hit")
        }
      },
      test("a None default is sent as the element's zero, never as null") {
        withFlags("sentinel") { (p, ff) =>
          for {
            _  <- ff.value[Option[Boolean]]("b.hit", None)
            b  <- ZIO.succeed(p.lastDefault.get)
            _  <- ff.value[Option[String]]("s.hit", None)
            s  <- ZIO.succeed(p.lastDefault.get)
            _  <- ff.value[Option[Phase]]("p.hit", None)
            ph <- ZIO.succeed(p.lastDefault.get)
          } yield assertTrue(b == java.lang.Boolean.FALSE, s == "", ph == "off")
        }
      },
      test("a Some default is sent encoded and served back as Some on DEFAULT") {
        withFlags("some-default") { (p, ff) =>
          ff.value[Option[Int]]("i.default", Some(5)).map { v =>
            val sent = p.lastDefault.get
            assertTrue(v == Some(5), sent == Int.box(5))
          }
        }
      }
    ),
    suite("a None default reads back as None when the provider fell back to it")(
      test("FLAG_NOT_FOUND hands hooks None (not Some(false)) with the error code") {
        // The typed and details tiers fail on the code (#394); the resolution they fail from is what hooks see.
        withFlags("not-found") { (_, ff) =>
          for {
            seen <- Ref.make[Option[FlagResolution[_]]](None)
            hook = new FeatureHook {
              override def finallyAfter(
                ctx: HookContext,
                details: Option[FlagResolution[_]],
                hints: HookHints
              ): UIO[Unit] = seen.set(details)
            }
            _ <- ff
              .valueDetails[Option[Boolean]]("b.missing", None, EvaluationContext.empty, EvaluationOptions(hook))
              .either
            got <- seen.get
          } yield assertTrue(
            got.map(_.value) == Some(None),
            got.flatMap(_.errorCode) == Some(ErrorCode.FlagNotFound)
          )
        }
      },
      test("FLAG_NOT_FOUND on the total tier is None") {
        withFlags("not-found-total") { (_, ff) =>
          ff.valueOrDefault[Option[Boolean]]("b.missing", None).map(v => assertTrue(v == None))
        }
      },
      test("FLAG_NOT_FOUND on the typed tier still fails") {
        withFlags("not-found-typed") { (_, ff) =>
          ff.value[Option[Boolean]]("b.missing", None).either.map(r => assertTrue(r.isLeft))
        }
      },
      test("reason DEFAULT with the sentinel is None, not Some(false)") {
        withFlags("default-bool") { (_, ff) =>
          ff.value[Option[Boolean]]("b.default", None).map(v => assertTrue(v == None))
        }
      },
      test("reason DEFAULT with the sentinel is None for a string, too") {
        withFlags("default-str") { (_, ff) =>
          ff.value[Option[String]]("s.default", None).map(v => assertTrue(v == None))
        }
      },
      test("reason DISABLED with the sentinel is None") {
        withFlags("disabled") { (_, ff) =>
          ff.value[Option[Boolean]]("b.disabled", None).map(v => assertTrue(v == None))
        }
      },
      test("false with TARGETING_MATCH is Some(false): the sentinel value but a real resolution") {
        withFlags("false-targeted") { (_, ff) =>
          ff.value[Option[Boolean]]("b.false-targeted", None).map(v => assertTrue(v == Some(false)))
        }
      },
      test("7 with reason DEFAULT is Some(7): a fell-back reason but not the sentinel") {
        withFlags("seven-default") { (_, ff) =>
          ff.value[Option[Int]]("i.seven-default", None).map(v => assertTrue(v == Some(7)))
        }
      }
    ),
    suite("dispatch on the typed wire")(
      test("List[String] still resolves on the object path") {
        withFlags("list") { (p, ff) =>
          ff.valueDetails[List[String]]("l.obj", List("d")).either.map { _ =>
            // The object answer is a structure, not a list, so its decode fails; the getter is what this pins.
            val call = p.lastCall.get
            assertTrue(call == "object:l.obj")
          }
        }
      },
      test("a legacy wireType override spelled \"string\" reaches the string resolver") {
        withFlags("legacy") { (p, ff) =>
          ff.value[Code]("c.hit", Code("d")).map { v =>
            val call = p.lastCall.get
            assertTrue(v == Code("hello"), call == "string:c.hit")
          }
        }
      },
      test("the built-in object instance serves the raw map") {
        withFlags("builtin-obj") { (p, ff) =>
          ff.obj("o.flag", Map.empty).map { m =>
            val call = p.lastCall.get
            assertTrue(m == Map[String, Any]("a" -> "x"), call == "object:o.flag")
          }
        }
      },
      test("a custom from(\"Object\", ...) with a Map default runs its own decode") {
        val decoded                                        = new AtomicBoolean(false)
        implicit val customMap: FlagType[Map[String, Any]] = FlagType.from[Map[String, Any]](
          "Object",
          Map.empty,
          v => {
            decoded.set(true)
            FlagType.objectFlagType.decode(v).map(_ + ("decoded" -> true))
          }
        )
        withFlags("custom-obj-map") { (_, ff) =>
          ff.value[Map[String, Any]]("o.flag", Map("k" -> "v")).map { m =>
            val ran = decoded.get
            assertTrue(ran, m == Map[String, Any]("a" -> "x", "decoded" -> true))
          }
        }
      },
      test("a custom from(\"Object\", ...) with a non-Map default decodes instead of failing with a cast") {
        withFlags("custom-obj-bag") { (_, ff) =>
          ff.value[Bag]("o.flag", Bag(Map("k" -> "v"))).either.map { r =>
            assertTrue(r == Right(Bag(Map[String, Any]("a" -> "x"))))
          }
        }
      }
    )
  ) @@ sequential @@ withLiveClock
}
