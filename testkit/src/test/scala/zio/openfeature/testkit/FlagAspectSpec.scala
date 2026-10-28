package zio.openfeature.testkit

import zio._
import zio.test._
import zio.openfeature._
import zio.openfeature.testkit.FlagOverride.Ops
import java.util.concurrent.atomic.AtomicReference

/** Per-test flag overrides as TestAspects, restored per key.
  *
  * Every suite here shares one provider (`provideShared`) and runs sequentially, so a test can observe what the
  * previous test's aspect left behind. Shared test source dir → compiles on 2.13 and 3.
  */
object FlagAspectSpec extends ZIOSpecDefault {

  private val Color = FlagDef[String]("color", "red")

  private def readColor: ZIO[FeatureFlags, FeatureFlagError, String] = FeatureFlags.string("color", "default")

  // What the flag held between the inner and the outer aspect's restore, captured by `probe` below.
  private val betweenRestores = new AtomicReference[Option[String]](None)

  private val probe: TestAspect[Nothing, FeatureFlags, Nothing, Any] =
    TestAspect.after(
      FeatureFlags.stringOrDefault("color", "default").flatMap(v => ZIO.succeed(betweenRestores.set(Some(v))))
    )

  private def layer = TestFeatureProvider.layer(Map("color" -> "blue"))

  def spec = suite("Flag aspects")(
    suite("typed withFlags")(
      test("the test reads the overridden value") {
        for (v <- readColor) yield assertTrue(v == "green")
      } @@ TestFeatureProvider.withFlags(Color := "green"),
      test("the next test reads the original value again") {
        for (v <- readColor) yield assertTrue(v == "blue")
      }
    ).provideShared(layer) @@ TestAspect.sequential,
    suite("withFlag overloads")(
      test("typed withFlag pins one flag") {
        for (v <- readColor) yield assertTrue(v == "yellow")
      } @@ TestFeatureProvider.withFlag(Color, "yellow"),
      test("untyped withFlag pins one key") {
        for (v <- FeatureFlags.int("limit", 0)) yield assertTrue(v == 42)
      } @@ TestFeatureProvider.withFlag("limit", 42),
      test("both are restored afterwards") {
        for {
          c     <- readColor
          limit <- FeatureFlags.resolveOrDefault[Int]("limit", 0)
        } yield assertTrue(c == "blue", limit.errorCode.contains(ErrorCode.FlagNotFound))
      }
    ).provideShared(layer) @@ TestAspect.sequential,
    suite("absent key")(
      test("an override can add a key the provider did not hold") {
        for (v <- FeatureFlags.string("extra", "default")) yield assertTrue(v == "added")
      } @@ TestFeatureProvider.withFlags(Map[String, Any]("extra" -> "added")),
      test("the key is absent again afterwards") {
        for {
          r <- FeatureFlags.resolveOrDefault[String]("extra", "default")
        } yield assertTrue(r.value == "default", r.errorCode.contains(ErrorCode.FlagNotFound))
      }
    ).provideShared(layer) @@ TestAspect.sequential,
    suite("nesting")(
      suite("suite-level override")(
        test("the innermost value wins inside the test") {
          for (v <- readColor) yield assertTrue(v == "inner")
        } @@ TestFeatureProvider.withFlag("color", "inner") @@ probe,
        test("the inner restore returned the suite value, not the original") {
          val seen = betweenRestores.get()
          assertTrue(seen.contains("suite"))
        },
        test("a sibling test sees the suite value") {
          for (v <- readColor) yield assertTrue(v == "suite")
        }
      ) @@ TestFeatureProvider.withFlags(Map[String, Any]("color" -> "suite")),
      test("outside the suite the original value is back") {
        for (v <- readColor) yield assertTrue(v == "blue")
      }
    ).provideShared(layer) @@ TestAspect.sequential,
    suite("other keys")(
      test("a setFlag on another key inside the test") {
        for {
          tp <- ZIO.service[TestFeatureProvider]
          _  <- tp.setFlag("other", 7)
          v  <- readColor
        } yield assertTrue(v == "green")
      } @@ TestFeatureProvider.withFlag("color", "green"),
      test("survives the aspect's restore") {
        for {
          other <- FeatureFlags.int("other", 0)
          c     <- readColor
        } yield assertTrue(other == 7, c == "blue")
      }
    ).provideShared(layer) @@ TestAspect.sequential,
    test("duplicate typed overrides are rejected when the aspect is built") {
      val result = scala.util.Try(TestFeatureProvider.withFlags(Color := "a", Color := "b"))
      assertTrue(result.isFailure)
    }
  )
}
