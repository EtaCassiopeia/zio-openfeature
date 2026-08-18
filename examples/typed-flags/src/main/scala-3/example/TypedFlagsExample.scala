package example

import zio.*
import zio.openfeature.*
import zio.openfeature.testkit.*
import zio.openfeature.testkit.FlagOverride.Ops // brings `:=` into scope

/** Compiles the snippets on the [[https://etacassiopeia.github.io/zio-openfeature/typed-flags Typed Flags]] docs page
  * (and the `FlagDef` snippet in the top-level README) against the real public API.
  *
  * ==Why this module exists==
  *
  * The docs page leads with `FlagDef`, and nothing was compiling the code it shows. A change to `FlagDef`, to the
  * `FlagDef`-taking overloads of `value`/`valueOrDefault`/`valueDetails`/`resolveOrDefault`, to `FlagType.derived` or
  * `FlagType.mapped`, or to `TestFeatureProvider.layer(FlagOverride*)` would have left the published page showing code
  * that no longer compiles, with CI green throughout (#385).
  *
  * ==How to keep it honest==
  *
  * '''This file is a mirror of `docs/typed-flags.md`, not an independent example.''' When you change one, change the
  * other. Its value comes entirely from staying a faithful copy — an example that drifts into its own shape stops
  * testing the docs.
  *
  * '''Every documented result type is ascribed explicitly.''' That is the actual assertion here, and it is not
  * decoration: with inference, a change to a signature would simply be re-inferred and this module would keep compiling
  * while the docs went stale. The ascriptions are what turn the page's type claims into a build failure. They already
  * caught one real error while #383 was being written — the companion accessors were documented as
  * `IO[FeatureFlagError, A]` when they return `ZIO[FeatureFlags, FeatureFlagError, A]`.
  */
object TypedFlagsExample extends ZIOAppDefault:

  // Domain types — docs: "Flags that are not Boolean, String or a number"

  /** A parameterless enum derives a STRING codec over the case labels. */
  enum Tier derives FlagType:
    case Free, Premium, Enterprise

  /** A case class derives a `Map[String, Any]` codec, field by field. `pct` carries a Scala default and `note` is an
    * `Option`, so both absent-key decode paths the docs describe are exercised.
    */
  final case class Rollout(tier: String, pct: Int = 10, note: Option[String] = None) derives FlagType

  /** A newtype over a scalar: `mapped` inherits the underlying instance's wire type. */
  final case class Level(n: Int)

  object Level:
    given FlagType[Level] = FlagType.mapped[Level, Int]("Level", Level(0))(Level.apply, _.n)

  // The catalog — docs: "Define your flags once"

  object Flags:
    val CheckoutV2: FlagDef[Boolean] = FlagDef("checkout-v2", false, "new checkout flow")
    val Banner: FlagDef[String]      = FlagDef("homepage-banner", "none")
    val MaxItems: FlagDef[Int]       = FlagDef("cart-max-items", 100)
    val MonthlyCap: FlagDef[Long]    = FlagDef("billing-cap-cents", 0L)

    // Named type parameter, per the docs' warning: `FlagDef("k", Tier.Free)` would infer `Tier.Free.type`.
    val Plan: FlagDef[Tier]       = FlagDef[Tier]("user-plan", Tier.Free)
    val Staging: FlagDef[Rollout] = FlagDef("rollout", Rollout("stable"))
    val PageSize: FlagDef[Level]  = FlagDef("page-size", Level(1))

  private val ctx: EvaluationContext  = EvaluationContext.builder.attribute("userId", "alice").build
  private val opts: EvaluationOptions = EvaluationOptions.empty

  /** The documented signatures, ascribed. A signature change breaks the build here.
    *
    * These are `def`s rather than `val`s so the object stays cheap to initialise and nothing evaluates a flag at class
    * load; the ascriptions do their work at compile time either way.
    */
  object Snippets:

    // docs: "Evaluate by definition" — the four forms, no context
    def value: ZIO[FeatureFlags, FeatureFlagError, Boolean] = FeatureFlags.value(Flags.CheckoutV2)
    def valueOrDefault: ZIO[FeatureFlags, Nothing, Boolean] = FeatureFlags.valueOrDefault(Flags.CheckoutV2)
    def valueDetails: ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Boolean]] =
      FeatureFlags.valueDetails(Flags.CheckoutV2)
    def resolveOrDefault: ZIO[FeatureFlags, Nothing, FlagResolution[Boolean]] =
      FeatureFlags.resolveOrDefault(Flags.CheckoutV2)

    // ...and the context / options arities
    def withContext: ZIO[FeatureFlags, FeatureFlagError, Boolean] = FeatureFlags.value(Flags.CheckoutV2, ctx)
    def withOptions: ZIO[FeatureFlags, FeatureFlagError, FlagResolution[Boolean]] =
      FeatureFlags.valueDetails(Flags.CheckoutV2, ctx, opts)

    // docs: derived and mapped types read back at their DOMAIN type, not their wire type
    def plan: ZIO[FeatureFlags, FeatureFlagError, Tier]       = FeatureFlags.value(Flags.Plan)
    def staging: ZIO[FeatureFlags, FeatureFlagError, Rollout] = FeatureFlags.value(Flags.Staging)
    def pageSize: ZIO[FeatureFlags, FeatureFlagError, Level]  = FeatureFlags.value(Flags.PageSize)

    // docs: "Comparing definitions"
    def sameKey: Boolean = Flags.CheckoutV2.sameKey(FlagDef("checkout-v2", true))

    // docs: "Test with the same definitions" — typed fixtures through the flag's own codec
    def fixture: ZLayer[Scope, Throwable, TestFeatureProvider with FeatureFlags] =
      TestFeatureProvider.layer(
        Flags.Plan       := Tier.Premium,
        Flags.CheckoutV2 := true
      )

    // docs (getting-started): a definition declared at the use site rather than in a catalog
    def locallyDeclared: ZIO[FeatureFlags, FeatureFlagError, Int] =
      FeatureFlags.value(FlagDef("max-items", 100, "cart page size"))

  /** Runs the snippets against the docs' own fixture, so the module is executable with no external setup — no provider,
    * no network, no environment variables.
    */
  private val program: ZIO[FeatureFlags, FeatureFlagError, Unit] =
    for
      checkout <- Snippets.value
      plan     <- Snippets.plan
      details  <- Snippets.valueDetails
      // The total tier cannot fail, so an unseeded flag serves its own declared default rather than erroring.
      capacity <- FeatureFlags.valueOrDefault(Flags.PageSize)
      _        <- Console.printLine(s"checkout-v2 = $checkout").orDie
      _        <- Console.printLine(s"user-plan   = $plan").orDie
      _        <- Console.printLine(s"page-size   = $capacity (unseeded, so the FlagDef default)").orDie
      _        <- Console.printLine(s"reason      = ${details.reason}").orDie
      _        <- Console.printLine(s"sameKey     = ${Snippets.sameKey}").orDie
    yield ()

  // The union is the honest type, and it is ascribed for the same reason everything else here is: `FeatureFlagError` is
  // deliberately NOT a `Throwable`, so an evaluation failure and a layer-construction failure stay distinguishable
  // instead of collapsing into one channel. Widening this to `Any` would hide exactly that distinction.
  def run: ZIO[Any, Throwable | FeatureFlagError, Unit] =
    ZIO.scoped(program.provideSome[Scope](Snippets.fixture))
