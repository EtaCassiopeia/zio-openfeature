package zio.openfeature

import zio._
import zio.test._
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Value
}
import java.util.concurrent.atomic.AtomicInteger

/** #359: the transaction machinery fed `FlagType.decode` — a WIRE → domain function — DOMAIN values in two places, and
  * read a `Left` as "not this type". For the built-ins `decode` is idempotent on domain values, which hid it; for a
  * custom type whose wire form differs (a `mapped` enum, a `from` object type) the in-transaction cache never hit and
  * an override could only be given in its wire form.
  *
  * The fix caches `encode(value)` next to each evaluation and decodes THAT on re-read (symmetric with the provider
  * path), accepts an override in either its wire or its domain form, and stops discarding the decode reason.
  *
  * Shared (cross-compiled) test source dir → must compile on 2.13 and 3: braces only, no `given`/`using`, no `enum`.
  */
object TransactionCustomTypeSpec extends ZIOSpecDefault {

  // A string-backed domain enum via `mapped` — decode is TOTAL (unknown → Off), wire "dual_write" ↔ DualWrite.
  sealed trait Phase extends Product with Serializable
  object Phase {
    case object Off       extends Phase
    case object DualWrite extends Phase

    val render: Phase => String = {
      case Off       => "off"
      case DualWrite => "dual_write"
    }
    val parseTotal: String => Phase = {
      case "dual_write" => DualWrite
      case _            => Off
    }
  }

  // A string-backed type with a STRICT decoder, hand-rolled (the only way to get rejection on the scalar path).
  sealed trait Tier extends Product with Serializable
  object Tier {
    case object Free extends Tier
    case object Paid extends Tier

    val render: Tier => String = {
      case Free => "free"
      case Paid => "paid"
    }
    val parseStrict: Any => Either[String, Tier] = {
      case "free" => Right(Free)
      case "paid" => Right(Paid)
      case other  => Left("Unknown tier: " + other)
    }
  }

  // An OBJECT-backed custom type built with `FlagType.from` — resolved on the object path, decoded from a raw value.
  sealed trait Region extends Product with Serializable
  object Region {
    case object Us extends Region
    case object Eu extends Region

    val render: Region => String = {
      case Us => "us"
      case Eu => "eu"
    }
    val parse: Any => Either[String, Region] = {
      case "us"  => Right(Us)
      case "eu"  => Right(Eu)
      case other => Left("Unknown region: " + other)
    }
  }

  implicit val phaseFlagType: FlagType[Phase] =
    FlagType.mapped[Phase, String]("Phase", Phase.Off)(Phase.parseTotal, Phase.render)

  implicit val tierFlagType: FlagType[Tier] = new FlagType[Tier] {
    def typeName: String                         = "Tier"
    override def wireType: String                = "String"
    def defaultValue: Tier                       = Tier.Free
    def decode(value: Any): Either[String, Tier] = Tier.parseStrict(value)
    override def encode(value: Tier): Any        = Tier.render(value)
  }

  implicit val regionFlagType: FlagType[Region] =
    FlagType.from[Region]("Region", Region.Us, Region.parse, Region.render)

