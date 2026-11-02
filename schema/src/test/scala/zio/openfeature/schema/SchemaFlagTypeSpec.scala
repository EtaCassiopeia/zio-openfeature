package zio.openfeature.schema

import zio._
import zio.openfeature.{FeatureFlagError, FeatureFlags, FlagDef, FlagType, WireType}
import zio.openfeature.testkit.{FlagTypeLaws, TestFeatureProvider}
import zio.openfeature.testkit.FlagOverride.Ops
import zio.openfeature.testsupport.PropertyBudget
import zio.schema.Schema
import zio.test._

import Fixtures._

object SchemaFlagTypeSpec extends ZIOSpecDefault {

  private def javaMap(entries: (String, Any)*): java.util.Map[String, Any] = {
    val m = new java.util.HashMap[String, Any]()
    entries.foreach { case (k, v) => m.put(k, v) }
    m
  }

  private def javaList(elems: Any*): java.util.List[Any] = {
    val l = new java.util.ArrayList[Any]()
    elems.foreach(e => l.add(e))
    l
  }

  def spec = suite("SchemaFlagType")(
    suite("laws")(
      FlagTypeLaws.all(Rollout.gen),
      FlagTypeLaws.all(Checkout.gen),
      FlagTypeLaws.all(Collections.gen),
      FlagTypeLaws.all(Tier.gen),
      FlagTypeLaws.all(Shape.gen),
      FlagTypeLaws.all(Plan.gen),
      // A primitive schema delegates to the built-in instance.
      FlagTypeLaws.all(Gen.int)(SchemaFlagType[Int]),
      // A transformed schema is stored as its underlying shape.
      FlagTypeLaws.all(Gen.alphaNumericString.map(Name(_)))(Name.flagType)
    ) @@ PropertyBudget(50),
    suite("decoding SDK-shaped values")(
      test("numbers arriving as Double read into Int and Long fields") {
        val decoded = Rollout.flagType.decode(
          Map("tier" -> "beta", "pct" -> 5.0, "ratio" -> 0.5, "weight" -> 1.5, "enabled" -> true, "cap" -> 7.0)
        )
        assertTrue(decoded == Right(Rollout("beta", 5, 0.5, 1.5f, enabled = true, 7L)))
      },
      test("a non-integral Double for an Int field fails naming the field") {
        val decoded = Limits.flagType.decode(Map("maxItems" -> 1.5, "label" -> "x"))
        assertTrue(decoded.left.exists(_.contains("Limits.maxItems")))
      },
      test("java.util.Map and java.util.List are accepted") {
        val decoded = Collections.flagType.decode(
          javaMap("note" -> "n", "ids" -> javaList(1.0, 2.0), "weights" -> javaMap("a" -> 3.0), "tags" -> javaList())
        )
        assertTrue(decoded == Right(Collections(Some("n"), List(1L, 2L), Map("a" -> 3), Nil)))
      },
      test("a dropped null field reads as None") {
        val absent  = Collections.flagType.decode(Map("ids" -> Nil, "weights" -> Map.empty, "tags" -> Nil))
        val present = Collections.flagType.decode(Map("note" -> null, "ids" -> Nil, "weights" -> Map(), "tags" -> Nil))
        assertTrue(absent == Right(Collections(None, Nil, Map.empty, Nil)), present == absent)
      },
      test("unknown keys are ignored") {
        assertTrue(Limits.flagType.decode(Map("maxItems" -> 3, "label" -> "x", "extra" -> 1)) == Right(Limits(3, "x")))
      },
      test("a missing key takes the declared default, a missing Option field reads None") {
        assertTrue(WithDefaults.flagType.decode(Map("name" -> "a")) == Right(WithDefaults("a", 10, None)))
      },
      test("a missing required field fails naming it") {
        val decoded = WithDefaults.flagType.decode(Map("pct" -> 3))
        assertTrue(decoded.left.exists(r => r.contains("WithDefaults.name") && r.contains("missing key")))
      },
      test("a Long above 2^53 keeps its precision") {
        val big     = (1L << 53) + 1
        val decoded = Collections.flagType.decode(Map("ids" -> List(big), "weights" -> Map.empty, "tags" -> Nil))
        assertTrue(decoded.map(_.ids) == Right(List(big)))
      },
      test("a case-name enum reads case-insensitively and rejects an unknown name") {
        assertTrue(
          Tier.flagType.decode("premium") == Right(Tier.Premium),
          Tier.flagType.decode("Enterprise") == Right(Tier.Enterprise),
          Tier.flagType.decode("gold").left.exists(_.contains("Unknown Tier")),
          Tier.flagType.encode(Tier.Premium) == "Premium"
        )
      },
      test("a sum with data is a wrapper object keyed by the case name") {
        assertTrue(
          Shape.flagType.encode(Shape.Rect(2, 3)) == Map("Rect" -> Map("width" -> 2, "height" -> 3)),
          Shape.flagType.encode(Shape.Empty) == Map("Empty" -> Map.empty),
          Shape.flagType.decode(javaMap("Circle" -> javaMap("radius" -> 1.5))) == Right(Shape.Circle(1.5)),
          Shape.flagType.decode(Map("Empty" -> Map.empty)) == Right(Shape.Empty),
          Shape.flagType.decode(Map("Triangle" -> Map.empty)).left.exists(_.contains("Unknown Shape case")),
          Shape.flagType.decode(Map("Empty" -> Map.empty, "Circle" -> Map.empty)).isLeft
        )
      },
      test("an unsupported primitive fails to decode, naming its type") {
        val ft = SchemaFlagType[WithUuid]
        assertTrue(ft.decode(Map("id" -> "00000000-0000-0000-0000-000000000000")).left.exists(_.contains("uuid")))
      },
      test("a Map with non-String keys fails to decode") {
        val ft = SchemaFlagType[Map[Int, String]]
        assertTrue(ft.decode(Map("1" -> "a")).isLeft)
      }
    ),
    suite("wire")(
      test("a primitive is its scalar") {
        assertTrue(
          SchemaFlagType[Boolean].wire == WireType.Boolean,
          SchemaFlagType[String].wire == WireType.String,
          SchemaFlagType[Int].wire == WireType.Int,
          SchemaFlagType[Long].wire == WireType.Long,
          SchemaFlagType[Float].wire == WireType.Float,
          SchemaFlagType[Double].wire == WireType.Double
        )
      },
      test("a transform over a primitive is that primitive's scalar") {
        assertTrue(Name.flagType.wire == WireType.String, Name.flagType.encode(Name("a")) == "a")
      },
      test("a case-name enum is a String") {
        assertTrue(Tier.flagType.wire == WireType.String)
      },
      test("records, sums with data, sequences and maps are Objects") {
        assertTrue(
          Rollout.flagType.wire == WireType.Object,
          Shape.flagType.wire == WireType.Object,
          SchemaFlagType[List[Int]].wire == WireType.Object,
          SchemaFlagType[Map[String, Int]].wire == WireType.Object
        )
      },
      test("typeName is the schema's type name") {
        assertTrue(Rollout.flagType.typeName == "Rollout", Shape.flagType.typeName == "Shape")
      },
      test("defaultValue is the type-level zero") {
        assertTrue(
          WithDefaults.flagType.defaultValue == WithDefaults("", 0, None),
          Tier.flagType.defaultValue == Tier.Free,
          Shape.flagType.defaultValue == Shape.Circle(0.0),
          SchemaFlagType[Int].defaultValue == 0
        )
      }
    ),
    suite("evaluation through the in-memory provider")(
      test("a case class flag") {
        val flag = FlagDef[WithDefaults]("rollout", WithDefaults("stable", 1, None))
        (for {
          read <- FeatureFlags.value(flag)
        } yield assertTrue(read == WithDefaults("beta", 5, Some("n"))))
          .provide(TestFeatureProvider.layer(Map("rollout" -> Map("name" -> "beta", "pct" -> 5.0, "note" -> "n"))))
      },
      test("a sum with data, seeded through its FlagDef") {
        val flag = FlagDef[Shape]("shape", Shape.Empty)
        (for {
          read <- FeatureFlags.value(flag)
        } yield assertTrue(read == Shape.Rect(4, 5))).provide(TestFeatureProvider.layer(flag := Shape.Rect(4, 5)))
      },
      test("a case-name enum is read through the string resolver") {
        val flag = FlagDef[Tier]("plan", Tier.Free)
        (for {
          read <- FeatureFlags.value(flag)
        } yield assertTrue(read == Tier.Enterprise)).provide(TestFeatureProvider.layer(Map("plan" -> "enterprise")))
      },
      test("an optional flag uses core's Option instance over this one") {
        val flag = FlagDef[Option[Tier]]("plan", None)
        (for {
          read <- FeatureFlags.value(flag)
        } yield assertTrue(read == Some(Tier.Premium))).provide(TestFeatureProvider.layer(Map("plan" -> "premium")))
      },
      test("an unknown enum name is a TypeMismatch") {
        val flag = FlagDef[Tier]("plan", Tier.Free)
        (for {
          read <- FeatureFlags.value(flag).either
        } yield assertTrue(read.left.exists(_.isInstanceOf[FeatureFlagError.TypeMismatch])))
          .provide(TestFeatureProvider.layer(Map("plan" -> "gold")))
      }
    )
  )

  final case class Name(value: String)
  object Name {
    implicit val schema: Schema[Name]     = Schema[String].transform(Name(_), _.value)
    implicit val flagType: FlagType[Name] = SchemaFlagType[Name]
  }
}
