package zio.openfeature.testkit

import dev.openfeature.sdk.ImmutableContext
import zio._
import zio.openfeature.{FeatureFlags, FlagDef, FlagType}
import zio.openfeature.testkit.FlagOverride.Ops
import zio.test._

/** #351: typed test fixtures built from a `FlagDef` rather than a bare key.
  *
  * The key-based `setFlag[A](key, value)` accepts anything, so a fixture can pin a value production could never decode
  * — the test passes and production fails with `TYPE_MISMATCH`. A `FlagDef`-based fixture is checked against the flag's
  * declared type and stored through `flagType.encode`, so the test reads it back through the same decode path.
  *
  * Shared test source dir → compiles on 2.13 and 3: braces only, no `given`/`using`, no `enum`.
  */
object TypedOverrideSpec extends ZIOSpecDefault {

  // A string-backed domain type, so encode/decode are genuinely non-identity and the encoding is observable.
  sealed trait Tier extends Product with Serializable
  object Tier {
    case object Free extends Tier
    case object Paid extends Tier

    val render: Tier => String = {
      case Free => "free"
      case Paid => "paid"
    }

    val parse: String => Tier = {
      case "paid" => Paid
      case _      => Free
    }
  }

  implicit val tierFlagType: FlagType[Tier] =
    FlagType.mapped[Tier, String]("Tier", Tier.Free)(Tier.parse, Tier.render)

  // A codec that never decodes, for the round-trip check below. Its own type, so its instance cannot be ambiguous
  // with `tierFlagType` — and declared implicitly rather than passed explicitly, since `(using x)` (Scala 3) and
  // `(x)` (2.13) cannot be spelled once in this shared source tree.
  final case class Unreadable(v: String)
  implicit val unreadableFlagType: FlagType[Unreadable] = new FlagType[Unreadable] {
    def typeName: String                               = "Unreadable"
    def defaultValue: Unreadable                       = Unreadable("")
    def decode(value: Any): Either[String, Unreadable] = Left("this codec never decodes")
    override def encode(value: Unreadable): Any        = value.v
  }

  // The type parameter is explicit: `FlagDef("k", Tier.Free)` would infer `A = Tier.Free.type` and then look for a
  // `FlagType[Tier.Free.type]`. That is a real trap for `sealed trait` + `case object` ADTs (a Scala 3 `enum`'s cases
  // are typed as the enum, so they do not hit it), and it is now called out in `FlagDef`'s scaladoc.
  private val TierFlag  = FlagDef[Tier]("user.tier", Tier.Free, "subscription tier")
  private val CountFlag = FlagDef("cart.limit", 10)