  /** Counts every resolver call so a test can tell a cache hit (one call) from a silent re-evaluation (two). Answers
    * are fixed per resolver so a second call is indistinguishable by VALUE — only the counters discriminate.
    */
  final private class CountingProvider extends EventProvider {
    val booleanCalls = new AtomicInteger(0)
    val stringCalls  = new AtomicInteger(0)
    val intCalls     = new AtomicInteger(0)
    val longCalls    = new AtomicInteger(0)
    val objectCalls  = new AtomicInteger(0)

    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Counting" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()

    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) = {
      booleanCalls.incrementAndGet()
      ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.TRUE, "TARGETING_MATCH")
    }
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) = {
      stringCalls.incrementAndGet()
      val answer = if (k.startsWith("tier")) "paid" else "dual_write"
      ProviderEvaluations.of[String](answer, "TARGETING_MATCH")
    }
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) = {
      intCalls.incrementAndGet()
      ProviderEvaluations.of[java.lang.Integer](Int.box(7), "TARGETING_MATCH")
    }
    override def getLongEvaluation(k: String, d: java.lang.Long, c: OFEvaluationContext) = {
      longCalls.incrementAndGet()
      ProviderEvaluations.of[java.lang.Long](java.lang.Long.valueOf(7L), "TARGETING_MATCH")
    }
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) = {
      objectCalls.incrementAndGet()
      val answer = if (k == "region.bad") "mars" else "us"
      ProviderEvaluations.of[Value](new Value(answer), "TARGETING_MATCH")
    }
  }

  private def build(p: EventProvider): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      p,
      domain = Some(s"tx-custom-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      evaluationTimeout = Some(5.seconds)
    )

  private def overrideError(r: Either[Any, Any]): Option[FeatureFlagError.OverrideTypeMismatch] =
    r.left.toOption.collect { case e: FeatureFlagError.OverrideTypeMismatch => e }

  private def withProvider[A](f: (FeatureFlags, CountingProvider) => ZIO[Any, Any, A]): ZIO[Any, Any, A] =
    ZIO.scoped {
      val p = new CountingProvider
      build(p).flatMap(ff => f(ff, p))
    }

  def spec = suite("TransactionCustomTypeSpec")(
    // --- caching ---
    test("mapped scalar-backed type hits the transaction cache on a same-key re-read") {
      withProvider { (ff, p) =>
        ff.transaction() {
          ff.value[Phase]("phase.flag", Phase.Off).zip(ff.value[Phase]("phase.flag", Phase.Off))
        }.map { tx =>
          assertTrue(
            p.stringCalls.get() == 1,
            tx.result == ((Phase.DualWrite, Phase.DualWrite)),
            tx.wasEvaluated("phase.flag"),
            !tx.wasOverridden("phase.flag")
          )
        }
      }
    },
    test("object-backed FlagType.from type hits the transaction cache on a same-key re-read") {
      withProvider { (ff, p) =>
        ff.transaction() {
          ff.value[Region]("region.flag", Region.Eu).zip(ff.value[Region]("region.flag", Region.Eu))
        }.map { tx =>
          assertTrue(
            p.objectCalls.get() == 1,
            tx.result == ((Region.Us, Region.Us))
          )
        }
      }
    },
    test("cacheEvaluations = false still re-evaluates a custom type each read") {
      withProvider { (ff, p) =>
        ff.transaction(cacheEvaluations = false) {
          ff.value[Phase]("phase.flag", Phase.Off) *> ff.value[Phase]("phase.flag", Phase.Off)
        }.map(tx => assertTrue(p.stringCalls.get() == 2, tx.result == Phase.DualWrite))
      }
    },
    test("a same-key cross-type read that decodes from the cached wire value stays a cache hit") {
      withProvider { (ff, p) =>
        ff.transaction() {
          ff.int("num.flag", 0).zip(ff.long("num.flag", 0L))
        }.map { tx =>
          assertTrue(
            p.intCalls.get() == 1,
            p.longCalls.get() == 0,
            tx.result == ((7, 7L))
          )
        }
      }
    },
    test("a same-key cross-type read that does not decode falls through to the provider without failing") {
      withProvider { (ff, p) =>
        ff.transaction() {
          ff.boolean("mixed.flag", false).zip(ff.string("mixed.flag", "x"))
        }.map { tx =>
          assertTrue(
            p.booleanCalls.get() == 1,
            p.stringCalls.get() == 1,
            tx.result == ((true, "dual_write"))
          )
        }
      }
    },
    // --- overrides ---
    test("an override given as the domain value is accepted") {
      withProvider { (ff, p) =>
        ff.transaction(overrides = Map("phase.flag" -> Phase.DualWrite)) {
          ff.value[Phase]("phase.flag", Phase.Off)
        }.map { tx =>
          assertTrue(
            tx.result == Phase.DualWrite,
            tx.wasOverridden("phase.flag"),
            tx.toValueMap("phase.flag") == Phase.DualWrite,
            p.stringCalls.get() == 0
          )
        }
      }
    },
    test("an override given as the wire value is accepted") {
      withProvider { (ff, p) =>
        ff.transaction(overrides = Map("phase.flag" -> "dual_write")) {
          ff.value[Phase]("phase.flag", Phase.Off)
        }.map(tx => assertTrue(tx.result == Phase.DualWrite, tx.wasOverridden("phase.flag"), p.stringCalls.get() == 0))
      }
    },
    test("a domain override for a hand-rolled strict decoder round-trips through encode") {
      withProvider { (ff, p) =>
        ff.transaction(overrides = Map("tier.flag" -> Tier.Paid)) {
          ff.value[Tier]("tier.flag", Tier.Free)
        }.map(tx => assertTrue(tx.result == Tier.Paid, p.stringCalls.get() == 0))
      }
    },
    test("a domain override for an object-backed FlagType.from type is accepted") {
      withProvider { (ff, p) =>
        ff.transaction(overrides = Map("region.flag" -> Region.Eu)) {
          ff.value[Region]("region.flag", Region.Us)
        }.map(tx => assertTrue(tx.result == Region.Eu, p.objectCalls.get() == 0))
      }
    },
    test("a value that is neither wire nor domain fails OverrideTypeMismatch with the decode reason") {
      withProvider { (ff, _) =>
        ff.transaction(overrides = Map("phase.flag" -> 42)) {
          ff.value[Phase]("phase.flag", Phase.Off)
        }.either
          .map { r =>
            val err = overrideError(r)
            assertTrue(
              err.exists(_.key == "phase.flag"),
              err.exists(_.expected == "Phase"),
              err.exists(_.actual.contains("Integer")),
              err.exists(_.actual.contains("Cannot convert Integer to String"))
            )
          }
      }
    },
    test("a null override fails OverrideTypeMismatch rather than a defect") {
      withProvider { (ff, _) =>
        // `.either` does not catch defects, so an NPE on `null.getClass` would fail this test outright.
        ff.transaction(overrides = Map[String, Any]("phase.flag" -> null)) {
          ff.value[Phase]("phase.flag", Phase.Off)
        }.either
          .map { r =>
            val err = overrideError(r)
            assertTrue(err.exists(_.actual.contains("null")))
          }
      }
    },
    test("a null override for a numeric built-in fails OverrideTypeMismatch rather than a defect") {
      // The Int/Long/Double/Float/Object/List decoders used to NPE on null (`value.getClass`), so this was a defect
      // even before the override path looked at the value.
      withProvider { (ff, _) =>
        ff.transaction(overrides = Map[String, Any]("num.flag" -> null)) {
          ff.int("num.flag", 0)
        }.either
          .map { r =>
            val err = overrideError(r)
            assertTrue(err.exists(_.actual == "null (Cannot convert null to Int)"))
          }
      }
    },
    test("a null override for an Option flag is None") {
      withProvider { (ff, _) =>
        ff.transaction(overrides = Map[String, Any]("opt.flag" -> null)) {
          ff.value[Option[String]]("opt.flag", Some("default"))
        }.map(tx => assertTrue(tx.result.isEmpty, tx.wasOverridden("opt.flag")))
      }
    },
    test("a garbage override for an object-backed type reports that type's decode reason") {
      withProvider { (ff, _) =>
        ff.transaction(overrides = Map("region.flag" -> "mars")) {
          ff.value[Region]("region.flag", Region.Us)
        }.either
          .map { r =>
            val err = overrideError(r)
            assertTrue(err.exists(_.expected == "Region"), err.exists(_.actual.contains("Unknown region: mars")))
          }
      }
    },
    // --- Option / List of a custom type ---
    test("Option of a custom type hits the transaction cache on a same-key re-read") {
      withProvider { (ff, p) =>
        ff.transaction() {
          ff.value[Option[Phase]]("opt.phase", None).zip(ff.value[Option[Phase]]("opt.phase", None))
        }.map { tx =>
          // The object path answers "us", which the total Phase parser maps to Off.
          assertTrue(p.objectCalls.get() == 1, tx.result == ((Some(Phase.Off), Some(Phase.Off))))
        }
      }
    },
    test("Option and List of a custom type accept a domain override") {
      withProvider { (ff, p) =>
        ff.transaction(overrides =
          Map("opt.phase" -> Some(Phase.DualWrite), "list.phase" -> List(Phase.DualWrite, Phase.Off))
        ) {
          ff.value[Option[Phase]]("opt.phase", None).zip(ff.value[List[Phase]]("list.phase", Nil))
        }.map { tx =>
          assertTrue(
            tx.result == ((Some(Phase.DualWrite), List(Phase.DualWrite, Phase.Off))),
            p.objectCalls.get() == 0
          )
        }
      }
    },
    // --- decode reasons on the provider object path ---
    test("a rejected object-path decode names the decoder's reason, not the literal \"Object\"") {
      withProvider { (ff, _) =>
        ff.value[Region]("region.bad", Region.Us).either.map { r =>
          val err = r.left.toOption.collect { case e: FeatureFlagError.TypeMismatch => e }
          assertTrue(err.exists(_.expected == "Region"), err.exists(_.actual == "Unknown region: mars"))
        }
      }
    }
  )
}
