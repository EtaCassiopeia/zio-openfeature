# Typed flags

A `FlagDef` declares a flag's key, type and default once, and you read it by name. Enums, case classes and newtypes
work as flag values.

## Define your flags

Written out at each call site, a key, a type and a default can drift apart:

```scala
--8<-- "TypedFlags.scala:drift"
```

A plain object of `FlagDef`s is your flag catalog instead. The optional third argument is documentation only and is
never sent to the provider.

```scala
--8<-- "TypedFlags.scala:catalog"
```

!!! warning "Name the type when the default is a case object"
    `FlagDef("k", Tier.Free)` with a `case object Free` infers `Tier.Free.type`, which has no `FlagType`. Write
    `FlagDef[Tier]("k", Tier.Free)`. Parameterless cases of a Scala 3 `enum` are typed as the enum and are not affected.

## Evaluate by definition

`value`, `valueOrDefault`, `valueDetails` and `resolveOrDefault` take a `FlagDef` in place of the key and default. They
run the same evaluation as the key-based methods and return what [Evaluating flags](evaluating.md#two-tiers) describes
for each tier and for [the resolution](evaluating.md#the-resolution).

```scala
--8<-- "TypedFlags.scala:evaluate"
```

The `FlagDef`'s default is the one served when the flag is missing or broken; a `FlagType`'s `defaultValue` is never
used by evaluation. Two definitions are equal only if key, default and description match; `sameKey` compares keys:

```scala
--8<-- "TypedFlags.scala:same-key"
```

## Your own types

A `FlagDef[A]` needs a `FlagType[A]`, the codec between `A` and what the provider stores. Instances exist for
`Boolean`, `String`, `Int`, `Long`, `Float`, `Double`, `Map[String, Any]` (an object flag), and `Option` and `List` of
any type that has one. `Option[A]` is fetched with `A`'s resolver and reads `None` when the provider answered with an error or a default.

### Derive it (Scala 3)

```scala
--8<-- "TypedFlags.scala:derived"
```

- An **enum** with parameterless cases is stored as a **string** holding the case name. Reading ignores case, but an
  exact match wins when two names differ only by case; an unknown name fails with `TypeMismatch` instead of serving the
  default. A case with parameters does not compile.
- A **case class** is stored as an **object**, each field through its own `FlagType`. Unknown keys are ignored. A
  missing key takes the default declared on the case class itself (never one an overload of `apply` declares), else
  `None` for an `Option`, else fails naming the field. Defaults are not read for a case class compiled by Scala 2.13
  (one from a library built for 2.13): its missing fields follow the last two rules.

`FlagType.derived` is not a `given`, so it never competes with the built-in instances: use `derives`, or write
`given FlagType[X] = FlagType.derived[X]`.

### Map it from another type

`FlagType.mapped` builds a codec from an existing one and two functions. The flag is stored as the underlying type:

```scala
--8<-- "TypedFlags.scala:mapped"
```

??? note "Custom codecs (advanced)"
    A `FlagType` has a domain type your code sees and a **wire type** (`wire`, a `WireType`) that picks which provider
    resolver is called:

    ```mermaid
    flowchart LR
        D["domain type A"] -- "encode / decode" --> F["FlagType[A]"]
        F -- "wire" --> W["WireType"]
        W -- "scalar: Boolean, String, Int, Long, Float, Double" --> R1["typed resolver"]
        W -- "Object" --> R2["object resolver"]
    ```

    Derived enums use `String`; derived case classes, `List` and `from` use `Object`; `mapped` and `Option` inherit
    the underlying type's.

    `FlagType.from` takes a decoder and an encoder, and the flag is stored as an **object**. Values inside an object
    flag pass through the SDK, so numbers arrive as `Double` and `null` fields are dropped: decode numbers through
    `FlagType[Int]` or `FlagType[Long]`, and model optional fields as `Option`. The encoder matters even if you never
    read its output, since your default is encoded and handed to the provider.

    ```scala
    --8<-- "TypedFlags.scala:from"
    ```

    Do not use `from` for a value stored as a string: the provider would answer `TYPE_MISMATCH`. To store a scalar
    but reject values you do not recognise, write the instance by hand and override `wire` and `encode` together.
    `encode` must return the boxed type `wire` names (`String`, `java.lang.Integer`, …) or evaluation fails with a
    `TypeMismatch`.

    ```scala
    --8<-- "TypedFlags.scala:fallible"
    ```

    The string-valued `wireType` is deprecated in favor of `wire`; an instance that still overrides it keeps working.
    [Hooks](hooks.md) filter by the wire type, so a hook scoped to `FlagValueType.String` sees this flag. `mapped` and
    `from` work on Scala 2.13; derivation is Scala 3 only.

## Test with the same definitions

The testkit takes `FlagDef := value` pairs, checked against the flag's type and stored through its codec, so the test
reads the value back the way production does. Two values for one key are rejected. More fixtures are in
[Testkit](testkit.md).

```scala
--8<-- "TypedFlags.scala:fixture"
```

Check a hand-written `FlagType` with `FlagTypeLaws` from the testkit; see
[Testing providers and wrappers](testing-providers.md).

Keep using string keys for a flag you read but do not own, or a key computed at runtime. Both styles can read the same
flag.
