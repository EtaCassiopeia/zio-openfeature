package zio.openfeature.testkit

import zio.openfeature.FlagType
import zio.openfeature.internal.ValueBridge
import zio.test._

/** Law checks for a [[zio.openfeature.FlagType]] instance, so a hand-written codec can be held to the same contract as
  * the library's own.
  *
  * `FlagType` documents a round-trip law — `decode(encode(a)) == Right(a)` — and evaluation relies on it in three
  * places: the default handed to a provider is `encode(default)` and comes back through `decode`; a transaction caches
  * `encode(value)` and decodes it on re-read; and a transaction override may be given in domain form and is accepted by
  * round-tripping it. An instance that breaks the law sees values come back different from what went in.
  *
  * Two laws are offered, and '''the difference between them matters''':
  *   - [[roundTrip]] checks `decode(encode(a))` in memory. It is the law as stated, and it passes trivially for every
  *     built-in instance because their `encode` is (or delegates to) the identity.
  *   - [[throughValueBridge]] additionally crosses the OpenFeature `Value` conversion that every object-path evaluation
  *     crosses for real. That is where the interesting failures live: '''every number comes back as a `Double`''', so a
  *     `Long` beyond 2^53 does not survive, and a structure member the bridge cannot convert is dropped so its key
  *     reads back absent. A codec can satisfy `roundTrip` and still fail here.
  *
  * Use [[all]] to check both. Reach for [[throughValueBridge]] specifically if your type is object-backed (its
  * `wireType` is not one of the scalars), since that is the path it will actually be evaluated on.
  *
  * {{{
  * object MyCodecSpec extends ZIOSpecDefault {
  *   def spec = suite("MyCodec")(
  *     FlagTypeLaws.all(Gen.int.map(Celsius(_)))
  *   )
  * }
  * }}}
  *
  * This lives in the shared source tree, not a Scala-3-only one: the laws use nothing version-specific, so a 2.13
  * project can law-check its hand-written instances too. (Only `FlagType.derived` is Scala 3 only.)
  */
object FlagTypeLaws {

  /** `decode(encode(a)) == Right(a)`, the law as `FlagType` states it.
    *
    * Note this does NOT exercise the `Value` bridge — see [[throughValueBridge]].
    */
  def roundTrip[A](gen: Gen[Any, A])(implicit ft: FlagType[A]): Spec[Any, Nothing] =
    test(s"${ft.typeName}: decode(encode(a)) == Right(a)") {
      check(gen) { a =>
        assertTrue(ft.decode(ft.encode(a)) == Right(a))
      }
    }

  /** `decode` of the value recovered after crossing the OpenFeature `Value` conversion, which is what an object-path
    * evaluation really does with a default.
    *
    * This is the stricter of the two laws, and the one that catches lossy encodings: numbers return as `Double`, and
    * unconvertible structure members are dropped.
    */
  def throughValueBridge[A](gen: Gen[Any, A])(implicit ft: FlagType[A]): Spec[Any, Nothing] =
    test(s"${ft.typeName}: survives the SDK Value bridge") {
      check(gen) { a =>
        val recovered = ValueBridge.valueToAny(ValueBridge.anyToValue(ft.encode(a)))
        // `None` means the bridge could not represent the encoded form at all — a legitimate outcome only for an
        // instance whose own `decode` accepts an absent value (`Option` does), so it is routed through `decode`
        // rather than being asserted away.
        assertTrue(ft.decode(recovered.orNull) == Right(a))
      }
    }

  /** Both laws. */
  def all[A](gen: Gen[Any, A])(implicit ft: FlagType[A]): Spec[Any, Nothing] =
    suite(s"FlagType[${ft.typeName}] laws")(roundTrip(gen), throughValueBridge(gen))
}
