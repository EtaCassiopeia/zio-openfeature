package zio.openfeature.schema

import zio._
import zio.openfeature.{FeatureFlags, FlagType}
import zio.openfeature.testkit.TestFeatureProvider
import zio.schema.{DeriveSchema, Schema}
import zio.test._

/** SchemaFlagType edges the law suite's generators do not reach: an `Option` field set to `None`. */
object SchemaOptionNoneSpec extends ZIOSpecDefault {

  // An Option field whose declared default is not None.
  final case class Timeouts(name: String, retryMs: Option[Int] = Some(30))
  object Timeouts {
    implicit val schema: Schema[Timeouts]     = DeriveSchema.gen[Timeouts]
    implicit val flagType: FlagType[Timeouts] = SchemaFlagType[Timeouts]
  }

  // Control: an Option field without a declared default.
  final case class Plain(name: String, retryMs: Option[Int])
  object Plain {
    implicit val schema: Schema[Plain]     = DeriveSchema.gen[Plain]
    implicit val flagType: FlagType[Plain] = SchemaFlagType[Plain]
  }

  final case class Nested(inner: Option[Option[Int]])
  object Nested {
    implicit val schema: Schema[Nested]     = DeriveSchema.gen[Nested]
    implicit val flagType: FlagType[Nested] = SchemaFlagType[Nested]
  }

  private def roundTrip[A](a: A)(implicit ft: FlagType[A]): Either[String, A] = ft.decode(ft.encode(a))

  def spec = suite("SchemaOptionNoneSpec")(
    test("control: an Option field without a default round-trips None") {
      assertTrue(roundTrip(Plain("a", None)) == Right(Plain("a", None)))
    },
    test("round-trip law: an Option field set to None whose declared default is Some stays None") {
      assertTrue(roundTrip(Timeouts("a", None)) == Right(Timeouts("a", None)))
    },
    // Documented exception: Some(None) and None both encode as null, as with core's FlagType[Option[Option[_]]].
    test("a nested Option field Some(None) collapses to None") {
      assertTrue(roundTrip(Nested(Some(None))) == Right(Nested(None)))
    },
    test("end to end: a domain-value override Timeouts(None) is served as itself") {
      for {
        p   <- TestFeatureProvider.make
        env <- FeatureFlags.fromProvider(p).build
        ff = env.get[FeatureFlags]
        r <- ff
          .transactionEither(overrides = Map("t" -> Timeouts("a", None)))(
            ff.value[Timeouts]("t", Timeouts("dflt", Some(1)))
          )
          .either
        served = r.map(_.result)
      } yield assertTrue(served == Right(Timeouts("a", None)))
    },
    test("end to end: a missing flag serves the caller's default Timeouts(None) unchanged") {
      for {
        p   <- TestFeatureProvider.make
        env <- FeatureFlags.fromProvider(p).build
        ff = env.get[FeatureFlags]
        d <- ff.resolveOrDefault[Timeouts]("absent", Timeouts("dflt", None))
      } yield assertTrue(d.value == Timeouts("dflt", None))
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
}
