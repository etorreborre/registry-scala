package registry.circe

import io.circe.{Encoder, Decoder}
import io.circe.Json
import io.circe.parser as circeParser
import org.specs2.mutable.Specification
import registry.{Person as _, *}
import registry.circe.DataTypes.*

/**
 * Scala-port analogue of `test/Test/Data/Registry/Aeson/DecoderSpec.hs`.
 *
 * Exercises every [[JsonOptions]] flag on decoding, plus field-path error messages and
 * `rejectUnknownFields` behavior across sum-encoding modes.
 */
class DecoderSpec extends Specification:

  "decode Identifier" >> {
    // Single-field record: input is {"value": 123}.
    val d = decoders.make[Decoder[Identifier]]
    d.decodeJson(parse("""{"value":123}""")) === Right(Identifier(123))
  }

  "decode Email" >> {
    val d = decoders.make[Decoder[Email]]
    d.decodeJson(parse("""{"email":"me@here.com"}""")) === Right(email1)
  }

  "decode Person" >> {
    val d = decoders.make[Decoder[Person]]
    d.decodeJson(parse("""{"identifier":{"value":123},"email":{"email":"me@here.com"}}""")) === Right(person1)
  }

  "decode NoDelivery" >> {
    val d = decoders.make[Decoder[Delivery]]
    d.decodeJson(parse("""{"tag":"NoDelivery"}""")) === Right(delivery0)
  }

  "decode ByEmail" >> {
    val d = decoders.make[Decoder[Delivery]]
    d.decodeJson(parse("""{"tag":"ByEmail","email":{"email":"me@here.com"}}""")) === Right(delivery1)
  }

  "decode InPerson" >> {
    val d = decoders.make[Decoder[Delivery]]
    d.decodeJson(
      parse(
        """{"tag":"InPerson","person":{"identifier":{"value":123},"email":{"email":"me@here.com"}},"datetime":{"datetime":"2022-04-18T00:00:12Z"}}"""
      )
    ) === Right(delivery2)
  }

  "allNullaryToStringTag decodes from plain strings" >> {
    val r = value(JsonOptions.default.copy(allNullaryToStringTag = true)) -: decoders
    val d = r.make[Decoder[AllNullary]]
    d.decodeJson(parse("\"AllNullary1\"")) === Right(AllNullary.AllNullary1)
    d.decodeJson(parse("\"AllNullary2\"")) === Right(AllNullary.AllNullary2)
  }

  "fieldLabelModifier maps JSON keys to constructor fields" >> {
    val r = value(JsonOptions.default.copy(fieldLabelModifier = "__" + _)) -: decoders
    val d = r.make[Decoder[FieldLabelModifier]]
    d.decodeJson(parse("""{"tag":"FieldLabelModifier1","__field1":123}""")) ===
      Right(FieldLabelModifier.FieldLabelModifier1(123))
  }

  "constructorTagModifier maps JSON tag values to constructors" >> {
    val r = value(JsonOptions.default.copy(constructorTagModifier = "__" + _)) -: decoders
    val d = r.make[Decoder[ConstructorTagModifier]]
    d.decodeJson(parse("""{"tag":"__ConstructorTagModifier1","ctField1":123}""")) ===
      Right(ConstructorTagModifier.ConstructorTagModifier1(123))
  }

  "omitNothingFields treats missing Option fields as None" >> {
    val r = value(JsonOptions.default.copy(omitNothingFields = true)) -: decoders
    val d = r.make[Decoder[OmitNothingFields]]
    d.decodeJson(parse("""{"tag":"OmitNothingFields1","onField2":123}""")) ===
      Right(OmitNothingFields.OmitNothingFields1(None, 123))
  }

  "unwrapUnaryRecords decodes a single-field record from the raw value" >> {
    val r = value(JsonOptions.default.copy(unwrapUnaryRecords = true)) -: decoders
    val d = r.make[Decoder[UnwrapUnaryRecords]]
    d.decodeJson(parse("123")) === Right(UnwrapUnaryRecords(123))
  }

  "tagSingleConstructors reads a tag-prefixed single-constructor value" >> {
    val r = value(JsonOptions.default.copy(tagSingleConstructors = true)) -: decoders
    val d = r.make[Decoder[TagSingleConstructors]]
    d.decodeJson(parse("""{"tag":"TagSingleConstructors","tsField1":123}""")) === Right(TagSingleConstructors(123))
  }

  "UntaggedValue picks the right constructor by shape" >> {
    val r = value(JsonOptions.default.copy(sumEncoding = SumEncoding.UntaggedValue)) -: decoders
    val d = r.make[Decoder[UntaggedValueSumEncoding]]
    d.decodeJson(parse("""{"uvField1":123}""")) === Right(UntaggedValueSumEncoding.UntaggedValueSumEncoding1(123))
  }

  "ObjectWithSingleField decodes by top-level key" >> {
    val r = value(JsonOptions.default.copy(sumEncoding = SumEncoding.ObjectWithSingleField)) -: decoders
    val d = r.make[Decoder[ObjectWithSingleFieldSumEncoding]]
    d.decodeJson(parse("""{"ObjectWithSingleFieldSumEncoding1":{"owsfField1":123}}""")) ===
      Right(ObjectWithSingleFieldSumEncoding.ObjectWithSingleFieldSumEncoding1(123))
  }

  "TwoElemArray decodes from [tag, contents]" >> {
    val r = value(JsonOptions.default.copy(sumEncoding = SumEncoding.TwoElemArray)) -: decoders
    val d = r.make[Decoder[TwoElemArraySumEncoding]]
    d.decodeJson(parse("""["TwoElemArraySumEncoding1",{"teaField1":123}]""")) ===
      Right(TwoElemArraySumEncoding.TwoElemArraySumEncoding1(123))
  }

  "reject unknown tag values with a helpful error" >> {
    val d = decoders.make[Decoder[Delivery]]
    d.decodeJson(parse("""{"tag":"NoDeliveryX"}""")) must beLeft.like { case f =>
      f.message must contain("expected the tag field to be one of: NoDelivery, ByEmail, InPerson, found: NoDeliveryX")
    }
  }

  "reject missing tag field with a helpful error" >> {
    val d = decoders.make[Decoder[Delivery]]
    d.decodeJson(parse("""{"tag1":"NoDelivery"}""")) must beLeft.like { case f =>
      f.message must contain("tag field 'tag' not found")
    }
  }

  "rejectUnknownFields fires when extra keys are present" >> {
    val r = value(JsonOptions.default.copy(rejectUnknownFields = true)) -: decoders
    val d = r.make[Decoder[Email]]
    d.decodeJson(parse("""{"email":"me@here.com","f":1}""")) must beLeft.like { case f =>
      f.message must contain("unknown field: f")
    }
  }

  "decodeMapOf reconstructs a Map" >> {
    val r =
      decodeMapOf[Name, Int] *:
        decodeKey[Name](s => Some(Name(s))) *:
        decoderOf[Int] *:
        decoderOf[String] *:
        defaultDecoderOptions
    val d = r.make[Decoder[Map[Name, Int]]]
    d.decodeJson(parse("""{"name1":1,"name2":2}""")) === Right(Map(Name("name1") -> 1, Name("name2") -> 2))
  }

  "makeDecoder compiles when two fields have the same type" >> {
    val _ =
      makeDecoder[Stats] *:
        decoderOf[Int] *:
        defaultDecoderOptions
    success
  }

  // ---- shared decoder registry ----

  lazy val decoders =
    makeDecoder[Delivery] *:
      makeDecoder[Team] *:
      decodeListOf[Person] *:
      makeDecoder[Person] *:
      makeDecoder[Email] *:
      makeDecoder[Identifier] *:
      makeDecoder[DateTime] *:
      makeDecoder[AllNullary] *:
      makeDecoder[FieldLabelModifier] *:
      makeDecoder[ConstructorTagModifier] *:
      makeDecoder[OmitNothingFields] *:
      makeDecoder[UnwrapUnaryRecords] *:
      makeDecoder[TagSingleConstructors] *:
      makeDecoder[UntaggedValueSumEncoding] *:
      makeDecoder[ObjectWithSingleFieldSumEncoding] *:
      makeDecoder[TwoElemArraySumEncoding] *:
      decodeOptionOf[Int] *:
      decodeOptionOf[String] *:
      decoderOf[String] *:
      decoderOf[Int] *:
      defaultDecoderOptions

  // ---- helpers ----

  private def parse(s: String): Json =
    circeParser.parse(s).getOrElse(sys.error(s"invalid test JSON: $s"))
