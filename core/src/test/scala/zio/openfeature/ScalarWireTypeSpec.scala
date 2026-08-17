package zio.openfeature

import zio._
import zio.test._
import zio.test.TestAspect.{sequential, withLiveClock}
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext => OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Value
}

/** #356: a custom `FlagType[A]` whose wire representation is a *scalar* (an enum-valued flag stored as a string, a
  * newtype over an int) had no working evaluation path — dispatch was on `typeName`, so a domain `typeName` fell to the
  * object path and asked the provider for an object it does not have, while forcing `typeName = "String"` hit a
  * `ClassCastException` on `default.asInstanceOf[String]`.
  *
  * The fix adds `FlagType.wireType` (defaulting to `typeName`), dispatches on it, sends `encode(default)`, and decodes
  * the extracted wire value with a `Left` becoming `TypeMismatch`.
  *
  * Shared (cross-compiled) test source dir → must compile on 2.13 and 3: braces only, no `given`/`using`, no `enum`.
  */
object ScalarWireTypeSpec extends ZIOSpecDefault {

  // A string-backed domain enum — the shape the issue calls the most common kind of feature flag.
  // `sealed trait` + `case object` rather than `enum`, because this file also compiles on 2.13.
  sealed trait Phase extends Product with Serializable
  object Phase {
    case object Off       extends Phase
    case object DualWrite extends Phase
    case object ShardOnly extends Phase

    val render: Phase => String = {
      case Off       => "off"
      case DualWrite => "dual_write"
      case ShardOnly => "shard_only"
    }

    // `mapped` takes a TOTAL B => A, so an unknown variant has to fall somewhere; that is exactly why the
    // strict/fallible case below needs `from` instead.
    val parseTotal: String => Phase = {
      case "dual_write" => DualWrite
      case "shard_only" => ShardOnly
      case _            => Off
    }
  }

  // A second string-backed type whose decoder REJECTS unknown input, so the `Left => TypeMismatch`
  // contract is reachable at all. A separate type (not another instance for `Phase`) keeps both
  // instances implicit without ambiguity.
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

  // A numeric-backed mapped type: the one place the "encode's box always matches the evaluator" argument
  // could break, since Int/Long/Float/Double go through boxing that String does not.
  final case class Level(n: Int)

  // An OBJECT-backed custom type built with `FlagType.from` — its wireType stays the domain name, so it
  // must keep taking the object decode path rather than any scalar resolver.
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

  implicit val levelFlagType: FlagType[Level] =
    FlagType.mapped[Level, Int]("Level", Level(0))(Level.apply, _.n)

  implicit val regionFlagType: FlagType[Region] =
    FlagType.from[Region]("Region", Region.Us, Region.parse, Region.render)

  /** #360: an instance that declares a scalar `wireType` its `encode` does not actually produce. This is the mistake
    * the `wireType` scaladoc warns about, and it is reachable at a documented extension point, so it must surface as a
    * diagnostic typed error rather than an opaque `ClassCastException` from inside the SDK bridge.
    */
  final case class Broken(v: String)
  implicit val brokenFlagType: FlagType[Broken] = new FlagType[Broken] {
    def typeName: String                           = "Broken"
    override def wireType: String                  = "Int"   // declares Int…
    def defaultValue: Broken                       = Broken("x")
    def decode(value: Any): Either[String, Broken] = Right(Broken(value.toString))
    override def encode(value: Broken): Any        = value.v // …but encodes to String
  }

  // Hand-rolled rather than via `FlagType.from`, because `from` builds object-backed instances. Overriding
  // `wireType` on the trait is the supported way to declare a scalar-backed type with a FALLIBLE decoder —
  // `mapped` cannot express rejection, since its `B => A` is total.
  implicit val tierFlagType: FlagType[Tier] = new FlagType[Tier] {
    def typeName: String                         = "Tier"
    override def wireType: String                = "String"
    def defaultValue: Tier                       = Tier.Free
    def decode(value: Any): Either[String, Tier] = Tier.parseStrict(value)
    override def encode(value: Tier): Any        = Tier.render(value)
  }

  /** `stringReply` decides what the string resolver answers — `identity` echoes back whatever default the evaluation
    * path handed down, which is how a test can tell `encode(default)` was sent rather than a raw cast.
    *
    * `getObjectEvaluation` deliberately returns the passed default with reason DEFAULT: if the implementation still
    * routed a scalar-backed custom type through the object path, these tests see the default instead of the provider's
    * variant, and fail. That is the discriminator between the fixed and the broken dispatch.
    */
  private class WireProvider(stringReply: String => String, stringReason: String) extends EventProvider {

