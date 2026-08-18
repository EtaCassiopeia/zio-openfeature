package zio.openfeature.conformance.bdd.library

import zio.openfeature.{FlagDef, FlagType}

/** Domain types and flag definitions shared by the library conformance suites.
  *
  * Each type here exists to exercise one `FlagType` shape:
  *   - [[Tier]] — a Mirror-derived enum, carried on the wire as a `String` (#366);
  *   - [[Release]] — a Mirror-derived product, carried on the object path (#366);
  *   - [[Level]] — `FlagType.mapped` over `Int`, so its wire type is a *numeric* scalar (#361);
  *   - [[Broken]] — declares a `wireType` its `encode` does not produce, the mistake #362 turns into a diagnostic.
  */
enum Tier derives FlagType:
  case Free, Premium, Enterprise

/** A product with a Scala-declared default (`pct`) and an `Option` field, so decoding an absent key is exercised both
  * ways: the declared default for `pct`, `None` for `note`.
  */
final case class Release(tier: String, pct: Int = 10, note: Option[String] = None) derives FlagType

/** A newtype over `Int`. `FlagType.mapped` inherits the underlying instance's `wireType`, so this must be resolved
  * through the provider's *integer* resolver rather than the object path.
  */
final case class Level(n: Int)

object Level:
  given FlagType[Level] = FlagType.mapped[Level, Int]("Level", Level(0))(Level.apply, _.n)

/** Declares `wireType = "Int"` but encodes to a `String`. The pairing is not checked at compile time, so evaluation has
  * to surface it as a diagnostic `TypeMismatch` rather than an opaque `ClassCastException` from inside the SDK bridge.
  */
final case class Broken(v: String)

object Broken:
  given FlagType[Broken] = new FlagType[Broken]:
    def typeName: String                           = "Broken"
    override def wireType: String                  = "Int"   // declares Int…
    def defaultValue: Broken                       = Broken("x")
    def decode(value: Any): Either[String, Broken] = Right(Broken(value.toString))
    override def encode(value: Broken): Any        = value.v // …but encodes to String

/** The `FlagDef`s the feature files name. Keeping them in one place is the point of #357: a key, its type and its
  * default are stated once, and every scenario that mentions the flag by name gets all three.
  */
object Flags:

  /** Type parameter named explicitly: `FlagDef("user.plan", Tier.Free)` would infer `Tier.Free.type`. */
  val Plan: FlagDef[Tier] = FlagDef[Tier]("user.plan", Tier.Free, "subscription tier")

  val Rollout: FlagDef[Release] = FlagDef("rollout", Release("stable"), "staged rollout config")

  val MaxItems: FlagDef[Level] = FlagDef("max.items", Level(1), "page size")

  val Contradictory: FlagDef[Broken] = FlagDef("broken.flag", Broken("x"), "a codec that lies about its wire type")

  val KillSwitch: FlagDef[Boolean] = FlagDef("kill.switch", false, "emergency stop")

  val Budget: FlagDef[Long] = FlagDef("budget.cents", 0L, "a 64-bit counter")

  /** Looked up by the feature files, which name a flag rather than restating its key/type/default. */
  val byName: Map[String, FlagDef[?]] = Map(
    "Plan"          -> Plan,
    "Rollout"       -> Rollout,
    "MaxItems"      -> MaxItems,
    "Contradictory" -> Contradictory,
    "KillSwitch"    -> KillSwitch,
    "Budget"        -> Budget
  )
