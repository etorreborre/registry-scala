---
title: Cheat sheet
parent: CBOR
grand_parent: Modules
nav_order: 1
---

# `registry-cbor` cheat sheet

Quick reference for the API surface. For a guided walkthrough — how the
pieces compose, sum encodings, recursion, the companion-given fast
path — see [CBOR](cbor.md).

## Macros — derivation from a type

| Factory      | Use                                                                     |
|--------------|-------------------------------------------------------------------------|
| `encoder[T]` | derive an `Encoder[T]` from `T`'s primary constructor (or sum variants) |
| `decoder[T]` | derive a `Decoder[T]` from `T`'s primary constructor (or sum variants)  |

The macros emit the fully-qualified constructor name. By default,
`CborOptions.constructorTagMode = IntegerTags` so the name is **not** used
in the wire format — constructor *indices* are. Set `constructorTagMode =
StringTags` to use names, in which case `constructorTagModifier`
transforms them (default `CborOptions.dropQualifier` keeps only the last
segment).

## Macros — derivation from a value

| Factory                          | Effect                                                             |
| -------------------------------- | ------------------------------------------------------------------ |
| `encoder(f: S => T)`             | `Encoder[T] → Encoder[S]` (contramap mode)                         |
| `encoder(f: (A1, …, An) => Encoder[S])` | fun-style entry; inputs are `A1, …, An`                     |
| `encoder(e: Encoder[S])`         | register the value directly as a zero-input entry                  |
| `decoder(f: T => S)`             | `Decoder[T] → Decoder[S]` (map mode)                               |
| `decoder(f: (A1, …, An) => Decoder[S])` | fun-style entry; inputs are `A1, …, An`                     |
| `decoder(d: Decoder[S])`         | register the value directly as a zero-input entry                  |

## Bridging existing borer instances

| Factory                          | Use                                                                |
| -------------------------------- | ------------------------------------------------------------------ |
| `encoderOf[A]`                   | summon `given Encoder[A]` and register it                          |
| `decoderOf[A]`                   | summon `given Decoder[A]` and register it                          |
| `value(e: Encoder[A])`           | register an `Encoder[A]` value (no `given` required)               |
| `value(d: Decoder[A])`           | register a `Decoder[A]` value                                      |

## Mapping

| Combinator                              | Effect                                                |
| --------------------------------------- | ----------------------------------------------------- |
| `contramap[S, T](f: S => T)`            | `Encoder[T] → Encoder[S]`                             |
| `map[S, T](f: T => S)`                  | `Decoder[T] → Decoder[S]`                             |
| `emap[S, T](f: T => Either[String, S])` | `Decoder[T] → Decoder[S]` with a borer validation failure on `Left` |

## Containers

| Factory                          | Produces                                                  |
| -------------------------------- | --------------------------------------------------------- |
| `encodeListOf[A]`                | `Encoder[List[A]]` (sized CBOR array)                     |
| `encodeSeqOf[A]`                 | `Encoder[Seq[A]]`                                         |
| `encodeVectorOf[A]`              | `Encoder[Vector[A]]`                                      |
| `encodeIArrayOf[A]`              | `Encoder[IArray[A]]`                                      |
| `encodeSetOf[A]`                 | `Encoder[Set[A]]`                                         |
| `encodeOptionOf[A]`              | `Encoder[Option[A]]` (`None` → CBOR null)                 |
| `encodePairOf[A, B]`             | `Encoder[(A, B)]` (CBOR 2-element array)                  |
| `encodeTripleOf[A, B, C]`        | `Encoder[(A, B, C)]`                                      |
| `encodeMapOf[K, V]`              | `Encoder[Map[K, V]]` — needs `Encoder[K]`                 |
| `encodeTreeMapOf[K, V]`          | `Encoder[TreeMap[K, V]]` — needs `Encoder[K]`             |
| `decodeListOf[A]`                | `Decoder[List[A]]`                                        |
| `decodeSeqOf[A]`                 | `Decoder[Seq[A]]`                                         |
| `decodeVectorOf[A]`              | `Decoder[Vector[A]]`                                      |
| `decodeIArrayOf[A]`              | `Decoder[IArray[A]]`                                      |
| `decodeSetOf[A]`                 | `Decoder[Set[A]]`                                         |
| `decodeOptionOf[A]`              | `Decoder[Option[A]]`                                      |
| `decodePairOf[A, B]`             | `Decoder[(A, B)]`                                         |
| `decodeTripleOf[A, B, C]`        | `Decoder[(A, B, C)]`                                      |
| `decodeMapOf[K, V]`              | `Decoder[Map[K, V]]` — needs `Decoder[K]`                 |
| `decodeTreeMapOf[K, V]`          | `Decoder[TreeMap[K, V]]` — needs `Decoder[K]` and `Ordering[K]` |

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
| `Decoders.primitives`             | all decoder primitives bundled (adds `Decoder[Dom.Element]`) |

