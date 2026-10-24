# Typed flags

Declare each flag once, with its key, type and default, and read it by name. Use your own enums and case classes as
flag values.

## Why

The key-based API restates three things at every call site, and nothing checks that they agree:

```scala
--8<-- "TypedFlags.scala:drift"
```

A `FlagDef[A]` states the key, the type and the default once. Every call site refers to the definition, so there is no
second default to disagree with and no way to read the flag at another type.

## Define your flags

A plain object of `FlagDef`s is your flag catalog:

```scala
--8<-- "TypedFlags.scala:catalog"
```

The third argument is an optional description. It is documentation only and never sent to the provider.

!!! warning "Name the type when the default is a case object"
    `FlagDef("k", Tier.Free)` with a `case object Free` infers the type `Tier.Free.type`, which has no `FlagType`.
    Write `FlagDef[Tier]("k", Tier.Free)`. The parameterless cases of a Scala 3 `enum` are typed as the enum itself,
    so they are not affected, but naming the type does no harm.

## Evaluate by definition

`value`, `valueOrDefault`, `valueDetails` and `resolveOrDefault` take a `FlagDef` in place of the key and default:

```scala
--8<-- "TypedFlags.scala:evaluate"
```

| Method | Returns | Use when |
|---|---|---|
| `value` | the value, or a `FeatureFlagError` | a missing or broken flag should fail the call |
| `valueOrDefault` | the value, never failing | the flag must never take the request down |
| `valueDetails` | the [resolution](evaluating.md#the-resolution), or a `FeatureFlagError` | you need the reason, variant or metadata |
| `resolveOrDefault` | the resolution, never failing | both |

These run exactly the same evaluation as the key-based methods: hooks, transactions, timeouts and errors all behave the
same. See [Evaluating flags](evaluating.md#two-tiers) for the two tiers.

**The `FlagDef`'s default is the one served** when the flag is missing or broken. A `FlagType` has a `defaultValue`
too, but evaluation never uses it.

Two definitions are equal only if key, default and description all match. Compare keys alone with `sameKey`:

```scala
--8<-- "TypedFlags.scala:same-key"
```

## Your own types

A `FlagDef[A]` needs a `FlagType[A]`, the codec between `A` and what the provider stores. Built-in instances cover
`Boolean`, `String`, `Int`, `Long`, `Float`, `Double`, `Map[String, Any]` (an object flag), and `Option` and `List` of
any type that has one.

!!! note "`Option` reads its element's type; `List` reads an object"
    `Option[A]` is fetched with `A`'s resolver, so `Option[String]` against a string flag reads `Some(value)`. A `None`
    default cannot be sent as a scalar, so `A`'s zero (`false`, `""`, `0`, or a derived enum's first case) is sent in
    its place. The result reads `None` when the provider answers with an error code, or with reason `DEFAULT`,
    `DISABLED` or `ERROR` and exactly that zero. So a provider that deliberately serves the zero value with one of
    those reasons reads as `None`; any other value, or the zero with a real reason such as `TARGETING_MATCH`, reads as
    `Some`. `List[A]` has no scalar form and is always fetched with the object resolver.

### Derive it (Scala 3)

```scala
--8<-- "TypedFlags.scala:derived"
```

An **enum** whose cases take no parameters is stored as a **string** holding the case's name. Reading matches the name
ignoring case, so `"premium"` reads as `Tier.Premium`. An unknown name fails with `TypeMismatch` rather than quietly
serving the default. A case with parameters does not compile; write that type with `from` or `mapped`.

A **case class** is stored as an **object**, each field through its own `FlagType`, so nested case classes, `Option`
and `List` fields work too. Keys the class does not know are ignored. A missing key takes the field's Scala default if
it has one (`pct = 10`), otherwise `None` for an `Option`, and otherwise fails naming the field.

`FlagType.derived` is not a `given`, so it never competes with the built-in instances. Use a `derives` clause, or write
`given FlagType[X] = FlagType.derived[X]`.

### Map it from another type

`FlagType.mapped` builds a codec from an existing one and two functions. The flag is stored as the underlying type:

```scala
--8<-- "TypedFlags.scala:mapped"
```

### Write the decoder yourself

`FlagType.from` takes a decoder and an encoder. The flag is stored as an **object**:

```scala
--8<-- "TypedFlags.scala:from"
```

Values inside an object flag pass through the OpenFeature SDK on the way, so numbers arrive as `Double` and `null`
fields are dropped. Decode numbers through `FlagType[Int]` or `FlagType[Long]` rather than casting, and model optional
fields as `Option`. The encoder matters even if you never read its output: your default is encoded and handed to the
provider.

`mapped` and `from` work on Scala 2.13 as well. Only derivation is Scala 3 only.

### Wire type and domain type

A `FlagType` has a domain type, `typeName`, which your code sees, and a **wire type**, `wire`, which the provider is
asked for. Evaluation picks the provider method from the wire type, a `WireType`: `Boolean`, `String`, `Int`, `Long`,
`Float` or `Double` select that type's method, and `Object` reads an object.

| Built with | Wire type |
|---|---|
| built-in instances | the type itself |
| `derives` on an enum | `String` |
| `derives` on a case class | object |
| `mapped[A, B]` | `B`'s wire type |
| `Option[A]` | `A`'s wire type |
| `List[A]` | object |
| `from` | object |

!!! warning "Do not use `from` for a value stored as a string"
    `from` asks the provider for an object. If the flag actually holds a string, the provider answers
    `TYPE_MISMATCH`. Use a derived enum or `mapped` instead.

To store a scalar but reject values you do not recognise (which a total `mapped` function cannot do), write the instance
by hand. Override `wire` and `encode` together: `encode` must return the boxed type the wire type names (`String`,
`java.lang.Integer`, …), or evaluation fails with a `TypeMismatch` naming both.

```scala
--8<-- "TypedFlags.scala:fallible"
```

`wire` replaces the string-valued `wireType`, which is deprecated. An instance that still overrides `wireType` keeps
working: the name is matched ignoring case (`"string"` selects `String`), and any other name reads as an object. Only
the built-in `Map[String, Any]` instance is served as a raw map; any other object-backed instance has its `decode` run,
even one built with `from("Object", ...)`.

[Hooks](hooks.md) filter by the wire type, so a hook scoped to `FlagValueType.String` sees this flag. In a hook, the
flag's type is the wire type, while the default and the resolved value are domain values.

## Test with the same definitions

The testkit takes `FlagDef := value` pairs, checked against the flag's type and stored through its codec. The test
reads the value back the same way production does:

```scala
--8<-- "TypedFlags.scala:fixture"
```

Two values for the same key are rejected rather than silently merged. See [Testkit](testkit.md) for the other typed
fixtures.

Check a hand-written `FlagType` with `FlagTypeLaws` from the testkit. `roundTrip` checks that
`decode(encode(a)) == Right(a)`; `throughValueBridge` also passes the value through the SDK's conversion, which is where
object-backed codecs usually break; `all` runs both:

```scala
--8<-- "TypedFlagsSpec.scala:laws"
```

## When to keep using string keys

`FlagDef` adds to the key-based API; it does not replace it. Keys are still the right tool for a flag you read but do not
own, a key computed at runtime, or a test that a key is *not* defined. Both styles can read the same flag.
