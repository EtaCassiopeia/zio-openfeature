package zio.openfeature.testkit

import zio.openfeature.FlagType
import zio.test._

/** Applies [[FlagTypeLaws]] to the library's own `FlagType` instances.
  *
  * This is both a check on those instances and the worked example of the laws. Shared source tree, so it runs on both
  * cross-build twins: braces only, no `given`/`using`, no `enum`.
  *
  * Note which generators are deliberately bounded, because the bound IS the finding:
  *   - `Long` is generated within ±2^53. Beyond that the `Value` bridge returns a `Double` and the value does not come
  *     back intact — a genuine, documented limitation of object-path evaluation rather than a bug in the instance, so
  *     the law is checked over the range where the contract holds.
  *   - `Float` is generated from whole numbers, since `Double`-to-`Float` rounding is inherent to the bridge.
  */
object FlagTypeLawsSpec extends ZIOSpecDefault {

  private val exactLongRange = 9007199254740992L // 2^53

  def spec = suite("FlagTypeLawsSpec")(
    FlagTypeLaws.all(Gen.boolean),
    FlagTypeLaws.all(Gen.alphaNumericString),
    FlagTypeLaws.all(Gen.int),
    FlagTypeLaws.all(Gen.long(-exactLongRange, exactLongRange)),
    FlagTypeLaws.all(Gen.double(-1.0e9, 1.0e9)),
    FlagTypeLaws.all(Gen.int(-1000000, 1000000).map(_.toFloat)),
    // Containers over a scalar, exercising the recursion in both the instances and the bridge.
    FlagTypeLaws.all(Gen.option(Gen.alphaNumericString)),
    FlagTypeLaws.all(Gen.listOf(Gen.int)),
    // The object instance itself: a flat structure of strings, which is what survives the bridge unchanged.
    FlagTypeLaws.all(
      Gen.mapOf(Gen.alphaNumericStringBounded(1, 8), Gen.alphaNumericString).map(m => m: Map[String, Any])
    ),
    test("roundTrip alone does NOT catch a lossy encoding — throughValueBridge is what does") {
      // Pins the documented difference between the two laws. This instance satisfies `decode(encode(a)) == Right(a)`
      // in memory for every Long, but loses precision above 2^53 once it crosses the bridge, because the SDK
      // represents every number as a Double. If a future change made the bridge lossless, this assertion is the
      // thing that should be revisited — it is asserting a limitation, not a desired behaviour.
      val big   = exactLongRange + 1L
      val ft    = FlagType[Long]
      val inMem = ft.decode(ft.encode(big)) == Right(big)
      val bridged = zio.openfeature.internal.ValueBridge
        .valueToAny(zio.openfeature.internal.ValueBridge.anyToValue(ft.encode(big)))
        .map(v => ft.decode(v))
      assertTrue(inMem, bridged != Some(Right(big)))
    }
  )
}
