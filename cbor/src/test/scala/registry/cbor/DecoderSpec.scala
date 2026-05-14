package registry.cbor

import io.bullet.borer.{Cbor, Decoder, Dom, Tag}
import io.bullet.borer.Dom.*
import org.specs2.mutable.Specification
import registry.{Person as _, *}
import registry.cbor.DataTypes.*

/**
 * Verifies the macro-generated decoders against `Dom.Element` shapes (encoded to bytes via borer's
 * `Cbor.encode` for `Dom.Element`).
 */
class DecoderSpec extends Specification:

  "decode Identifier from {0: 123}" >> {
    val d = decoders.make[Decoder[Identifier]]
    decode(d, MapElem.Sized(IntElem(0) -> IntElem(123))) === Right(Identifier(123))
  }

  "decode Email" >> {
    val d = decoders.make[Decoder[Email]]
    decode(d, MapElem.Sized(IntElem(0) -> StringElem("me@here.com"))) === Right(email1)
  }

  "decode Person" >> {
    val d = decoders.make[Decoder[Person]]
    decode(
      d,
      MapElem.Sized(
        IntElem(0) -> MapElem.Sized(IntElem(0) -> IntElem(123)),
        IntElem(1) -> MapElem.Sized(IntElem(0) -> StringElem("me@here.com"))
      )
    ) === Right(person1)
  }

  "decode NoDelivery from the default plain integer tag" >> {
    val d = decoders.make[Decoder[Delivery]]
    decode(d, IntElem(0)) === Right(delivery0)
  }

  "decode ByEmail from TwoElemArray" >> {
    val d = decoders.make[Decoder[Delivery]]
    decode(
      d,
      ArrayElem.Sized(
        IntElem(1),
        MapElem.Sized(IntElem(0) -> MapElem.Sized(IntElem(0) -> StringElem("me@here.com")))
      )
    ) === Right(delivery1)
  }

  "decode InPerson from TwoElemArray" >> {
    val d = decoders.make[Decoder[Delivery]]
    val elem = ArrayElem.Sized(
      IntElem(2),
      MapElem.Sized(
        IntElem(0) -> MapElem.Sized(
          IntElem(0) -> MapElem.Sized(IntElem(0) -> IntElem(123)),
          IntElem(1) -> MapElem.Sized(IntElem(0) -> StringElem("me@here.com"))
        ),
        IntElem(1) -> MapElem.Sized(IntElem(0) -> StringElem("2022-04-18T00:00:12Z"))
      )
    )
    decode(d, elem) === Right(delivery2)
  }

  "StringKeys mode decodes from text-keyed maps" >> {
    val r = value(CborOptions.default.copy(fieldKeyMode = FieldKeyMode.StringKeys)) -: decoders
    val d = r.make[Decoder[Identifier]]
    decode(d, MapElem.Sized(StringElem("value") -> IntElem(7))) === Right(Identifier(7))
  }

  "StringTags decodes from text constructor tags" >> {
    val r = value(
      CborOptions.default.copy(constructorTagMode = ConstructorTagMode.StringTags)
    ) -: decoders
    val d = r.make[Decoder[Delivery]]
    decode(d, StringElem("NoDelivery")) === Right(delivery0)
  }

  "fieldLabelModifier maps modified keys back to fields (StringKeys mode)" >> {
    val r = value(
      CborOptions.default.copy(
        fieldKeyMode = FieldKeyMode.StringKeys,
        fieldLabelModifier = "__" + _
      )
    ) -: decoders
    val d = r.make[Decoder[FieldLabelModifier]]
    decode(d, ArrayElem.Sized(IntElem(0), MapElem.Sized(StringElem("__field1") -> IntElem(123)))) ===
      Right(FieldLabelModifier.FieldLabelModifier1(123))
  }

  "omitNothingFields treats missing Option fields as None" >> {
    val r = value(CborOptions.default.copy(omitNothingFields = true)) -: decoders
    val d = r.make[Decoder[OmitNothingFields]]
    decode(d, ArrayElem.Sized(IntElem(0), MapElem.Sized(IntElem(1) -> IntElem(123)))) ===
      Right(OmitNothingFields.OmitNothingFields1(None, 123))
  }

  "unwrapUnaryRecords decodes a single-field record from the raw value" >> {
    val r = value(CborOptions.default.copy(unwrapUnaryRecords = true)) -: decoders
    val d = r.make[Decoder[UnwrapUnaryRecords]]
    decode(d, IntElem(123)) === Right(UnwrapUnaryRecords(123))
  }

  "tagSingleConstructors reads a tag-prefixed single-constructor value" >> {
    val r = value(CborOptions.default.copy(tagSingleConstructors = true)) -: decoders
    val d = r.make[Decoder[TagSingleConstructors]]
    decode(d, ArrayElem.Sized(IntElem(0), MapElem.Sized(IntElem(0) -> IntElem(123)))) ===
      Right(TagSingleConstructors(123))
  }

  "Untagged picks the right constructor by shape" >> {
    val r = value(CborOptions.default.copy(sumEncoding = SumEncoding.Untagged)) -: decoders
    val d = r.make[Decoder[UntaggedSumEncoding]]
    decode(d, MapElem.Sized(IntElem(0) -> IntElem(123))) ===
      Right(UntaggedSumEncoding.UntaggedSumEncoding1(123))
  }

  "SingleKeyMap decodes by tag key" >> {
    val r = value(CborOptions.default.copy(sumEncoding = SumEncoding.SingleKeyMap)) -: decoders
    val d = r.make[Decoder[SingleKeyMapSumEncoding]]
    decode(d, MapElem.Sized(IntElem(0) -> MapElem.Sized(IntElem(0) -> IntElem(123)))) ===
      Right(SingleKeyMapSumEncoding.SingleKeyMapSumEncoding1(123))
  }

  "TwoElemArray decodes from [tag, contents]" >> {
    val r = value(CborOptions.default.copy(sumEncoding = SumEncoding.TwoElemArray)) -: decoders
    val d = r.make[Decoder[TwoElemArraySumEncoding]]
    decode(d, ArrayElem.Sized(IntElem(0), MapElem.Sized(IntElem(0) -> IntElem(123)))) ===
      Right(TwoElemArraySumEncoding.TwoElemArraySumEncoding1(123))
  }

  "CborTagged decodes from a CBOR semantic tag" >> {
    val baseTag = 1000L
    val r = value(CborOptions.default.copy(sumEncoding = SumEncoding.CborTagged(baseTag))) -: decoders
    val d = r.make[Decoder[CborTaggedSumEncoding]]
    decode(d, TaggedElem(Tag.Other(baseTag + 1L), MapElem.Sized(IntElem(0) -> IntElem(99)))) ===
      Right(CborTaggedSumEncoding.CborTaggedSumEncoding2(99))
  }

  "decoder compiles when two fields have the same type" >> {
    val _ =
      decoder[Stats] *:
        decoderOf[Int] *:
        defaultDecoderOptions
    success
  }

  // ---- shared decoder registry ----

  lazy val decoders =
    decoder[Delivery] *:
      decoder[Team] *:
      decodeListOf[Person] *:
      decoder[Person] *:
      decoder[Email] *:
      decoder[Identifier] *:
      decoder[DateTime] *:
      decoder[AllNullary] *:
      decoder[FieldLabelModifier] *:
      decoder[ConstructorTagModifier] *:
      decoder[OmitNothingFields] *:
      decoder[UnwrapUnaryRecords] *:
      decoder[TagSingleConstructors] *:
      decoder[UntaggedSumEncoding] *:
      decoder[SingleKeyMapSumEncoding] *:
      decoder[TwoElemArraySumEncoding] *:
      decoder[CborTaggedSumEncoding] *:
      decodeOptionOf[Int] *:
      decodeOptionOf[String] *:
      decoderOf[String] *:
      decoderOf[Int] *:
      defaultDecoderOptions

  // ---- helpers ----

  private def decode[A](d: Decoder[A], e: Dom.Element): Either[String, A] =
    val bytes = Cbor.encode(e).toByteArray
    Cbor.decode(bytes).to[A](using d).valueEither.left.map(_.toString)
