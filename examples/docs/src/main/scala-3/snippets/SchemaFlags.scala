package snippets

import zio.*
import zio.openfeature.*
import zio.openfeature.schema.SchemaFlagType
import zio.schema.{DeriveSchema, Schema}

/** The zio-schema section of the Typed flags page. */
object SchemaFlags:

  // --8<-- [start:schema]
  // Case objects only: carried as a string, the case name
  sealed trait Tier
  object Tier:
    case object Free    extends Tier
    case object Premium extends Tier
    given Schema[Tier]   = DeriveSchema.gen[Tier]
    given FlagType[Tier] = SchemaFlagType[Tier]

  // Cases with data: carried as an object, {"Percentage": {"pct": 10}}
  sealed trait Rollout
  object Rollout:
    final case class Percentage(pct: Int)          extends Rollout
    final case class Allow(users: List[String])    extends Rollout
    case object Off                                extends Rollout
    given Schema[Rollout]   = DeriveSchema.gen[Rollout]
    given FlagType[Rollout] = SchemaFlagType[Rollout]

  // A case class: carried as an object, field by field
  final case class Checkout(tier: Tier, rollout: Rollout, maxItems: Int = 100)
  object Checkout:
    given Schema[Checkout]   = DeriveSchema.gen[Checkout]
    given FlagType[Checkout] = SchemaFlagType[Checkout]

  val CheckoutFlag = FlagDef("checkout", Checkout(Tier.Free, Rollout.Off))
  // --8<-- [end:schema]

  val checkout: ZIO[FeatureFlags, FeatureFlagError, Checkout] = FeatureFlags.value(CheckoutFlag)