## Defaults

| Bundle                       | Contents                                                                 |
| ---------------------------- | ------------------------------------------------------------------------ |
| `defaultEncoderOptions`      | `ConstructorEncoder.default` + `CborOptions.default`                     |
| `defaultDecoderOptions`      | `ConstructorsDecoder.default` + `CborOptions.default`                    |

Required by every `encoder[T]` / `decoder[T]` registry. Override one of
the two entries (typically `CborOptions`) by prepending it.

## CBOR options

| Flag                      | Type                  | Default                                          |
| ------------------------- | --------------------- | ------------------------------------------------ |
| `fieldKeyMode`            | `FieldKeyMode`        | `IntegerKeys`                                    |
| `constructorTagMode`      | `ConstructorTagMode`  | `IntegerTags`                                    |
| `fieldLabelModifier`      | `String => String`    | `identity`                                       |
| `constructorTagModifier`  | `String => String`    | `CborOptions.dropQualifier` (last segment of FQN) |
| `allNullaryToTag`         | `Boolean`             | `true`                                           |
| `omitNothingFields`       | `Boolean`             | `false`                                          |
| `sumEncoding`             | `SumEncoding`         | `TwoElemArray`                                   |
| `unwrapUnaryRecords`      | `Boolean`             | `false`                                          |
| `tagSingleConstructors`   | `Boolean`             | `false`                                          |
| `rejectUnknownFields`     | `Boolean`             | `false`                                          |

### `FieldKeyMode`

| Constructor                          | Shape                                          |
| ------------------------------------ | ---------------------------------------------- |
| `IntegerKeys`                        | record map keys are integers `0..N-1`          |
| `StringKeys`                         | record map keys are text strings (modified field names) |

### `ConstructorTagMode`

| Constructor                          | Shape                                          |
| ------------------------------------ | ---------------------------------------------- |
| `IntegerTags`                        | sum tag is an integer index `0..N-1`           |
| `StringTags`                         | sum tag is a text string (modified constructor name) |

### `SumEncoding`

| Constructor                                  | Shape                                          |
| -------------------------------------------- | ---------------------------------------------- |
| `TwoElemArray`                               | `[tag, contents]`                              |
| `SingleKeyMap`                               | `{tag: contents}`                              |
| `Untagged`                                   | no tag — decoders try each branch              |
| `CborTagged(baseTagNumber)`                  | `Tag(baseTagNumber + i, contents)` — CBOR major-type-6 tag |

## Resolution

| Method                                  | Effect                                          |
| --------------------------------------- | ----------------------------------------------- |
| `r.makeEncoder[T]`                      | resolve `Encoder[T]` (alias for `r.make[Encoder[T]]`) |
| `r.makeDecoder[T]`                      | resolve `Decoder[T]` (alias for `r.make[Decoder[T]]`) |
| `Encoders.encodeByteString(e, a)`       | encode `a: A` to CBOR bytes                     |
| `Encoders.encodeHex(e, a)`              | encode `a: A` to a hex-encoded CBOR string      |
| `Decoders.decodeByteString(d, bytes)`   | decode CBOR bytes via `d` (returns `Either`)    |
| `Decoders.decodeHex(d, hex)`            | decode a hex-encoded CBOR string                |
