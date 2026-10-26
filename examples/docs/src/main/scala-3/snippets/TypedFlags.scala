package snippets

import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*

/** The code on the Typed flags page. Documented result types are ascribed, so a signature change breaks the build. */
object TypedFlags:

  // --8<-- [start:drift]
  // Three call sites, one flag, and nothing stops them disagreeing
  val inCheckout = FeatureFlags.boolean("checkout-v2", default = false)
  val inCart     = FeatureFlags.boolean("checkout-v2", default = true) // a different default
  val inAdmin    = FeatureFlags.string("checkout-v2", default = "off") // a different type
  // --8<-- [end:drift]

  // --8<-- [start:derived]
  // A parameterless enum is carried as a string: "free", "premium", ...
  enum Tier derives FlagType:
    case Free, Premium, Enterprise

  // A case class is carried as an object, field by field
  final case class Rollout(tier: String, pct: Int = 10, note: Option[String] = None) derives FlagType
  // --8<-- [end:derived]

  // --8<-- [start:mapped]
  // A newtype over an Int: carried as an Int, read back as a Level
  final case class Level(n: Int)

  object Level:
    given FlagType[Level] = FlagType.mapped[Level, Int]("Level", Level(0))(Level.apply, _.n)
  // --8<-- [end:mapped]

  // --8<-- [start:catalog]
  object Flags:
    val CheckoutV2 = FlagDef("checkout-v2", false, "new checkout flow")
    val Banner     = FlagDef("homepage-banner", "none")
    val MaxItems   = FlagDef("cart-max-items", 100)
    val MonthlyCap = FlagDef("billing-cap-cents", 0L)
    val Plan       = FlagDef[Tier]("user-plan", Tier.Free)
    val Staging    = FlagDef("rollout", Rollout("stable"))
    val PageSize   = FlagDef("page-size", Level(1))
  // --8<-- [end:catalog]

  val ctx: EvaluationContext     = EvaluationContext("user-42")
  val options: EvaluationOptions = EvaluationOptions.empty.withTimeout(200.millis)

  // --8<-- [start:evaluate]
  val value: ZIO[FeatureFlags, FeatureFlagError, Boolean] = FeatureFlags.value(Flags.CheckoutV2)
  val orDefault: ZIO[FeatureFlags, Nothing, Boolean]      = FeatureFlags.valueOrDefault(Flags.CheckoutV2)
  val details: ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Boolean]] =
    FeatureFlags.valueDetails(Flags.CheckoutV2)
  val resolution: ZIO[FeatureFlags, Nothing, FlagResolution[Boolean]] = FeatureFlags.resolveOrDefault(Flags.CheckoutV2)

  // Each also takes a context, and the two details forms an EvaluationOptions as well
  val forUser: ZIO[FeatureFlags, FeatureFlagError, Boolean] = FeatureFlags.value(Flags.CheckoutV2, ctx)
  val withOptions: ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Boolean]] =
    FeatureFlags.valueDetails(Flags.CheckoutV2, ctx, options)

  // Custom types come back as the domain type
  val plan: ZIO[FeatureFlags, FeatureFlagError, Tier]       = FeatureFlags.value(Flags.Plan)
  val staging: ZIO[FeatureFlags, FeatureFlagError, Rollout] = FeatureFlags.value(Flags.Staging)
  val pageSize: ZIO[FeatureFlags, FeatureFlagError, Level]  = FeatureFlags.value(Flags.PageSize)
  // --8<-- [end:evaluate]

  // --8<-- [start:same-key]
  val equal: Boolean = Flags.CheckoutV2 == FlagDef("checkout-v2", true)       // false: the defaults differ
  val same: Boolean  = Flags.CheckoutV2.sameKey(FlagDef("checkout-v2", true)) // true
  // --8<-- [end:same-key]

  // --8<-- [start:from]
  final case class RolloutPlan(tier: String, percentage: Int)

  given FlagType[RolloutPlan] = FlagType.from(
    name = "RolloutPlan",
    default = RolloutPlan("free", 0),
    decoder = {
      case m: Map[?, ?] =>
        val fields = m.asInstanceOf[Map[String, Any]]
        for
          tier <- FlagType[String].decode(fields.getOrElse("tier", "free"))
          pct  <- FlagType[Int].decode(fields.getOrElse("percentage", 0)) // numbers arrive as Double
        yield RolloutPlan(tier, pct)
      case other => Left(s"not a rollout plan: $other")
    },
    encoder = plan => Map("tier" -> plan.tier, "percentage" -> plan.percentage)
  )
  // --8<-- [end:from]

  // --8<-- [start:fallible]
  enum Region:
    case Eu, Us

  object Region:
    given FlagType[Region] with
      def typeName: String                       = "Region"
      override def wire: WireType                = WireType.String        // ask the provider for a string
      def defaultValue: Region                   = Region.Eu
      override def encode(r: Region)             = r.toString.toLowerCase // must be a String, to match wire
      def decode(v: Any): Either[String, Region] = v match
        case "eu"  => Right(Region.Eu)
        case "us"  => Right(Region.Us)
        case other => Left(s"unknown region: $other") // a TypeMismatch, not a silent default
  // --8<-- [end:fallible]

  // --8<-- [start:fixture]
  import zio.openfeature.testkit.FlagOverride.Ops // brings := into scope

  val fixture: ZLayer[Any, Throwable, TestFeatureProvider & FeatureFlags] =
    TestFeatureProvider.layer(
      Flags.Plan       := Tier.Premium,
      Flags.CheckoutV2 := true
    )

  // TestFeatureProvider.layer(Flags.Plan := "premium")  // does not compile: "premium" is not a Tier
  // --8<-- [end:fixture]
