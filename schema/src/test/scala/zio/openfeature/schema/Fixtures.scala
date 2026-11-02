package zio.openfeature.schema

import zio.openfeature.FlagType
import zio.schema.{DeriveSchema, Schema}
import zio.test.Gen

// Written as sealed traits and case classes so one source serves Scala 2.13 and 3.
object Fixtures {

  final case class Rollout(tier: String, pct: Int, ratio: Double, weight: Float, enabled: Boolean, cap: Long)
  object Rollout {
    implicit val schema: Schema[Rollout]     = DeriveSchema.gen[Rollout]
    implicit val flagType: FlagType[Rollout] = SchemaFlagType[Rollout]
    val gen: Gen[Any, Rollout]               =
      for {
        tier    <- Gen.alphaNumericString
        pct     <- Gen.int
        ratio   <- Gen.double(-1e9, 1e9)
        weight  <- Gen.float.map(f => if (f.isNaN || f.isInfinite) 0f else f)
        enabled <- Gen.boolean
        cap     <- Gen.long
      } yield Rollout(tier, pct, ratio, weight, enabled, cap)
  }

  final case class Limits(maxItems: Int, label: String)
  object Limits {
    implicit val schema: Schema[Limits]     = DeriveSchema.gen[Limits]
    implicit val flagType: FlagType[Limits] = SchemaFlagType[Limits]
    val gen: Gen[Any, Limits]               = Gen.int.zipWith(Gen.alphaNumericString)(Limits(_, _))
  }

  final case class Checkout(name: String, limits: Limits, items: List[Limits])
  object Checkout {
    implicit val schema: Schema[Checkout]     = DeriveSchema.gen[Checkout]
    implicit val flagType: FlagType[Checkout] = SchemaFlagType[Checkout]
    val gen: Gen[Any, Checkout]               =
      for {
        name   <- Gen.alphaNumericString
        limits <- Limits.gen
        items  <- Gen.listOfBounded(0, 3)(Limits.gen)
      } yield Checkout(name, limits, items)
  }

  final case class Collections(note: Option[String], ids: List[Long], weights: Map[String, Int], tags: List[String])
  object Collections {
    implicit val schema: Schema[Collections]     = DeriveSchema.gen[Collections]
    implicit val flagType: FlagType[Collections] = SchemaFlagType[Collections]
    val gen: Gen[Any, Collections]               =
      for {
        note    <- Gen.option(Gen.alphaNumericString)
        ids     <- Gen.listOfBounded(0, 4)(Gen.long)
        weights <- Gen.mapOfBounded(0, 3)(Gen.alphaNumericStringBounded(1, 6), Gen.int)
        tags    <- Gen.listOfBounded(0, 3)(Gen.alphaNumericString)
      } yield Collections(note, ids, weights, tags)
  }

  final case class WithDefaults(name: String, pct: Int = 10, note: Option[String])
  object WithDefaults {
    implicit val schema: Schema[WithDefaults]     = DeriveSchema.gen[WithDefaults]
    implicit val flagType: FlagType[WithDefaults] = SchemaFlagType[WithDefaults]
  }

  sealed trait Tier
  object Tier {
    case object Free       extends Tier
    case object Premium    extends Tier
    case object Enterprise extends Tier

    implicit val schema: Schema[Tier]     = DeriveSchema.gen[Tier]
    implicit val flagType: FlagType[Tier] = SchemaFlagType[Tier]
    val gen: Gen[Any, Tier]               = Gen.elements(Free, Premium, Enterprise)
  }

  sealed trait Shape
  object Shape {
    final case class Circle(radius: Double)        extends Shape
    final case class Rect(width: Int, height: Int) extends Shape
    case object Empty                              extends Shape

    implicit val schema: Schema[Shape]     = DeriveSchema.gen[Shape]
    implicit val flagType: FlagType[Shape] = SchemaFlagType[Shape]
    val gen: Gen[Any, Shape]               = Gen.oneOf(
      Gen.double(-1e6, 1e6).map(Circle(_)),
      Gen.int.zipWith(Gen.int)(Rect(_, _)),
      Gen.const(Empty)
    )
  }

  final case class Plan(tier: Tier, shape: Shape, fallback: Option[Shape])
  object Plan {
    implicit val schema: Schema[Plan]     = DeriveSchema.gen[Plan]
    implicit val flagType: FlagType[Plan] = SchemaFlagType[Plan]
    val gen: Gen[Any, Plan]               =
      for {
        tier     <- Tier.gen
        shape    <- Shape.gen
        fallback <- Gen.option(Shape.gen)
      } yield Plan(tier, shape, fallback)
  }

  final case class WithUuid(id: java.util.UUID)
  object WithUuid {
    implicit val schema: Schema[WithUuid] = DeriveSchema.gen[WithUuid]
  }
}
