package zio.openfeature.testkit

import zio.openfeature.FlagType
import zio.test._

/** Applies [[FlagTypeLaws]] to the library's own `FlagType` instances.
  *
  * This is both a check on those instances and the worked example of the laws. Shared source tree, so it runs on both
  * cross-build twins: braces only, no `given`/`using`, no `enum`.
  *
  * Note which generators are deliberately bounded, because the bound IS the finding:
  *   - `Float` is generated from whole numbers, since `Double`-to-`Float` rounding is inherent to the bridge.
  */
object FlagTypeLawsSpec extends ZIOSpecDefault {

  private val exactLongRange = 9007199254740992L // 2^53

  /** Longs a Double cannot in general represent: |l| > 2^53. */
  private val beyondSafeLong: Gen[Any, Long] =
    Gen.oneOf(Gen.long(exactLongRange + 1, Long.MaxValue), Gen.long(Long.MinValue, -exactLongRange - 1))

  def spec = suite("FlagTypeLawsSpec")(
    FlagTypeLaws.all(Gen.boolean),
    FlagTypeLaws.all(Gen.alphaNumericString),
    FlagTypeLaws.all(Gen.int),
    FlagTypeLaws.all(Gen.long),
    FlagTypeLaws.all(Gen.double(-1.0e9, 1.0e9)),
    FlagTypeLaws.all(Gen.int(-1000000, 1000000).map(_.toFloat)),
    // Containers over a scalar, exercising the recursion in both the instances and the bridge.
    FlagTypeLaws.all(Gen.option(Gen.alphaNumericString)),
    FlagTypeLaws.all(Gen.listOf(Gen.int)),
    // The object instance itself: a flat structure of strings, which is what survives the bridge unchanged.
    FlagTypeLaws.all(
      Gen.mapOf(Gen.alphaNumericStringBounded(1, 8), Gen.alphaNumericString).map(m => m: Map[String, Any])
    ),
    // A Long member keeps its exact value across the bridge, beyond 2^53 too.
    FlagTypeLaws.all(Gen.mapOf(Gen.alphaNumericStringBounded(1, 8), beyondSafeLong).map(m => m: Map[String, Any])),
    test("roundTrip alone does NOT catch a lossy encoding — throughValueBridge is what does") {
      // Pins the documented difference between the two laws. A map with a member the SDK has no Value for (a UUID)
      // satisfies `decode(encode(a)) == Right(a)` in memory, but the bridge sends that member as its string, which is
      // what comes back. It asserts a limitation, not a desired behaviour.
      val m       = Map[String, Any]("id" -> java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))
      val ft      = FlagType[Map[String, Any]]
      val inMem   = ft.decode(ft.encode(m)) == Right(m)
      val bridged = zio.openfeature.internal.ValueBridge
        .valueToAny(zio.openfeature.internal.ValueBridge.anyToValue(ft.encode(m)))
        .map(v => ft.decode(v))
      assertTrue(inMem, bridged != Some(Right(m)))
    }
  )
}
