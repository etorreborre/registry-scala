# `registry-circe`

Customizable circe JSON encoders and decoders built on top of [`registry`](../core).

## Overview

Register a set of functions that take `Encoder`s / `Decoder`s as input and return `Encoder`s / `Decoder`s
for new types. The registry assembles them on demand; use the `makeEncoder[T]` / `makeDecoder[T]`
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
  makeEncoder[Delivery] *:
    makeEncoder[Person] *:
    makeEncoder[Email] *:
    makeEncoder[Identifier] *:
    jsonEncoder[String] *:
    jsonEncoder[Int] *:
    defaultEncoderOptions

val e: Encoder[Person] = encoders.make[Encoder[Person]]
val json = e.encode(Person(Identifier(1), Email("me@here.com")))
// {"identifier":{"value":1},"email":{"email":"me@here.com"}}
```

`jsonEncoder[A]` and `jsonDecoder[A]` bridge existing `io.circe.Encoder[A]` / `io.circe.Decoder[A]`
instances into the registry's native types, so you get all of circe's built-in `Int` / `String` /
`List` / `Option` / `Map` / … instances for free. The generated encoders/decoders produce `io.circe.Json`
values directly — circe's parser and printer are used for string I/O (see `Encoder.encodeString` /
`Decoder.decodeString`).

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
  makeDecoder[Delivery] *:
    makeDecoder[Person] *:
    makeDecoder[Email] *:
    makeDecoder[Identifier] *:
    jsonDecoder[String] *:
    jsonDecoder[Int] *:
    defaultDecoderOptions

val d: Decoder[Person] = decoders.make[Decoder[Person]]
d.decodeJson(json) // Right(Person(Identifier(1), Email("me@here.com")))
```

`Encoder[A]` / `Decoder[A]` are aliases for `io.circe.Encoder[A]` / `io.circe.Decoder[A]` — the
registry holds circe instances directly, with no wrapper in between. That means any existing
circe instance (semiauto-derived, magnolia, third-party) drops straight in as
`value(summon[Encoder[Foo]])` or via the `jsonEncoder[Foo]` / `jsonDecoder[Foo]` helpers. Failure
messages embed field-path context on top of circe's `CursorOp` history:

```text
Cannot decode the type 'Person' >> 'email :: Email' >> 'email :: String' >> …
```

## Relation to circe's own typeclasses

`registry.circe.Encoder[A]` and `registry.circe.Decoder[A]` are type aliases for `io.circe.Encoder[A]`
and `io.circe.Decoder[A]`. The registry just holds circe instances and arranges how they're assembled.
This keeps registry-driven customization (options, per-context overrides) compatible with circe's
typeclass resolution — any circe-derived instance can be registered without conversion.

## Limitations

- Recursive data types are not supported by the auto-generated encoders/decoders. Register
  hand-written `Encoder[T]` / `Decoder[T]` entries via `fun(...)` for those cases.
- Scala 3 enum cases always carry named fields — if your on-wire format needs positional "contents"
  wrapping (aeson's behavior for Haskell positional constructors), either register a custom
  `ConstructorEncoder` / `ConstructorsDecoder` or use the `SumEncoding.TwoElemArray` mode.
