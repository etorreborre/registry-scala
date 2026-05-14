package registry.cbor

import io.bullet.borer.derivation.{CompactMapBasedCodecs, MapBasedCodecs}
import io.bullet.borer.{Cbor, Decoder, Encoder}
import org.specs2.mutable.Specification
import registry.*

/**
 * Side-by-side check that `registry-cbor` can reproduce the wire format that `borer-derivation`'s
 * `MapBasedCodecs.derived` and `CompactMapBasedCodecs.derived` produce, given the right
 * `CborOptions`. The goal is to know how close a drop-in replacement of `Encoder.derived` /
 * `Decoder.derived` with `encoder[T]` / `decoder[T]` would be for an existing codebase like
 * hydrozoa.
 *
 * Each test encodes the same value via both paths and compares the resulting bytes. When they
 * differ, the test still records the bytes so the divergence is documented.
 */
class BorerDerivationParitySpec extends Specification:

  import BorerDerivationParitySpec.*

  // ----- a registry that mirrors MapBasedCodecs: StringKeys + non-unwrapped unary records -----

  private val mapBasedOptions: CborOptions = CborOptions.default.copy(
    fieldKeyMode = FieldKeyMode.StringKeys,
    constructorTagMode = ConstructorTagMode.StringTags
  )

  // For raw byte parity we must mirror borer's option encoding (array-wrapping: None -> [], Some(x) -> [x])
  // rather than registry-cbor's null-based default. We register the option codecs as a value entry.
  private def borerOptionEncoder[A: Encoder]: Encoder[Option[A]] = Encoder.forOption[A]
  private def borerOptionDecoder[A: Decoder]: Decoder[Option[A]] = Decoder.forOption[A]

  "MapBasedCodecs parity — single-field case class" >> {
    val derivedE: Encoder[Identifier] = MapBasedCodecs.deriveEncoder[Identifier]
    val derivedD: Decoder[Identifier] = MapBasedCodecs.deriveDecoder[Identifier]

    val r =
      encoder[Identifier] *: decoder[Identifier] *: encoderOf[Int] *: decoderOf[Int] *:
        value(mapBasedOptions) *: value(ConstructorEncoder.default) *: value(ConstructorsDecoder.default)

    val e = r.make[Encoder[Identifier]]
    val d = r.make[Decoder[Identifier]]

    val v = Identifier(42)
    val borerBytes = Cbor.encode(v)(using derivedE).toByteArray
    val registryBytes = Cbor.encode(v)(using e).toByteArray

    // Should match byte-for-byte.
    hex(borerBytes) === hex(registryBytes)
    // Round-trips on both sides.
    Cbor.decode(borerBytes).to[Identifier](using d).value === v
    Cbor.decode(registryBytes).to[Identifier](using derivedD).value === v
  }

  "MapBasedCodecs parity — multi-field case class" >> {
    given Encoder[Identifier] = MapBasedCodecs.deriveEncoder[Identifier]
    given Decoder[Identifier] = MapBasedCodecs.deriveDecoder[Identifier]
    given Encoder[Email] = MapBasedCodecs.deriveEncoder[Email]
    given Decoder[Email] = MapBasedCodecs.deriveDecoder[Email]
    val derivedE = MapBasedCodecs.deriveEncoder[Person]
    val derivedD = MapBasedCodecs.deriveDecoder[Person]

    val r =
      encoder[Person] *: decoder[Person] *:
        encoder[Identifier] *: decoder[Identifier] *:
        encoder[Email] *: decoder[Email] *:
        encoderOf[Int] *: decoderOf[Int] *:
        encoderOf[String] *: decoderOf[String] *:
        value(mapBasedOptions) *: value(ConstructorEncoder.default) *: value(ConstructorsDecoder.default)

    val e = r.make[Encoder[Person]]
    val d = r.make[Decoder[Person]]

    val v = Person(Identifier(1), Email("me@here.com"))
    val borerBytes = Cbor.encode(v)(using derivedE).toByteArray
    val registryBytes = Cbor.encode(v)(using e).toByteArray

    hex(borerBytes) === hex(registryBytes)
    Cbor.decode(borerBytes).to[Person](using d).value === v
    Cbor.decode(registryBytes).to[Person](using derivedD).value === v
  }

  "MapBasedCodecs parity — record with Option[Int] field (borer's array-wrapped None encoding)" >> {
    val derivedE = MapBasedCodecs.deriveEncoder[WithOpt]
    val derivedD = MapBasedCodecs.deriveDecoder[WithOpt]

    // Bypass our own encodeOptionOf entirely — supply borer's array-wrapped Option codec directly
    // so we mirror MapBasedCodecs.derived's wire shape exactly.
    val r =
      encoder[WithOpt] *: decoder[WithOpt] *:
        value(borerOptionEncoder[Int]) *: value(borerOptionDecoder[Int]) *:
        encoderOf[Int] *: decoderOf[Int] *:
        encoderOf[String] *: decoderOf[String] *:
        value(mapBasedOptions) *: value(ConstructorEncoder.default) *: value(ConstructorsDecoder.default)

    val e = r.make[Encoder[WithOpt]]
    val d = r.make[Decoder[WithOpt]]

    Seq(WithOpt("a", None), WithOpt("b", Some(7))).foreach { v =>
      val borerBytes = Cbor.encode(v)(using derivedE).toByteArray
      val registryBytes = Cbor.encode(v)(using e).toByteArray
      (hex(registryBytes), hex(borerBytes), v) === (hex(borerBytes), hex(borerBytes), v)
      Cbor.decode(borerBytes).to[WithOpt](using d).value === v
      Cbor.decode(registryBytes).to[WithOpt](using derivedD).value === v
    }
    success
  }

  "CompactMapBasedCodecs parity — unwrapped unary record" >> {
    val derivedE = CompactMapBasedCodecs.deriveEncoder[Identifier]
    val derivedD = CompactMapBasedCodecs.deriveDecoder[Identifier]

    val r =
      encoder[Identifier] *: decoder[Identifier] *: encoderOf[Int] *: decoderOf[Int] *:
        value(mapBasedOptions.copy(unwrapUnaryRecords = true)) *:
        value(ConstructorEncoder.default) *: value(ConstructorsDecoder.default)

    val e = r.make[Encoder[Identifier]]
    val d = r.make[Decoder[Identifier]]

    val v = Identifier(123)
    val borerBytes = Cbor.encode(v)(using derivedE).toByteArray
    val registryBytes = Cbor.encode(v)(using e).toByteArray
    hex(borerBytes) === hex(registryBytes)
    Cbor.decode(borerBytes).to[Identifier](using d).value === v
    Cbor.decode(registryBytes).to[Identifier](using derivedD).value === v
  }

  "MapBasedCodecs parity — sum type via SingleKeyMap (default AdtEncodingStrategy)" >> {
    // borer-derivation's default AdtEncodingStrategy emits each case as
    // `{ "ConstructorName": { ...fields } }` — which lines up exactly with our
    // `SumEncoding.SingleKeyMap` + `StringTags`. So sum types are byte-compatible too.
    given Encoder[Shape.Circle] = MapBasedCodecs.deriveEncoder[Shape.Circle]
    given Decoder[Shape.Circle] = MapBasedCodecs.deriveDecoder[Shape.Circle]
    given Encoder[Shape.Square] = MapBasedCodecs.deriveEncoder[Shape.Square]
    given Decoder[Shape.Square] = MapBasedCodecs.deriveDecoder[Shape.Square]
    val derivedE = MapBasedCodecs.deriveEncoder[Shape]
    val derivedD = MapBasedCodecs.deriveDecoder[Shape]

    val r =
      encoder[Shape] *: decoder[Shape] *:
        encoderOf[Int] *: decoderOf[Int] *:
        value(mapBasedOptions.copy(sumEncoding = SumEncoding.SingleKeyMap)) *:
        value(ConstructorEncoder.default) *: value(ConstructorsDecoder.default)
    val e = r.make[Encoder[Shape]]
    val d = r.make[Decoder[Shape]]

    Seq[Shape](Shape.Circle(2), Shape.Square(5)).foreach { v =>
      val borerBytes = Cbor.encode(v)(using derivedE).toByteArray
      val registryBytes = Cbor.encode(v)(using e).toByteArray
      hex(borerBytes) === hex(registryBytes)
      Cbor.decode(borerBytes).to[Shape](using d).value === v
      Cbor.decode(registryBytes).to[Shape](using derivedD).value === v
    }
    success
  }

  "MapBasedCodecs parity — sum type with a nullary case" >> {
    // Pure-enumeration case: borer-derivation encodes a nullary case as `{"NoOp": {}}` (empty
    // inner map), NOT as a bare string. Our `allNullaryToTag = true` collapses to a bare tag
    // (string in StringTags mode), which DIVERGES. Disabling `allNullaryToTag` brings them back
    // in line.
    given Encoder[Cmd.NoOp.type] = MapBasedCodecs.deriveEncoder[Cmd.NoOp.type]
    given Decoder[Cmd.NoOp.type] = MapBasedCodecs.deriveDecoder[Cmd.NoOp.type]
    given Encoder[Cmd.WithArg] = MapBasedCodecs.deriveEncoder[Cmd.WithArg]
    given Decoder[Cmd.WithArg] = MapBasedCodecs.deriveDecoder[Cmd.WithArg]
    val derivedE = MapBasedCodecs.deriveEncoder[Cmd]
    val derivedD = MapBasedCodecs.deriveDecoder[Cmd]

    val r =
      encoder[Cmd] *: decoder[Cmd] *:
        encoderOf[Int] *: decoderOf[Int] *:
        value(mapBasedOptions.copy(sumEncoding = SumEncoding.SingleKeyMap, allNullaryToTag = false)) *:
        value(ConstructorEncoder.default) *: value(ConstructorsDecoder.default)
    val e = r.make[Encoder[Cmd]]
    val d = r.make[Decoder[Cmd]]

    Seq[Cmd](Cmd.NoOp, Cmd.WithArg(7)).foreach { v =>
      val borerBytes = Cbor.encode(v)(using derivedE).toByteArray
      val registryBytes = Cbor.encode(v)(using e).toByteArray
      hex(borerBytes) === hex(registryBytes)
      Cbor.decode(borerBytes).to[Cmd](using d).value === v
      Cbor.decode(registryBytes).to[Cmd](using derivedD).value === v
    }
    success
  }

object BorerDerivationParitySpec:
  // shared test types — top-level so borer-derivation can find the Mirror
  final case class Identifier(value: Int)
  final case class Email(email: String)
  final case class Person(identifier: Identifier, email: Email)
  final case class WithOpt(label: String, count: Option[Int])

  enum Shape:
    case Circle(r: Int)
    case Square(side: Int)

  enum Cmd:
    case NoOp
    case WithArg(value: Int)

  private def hex(bytes: Array[Byte]): String =
    bytes.iterator.map(b => f"${b & 0xff}%02x").mkString
