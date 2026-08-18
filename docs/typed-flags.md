---
layout: default
title: Typed Flags
nav_order: 3
---

# Typed Flags
{: .no_toc }

Declare each flag once — key, type and default together — and evaluate it by name.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Why

The string-key API is complete, and it stays. But it restates three things at every call site:

```scala
// module A
FeatureFlags.boolean("checkout-v2", default = false)

// module B — same flag, different default. Nothing catches this.
FeatureFlags.boolean("checkout-v2", default = true)

// module C — same flag, read at the wrong type. Nothing catches this either.
FeatureFlags.string("checkout-v2", default = "off")
```

Every one of those is legal Scala, and the disagreement only shows up in production. A `FlagDef[A]` states
the key, the type and the default **once**, and every use site refers to the definition:

```scala
import zio.openfeature.*

object Flags:
  val CheckoutV2 = FlagDef("checkout-v2", false, "new checkout flow")

// everywhere else
FeatureFlags.value(Flags.CheckoutV2)        // ZIO[FeatureFlags, FeatureFlagError, Boolean]
```

The two disagreements above are now a compile error and a nonsense expression respectively — there is no
second place to state a default, and `value` returns the definition's `A`.

{: .note }
> This is **additive and complementary**, not a replacement. `FlagDef` overloads delegate to exactly the same
> generic evaluation tier as the string-key calls, so hooks, transactions, caching, timeouts, provider
> selection and error semantics are all identical. Mix both freely — see
> [when to keep using string keys](#when-to-keep-using-string-keys).

---

## Define your flags once

A plain object holding `FlagDef` values is the whole pattern — it becomes your application's flag catalog,
and `grep` for a key finds one line instead of a dozen:

```scala
import zio.openfeature.*

object Flags:
  val CheckoutV2  = FlagDef("checkout-v2", false, "new checkout flow")
  val Banner      = FlagDef("homepage-banner", "none")
  val MaxItems    = FlagDef("cart-max-items", 100)
  val MonthlyCap  = FlagDef("billing-cap-cents", 0L)
```

The third argument is an optional description, carried for documentation only — it is never sent to the
provider.

{: .warning }
> **Name the type when the default is a `case object`.** `FlagDef("k", Tier.Free)` infers `A` as
> `Tier.Free.type`, then looks for a `FlagType[Tier.Free.type]` that does not exist. Write
> `FlagDef[Tier]("k", Tier.Free)`. A Scala 3 `enum`'s *parameterless* cases are typed as the enum itself, so
> they are unaffected.

---

## Evaluate by definition

Four methods take a `FlagDef` in place of the `(key, default)` pair — on the `FeatureFlags` trait and on its
companion accessors alike, with the same context/options arities as the key-based forms:

```scala
FeatureFlags.value(Flags.CheckoutV2)                    // ZIO[FeatureFlags, FeatureFlagError, Boolean]
FeatureFlags.valueOrDefault(Flags.CheckoutV2)           // ZIO[FeatureFlags, Nothing, Boolean] — never fails
FeatureFlags.valueDetails(Flags.CheckoutV2)             // ZIO[FeatureFlags, …, FlagResolution[Boolean]]
FeatureFlags.resolveOrDefault(Flags.CheckoutV2)         // ZIO[FeatureFlags, Nothing, FlagResolution[Boolean]]

FeatureFlags.value(Flags.CheckoutV2, ctx)               // with a targeting context
FeatureFlags.valueDetails(Flags.CheckoutV2, ctx, opts)  // + per-invocation hooks / timeout
```

Which one to reach for:

| Method | Returns | Use when |
|:-------|:--------|:---------|
| `value` | `IO[FeatureFlagError, A]` | you want a provider outage to fail the effect |
| `valueOrDefault` | `UIO[A]` | the flag must never take down the request — serves `FlagDef.default` |
| `valueDetails` | `IO[…, FlagResolution[A]]` | you need the reason, variant or metadata |
| `resolveOrDefault` | `UIO[FlagResolution[A]]` | both of the above: full resolution, never fails |

Result types above are as declared on the `FeatureFlags` **trait**. The `FeatureFlags` companion accessors
used in the snippets have the same names and arities but carry the service in the environment, so
`IO[E, A]` reads `ZIO[FeatureFlags, E, A]` and `UIO[A]` reads `ZIO[FeatureFlags, Nothing, A]`.

The `*OrDefault` pair serve `FlagDef.default` on a miss or error, and log a rate-limited warning naming the
flag and why it degraded — see
[Total evaluation]({{ site.baseurl }}/getting-started#total-evaluation-never-fails).

{: .note }
> **Which default is used.** `FlagType[A]` also carries a `defaultValue`, so a `FlagDef` looks like it holds
> two defaults. It does not in any way that matters: **`FlagDef.default` is always the value served**.
> `FlagType.defaultValue` is a type-level zero needed internally by `FlagType.from`/`mapped` and is never
> consulted when evaluating.

### Comparing definitions

Equality is structural over key, default and description, so two definitions for the same key with
**different** defaults are deliberately *not* equal — they are genuinely different definitions. Compare by key
alone, across differing type parameters, with `sameKey`:

```scala
Flags.CheckoutV2.sameKey(FlagDef("checkout-v2", true))  // true
```

---

## Flags that are not `Boolean`, `String` or a number

A `FlagDef[A]` needs a `FlagType[A]`. The built-ins cover `Boolean`, `String`, `Int`, `Long`, `Float`,
`Double`, `Map[String, Any]`, plus `Option` and `List` of any of those. For your own domain types, Scala 3
derives the instance:

```scala
// A parameterless enum derives a STRING codec over the case labels.
enum Tier derives FlagType:
  case Free, Premium, Enterprise

// A case class derives a Map[String, Any] codec, field by field.
final case class Rollout(tier: String, pct: Int = 10, note: Option[String] = None) derives FlagType

object Flags:
  val Plan    = FlagDef[Tier]("user-plan", Tier.Free)
  val Staging = FlagDef("rollout", Rollout("stable"))
```

Now `FeatureFlags.value(Flags.Plan)` returns a `Tier`, and a provider serving the string `"premium"` decodes
to `Tier.Premium` (matching is case-insensitive; encoding emits the label as declared). An unknown variant is
a typed `TypeMismatch`, not a silent fall back to the default.

For a newtype over a scalar, `FlagType.mapped` derives the codec from the underlying instance:

```scala
final case class Level(n: Int)
object Level:
  given FlagType[Level] = FlagType.mapped[Level, Int]("Level", Level(0))(Level.apply, _.n)
```

### Wire type vs domain type

A `FlagType` has a **domain** type (what your code sees) and a **wire** type (what the provider is asked
for). For the built-ins they coincide. For a derived enum or a `mapped` newtype they differ, and `wireType`
is what evaluation dispatches on — so a string-backed enum is resolved through the provider's *string*
method and then decoded, rather than being asked for as an object the provider does not have.

Two consequences worth knowing:

- **Hooks filter on the wire type.** A hook scoped to `FlagValueType.String` fires for a string-backed
  custom flag. In `HookContext`, `flagType` is the **wire** type while `defaultValue` and
  `FlagResolution.value` carry **domain** values — do not cast the latter based on the former.
- **A hand-rolled instance must keep the two consistent.** If you override `wireType` to a scalar, `encode`
  must produce the matching boxed type. A mismatch fails with a diagnostic `TypeMismatch` naming the domain
  type, the declared `wireType` and what `encode` actually produced — rather than an opaque
  `ClassCastException` from inside the SDK bridge. `FlagType.mapped` gets the pairing right by construction,
  so prefer it where it fits.

See [Architecture → Type-Safe Flag Evaluation]({{ site.baseurl }}/architecture#type-safe-flag-evaluation) for
the full `FlagType` contract, including `from`, `mapped` and the round-trip law.

---

## Test with the same definitions

The definitions you evaluate in production are the ones you pin in tests, so a fixture cannot hold a value
production could never decode:

```scala
import zio.openfeature.testkit.*
import zio.openfeature.testkit.FlagOverride.Ops   // brings `:=` into scope

val layer = TestFeatureProvider.layer(
  Flags.Plan       := Tier.Premium,
  Flags.CheckoutV2 := true
)

TestFeatureProvider.layer(Flags.Plan := "premium")   // does not compile
```

`:=` type-checks the value against the flag's declared type and stores it through `flagType.encode`, so the
test reads it back through the same decode path production uses. Two overrides for the same key are rejected
rather than silently merged last-wins.

See [Testkit → Typed fixtures with FlagDef]({{ site.baseurl }}/testkit#typed-fixtures-with-flagdef) for the
full set of typed factories, and
[Law-checking a custom FlagType]({{ site.baseurl }}/testkit#law-checking-a-custom-flagtype) for verifying a
hand-written codec round-trips.

---

## When to keep using string keys

`FlagDef` is additive; the key-based API is not deprecated and is still the right tool for:

- **A key you do not own** — a foreign or third-party flag you read but do not declare.
- **A dynamic key** — one computed at runtime, where there is no definition to point at.
- **Negative tests** — asserting `FLAG_NOT_FOUND` for a key that deliberately has no definition.
- **Scala 2.13 call sites** where naming the type parameter is more ceremony than the guarantee is worth.

Both styles can address the same flag in the same codebase; they are two front doors onto one evaluation
path.

---

## Next steps

| Go to | For |
|:------|:----|
| [Getting Started]({{ site.baseurl }}/getting-started) | installation and your first evaluation |
| [Architecture]({{ site.baseurl }}/architecture#type-safe-flag-evaluation) | the `FlagType` contract, custom and derived instances |
| [Evaluation Context]({{ site.baseurl }}/context) | targeting, the context hierarchy, and ambient context |
| [Testkit]({{ site.baseurl }}/testkit) | typed fixtures, law-checking, test layers |
| [Transactions]({{ site.baseurl }}/transactions) | scoped overrides — which accept domain *or* wire values |
