---
title: CBOR
parent: Modules
nav_order: 3
has_children: true
---

# `registry-cbor`

Derives [borer](https://github.com/sirthias/borer) `Encoder[T]` / `Decoder[T]`
instances from a registry, for CBOR (Concise Binary Object Representation).
Instead of relying on borer's `@key`/`derives` derivation for every type,
register one `encoder[T]` / `decoder[T]` per type and let the registry wire
the codecs together. The macro inspects `T`'s primary constructor (or
sealed-trait variants) and emits an entry that depends on codecs for every
field type.

The walkthrough below builds a codec registry from a simple case class up
to recursive types and CBOR option overrides. For a quick overview of the API,
see the [Cheat sheet](cbor-cheat-sheet.md).

Defaults follow CBOR-native conventions: record fields use **integer keys**
(positional, 0..N-1) and sum-type tags are **integer constructor indices**.
Switch to text-string keys/tags via `CborOptions` for JSON-like interop.

## Setup

```scala
import registry.*
import registry.cbor.*
import io.bullet.borer.{Cbor, Dom} // for encoding to bytes / inspecting Dom.Element
```

`registry.cbor` re-exports `io.bullet.borer.{Encoder, Decoder, Codec, Dom}`,
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
val bytes: Array[Byte] = Cbor.encode(Person(Identifier(1), Email("me@here.com")))(using e).toByteArray
```

`encoder[Person]` walks `Person`'s primary constructor and registers one
entry that consumes `CborOptions`, `ConstructorEncoder`, `Encoder[Identifier]`,
and `Encoder[Email]`. The registry resolves each via LIFO; the entries on
the right supply the leaves.

`Encoders.primitives` is a pre-bundled registry of `Encoder` instances
for `Unit / String / Int / Long / Boolean / Double / Byte / BigInt`.

`defaultEncoderOptions` carries the two entries the macro needs at
resolution time: a `ConstructorEncoder` and a `CborOptions` (see
[CBOR options](#cbor-options) below).

By default the encoded `Person` is a CBOR map with integer keys:
`{0: {0: 1}, 1: {0: "me@here.com"}}`. Switch on `StringKeys` mode for
text-keyed maps (see options).

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
Cbor.decode(bytes).to[Person](using d).value
```

## Value-driven `encoder(...)` and `decoder(...)`

The macro also accepts a *function*, not just a type:

```scala
case class UserId(value: Long)

val userIdCodecs =
  encoder((_: UserId).value) +:        // uses contramap: Encoder[Long] -> Encoder[UserId]
    decoder((l: Long) => UserId(l)) +: // uses map:       Decoder[Long] -> Decoder[UserId]
    Encoders.primitives +:
    Decoders.primitives
```

Three shapes are accepted by `encoder(x)` / `decoder(x)`:

- `S => T` (single-arg, `T` not an `Encoder`/`Decoder`) → registered as
  `contramap` / `map`.
- `(A1, ..., An) => Encoder[S]` / `Decoder[S]` (any arity) → fun-style
  entry whose inputs are pulled from the registry.
- An existing `Encoder[S]` / `Decoder[S]` value → registered as-is.

For pure type-based derivation, use `encoder[T]` / `decoder[T]`.

## Sum types (sealed traits, enums)

`encoder[T]` and `decoder[T]` handle Scala 3 enums and sealed hierarchies the
same way they handle case classes: one pattern-match branch per variant.

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

The default `SumEncoding.TwoElemArray` shape is `[constructorIndex, contents]`.
Pure-enumeration sums (no fields anywhere) collapse to a bare integer when
`allNullaryToTag = true`. Switch to a different scheme by overriding
`CborOptions.sumEncoding` (see below).

## Container helpers

Each helper below registers a 1-input entry (two for `Map`/`TreeMap`/
`Pair`) that wraps a borer container codec.

```scala
val intCollections =
  encodeListOf[Int] +:
    encodeOptionOf[Int] +:
    decodeListOf[Int] +:
    decodeOptionOf[Int] +:
    Encoders.primitives +:
    Decoders.primitives
```

The full family is summarized in the [cheat sheet](cbor-cheat-sheet.md).

## Bridging existing borer instances

