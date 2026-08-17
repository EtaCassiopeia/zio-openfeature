package zio.openfeature.testkit

import zio.openfeature.FlagDef

/** A type-checked flag value for a test fixture (#351).
  *
  * `TestFeatureProvider`'s key-based `setFlag[A](key, value)` accepts anything, so a fixture can pin a value production
  * could never decode: the test passes against the fixture and production fails with `TYPE_MISMATCH`. Building the
  * fixture from a [[zio.openfeature.FlagDef]] instead makes the value's type checked against the flag's declared type,
  * and stores it through `flagType.encode` so the test exercises the same decode path production does.
  *
  * {{{
  * import zio.openfeature.testkit.FlagOverride.Ops   // `:=` lives here, and is not in implicit scope without it
  *
  * // Name the type parameter: `FlagDef("user-plan", Tier.Free)` would infer `Tier.Free.type` for a case object.
  * val Plan = FlagDef[Tier]("user-plan", Tier.Free)
  *
  * TestFeatureProvider.layer(Plan := Tier.Premium)   // compiles
  * TestFeatureProvider.layer(Plan := "premium")      // does not
  * }}}
  *
  * ==Why this type has no type parameter==
  *
  * The issue that introduced it proposed `FlagOverride[A]`, but the parameter is never used after construction — the
  * value is stored erased either way — and a parameterised version forces a `[?]`/`[_]` wildcard into the varargs
  * signatures, which cannot be spelled once across Scala 2.13 and 3. The compile-time guarantee lives entirely in
  * [[FlagOverride.Ops.:=]], which requires the value to be an `A` for a `FlagDef[A]`, so dropping the parameter costs
  * nothing and keeps the cross-build surface clean.
  *
  * @param key
  *   the flag key, taken from the `FlagDef`
  * @param encoded
  *   the value as `flagType.encode` produced it — the wire form a provider would carry
  * @param typeName
  *   the domain type's name, for error messages
  */
final case class FlagOverride(key: String, encoded: Any, typeName: String)

object FlagOverride {

  /** Builds a [[FlagOverride]] from a `FlagDef` and a value of its type.
    *
    * An `implicit class` rather than a Scala 3 `extension`, deliberately: this file is cross-compiled, and an implicit
    * class is the one spelling valid on both versions — which also means a 2.13 project gets the same `:=` sugar. (The
    * project prefers `extension` in Scala-3-only code; the same cross-build exception already applies to
    * `internal/ClientEvaluator`'s instances.)
    */
  implicit class Ops[A](private val flag: FlagDef[A]) extends AnyVal {

    /** Pin this flag to `value` in a test fixture.
      *
      * The value is encoded eagerly, and the encoding is checked to be one the same `FlagType` '''accepts''' back — a
      * codec whose `decode` rejects its own `encode` output would otherwise produce a fixture the test believes in and
      * production cannot read, and failing here points at the codec rather than at a puzzling `TYPE_MISMATCH` later.
      *
      * Note what this deliberately does not check: that `decode` returns the '''same''' value (it compares nothing),
      * and that the value survives the OpenFeature `Value` bridge an object-path evaluation crosses — which is where
      * lossy encodings actually show up. Both are the job of `FlagTypeLaws` in this module; use `FlagTypeLaws.all(gen)`
      * on a hand-written codec. A cheap accept-check here, the real laws over there.
      *
      * Failure throws, because `:=` is used inline inside an argument list where an effect cannot compose, and a codec
      * that cannot read its own output is a programmer error rather than a domain one. One practical caveat: fixtures
      * usually sit in a `private val` at spec-object level, so the throw surfaces as a class-initialisation failure,
      * which zio-test reports less legibly than a failed assertion. It is loud either way.
      */
    def :=(value: A): FlagOverride = {
      val ft      = flag.flagType
      val encoded = ft.encode(value)
      ft.decode(encoded) match {
        case Right(_) => FlagOverride(flag.key, encoded, ft.typeName)
        case Left(reason) =>
          throw new IllegalArgumentException(
            s"FlagType[${ft.typeName}] cannot read back its own encoding of the fixture value for flag " +
              s"'${flag.key}': encode produced $encoded, which decode rejected with: $reason. " +
              "Fix the FlagType instance and law-check it with FlagTypeLaws in this module — a fixture the codec " +
              "cannot read back is one production could not read either."
          )
      }
    }
  }
}
