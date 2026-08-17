package zio.openfeature

import zio.*
import zio.test.*
import zio.test.TestAspect.{sequential, withLiveClock}
import zio.openfeature.internal.ProviderEvaluations
import dev.openfeature.sdk.{
  EvaluationContext as OFEvaluationContext,
  EventProvider,
  Metadata,
  OpenFeatureAPI,
  ProviderState,
  Structure,
  Value
}

/** #348: `FlagType.derived` via `Mirror`.
  *
  *   - a sum of singleton cases (an `enum` without parameters) derives a String codec over the case labels — which is
  *     only evaluatable because #356 made dispatch key on `wireType`;
  *   - a product derives a `Map[String, Any]` codec, field-by-field through each field's own `FlagType`.
  *
  * This spec lives in `core/src/test/scala-3/` rather than the shared test dir, because `enum` and `derives` do not
  * exist on 2.13. Derivation is Scala-3 only and additive; the scala-2 twin keeps `from`/`mapped`.
  */
object FlagTypeDerivedSpec extends ZIOSpecDefault:

  enum Plan derives FlagType:
    case Free, Premium, Enterprise

  // Distinct from Plan so both can be given instances without ambiguity.
  enum Tier:
    case Bronze, Silver
  given tierFlagType: FlagType[Tier] = FlagType.derived[Tier]

  // `pct` has a Scala default; `note` is Option; `tier` is required. Covers all three missing-key routes.
  final case class Rollout(tier: String, pct: Int = 10, note: Option[String]) derives FlagType

  final case class Inner(a: Int) derives FlagType
  final case class Outer(name: String, inner: Inner, tags: List[String]) derives FlagType

  /** Two adjacent fields of the SAME type, so a labels/fields mispairing produces a type-correct but wrong result that
    * no compiler check and no heterogeneous fixture would catch.
    */
  final case class Pair(first: Int, second: Int) derives FlagType

  /** Companion carries an unrelated method with a default argument. Scala emits that as `parse$default$2`, and
    * `<methodName>$default$<n>` is the scheme for EVERY defaulted parameter — so a lookup matching by suffix rather
    * than exact name would take `true` as field 2's default and either throw from `fromProduct` or decode a silently
    * wrong value. `label` has no declared default, so a missing key must be a Left.
    */
  final case class Poisoned(name: String, label: String) derives FlagType
  object Poisoned:
    def parse(raw: String, strict: Boolean = true): Poisoned = Poisoned(raw, if strict then "strict" else "lax")

  /** Answers the string resolver with a fixed variant and echoes the object default back, so a derived enum can be
    * shown to take the STRING path while a derived product takes the OBJECT path and receives its encoded default.
    */
  private class DerivedProvider(stringVariant: String) extends EventProvider:
    /** The object default the evaluation path actually handed down. Asserting on this pins the encoding at the point it
      * happens, instead of only inferring it from a round trip three layers away.
      */
    val sawObjectDefault = new java.util.concurrent.atomic.AtomicReference[Value](null)

    override def getMetadata: Metadata = new Metadata:
      def getName: String = "Derived"
    override def getState: ProviderState                    = ProviderState.READY
    override def initialize(ctx: OFEvaluationContext): Unit = ()
    override def shutdown(): Unit                           = ()
    override def getBooleanEvaluation(k: String, d: java.lang.Boolean, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Boolean](d, "DEFAULT")
    override def getStringEvaluation(k: String, d: String, c: OFEvaluationContext) =
      ProviderEvaluations.of[String](stringVariant, "TARGETING_MATCH")
    override def getIntegerEvaluation(k: String, d: java.lang.Integer, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Integer](d, "DEFAULT")
    override def getDoubleEvaluation(k: String, d: java.lang.Double, c: OFEvaluationContext) =
      ProviderEvaluations.of[java.lang.Double](d, "DEFAULT")
    override def getObjectEvaluation(k: String, d: Value, c: OFEvaluationContext) =
      sawObjectDefault.set(d)
      ProviderEvaluations.of[Value](d, "TARGETING_MATCH")

  private def build(p: EventProvider, tag: String): ZIO[Scope, Throwable, FeatureFlags] =
    FeatureFlags.build(
      p,
      domain = Some(s"derived-$tag-${java.util.UUID.randomUUID()}"),
      version = None,
      initialHooks = Nil,
      statusRef = None,
      addShutdownFinalizer = true,
      apiOverride = Some(OpenFeatureAPI.createIsolated()),
      evaluationTimeout = Some(5.seconds)
    )

  /** A HAND-WRITTEN object-backed encoder that emits a `Some` inside its map. `FlagType[Option[A]].encode` unwraps, so
    * a derived product never produces this shape — but a hand-rolled instance can, and the object path's encoder must
    * unwrap it rather than fall through to a `toString` (which sends the literal `"Some(v)"`).
    */
  final case class Wrapped(v: String)
  given wrappedFlagType: FlagType[Wrapped] with
    def typeName: String      = "Wrapped"
    def defaultValue: Wrapped = Wrapped("")
    def decode(value: Any): Either[String, Wrapped] = value match
      case m: Map[?, ?] =>
        m.asInstanceOf[Map[String, Any]].get("v") match
          case Some(s: String) => Right(Wrapped(s))
          case other           => Left(s"Wrapped.v: $other")
      case other => Left(s"Cannot convert $other to Wrapped")
    override def encode(value: Wrapped): Any = Map[String, Any]("v" -> Some(value.v))

  private val planFt    = FlagType[Plan]
  private val rolloutFt = FlagType[Rollout]
  private val outerFt   = FlagType[Outer]

  def spec = suite("FlagTypeDerivedSpec")(
    // --- sum of singletons ---
    test("a derived enum reports its own typeName and a String wireType") {
      // wireType String is the whole reason a derived enum is evaluatable at all (#356).
      assertTrue(planFt.typeName == "Plan", planFt.wireType == "String")
    },
    test("a derived enum encodes to its canonical case label") {
      assertTrue(planFt.encode(Plan.Premium) == "Premium", planFt.encode(Plan.Free) == "Free")
    },
    test("a derived enum decodes case-insensitively") {
      assertTrue(
        planFt.decode("Premium") == Right(Plan.Premium),
        planFt.decode("premium") == Right(Plan.Premium),
        planFt.decode("ENTERPRISE") == Right(Plan.Enterprise)
      )
    },
    test("a derived enum rejects an unknown label and a non-String input") {
      assertTrue(planFt.decode("platinum").isLeft, planFt.decode(42).isLeft)
    },
    test("a derived enum's defaultValue is its first declared case") {
      assertTrue(planFt.defaultValue == Plan.Free, FlagType[Tier].defaultValue == Tier.Bronze)
    },
    test("FlagType.derived works when called explicitly, not only via a derives clause") {
      assertTrue(tierFlagType.typeName == "Tier", tierFlagType.decode("silver") == Right(Tier.Silver))
    },
    // --- products ---
    test("a derived product keeps typeName as its wireType, so it stays on the object path") {
      assertTrue(rolloutFt.typeName == "Rollout", rolloutFt.wireType == "Rollout")
    },
    test("a derived product decodes a full map") {
      assertTrue(
        rolloutFt.decode(Map("tier" -> "pro", "pct" -> 5, "note" -> "hi")) ==
          Right(Rollout("pro", 5, Some("hi")))
      )
    },
    test("a missing key falls back to the field's declared Scala default") {
      // `pct = 10` in the source is consulted for the absent key.
      assertTrue(rolloutFt.decode(Map("tier" -> "pro")) == Right(Rollout("pro", 10, None)))
    },
    test("a missing Option field decodes to None") {
      assertTrue(rolloutFt.decode(Map("tier" -> "pro", "pct" -> 1)) == Right(Rollout("pro", 1, None)))
    },
    test("a missing required field is a Left naming the field") {
      val r = rolloutFt.decode(Map("pct" -> 1))
      assertTrue(r.isLeft, r.left.toOption.exists(_.contains("tier")))
    },
    test("extra keys are ignored, so payloads stay forward-compatible") {
      assertTrue(
        rolloutFt.decode(Map("tier" -> "pro", "pct" -> 2, "note" -> "x", "futureField" -> true)) ==
          Right(Rollout("pro", 2, Some("x")))
      )
    },
    test("a derived product encodes to a label-keyed map of wire values") {
      // `note` is the plain "n", not `Some("n")`: each field is encoded by its own instance, and
      // `FlagType[Option[A]].encode` unwraps to the underlying wire value (or null for None).
      assertTrue(
        rolloutFt.encode(Rollout("pro", 7, Some("n"))) == Map("tier" -> "pro", "pct" -> 7, "note" -> "n"),
        rolloutFt.encode(Rollout("pro", 7, None)) == Map("tier" -> "pro", "pct" -> 7, "note" -> null)
      )
    },
    test("a derived product's defaultValue is built from its fields' defaultValues") {
      // Deliberately the type-level zero, NOT the declared Scala defaults — see the scaladoc.
      assertTrue(rolloutFt.defaultValue == Rollout("", 0, None))
    },
    test("a field whose value cannot decode reports the field name") {
      val r = rolloutFt.decode(Map("tier" -> "pro", "pct" -> "not-a-number"))
      assertTrue(r.isLeft, r.left.toOption.exists(_.contains("pct")))
    },
    test("a non-map input is a Left") {
      assertTrue(rolloutFt.decode("nope").isLeft, rolloutFt.decode(7).isLeft)
    },
    test("nested products and list fields round-trip") {
      val o       = Outer("n", Inner(3), List("a", "b"))
      val encoded = outerFt.encode(o)
      assertTrue(outerFt.decode(encoded) == Right(o))
    },
    test("a nested product decodes from a raw nested map") {
      assertTrue(
        outerFt.decode(Map("name" -> "n", "inner" -> Map("a" -> 3), "tags" -> List("x"))) ==
          Right(Outer("n", Inner(3), List("x")))
      )
    },
    test("a companion's unrelated defaulted method is NOT mistaken for a field default") {
      // Suffix-matching `default$2` would find `parse$default$2` (a Boolean) and use it for `label`.
      val poisonedFt = FlagType[Poisoned]
      val missing    = poisonedFt.decode(Map("name" -> "n"))
      assertTrue(
        poisonedFt.decode(Map("name" -> "n", "label" -> "l")) == Right(Poisoned("n", "l")),
        // `label` has no real default, so this must be a Left naming it — not `true`, and not a thrown CCE.
        missing.isLeft,
        missing.left.toOption.exists(_.contains("label"))
      )
    },
    test("same-typed adjacent fields keep their declared order") {
      // Guards against a labels/fields mispairing, which for identically-typed fields would otherwise produce a
      // type-correct but silently wrong value.
      val pairFt = FlagType[Pair]
      assertTrue(
        pairFt.decode(Map("first" -> 1, "second" -> 2)) == Right(Pair(1, 2)),
        pairFt.encode(Pair(1, 2)) == Map("first" -> 1, "second" -> 2)
      )
    },
    test("a derived sum and product both decode null to a Left") {
      assertTrue(planFt.decode(null).isLeft, rolloutFt.decode(null).isLeft)
    },
    test("a missing required nested-product field is a Left naming it") {
      val r = outerFt.decode(Map("name" -> "n", "tags" -> List("x")))
      assertTrue(r.isLeft, r.left.toOption.exists(_.contains("inner")))
    },
    test("a parameterised enum case does not derive") {
      // The guarantee the scaladoc states. It is a compile error, so it cannot be exercised by a normal test —
      // `typeCheckErrors` asserts the failure without needing a module that fails to build.
      val errors = scala.compiletime.testing.typeCheckErrors(
        """
        enum Bad derives FlagType:
          case Plain
          case WithPayload(x: Int)
        """
      )
      assertTrue(errors.nonEmpty)
    },
    // --- integration: derivation + #356 wire dispatch + #364 object path ---
    test("a derived ENUM flag evaluates through the provider's string resolver") {
      ZIO.scoped {
        // Only reachable if wireType routed this to getStringEvaluation. The object path would not yield Enterprise.
        build(new DerivedProvider("enterprise"), "enum").flatMap { ff =>
          ff.value[Plan]("plan.flag", Plan.Free).map(p => assertTrue(p == Plan.Enterprise))
        }
      }
    },
    test("a Some inside a hand-written encoder's map reaches the provider unwrapped, not as \"Some(v)\"") {
      ZIO.scoped {
        val provider = new DerivedProvider("unused")
        build(provider, "wrapped").flatMap { ff =>
          ff.value[Wrapped]("wrapped.flag", Wrapped("hi")).map { r =>
            val sent = provider.sawObjectDefault.get
            val v    = Option(sent).filter(_.isStructure).flatMap(s => Option(s.asStructure().getValue("v")))
            assertTrue(
              // Without the Option unwrapping in the object path's encoder this arrives as the string "Some(hi)".
              v.exists(x => x.isString && x.asString() == "hi"),
              r == Wrapped("hi")
            )
          }
        }
      }
    },
    test("a derived PRODUCT flag evaluates through the object path and receives its encoded default") {
      ZIO.scoped {
        // The provider echoes the object default it was handed, so a successful decode proves the caller's
        // default was encoded and sent (the #364 fix) and that the derived codec reads it back.
        val provider = new DerivedProvider("unused")
        build(provider, "product").flatMap { ff =>
          ff.value[Rollout]("rollout.flag", Rollout("gold", 99, Some("hi"))).map { r =>
            // Assert on what the provider was HANDED, not only on the round trip: an Option field must arrive as
            // its inner value. Without the Option unwrapping it arrives as the string "Some(hi)", and a round-trip
            // assertion alone would report that three layers from the cause.
            val sent = provider.sawObjectDefault.get
            val note = Option(sent).filter(_.isStructure).flatMap(v => Option(v.asStructure().getValue("note")))
            assertTrue(
              r == Rollout("gold", 99, Some("hi")),
              note.exists(v => v.isString && v.asString() == "hi")
            )
          }
        }
      }
    }
  ) @@ sequential @@ withLiveClock