When a third-party library already gives you `given Encoder[X]` /
`given Decoder[X]`, `encoderOf[X]` / `decoderOf[X]` summon the instance
and register it as a zero-input entry.

For codecs that aren't `given`, `value(myEncoder)` registers any
`Encoder[A]` directly.

## Mapping: `contramap`, `map`, `emap`

| Combinator                              | Effect                                                   |
| --------------------------------------- | -------------------------------------------------------- |
| `contramap[S, T](f: S => T)`            | `Encoder[T] → Encoder[S]`                                |
| `map[S, T](f: T => S)`                  | `Decoder[T] → Decoder[S]`                                |
| `emap[S, T](f: T => Either[String, S])` | `Decoder[T] → Decoder[S]`, failure as a borer error      |

## CBOR options

`CborOptions` tweaks the encoding / decoding of CBOR values:

```scala
final case class CborOptions(
    fieldKeyMode: FieldKeyMode = FieldKeyMode.IntegerKeys,
    constructorTagMode: ConstructorTagMode = ConstructorTagMode.IntegerTags,
    fieldLabelModifier: String => String = identity,
    constructorTagModifier: String => String = CborOptions.dropQualifier,
    allNullaryToTag: Boolean = true,
    omitNothingFields: Boolean = false,
    sumEncoding: SumEncoding = SumEncoding.TwoElemArray,
    unwrapUnaryRecords: Boolean = false,
    tagSingleConstructors: Boolean = false,
    rejectUnknownFields: Boolean = false
)
```

Switch to string keys (matches JSON conventions) by prepending a custom
`CborOptions`:

```scala
val withStringKeys =
  value(CborOptions.default.copy(fieldKeyMode = FieldKeyMode.StringKeys)) -:
    encoders
```

`SumEncoding` controls how sum types are tagged:

- `TwoElemArray` (default): `[tag, contents]`.
- `SingleKeyMap`: `{tag: contents}`.
- `Untagged`: no tag, decoders try each branch.
- `CborTagged(baseTagNumber)`: CBOR semantic tag `Tag(baseTagNumber + i, contents)`.

## Recursive types

Self-recursion is detected automatically (any field type whose `TypeRepr`
mentions `T`, directly or wrapped such as `List[T]` / `Option[T]`). The
macro emits an extra forwarder entry that picks up the in-flight
`Encoder[T]` / `Decoder[T]` resolution; no special API is needed at the
call site.

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

Mutual recursion across distinct types is not handled — register
hand-written entries via `value(...)` or `fun(...)` for those cases.

## Companion-given fast path

If `T`'s companion object declares `given Encoder[T]` (or `given Decoder[T]`),
`encoder[T]` / `decoder[T]` register the companion's instance directly
instead of generating a structural codec.

```scala
case class CompanionKey(value: String)
object CompanionKey:
  given Encoder[CompanionKey] =
    Encoder.forString.contramap(k => s"k:${k.value}")
  given Decoder[CompanionKey] =
    Decoder.forString.map(s => CompanionKey(s.stripPrefix("k:")))

val ck = encoder[CompanionKey] +: decoder[CompanionKey]
```

The lookup is companion-only (not full implicit scope), so the choice
never silently depends on imports. Polymorphic givens are skipped — the
fast path is for monomorphic instances only.

## `makeEncoder[T]` vs `make[Encoder[T]]`

`makeEncoder[T]` and `makeDecoder[T]` are extension methods on `Registry` that
delegate to `make[Encoder[T]]` / `make[Decoder[T]]`. They exist for symmetry
with the other modules:

```scala
val e: Encoder[Person] = encoders.makeEncoder[Person]   // preferred
val e: Encoder[Person] = encoders.make[Encoder[Person]] // identical
```

## What to read next

- [Cheat sheet](cbor-cheat-sheet.md): every factory and combinator in one place.
- [Registry and entries](../concepts/registry-and-entries.md): prepend operators, LIFO precedence, what `<+>` does.
- [Resolution](../concepts/resolution.md): runtime algorithm; the in-flight skip used by recursive codec derivation.
- [Customization](../concepts/customization.md): `refine` for context-scoped codec overrides.
