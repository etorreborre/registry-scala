---
title: Circe
parent: Modules
nav_order: 4
has_children: true
---

# `registry-circe`

Derives [circe](https://circe.github.io/circe/) `Encoder[T]` / `Decoder[T]`
instances from a registry. Instead of relying on semiauto/auto derivation
for every type, register one `encoder[T]` / `decoder[T]` per type and let
the registry wire the codecs together. The macro inspects `T`'s primary
constructor (or sealed-trait variants) and emits an entry that depends on
codecs for every field type.

The walkthrough below builds a codec registry from a simple case class up
to recursive types and JSON option overrides. For a quick overview of the API, 
see the [Cheat sheet](circe-cheat-sheet.md).

## Setup

```scala
import registry.*
import registry.circe.*
import io.circe.Json // only needed for the snippets below
```

`registry.circe` re-exports `io.circe.{Encoder, Decoder, KeyEncoder, KeyDecoder}`,
so the wildcard import covers both the typeclasses and the registry-side
factories (`encoder[T]`, `decoder[T]`, the combinators, `Encoders` / `Decoders`).

## Encoding a case class

```scala
case class Identifier(value: Int)
case class Email(email: String)
case class Person(identifier: Identifier, email: Email)

val encoders =
  encoder[Person] +:
    encoder[Identifier] +:
    encoder[Email] +:
    Encoders.primitives +:
    defaultEncoderOptions
```

```scala
val e = encoders.makeEncoder[Person]
e(Person(Identifier(1), Email("me@here.com")))
```

`encoder[Person]` walks `Person`'s primary constructor and registers one
entry that consumes `JsonOptions`, `ConstructorEncoder`, `Encoder[Identifier]`,
and `Encoder[Email]`. The registry resolves each via LIFO; the entries on
the right supply the leaves.

`Encoders.primitives` is a pre-bundled registry of `Encoder` instances
for `Unit / String / Int / Long / Boolean / Double / Byte / BigInt`.

`defaultEncoderOptions` carries the three entries the macro needs at
resolution time: a `ConstructorEncoder`, a `KeyEncoder[String]`, and a
`JsonOptions` (see [Options](#json-options) below).

## Decoding a case class

The decoder side is symmetric:

```scala
val decoders =
  decoder[Person] +:
    decoder[Identifier] +:
    decoder[Email] +:
    Decoders.primitives +:
    defaultDecoderOptions
```

```scala
val d = decoders.makeDecoder[Person]
d.decodeJson(e(Person(Identifier(1), Email("me@here.com"))))
```

## Value-driven `encoder(...)` and `decoder(...)`

The macro also accepts a *function*, not just a type. This is useful when a field is
an opaque wrapper around a primitive, for example:

```scala
case class UserId(value: Long)

val userIdCodecs =
  encoder((_: UserId).value) +:        // uses contramap: Encoder[Long] -> Encoder[UserId]
    decoder((l: Long) => UserId(l)) +: // uses map:       Decoder[Long] -> Decoder[UserId]
    Encoders.primitives +:
    Decoders.primitives
```

```scala
userIdCodecs.makeEncoder[UserId](UserId(42L))
userIdCodecs.makeDecoder[UserId].decodeJson(Json.fromLong(42L))
```

Three shapes are accepted by `encoder(x)` / `decoder(x)`:

- `S => T` (single-arg, `T` not an `Encoder`/`Decoder`) → registered as
  `contramap` / `map`.
- `(A1, ..., An) => Encoder[S]` / `Decoder[S]` (any arity) → fun-style
  entry whose inputs are pulled from the registry.
- An existing `Encoder[S]` / `Decoder[S]` value → registered as-is.

For pure type-based derivation, use `encoder[T]` / `decoder[T]`.

## Sum types (sealed traits, enums)

`encoder[T]` and `decoder[T]` handle Scala 3 enums and sealed hierarchies
the same way they handle case classes: one pattern-match branch per variant.

```scala
enum Delivery:
  case NoDelivery
  case ByEmail(email: Email)
  case InPerson(person: Person, at: String)

val deliveryCodecs =
  encoder[Delivery] +:
    decoder[Delivery] +:
    encoder[Person] +:
    encoder[Identifier] +:
    encoder[Email] +:
    decoder[Person] +:
    decoder[Identifier] +:
    decoder[Email] +:
    Encoders.primitives +:
    Decoders.primitives +:
    (defaultEncoderOptions <+> defaultDecoderOptions)
```

```scala
val ed = deliveryCodecs.makeEncoder[Delivery]
ed(Delivery.NoDelivery)
ed(Delivery.ByEmail(Email("x@y.z")))
```

The default `SumEncoding.TaggedObject("tag", "contents")` adds a `"tag"`
field carrying the constructor name. Nullary cases without other fields
emit as plain tags. Switch to a different scheme by overriding the
`JsonOptions.sumEncoding` flag (see below).

## Container helpers

Each helper below registers a 1-input entry (two for `Map`/`TreeMap`/
`Pair`) that wraps the corresponding circe combinator.

```scala
val intCollections =
  encodeListOf[Int] +:
    encodeOptionOf[Int] +:
    decodeListOf[Int] +:
    decodeOptionOf[Int] +:
    Encoders.primitives +:
    Decoders.primitives
```

```scala
intCollections.makeEncoder[List[Int]](List(1, 2, 3))
intCollections.makeDecoder[Option[Int]].decodeJson(Json.fromInt(7))
```

The full family is summarized out in the [cheat sheet](circe-cheat-sheet.md).

## Bridging existing circe instances

When a third-party library already gives you `given Encoder[X]` /
`given Decoder[X]`, `encoderOf[X]` / `decoderOf[X]` summon the instance
and register it as a zero-input entry.

```scala
given Encoder[String] = io.circe.Encoder.encodeString
given Decoder[String] = io.circe.Decoder.decodeString

val withSummoned =
  encoder[Email] +:
    encoderOf[String] +:  // summons the given above
    decoder[Email] +:
    decoderOf[String] +:
    (defaultEncoderOptions <+> defaultDecoderOptions)
```

For codecs that aren't `given`, `value(myEncoder)` registers any
`Encoder[A]` directly.

## Mapping: `contramap`, `map`, `emap`

There a 3 combinators for deriving one codec from another:

| Combinator                   | Effect                                                   |
| ---------------------------- | -------------------------------------------------------- |
| `contramap[S, T](f: S => T)` | `Encoder[T] → Encoder[S]`                                |
| `map[S, T](f: T => S)`       | `Decoder[T] → Decoder[S]`                                |
| `emap[S, T](f: T => Either[String, S])` | `Decoder[T] → Decoder[S]`, failure as JSON error |

```scala
case class Name(value: String)

val slugs =
  contramap((_: Name).value) +: // Encoder[String] -> Encoder[Name]
    emap[Name, String](s =>
      if s.nonEmpty then Right(Name(s)) else Left("empty name")
    ) +:
    Encoders.primitives +:
    Decoders.primitives
```

```scala
slugs.makeEncoder[Name](Name("eric"))
slugs.makeDecoder[Name].decodeJson(Json.fromString(""))
```

These compose with the rest of the API: `contramap` is the same as
the single-arg form of `encoder((_: Name).value)` but spelled out for clarity at the call site.

## JSON options

`JsonOptions` gives you the possibility to tweak the encoding / decoding of JSON values:

```scala
final case class JsonOptions(
    fieldLabelModifier: String => String = identity,
    constructorTagModifier: String => String = identity,
    allNullaryToStringTag: Boolean = true,
    omitNothingFields: Boolean = false,
    sumEncoding: SumEncoding = SumEncoding.TaggedObject("tag", "contents"),
    unwrapUnaryRecords: Boolean = false,
    tagSingleConstructors: Boolean = false,
    rejectUnknownFields: Boolean = false
)
```

You can modify the options by prepending a custom `JsonOptions` to the registry:

```scala
val snakeCase =
  value(JsonOptions.default.copy(fieldLabelModifier = "snake_" + _)) -:
    encoders
```

```scala
snakeCase.makeEncoder[Person](Person(Identifier(1), Email("a@b.c")))
```

`SumEncoding` controls how sum types are tagged: 

 - `TaggedObject`: default, inlines fields under a `tag` key. 
 - `UntaggedValue`: no tag, decoders try each branch. 
 - `ObjectWithSingleField`: `{"Name": {...}}`.
 - `TwoElemArray`: `[tag, contents]`.

## Recursive types

Self-recursion is detected automatically (any field type whose
`TypeRepr` mentions `T`, directly or wrapped such as `List[T]` /
`Option[T]`). The macro emits an extra forwarder entry that picks up the
in-flight `Encoder[T]` / `Decoder[T]` resolution; no special API is
needed at the call site.

```scala
enum Cons:
  case End
  case Item(head: Int, tail: Cons)

val consCodecs =
  encoder[Cons] +:
    decoder[Cons] +:
    Encoders.primitives +:
    Decoders.primitives +:
    (defaultEncoderOptions <+> defaultDecoderOptions)
```

```scala
val sample: Cons = Cons.Item(1, Cons.Item(2, Cons.End))
val enc = consCodecs.makeEncoder[Cons]
consCodecs.makeDecoder[Cons].decodeJson(enc(sample))
```

Mutual recursion across distinct types is not handled. Register
hand-written entries via `value(...)` or `fun(...)` for those cases.

## Companion-given fast path

If `T`'s companion object declares `given Encoder[T]` (or
`given Decoder[T]`), `encoder[T]` / `decoder[T]` you can register the companion's
instance directly instead of generating a structural codec.

```scala
case class CompanionKey(value: String)
object CompanionKey:
  given Encoder[CompanionKey] =
    Encoder.encodeString.contramap(k => s"k:${k.value}")
  given Decoder[CompanionKey] =
    Decoder.decodeString.map(s => CompanionKey(s.stripPrefix("k:")))

val ck = encoder[CompanionKey] +: decoder[CompanionKey]
```

```scala
ck.makeEncoder[CompanionKey](CompanionKey("hello"))
```

The lookup is companion-only (not full implicit scope), so the choice
never silently depends on imports. Polymorphic givens are skipped — the
fast path is for monomorphic instances only.

## `makeEncoder[T]` vs `make[Encoder[T]]`

`makeEncoder[T]` and `makeDecoder[T]` are extension methods on
`Registry` that simply delegate to `make[Encoder[T]]` /
`make[Decoder[T]]`. They exist for symmetry with `registry-scalacheck`'s
`makeGen[T]` and to keep call sites short:

```scala
val e: Encoder[Person] = encoders.makeEncoder[Person]   // preferred
val e: Encoder[Person] = encoders.make[Encoder[Person]] // identical
```

There's no behavioral difference — pick whichever reads better at the
call site.

## What to read next

- [Cheat sheet](circe-cheat-sheet.md): every factory and combinator in one place, for quick lookup.
- [Registry and entries](../concepts/registry-and-entries.md): prepend operators, LIFO precedence, what `<+>` does.
- [Resolution](../concepts/resolution.md): runtime algorithm; the in-flight skip used by recursive codec derivation.
- [Customization](../concepts/customization.md): `refine` for context-scoped codec overrides (e.g. one `DateTime` encoding for
  birth dates, another for acquisition dates).
