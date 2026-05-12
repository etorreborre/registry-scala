# `registry-circe`

Customizable circe JSON encoders and decoders built on top of [`registry`](../core).

## Overview

Register a set of functions that take `Encoder`s / `Decoder`s as input and return `Encoder`s / `Decoder`s
for new types. The registry assembles them on demand; use the `encoder[T]` / `decoder[T]`
macros to auto-generate entries for case classes, sealed traits, and Scala 3 enums.

Benefits over typeclass-driven derivation:

- Override encoding options (`fieldLabelModifier`, `sumEncoding`, `omitNothingFields`, …) for an entire
  graph of types, or contextually for just one type.
- Provide a different encoder or decoder for the same type depending on caller context (e.g. a
  `DateTime` that is formatted differently in a birth-date vs. acquisition-date field).
- Evolve APIs incrementally — define a v1 and v2 registry, both sharing the same domain types.

## Encoders

```scala
import registry.*
import registry.circe.*

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
    Decoders.primitives +:
    defaultEncoderOptions

val e = encoders.makeEncoder[Person]
val json = e.encode(Person(Identifier(1), Email("me@here.com")))
// {"identifier":{"value":1},"email":{"email":"me@here.com"}}
```

## Options

`JsonOptions` mirrors aeson's `Options` flag for flag:

```scala
JsonOptions(
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

Override the default by prepending a custom `JsonOptions` to the registry:

```scala
val withModifier =
  value(JsonOptions.default.copy(fieldLabelModifier = "api_" + _)) -: encoders
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
d.decodeJson(json) // Right(Person(Identifier(1), Email("me@here.com")))
```

```text
Cannot decode the type 'Person' >> 'email :: Email' >> 'email :: String' >> …
```

## Limitations

- Mutual recursion across distinct types is not detected; self-recursion within a single type
  (directly or wrapped, e.g. `List[T]` / `Option[T]`) is handled automatically.
