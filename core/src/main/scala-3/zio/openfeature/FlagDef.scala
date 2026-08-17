package zio.openfeature

/** A typed flag definition: key, type and default value as a single first-class value.
  *
  * Without this, every call site restates a flag's type and default, so they can drift — two call sites falling back to
  * different defaults for the same key, or evaluating the same key at different types. A `FlagDef` states each once:
  *
  * {{{
  * val NewCheckout = FlagDef("checkout.v2", false, "new checkout flow")
  *
  * for
  *   enabled <- FeatureFlags.valueOrDefault(NewCheckout)
  * yield enabled
  * }}}
  *
  * ==Which default is used==
  *
  * `FlagType[A]` also carries a `defaultValue`, so a `FlagDef` appears to hold two defaults. It does not, in any way
  * that matters: '''`FlagDef.default` is always the value served''' when evaluation misses or fails.
  * `FlagType.defaultValue` is a type-level zero required internally by `FlagType.from`/`mapped` and is '''never'''
  * consulted on the evaluation path.
  *
  * ==Equality==
  *
  * Equality is the derived structural one over `key`, `default` and `description`; `flagType` is excluded because it
  * lives in the second parameter list. Two definitions for the same key with '''different''' defaults are therefore
  * '''not''' equal — that is deliberate, since they are genuinely different definitions. Use [[sameKey]] to compare by
  * key alone, across differing type parameters.
  *
  * ==Inference with a case-object default==
  *
  * `FlagDef("k", Tier.Free)` where `Tier.Free` is a `case object` infers `A` as `Tier.Free.type`, not `Tier`, and then
  * looks for a `FlagType[Tier.Free.type]` that does not exist. Name the type when the default is a case object —
  * `FlagDef[Tier]("k", Tier.Free)`. A Scala 3 `enum`'s *parameterless* cases are typed as the enum itself, so they are
  * unaffected.
  *
  * ==Copying==
  *
  * `flagType` lives in a `using` parameter list, so the derived `copy` re-summons a `FlagType[A]` from the call site
  * rather than reusing this instance. That is invisible for the built-in types (their instances are unique), but a
  * `FlagDef` built with an explicitly-passed instance will pick up whatever instance is in scope at the `copy` — so
  * prefer constructing a new `FlagDef` over `copy` in that case.
  *
  * This type is deliberately left open (not `final`) at the maintainer's request, so downstream wrappers keep the
  * option of extending it; the intended pattern is nonetheless for a wrapper to '''hold''' a `FlagDef` rather than
  * extend one.
  *
  * @param key
  *   the flag key as the provider knows it
  * @param default
  *   the value served when evaluation misses or fails
  * @param description
  *   optional human-readable note; carried for documentation only, never sent to the provider
  */
case class FlagDef[A](key: String, default: A, description: String = "")(using val flagType: FlagType[A]):

  /** True when `other` names the same flag key, regardless of its type parameter or default. */
  def sameKey(other: FlagDef[?]): Boolean = key == other.key