    /** The raw default the evaluation path actually handed to the string resolver. Capturing it is what lets a test
      * assert the ENCODED value literally, instead of relying on the round trip — `Phase.parseTotal` has a wildcard
      * `case _ => Off`, so a wrong encoding ("Off", "", "BUG") would still decode back to `Off` and a round-trip
      * assertion would pass against a broken `encode`.
      */
    val sawStringDefault = new java.util.concurrent.atomic.AtomicReference[String](null)

    override def getMetadata: Metadata                      = new Metadata { def getName: String = "Wire" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](java.lang.Boolean.TRUE, "TARGETING_MATCH")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) = {
      sawStringDefault.set(d)
      ProviderEvaluations.of[String](stringReply(d), stringReason)
    }
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](Int.box(123), "TARGETING_MATCH")
    override def getLongEvaluation(k: String, d: java.lang.Long, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Long](java.lang.Long.valueOf(9000000000L), "TARGETING_MATCH")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](Double.box(2.5), "TARGETING_MATCH")

    /** Key-sensitive so the object path can be exercised for real. `"region.flag"` gets a string-valued `Value`, which
      * `valueToAny` unwraps for the custom decode branch. Every other key echoes the default back, which is what makes
      * a wrongly-object-routed scalar evaluation observable (it yields the default instead of the variant).
      */
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      if (k == "region.flag") ProviderEvaluations.of[Value](new Value("us"), "TARGETING_MATCH")
      else ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  /** Returns `null` from the string resolver, to pin the one built-in behaviour change in #356: `decode` now runs on
    * the extracted value, so a null string becomes a typed `TypeMismatch` where it previously flowed through as a
    * `null` value.
    */
  private class NullStringProvider extends EventProvider {
    override def getMetadata: Metadata                      = new Metadata { def getName: String = "NullString" }
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](null, "TARGETING_MATCH")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      ProviderEvaluations.of[Value](d, "DEFAULT")
  }

  private def build(p: EventProvider, tag: String): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      p,
      domain = Some(s"wire-$tag-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      evaluationTimeout = Some(5.seconds)
    )

  private def typeWatchingHook(seen: Ref[Option[FlagValueType]], types: Set[FlagValueType]): FeatureHook =
    new FeatureHook {
      override def supportedFlagTypes: Set[FlagValueType] = types
      override def finallyAfter(ctx: HookContext, details: Option[FlagResolution[_]], hints: HookHints): UIO[Unit] =
        seen.set(Some(ctx.flagType))
    }

  def spec = suite("ScalarWireTypeSpec")(
    // --- wireType itself (pure) ---
    test("wireType defaults to typeName for every built-in instance") {
      assertTrue(
        FlagType[Boolean].wireType == "Boolean",
        FlagType[String].wireType == "String",
        FlagType[Int].wireType == "Int",
        FlagType[Long].wireType == "Long",
        FlagType[Float].wireType == "Float",
        FlagType[Double].wireType == "Double",
        FlagType[Map[String, Any]].wireType == "Object"
      )
    },
    test("wireType defaults to typeName for Option and List, so their dispatch is unchanged") {
      // These must NOT start dispatching on the underlying type — they stay on the object path.
      assertTrue(
        FlagType[Option[Int]].wireType == "Option[Int]",
        FlagType[List[Int]].wireType == "List[Int]"
      )
    },
    test("mapped sets wireType from the underlying instance while keeping its own typeName") {
      assertTrue(phaseFlagType.typeName == "Phase", phaseFlagType.wireType == "String")
    },
    test("an instance may override wireType directly while keeping its domain typeName") {
      assertTrue(tierFlagType.typeName == "Tier", tierFlagType.wireType == "String")
    },
    test("FlagType.from stays object-backed — its wireType is the domain name") {
      // `from` builds object-encoded instances; it does not silently acquire a scalar wire type.
      val objectBacked = FlagType.from[Tier]("TierObj", Tier.Free, Tier.parseStrict, Tier.render)
      assertTrue(objectBacked.wireType == "TierObj")
    },
    // --- FlagValueType follows wireType (drives hook filtering) ---
    test("FlagValueType.fromFlagType is unchanged for the built-ins") {
      assertTrue(
        FlagValueType.fromFlagType[Boolean] == FlagValueType.Boolean,
        FlagValueType.fromFlagType[String] == FlagValueType.String,
        FlagValueType.fromFlagType[Int] == FlagValueType.Int,
        FlagValueType.fromFlagType[Long] == FlagValueType.Long,
        // Float is resolved through the SDK's double surface, so it reports Double — as before.
        FlagValueType.fromFlagType[Float] == FlagValueType.Double,
        FlagValueType.fromFlagType[Double] == FlagValueType.Double,
        FlagValueType.fromFlagType[Map[String, Any]] == FlagValueType.Object
      )
    },
    test("FlagValueType.fromFlagType reports the wire type for a scalar-backed custom type") {
      // Was Object before the fix, which is what hid these evaluations from String-scoped hooks.
      assertTrue(
        FlagValueType.fromFlagType[Phase] == FlagValueType.String,
        FlagValueType.fromFlagType[Tier] == FlagValueType.String
      )
    },
    test("FlagValueType.fromFlagType still reports Object for Option and List") {
      assertTrue(
        FlagValueType.fromFlagType[Option[Int]] == FlagValueType.Object,
        FlagValueType.fromFlagType[List[Int]] == FlagValueType.Object
      )
    },
    // --- evaluation: the actual fix ---
    test("a mapped scalar-backed type evaluates through the string resolver and returns the provider variant") {
      ZIO.scoped {
        build(new WireProvider(_ => "dual_write", "TARGETING_MATCH"), "mapped").flatMap { ff =>
          // DualWrite is only reachable via the string resolver. The object path would yield Off.
          ff.value[Phase]("phase.flag", Phase.Off).map(p => assertTrue(p == Phase.DualWrite))
        }
      }
    },
    test("the ENCODED default literally reaches the provider (not the domain value, no raw cast)") {
      ZIO.scoped {
        // Asserts the captured argument == "off" LITERALLY rather than round-tripping: `Phase.parseTotal`
        // has a wildcard `case _ => Off`, so an `encode` bug sending "Off"/""/"BUG" would still decode
        // back to Off and a round-trip-only assertion would pass against broken code.
        // Before the fix this path threw ClassCastException casting a Phase to String.
        val provider = new WireProvider(identity, "DEFAULT")
        build(provider, "encode").flatMap { ff =>
          ff.value[Phase]("phase.flag", Phase.Off).map { p =>
            assertTrue(provider.sawStringDefault.get == "off", p == Phase.Off)
          }
        }
      }
    },
    test("a mapped type over a NUMERIC underlying evaluates through the integer resolver") {
      ZIO.scoped {
        // The boxing case: Level encodes to a java.lang.Integer, so this is where a wrong box would surface.
        build(new WireProvider(identity, "DEFAULT"), "mapped-int").flatMap { ff =>
          ff.value[Level]("level.flag", Level(1)).map(l => assertTrue(l == Level(123)))
        }
      }
    },
    test("an object-backed custom type still resolves through the object decode path") {
      ZIO.scoped {
        // Region's wireType is "Region" (not a scalar), so it must reach `getObjectEvaluation`, which
        // answers a string-valued Value for this key. Proves the `case _ => None` fall-through still works.
        build(new WireProvider(identity, "DEFAULT"), "region").flatMap { ff =>
          ff.value[Region]("region.flag", Region.Eu).map(r => assertTrue(r == Region.Us))
        }
      }
    },
    test("the built-in Object branch still evaluates a Map flag") {
      ZIO.scoped {
        // Exercises the `case None if flagType.typeName == "Object"` branch, which the diff deliberately
        // left keyed on typeName; nothing else in the suite executes it.
        build(new WireProvider(identity, "DEFAULT"), "objmap").flatMap { ff =>
          ff.obj("o.flag", Map("a" -> "x")).map(m => assertTrue(m == Map("a" -> "x")))
        }
      }
    },
    test("a valid value for a hand-rolled scalar-backed type decodes successfully") {
      ZIO.scoped {
        build(new WireProvider(_ => "paid", "TARGETING_MATCH"), "tier-ok").flatMap { ff =>
          ff.value[Tier]("tier.flag", Tier.Free).map(t => assertTrue(t == Tier.Paid))
        }
      }
    },
    test("a rejecting decoder on the scalar path fails with TypeMismatch, not a defect or a silent default") {
      ZIO.scoped {
        build(new WireProvider(_ => "garbage", "TARGETING_MATCH"), "reject").flatMap { ff =>
          ff.value[Tier]("tier.flag", Tier.Free).either.map { r =>
            // A typed TypeMismatch naming the DOMAIN type — not absorbed into the default, and not a
            // ClassCastException defect (a defect would not surface in the typed `.either` at all).
            assertTrue(
              r.isLeft,
              r.left.toOption.exists(_.isInstanceOf[FeatureFlagError.TypeMismatch]),
              r.left.toOption.collect { case tm: FeatureFlagError.TypeMismatch => tm.expected }.contains("Tier")
            )
          }
        }
      }
    },
    test("a String-scoped hook now fires for a scalar-backed custom flag and sees FlagValueType.String") {
      ZIO.scoped {
        build(new WireProvider(_ => "dual_write", "TARGETING_MATCH"), "hook").flatMap { ff =>
          for {
            seen <- Ref.make[Option[FlagValueType]](None)
            hook = typeWatchingHook(seen, Set(FlagValueType.String))
            _   <- ff.valueDetails[Phase]("phase.flag", Phase.Off, EvaluationContext.empty, EvaluationOptions(hook))
            got <- seen.get
            // Before the fix fromFlagType returned Object, so a String-scoped hook was filtered out
            // entirely and `got` stayed None.
          } yield assertTrue(got == Some(FlagValueType.String))
        }
      }
    },
    // --- regression sentinels for the built-in scalar paths ---
    test("a built-in String flag still evaluates through the string resolver") {
      ZIO.scoped {
        build(new WireProvider(_ => "hello", "TARGETING_MATCH"), "builtin-str").flatMap { ff =>
          ff.value[String]("s.flag", "fallback").map(v => assertTrue(v == "hello"))
        }
      }
    },
    test("a built-in Int flag still evaluates through the integer resolver") {
      ZIO.scoped {
        build(new WireProvider(identity, "DEFAULT"), "builtin-int").flatMap { ff =>
          ff.value[Int]("i.flag", 7).map(v => assertTrue(v == 123))
        }
      }
    },
    // Long and Float are the two extractors whose output shape the new `decode` step is most sensitive to
    // (`Number.longValue()` and `java.lang.Double.floatValue()`), so both get an explicit round trip.
    test("a built-in Long flag still evaluates through the long resolver, exact beyond 2^31") {
      ZIO.scoped {
        build(new WireProvider(identity, "DEFAULT"), "builtin-long").flatMap { ff =>
          ff.value[Long]("l.flag", 1L).map(v => assertTrue(v == 9000000000L))
        }
      }
    },
    test("a built-in Float flag still evaluates through the double resolver") {
      ZIO.scoped {
        build(new WireProvider(identity, "DEFAULT"), "builtin-float").flatMap { ff =>
          ff.value[Float]("f.flag", 1.0f).map(v => assertTrue(v == 2.5f))
        }
      }
    },
    test("a built-in Double flag still evaluates through the double resolver") {
      ZIO.scoped {
        build(new WireProvider(identity, "DEFAULT"), "builtin-double").flatMap { ff =>
          ff.value[Double]("d.flag", 1.0).map(v => assertTrue(v == 2.5))
        }
      }
    },
    test("a built-in Boolean flag still evaluates through the boolean resolver") {
      ZIO.scoped {
        build(new WireProvider(identity, "DEFAULT"), "builtin-bool").flatMap { ff =>
          ff.value[Boolean]("b.flag", false).map(v => assertTrue(v))
        }
      }
    },
    test("a FlagType whose encode contradicts its declared wireType fails with a diagnostic TypeMismatch") {
      ZIO.scoped {
        // Without the guard this is a bare ClassCastException from the evaluator's bridge method (unboxing a
        // String to Int) — a defect with no flag key and no hint about the wireType/encode mismatch. `.either`
        // does not catch defects, so this test fails outright if the guard is missing rather than passing vacuously.
        build(new WireProvider(identity, "DEFAULT"), "broken").flatMap { ff =>
          ff.value[Broken]("broken.flag", Broken("x")).either.map { r =>
            assertTrue(
              r.isLeft,
              r.left.toOption.exists(_.isInstanceOf[FeatureFlagError.TypeMismatch]),
              // The diagnostic must name the domain type, the declared wire type, and what encode actually produced.
              r.left.toOption.exists(_.message.contains("Broken")),
              r.left.toOption.exists(_.message.contains("Int")),
              r.left.toOption.exists(_.message.contains("String"))
            )
          }
        }
      }
    },
    test("a null String from the provider now fails with TypeMismatch instead of yielding null") {
      ZIO.scoped {
        // The one built-in behaviour change in #356: `decode` now runs on the extracted value, and
        // `FlagType[String].decode(null)` is a Left. Previously `null.asInstanceOf[String]` flowed through
        // as a null flag value. Erroring matches what the object path already does.
        build(new NullStringProvider, "null-str").flatMap { ff =>
          ff.value[String]("s.flag", "fallback").either.map { r =>
            assertTrue(r.isLeft, r.left.toOption.exists(_.isInstanceOf[FeatureFlagError.TypeMismatch]))
          }
        }
      }
    }
  ) @@ sequential @@ withLiveClock
}
