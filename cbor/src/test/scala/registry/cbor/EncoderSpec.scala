package registry.cbor

import io.bullet.borer.{Cbor, Dom, Encoder, Tag}
import io.bullet.borer.Dom.*
import org.specs2.mutable.Specification
import registry.{Person as _, *}
import registry.cbor.DataTypes.*

/**
 * Verifies the macro-generated encoders against expected `Dom.Element` shapes. Each test encodes a
 * value with a configured registry, decodes the bytes into a `Dom.Element`, and compares against an
 * explicit expected shape.
 */
class EncoderSpec extends Specification:

  "encode Identifier as a single-field map with integer key 0" >> {
    val e = encoders.make[Encoder[Identifier]]
    domOf(e, Identifier(123)) === MapElem.Sized(IntElem(0) -> IntElem(123))
  }

  "encode Email as a single-field map" >> {
    val e = encoders.make[Encoder[Email]]
    domOf(e, email1) === MapElem.Sized(IntElem(0) -> StringElem("me@here.com"))
  }

  "encode NoDelivery as a plain integer constructor index (default allNullaryToTag)" >> {
    // NoDelivery is the 0th constructor of Delivery
    val e = encoders.make[Encoder[Delivery]]
    domOf(e, delivery0) === IntElem(0)
  }

  "encode ByEmail as TwoElemArray [tag, contents]" >> {
    val e = encoders.make[Encoder[Delivery]]
    val out = domOf(e, delivery1)
    out === ArrayElem.Sized(
      IntElem(1),
      MapElem.Sized(IntElem(0) -> MapElem.Sized(IntElem(0) -> StringElem("me@here.com")))
    )
  }

  "encode Person as a multi-field map with integer keys" >> {
    val e = encoders.make[Encoder[Person]]
    domOf(e, person1) === MapElem.Sized(
      IntElem(0) -> MapElem.Sized(IntElem(0) -> IntElem(123)),
      IntElem(1) -> MapElem.Sized(IntElem(0) -> StringElem("me@here.com"))
    )
  }

  "encode InPerson as a multi-field sum constructor" >> {
    val e = encoders.make[Encoder[Delivery]]
    val expected = ArrayElem.Sized(
      IntElem(2),
      MapElem.Sized(
        IntElem(0) -> MapElem.Sized(
          IntElem(0) -> MapElem.Sized(IntElem(0) -> IntElem(123)),
          IntElem(1) -> MapElem.Sized(IntElem(0) -> StringElem("me@here.com"))
        ),
        IntElem(1) -> MapElem.Sized(IntElem(0) -> StringElem("2022-04-18T00:00:12Z"))
      )
    )
    domOf(e, delivery2) === expected
  }

  "StringKeys mode rewrites field keys as text strings" >> {
    val r = value(CborOptions.default.copy(fieldKeyMode = FieldKeyMode.StringKeys)) -: encoders
    val e = r.make[Encoder[Identifier]]
    domOf(e, Identifier(7)) === MapElem.Sized(StringElem("value") -> IntElem(7))
  }

  "StringTags mode rewrites the sum tag as a text string" >> {
    val r = value(
      CborOptions.default.copy(constructorTagMode = ConstructorTagMode.StringTags)
    ) -: encoders
    val e = r.make[Encoder[Delivery]]
    domOf(e, delivery0) === StringElem("NoDelivery")
  }

  "fieldLabelModifier rewrites field names (in StringKeys mode)" >> {
    val r = value(
      CborOptions.default.copy(
        fieldKeyMode = FieldKeyMode.StringKeys,
        fieldLabelModifier = "__" + _
      )
    ) -: encoders
    val e = r.make[Encoder[FieldLabelModifier]]
    domOf(e, FieldLabelModifier.FieldLabelModifier1(123)) === ArrayElem.Sized(
      IntElem(0),
      MapElem.Sized(StringElem("__field1") -> IntElem(123))
    )
  }

  "constructorTagModifier rewrites the constructor tag (in StringTags mode)" >> {
    val r = value(
      CborOptions.default.copy(
        constructorTagMode = ConstructorTagMode.StringTags,
        constructorTagModifier = CborOptions.dropQualifier andThen ("__" + _)
      )
    ) -: encoders
    val e = r.make[Encoder[ConstructorTagModifier]]
    domOf(e, ConstructorTagModifier.ConstructorTagModifier1(123)) === ArrayElem.Sized(
      StringElem("__ConstructorTagModifier1"),
      MapElem.Sized(IntElem(0) -> IntElem(123))
    )
  }

  "omitNothingFields drops null fields" >> {
    val r = value(CborOptions.default.copy(omitNothingFields = true)) -: encoders
    val e = r.make[Encoder[OmitNothingFields]]
    domOf(e, OmitNothingFields.OmitNothingFields1(None, 123)) === ArrayElem.Sized(
      IntElem(0),
      MapElem.Sized(IntElem(1) -> IntElem(123))
    )
  }

  "unwrapUnaryRecords encodes a single-field record as the wrapped value" >> {
    val r = value(CborOptions.default.copy(unwrapUnaryRecords = true)) -: encoders
    val e = r.make[Encoder[UnwrapUnaryRecords]]
    domOf(e, UnwrapUnaryRecords(123)) === IntElem(123)
  }

  "tagSingleConstructors tags even a single-constructor type" >> {
    val r = value(CborOptions.default.copy(tagSingleConstructors = true)) -: encoders
    val e = r.make[Encoder[TagSingleConstructors]]
    domOf(e, TagSingleConstructors(123)) === ArrayElem.Sized(
      IntElem(0),
      MapElem.Sized(IntElem(0) -> IntElem(123))
    )
  }

  "Untagged sum encoding emits no tag" >> {
    val r = value(CborOptions.default.copy(sumEncoding = SumEncoding.Untagged)) -: encoders
    val e = r.make[Encoder[UntaggedSumEncoding]]
    domOf(e, UntaggedSumEncoding.UntaggedSumEncoding1(123)) ===
      MapElem.Sized(IntElem(0) -> IntElem(123))
  }

  "SingleKeyMap wraps under the constructor tag" >> {
    val r = value(CborOptions.default.copy(sumEncoding = SumEncoding.SingleKeyMap)) -: encoders
    val e = r.make[Encoder[SingleKeyMapSumEncoding]]
    domOf(e, SingleKeyMapSumEncoding.SingleKeyMapSumEncoding1(123)) ===
      MapElem.Sized(IntElem(0) -> MapElem.Sized(IntElem(0) -> IntElem(123)))
  }

  "TwoElemArray wraps as [tag, contents]" >> {
    val r = value(CborOptions.default.copy(sumEncoding = SumEncoding.TwoElemArray)) -: encoders
    val e = r.make[Encoder[TwoElemArraySumEncoding]]
    domOf(e, TwoElemArraySumEncoding.TwoElemArraySumEncoding1(123)) ===
      ArrayElem.Sized(IntElem(0), MapElem.Sized(IntElem(0) -> IntElem(123)))
  }

  "CborTagged wraps using a CBOR semantic tag" >> {
    val baseTag = 1000L
    val r = value(CborOptions.default.copy(sumEncoding = SumEncoding.CborTagged(baseTag))) -: encoders
    val e = r.make[Encoder[CborTaggedSumEncoding]]
    domOf(e, CborTaggedSumEncoding.CborTaggedSumEncoding2(99)) ===
      TaggedElem(Tag.Other(baseTag + 1), MapElem.Sized(IntElem(0) -> IntElem(99)))
  }

  "encoder compiles when two fields have the same type" >> {
    val _ =
      encoder[Stats] *:
        encoderOf[Int] *:
        defaultEncoderOptions
    success
  }

  // ---- shared encoder registry ----

  lazy val encoders =
    encoder[Delivery] *:
      encoder[Person] *:
      encoder[Team] *:
      encoder[Email] *:
      encoder[Identifier] *:
      encoder[DateTime] *:
      encoder[AllNullary] *:
      encoder[FieldLabelModifier] *:
      encoder[ConstructorTagModifier] *:
      encoder[OmitNothingFields] *:
      encoder[UnwrapUnaryRecords] *:
      encoder[TagSingleConstructors] *:
      encoder[UntaggedSumEncoding] *:
      encoder[SingleKeyMapSumEncoding] *:
      encoder[TwoElemArraySumEncoding] *:
      encoder[CborTaggedSumEncoding] *:
      encodeOptionOf[Int] *:
      encodeOptionOf[String] *:
      encodeListOf[Person] *:
      encoderOf[String] *:
      encoderOf[Int] *:
      defaultEncoderOptions

  // ---- helpers ----

  private def domOf[A](e: Encoder[A], a: A): Dom.Element =
    val bytes = Cbor.encode(a)(using e).toByteArray
    Cbor.decode(bytes).to[Dom.Element].value
