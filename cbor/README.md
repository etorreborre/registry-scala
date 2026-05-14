# `registry-cbor`

Customizable CBOR encoders and decoders built on top of [`registry`](../core) and
[borer](https://github.com/sirthias/borer).

## Overview

Register a set of functions that take `Encoder`s / `Decoder`s as input and return `Encoder`s /
`Decoder`s for new types. The registry assembles them on demand; use the `encoder[T]` / `decoder[T]`
macros to auto-generate entries for case classes, sealed traits, and Scala 3 enums.

Benefits over typeclass-driven derivation:

- Override encoding options (`fieldKeyMode`, `sumEncoding`, `omitNothingFields`, …) for an entire
  graph of types, or contextually for just one type.
- Provide a different encoder or decoder for the same type depending on caller context (e.g. a
  `DateTime` formatted differently in a birth-date vs. acquisition-date field).
- Evolve APIs incrementally — define a v1 and v2 registry, both sharing the same domain types.

Defaults follow CBOR-native conventions: record fields use **integer keys** (positional, 0..N-1)
and sum-type tags are **integer constructor indices**. Switch to text-string keys/tags via
`CborOptions` for JSON-like interop.

## Encoders

```scala
import registry.*
import registry.cbor.*

final case class Identifier(value: Int)
final case class Email(email: String)
final case class Person(identifier: Identifier, email: Email)

enum Delivery:
  case NoDelivery
  case ByEmail(email: Email)
  case InPerson(person: Person, at: String)

val encoders =
  encoder[Delivery] +:
    encoder[Person] +:
    encoder[Email] +:
    encoder[Identifier] +:
    encoderOf[String] +:
    Encoders.primitives +:
    defaultEncoderOptions

val e = encoders.makeEncoder[Person]
val bytes: Array[Byte] = io.bullet.borer.Cbor.encode(Person(Identifier(1), Email("me@here.com"))).toByteArray
```

The default encoding for a `Person` is a CBOR map with integer keys:

```text
{0: {0: 1}, 1: {0: "me@here.com"}}
```

## Options

`CborOptions` is the analog of aeson's / circe's options:

```scala
CborOptions(
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

`SumEncoding` has four cases:

| Case | Shape |
| --- | --- |
| `TwoElemArray` (default) | `[tag, contents]` (2-element array) |
| `SingleKeyMap` | `{tag: contents}` (single-entry map) |
| `Untagged` | `contents` alone (decoder tries each constructor) |
| `CborTagged(baseTagNumber)` | `Tag(baseTagNumber + i, contents)` (CBOR major-type-6 tag) |

Override the default by prepending a custom `CborOptions` to the registry:

```scala
val withStringKeys =
  value(CborOptions.default.copy(fieldKeyMode = FieldKeyMode.StringKeys)) -: encoders
```

## Decoders

Symmetric to encoders:

```scala
val decoders =
  decoder[Delivery] +:
    decoder[Person] +:
    decoder[Email] +:
    decoder[Identifier] +:
    Decoders.primitives +:
    defaultDecoderOptions

val d = decoders.makeDecoder[Person]
val p: Person = io.bullet.borer.Cbor.decode(bytes).to[Person](using d).value
```

## Limitations

- Mutual recursion across distinct types is not detected; self-recursion within a single type
  (directly or wrapped, e.g. `List[T]` / `Option[T]`) is handled automatically.
- The macro materializes a `Dom.Element` per top-level encode/decode (the same overhead as
  `registry-circe`'s `Json` path).