  def spec = suite("TypedOverrideSpec")(
    suite("FlagOverride")(
      test("`:=` stores the ENCODED value, not the domain value") {
        val o = TierFlag := Tier.Paid
        assertTrue(o.key == "user.tier", o.encoded == "paid", o.typeName == "Tier")
      },
      test("a built-in flag type encodes to itself") {
        val o = CountFlag := 42
        assertTrue(o.key == "cart.limit", o.encoded == 42)
      },
      test("the round-trip check fires eagerly when a codec cannot read its own encoding") {
        // The owner's point on the issue: without this, a codec that cannot round-trip produces a fixture the test
        // believes and production cannot read. Failing at fixture-construction points at the codec instead.
        val brokenFlag = FlagDef("broken.flag", Unreadable("x"))
        val thrown     = scala.util.Try(brokenFlag := Unreadable("y")).failed.toOption
        assertTrue(
          thrown.exists(_.isInstanceOf[IllegalArgumentException]),
          thrown.exists(_.getMessage.contains("Unreadable")),
          thrown.exists(_.getMessage.contains("broken.flag")),
          // The decode reason is surfaced, so the message says WHY, not just that it failed.
          thrown.exists(_.getMessage.contains("never decodes"))
        )
      }
    ),
    suite("typed provider helpers")(
      test("setFlag(flag, value) is readable back through the real decode path") {
        // Asserted through evaluation rather than by inspecting storage: the point of encoding on the way in is that
        // the value survives the same decode production uses.
        ZIO.scoped {
          TestFeatureProvider.layer.build.flatMap { env =>
            val provider = env.get[TestFeatureProvider]
            val ff       = env.get[FeatureFlags]
            for {
              _    <- provider.setFlag(TierFlag, Tier.Paid)
              tier <- ff.value(TierFlag)
            } yield assertTrue(tier == Tier.Paid)
          }
        }
      },
      test("removeFlag / wasEvaluated / evaluationCount accept a FlagDef") {
        ZIO.scoped {
          TestFeatureProvider.layer.build.flatMap { env =>
            val provider = env.get[TestFeatureProvider]
            val ff       = env.get[FeatureFlags]
            for {
              _       <- provider.setFlag(TierFlag, Tier.Paid)
              _       <- ff.value(TierFlag)
              seen    <- provider.wasEvaluated(TierFlag)
              counted <- provider.evaluationCount(TierFlag)
              _       <- provider.removeFlag(TierFlag)
              // Removed, so the caller's default is what comes back.
              afterRemoval <- ff.valueOrDefault(TierFlag)
            } yield assertTrue(seen, counted == 1, afterRemoval == Tier.Free)
          }
        }
      }
    ),
    suite("typed factories")(
      test("layer(overrides*) seeds the provider and evaluates through the real decode path") {
        val flags = TestFeatureProvider.layer(TierFlag := Tier.Paid, CountFlag := 7)
        ZIO
          .scoped {
            flags.build.map(_.get[FeatureFlags]).flatMap { ff =>
              for {
                tier  <- ff.value(TierFlag)
                limit <- ff.value(CountFlag)
              } yield assertTrue(tier == Tier.Paid, limit == 7)
            }
          }
      },
      test("make(overrides*) seeds the provider") {
        // Read back through the provider's own SDK surface, which is where the encoded value has to land.
        for {
          provider <- TestFeatureProvider.make(TierFlag := Tier.Paid)
          result   <- ZIO.succeed(provider.getStringEvaluation("user.tier", "unset", new ImmutableContext()))
        } yield assertTrue(result.getValue == "paid")
      },
      test("scopedLayer(overrides*) works without an outer Scope") {
        ZIO
          .serviceWithZIO[FeatureFlags](_.value(TierFlag))
          .map(t => assertTrue(t == Tier.Paid))
          .provide(TestFeatureProvider.scopedLayer(TierFlag := Tier.Paid))
      },
      test("the untyped factories still work unchanged") {
        // The typed surface is additive: the key-based API remains the way to test an undeclared or foreign key.
        ZIO.scoped {
          TestFeatureProvider.layer(Map[String, Any]("user.tier" -> "paid")).build.map(_.get[FeatureFlags]).flatMap {
            ff => ff.value(TierFlag).map(t => assertTrue(t == Tier.Paid))
          }
        }
      },
      test("providerLayer and scopedAsyncLayer also have typed twins") {
        // The whole point of covering every factory: switching factory must not send you back to Map[String, Any].
        ZIO
          .serviceWithZIO[TestFeatureProvider](_.wasEvaluated(TierFlag))
          .map(seen => assertTrue(!seen))
          .provide(TestFeatureProvider.providerLayer(TierFlag := Tier.Paid))
      },
      test("duplicate overrides for one key are rejected rather than silently last-wins") {
        // Two FlagDefs sharing a key — most likely at different types — is a fixture bug, not a merge.
        val other  = FlagDef[Tier]("user.tier", Tier.Free)
        val thrown = scala.util.Try(TestFeatureProvider.make(TierFlag := Tier.Paid, other := Tier.Free)).failed.toOption
        assertTrue(thrown.exists(_.getMessage.contains("user.tier")))
      },
      test("every untyped asyncReadyLayer call shape still resolves") {
        // The stated reason the typed twin takes its delay explicitly and names it `delay`: these three shapes are
        // legal today and a careless overload would make them ambiguous. Compiling IS the assertion.
        val a = TestFeatureProvider.asyncReadyLayer()
        val b = TestFeatureProvider.asyncReadyLayer(Map[String, Any]("k" -> true))
        val c = TestFeatureProvider.asyncReadyLayer(initDelay = 50.millis)
        val d = TestFeatureProvider.asyncReadyLayer(50.millis, TierFlag := Tier.Paid)
        assertTrue(a != null, b != null, c != null, d != null)
      },
      test("a bare parameterless layer still resolves") {
        // Guards the overload set: parameterless `layer`, `layer(Map)` and `layer(FlagOverride*)` must coexist.
        ZIO.scoped {
          TestFeatureProvider.layer.build.map(_.get[FeatureFlags]).flatMap { ff =>
            ff.valueOrDefault(TierFlag).map(t => assertTrue(t == Tier.Free))
          }
        }
      }
    )
  )
}
