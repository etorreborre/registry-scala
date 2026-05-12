---
title: Cheat sheet
parent: Circe
grand_parent: Modules
nav_order: 1
---

# `registry-circe` cheat sheet

Quick reference for the API surface. For a guided walkthrough — how the
pieces compose, sum encodings, recursion, the companion-given fast
path — see [Circe](circe.md).

## Macros — derivation from a type

| Factory                          | Use                                                                |
| -------------------------------- | ------------------------------------------------------------------ |
| `encoder[T]`                     | derive an `Encoder[T]` from `T`'s primary constructor (or sum variants) |
| `decoder[T]`                     | derive a `Decoder[T]` from `T`'s primary constructor (or sum variants) |

The macros emit the fully-qualified constructor name and let
[`JsonOptions.constructorTagModifier`](#json-options) transform it for
the JSON tag. The default (`JsonOptions.dropQualifier`) keeps only the
last segment — matching aeson. Use `identity` to keep the FQN or
`JsonOptions.lastTwoSegments` for `Type.Foo`-style names.

## Macros — derivation from a value

| Factory                          | Effect                                                             |
| -------------------------------- | ------------------------------------------------------------------ |
| `encoder(f: S => T)`             | `Encoder[T] → Encoder[S]` (contramap mode)                         |
| `encoder(f: (A1, …, An) => Encoder[S])` | fun-style entry; inputs are `A1, …, An`                     |
| `encoder(e: Encoder[S])`         | register the value directly as a zero-input entry                  |
| `decoder(f: T => S)`             | `Decoder[T] → Decoder[S]` (map mode)                               |
| `decoder(f: (A1, …, An) => Decoder[S])` | fun-style entry; inputs are `A1, …, An`                     |
| `decoder(d: Decoder[S])`         | register the value directly as a zero-input entry                  |

## Bridging existing circe instances

| Factory                          | Use                                                                |
| -------------------------------- | ------------------------------------------------------------------ |
| `encoderOf[A]`                   | summon `given Encoder[A]` and register it                          |
| `decoderOf[A]`                   | summon `given Decoder[A]` and register it                          |
| `keyEncoderOf[A]`                | summon `given KeyEncoder[A]` and register it                       |
| `keyDecoderOf[A]`                | summon `given KeyDecoder[A]` and register it                       |
| `value(e: Encoder[A])`           | register an `Encoder[A]` value (no `given` required)               |
| `value(d: Decoder[A])`           | register a `Decoder[A]` value                                      |

## Mapping

| Combinator                              | Effect                                                |
| --------------------------------------- | ----------------------------------------------------- |
| `contramap[S, T](f: S => T)`            | `Encoder[T] → Encoder[S]`                             |
| `map[S, T](f: T => S)`                  | `Decoder[T] → Decoder[S]`                             |
| `emap[S, T](f: T => Either[String, S])` | `Decoder[T] → Decoder[S]` with JSON error on `Left`   |

`contramap` is equivalent to the single-arg form of `encoder(f)`;
`map` is equivalent to the single-arg form of `decoder(f)`. Use whichever
reads better at the call site.

## Containers

Each factory registers a 1-input entry (2 inputs for `Pair`/`Map`/
`TreeMap`, 3 for `Triple`) that wraps the corresponding circe
combinator.

| Factory                          | Produces                                                  |
| -------------------------------- | --------------------------------------------------------- |
| `encodeListOf[A]`                | `Encoder[List[A]]`                                        |
| `encodeSeqOf[A]`                 | `Encoder[Seq[A]]`                                         |
| `encodeVectorOf[A]`              | `Encoder[Vector[A]]`                                      |
| `encodeIArrayOf[A]`              | `Encoder[IArray[A]]`                                      |
| `encodeSetOf[A]`                 | `Encoder[Set[A]]`                                         |
| `encodeOptionOf[A]`              | `Encoder[Option[A]]` (`None` → `Json.Null`)               |
| `encodePairOf[A, B]`             | `Encoder[(A, B)]`                                         |
| `encodeTripleOf[A, B, C]`        | `Encoder[(A, B, C)]`                                      |
| `encodeMapOf[K, V]`              | `Encoder[Map[K, V]]` — needs `KeyEncoder[K]`              |
| `encodeTreeMapOf[K, V]`          | `Encoder[TreeMap[K, V]]` — needs `KeyEncoder[K]`          |
| `decodeListOf[A]`                | `Decoder[List[A]]`                                        |
| `decodeSeqOf[A]`                 | `Decoder[Seq[A]]`                                         |
| `decodeVectorOf[A]`              | `Decoder[Vector[A]]`                                      |
| `decodeIArrayOf[A]`              | `Decoder[IArray[A]]`                                      |
| `decodeSetOf[A]`                 | `Decoder[Set[A]]`                                         |
| `decodeOptionOf[A]`              | `Decoder[Option[A]]`                                      |
| `decodePairOf[A, B]`             | `Decoder[(A, B)]`                                         |
| `decodeTripleOf[A, B, C]`        | `Decoder[(A, B, C)]`                                      |
| `decodeMapOf[K, V]`              | `Decoder[Map[K, V]]` — needs `KeyDecoder[K]`              |
| `decodeTreeMapOf[K, V]`          | `Decoder[TreeMap[K, V]]` — needs `KeyDecoder[K]` and `Ordering[K]` |

## Keys

| Factory / value                          | Effect                                              |
| ---------------------------------------- | --------------------------------------------------- |
| `encodeKey[A](f: A => String)`           | build a `KeyEncoder[A]` entry from a function       |
| `decodeKey[A](f: String => Option[A])`   | build a `KeyDecoder[A]` entry from a parser         |
| `KeyEncoders.stringKeyEncoder`           | identity `KeyEncoder[String]`                       |
| `KeyDecoders.stringKeyDecoder`           | identity `KeyDecoder[String]`                       |

## Primitives

| Entry                          | Type                  |
| ------------------------------ | --------------------- |
| `stringEncoder` / `stringDecoder` | `Encoder[String]` / `Decoder[String]` |
| `intEncoder` / `intDecoder`       | `Encoder[Int]` / `Decoder[Int]`       |
| `longEncoder` / `longDecoder`     | `Encoder[Long]` / `Decoder[Long]`     |
| `booleanEncoder` / `booleanDecoder` | `Encoder[Boolean]` / `Decoder[Boolean]` |
| `doubleEncoder` / `doubleDecoder` | `Encoder[Double]` / `Decoder[Double]` |
| `byteEncoder` / `byteDecoder`     | `Encoder[Byte]` / `Decoder[Byte]`     |
| `bigIntEncoder` / `bigIntDecoder` | `Encoder[BigInt]` / `Decoder[BigInt]` |
| `unitEncoder` / `unitDecoder`     | `Encoder[Unit]` / `Decoder[Unit]`     |
| `Encoders.primitives`             | all encoder primitives bundled as one registry |
| `Decoders.primitives`             | all decoder primitives bundled (adds `Decoder[Json]`) |

## Defaults

| Bundle                       | Contents                                                                 |
| ---------------------------- | ------------------------------------------------------------------------ |
| `defaultEncoderOptions`      | `ConstructorEncoder.default` + `KeyEncoders.stringKeyEncoder` + `JsonOptions.default` |
| `defaultDecoderOptions`      | `ConstructorsDecoder.default` + `KeyDecoders.stringKeyDecoder` + `JsonOptions.default` |

Required by every `encoder[T]` / `decoder[T]` registry. Override one of
the three entries (typically `JsonOptions`) by prepending it.

## JSON options

`JsonOptions` is a direct port of aeson's `Data.Aeson.Options`:

| Flag                      | Type                 | Default                                          |
| ------------------------- | -------------------- | ------------------------------------------------ |
| `fieldLabelModifier`      | `String => String`   | `identity`                                       |
| `constructorTagModifier`  | `String => String`   | `JsonOptions.dropQualifier` (last segment of FQN) |
| `allNullaryToStringTag`   | `Boolean`            | `true`                                           |
| `omitNothingFields`       | `Boolean`            | `false`                                          |
| `sumEncoding`             | `SumEncoding`        | `TaggedObject("tag", "contents")`                |
| `unwrapUnaryRecords`      | `Boolean`            | `false`                                          |
| `tagSingleConstructors`   | `Boolean`            | `false`                                          |
| `rejectUnknownFields`     | `Boolean`            | `false`                                          |

### `SumEncoding`

| Constructor                                  | Shape                                          |
| -------------------------------------------- | ---------------------------------------------- |
| `TaggedObject(tagField, contentsField)`      | `{"tag": "Foo", "field1": ..., ...}`           |
| `UntaggedValue`                              | no tag — decoders try each branch              |
| `ObjectWithSingleField`                      | `{"Foo": {...}}`                               |
| `TwoElemArray`                               | `["Foo", {...}]`                               |

## Resolution

| Method                       | Effect                                          |
| ---------------------------- | ----------------------------------------------- |
| `r.makeEncoder[T]`           | resolve `Encoder[T]` (alias for `r.make[Encoder[T]]`) |
| `r.makeDecoder[T]`           | resolve `Decoder[T]` (alias for `r.make[Decoder[T]]`) |
| `Encoders.encodeString(e, a)` | render `a: A` as a compact JSON string         |
| `Encoders.encodeValue(e, a)` | run the encoder and return `io.circe.Json`     |
| `Decoders.decodeString(d, s)` | parse `s` and decode with `d`; embeds the type name in failures |

## Cursor extensions

| Method                                | Effect                                                |
| ------------------------------------- | ----------------------------------------------------- |
| `cursor.decodeAs[A](d: Decoder[A])`   | apply `d` to the focus and return `Option[A]`; drops the `DecodingFailure` history |
