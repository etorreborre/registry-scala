package registry.circe

import io.circe.{Encoder, Decoder}
import io.circe.Json
import io.circe.parser as circeParser
import org.specs2.mutable.Specification
import registry.{Person as _, *}
import registry.circe.DataTypes.*

/**
 * Scala-port analogue of `test/Test/Data/Registry/Aeson/EncoderSpec.hs`. Each case exercises one of
 * the [[JsonOptions]] flags against a representative data type.
 *
 * Where the Haskell spec compares against aeson's `genericToJSON options`, we compare against a
 * literal expected JSON (parsed from a string so quoting is readable).
 */
class EncoderSpec extends Specification:

  // ---- direct encoding against the defaults ----

  "encode Identifier as its wrapped value (single field, single-ctor newtype-like)" >> {
    // Single-field record: emits {"value": 123}
    val e = encoders.make[Encoder[Identifier]]
    Encoders.encodeString(e, Identifier(123)) === """{"value":123}"""
  }

  "encode Email as {\"email\":...}" >> {
    val e = encoders.make[Encoder[Email]]
    Encoders.encodeString(e, email1) === """{"email":"me@here.com"}"""
  }

  "encode NoDelivery with the default TaggedObject sum encoding" >> {
    val e = encoders.make[Encoder[Delivery]]
    Encoders.encodeString(e, delivery0) === """{"tag":"NoDelivery"}"""
  }

  "encode ByEmail with its named field inlined into the tagged object" >> {
    val e = encoders.make[Encoder[Delivery]]
    expectJsonEqual(Encoders.encodeString(e, delivery1), """{"tag":"ByEmail","email":{"email":"me@here.com"}}""")
  }

  "encode Person as a multi-field record" >> {
    val e = encoders.make[Encoder[Person]]
    expectJsonEqual(
      Encoders.encodeString(e, person1),
      """{"identifier":{"value":123},"email":{"email":"me@here.com"}}"""
    )
  }

  "encode InPerson as a multi-field sum constructor" >> {
    val e = encoders.make[Encoder[Delivery]]
    expectJsonEqual(
      Encoders.encodeString(e, delivery2),
      """{"tag":"InPerson","person":{"identifier":{"value":123},"email":{"email":"me@here.com"}},"datetime":{"datetime":"2022-04-18T00:00:12Z"}}"""
    )
  }

  // ---- option-driven behavior ----

  "allNullaryToStringTag emits pure enumerations as strings" >> {
    val r = value(JsonOptions.default.copy(allNullaryToStringTag = true)) -: encoders
    val e = r.make[Encoder[AllNullary]]
    Encoders.encodeString(e, AllNullary.AllNullary1) === "\"AllNullary1\""
    Encoders.encodeString(e, AllNullary.AllNullary2) === "\"AllNullary2\""
  }

  "fieldLabelModifier rewrites field names" >> {
    val r = value(JsonOptions.default.copy(fieldLabelModifier = "__" + _)) -: encoders
    val e = r.make[Encoder[FieldLabelModifier]]
    expectJsonEqual(
      Encoders.encodeString(e, FieldLabelModifier.FieldLabelModifier1(123)),
      """{"tag":"FieldLabelModifier1","__field1":123}"""
    )
  }

  "constructorTagModifier rewrites the constructor tag" >> {
    val r = value(JsonOptions.default.copy(constructorTagModifier = "__" + _)) -: encoders
    val e = r.make[Encoder[ConstructorTagModifier]]
    expectJsonEqual(
      Encoders.encodeString(e, ConstructorTagModifier.ConstructorTagModifier1(123)),
      """{"tag":"__ConstructorTagModifier1","ctField1":123}"""
    )
  }

  "omitNothingFields drops null fields" >> {
    val r = value(JsonOptions.default.copy(omitNothingFields = true)) -: encoders
    val e = r.make[Encoder[OmitNothingFields]]
    expectJsonEqual(
      Encoders.encodeString(e, OmitNothingFields.OmitNothingFields1(None, 123)),
      """{"tag":"OmitNothingFields1","onField2":123}"""
    )
  }

  "unwrapUnaryRecords encodes a single-field record as the wrapped value" >> {
    val r = value(JsonOptions.default.copy(unwrapUnaryRecords = true)) -: encoders
    val e = r.make[Encoder[UnwrapUnaryRecords]]
    Encoders.encodeString(e, UnwrapUnaryRecords(123)) === "123"
  }

  "tagSingleConstructors tags even a single-constructor type" >> {
    val r = value(JsonOptions.default.copy(tagSingleConstructors = true)) -: encoders
    val e = r.make[Encoder[TagSingleConstructors]]
    expectJsonEqual(
      Encoders.encodeString(e, TagSingleConstructors(123)),
      """{"tag":"TagSingleConstructors","tsField1":123}"""
    )
  }

  "UntaggedValue emits no tag" >> {
    val r = value(JsonOptions.default.copy(sumEncoding = SumEncoding.UntaggedValue)) -: encoders
    val e = r.make[Encoder[UntaggedValueSumEncoding]]
    expectJsonEqual(
      Encoders.encodeString(e, UntaggedValueSumEncoding.UntaggedValueSumEncoding1(123)),
      """{"uvField1":123}"""
    )
  }

  "ObjectWithSingleField wraps under the constructor name" >> {
    val r = value(JsonOptions.default.copy(sumEncoding = SumEncoding.ObjectWithSingleField)) -: encoders
    val e = r.make[Encoder[ObjectWithSingleFieldSumEncoding]]
    expectJsonEqual(
      Encoders.encodeString(e, ObjectWithSingleFieldSumEncoding.ObjectWithSingleFieldSumEncoding1(123)),
      """{"ObjectWithSingleFieldSumEncoding1":{"owsfField1":123}}"""
    )
  }

  "TwoElemArray wraps as [tag, contents]" >> {
    val r = value(JsonOptions.default.copy(sumEncoding = SumEncoding.TwoElemArray)) -: encoders
    val e = r.make[Encoder[TwoElemArraySumEncoding]]
    expectJsonEqual(
      Encoders.encodeString(e, TwoElemArraySumEncoding.TwoElemArraySumEncoding1(123)),
      """["TwoElemArraySumEncoding1",{"teaField1":123}]"""
    )
  }

  "encoder compiles when two fields have the same type" >> {
    // If it compiles the test passes — runtime behavior is not the concern here.
    val _ =
      encoder[Stats] *:
        encoderOf[Int] *:
        defaultEncoderOptions
    success
  }

  "encodeMapOf produces a JSON object" >> {
    val r =
      encodeMapOf[Name, Int] *:
        encoderOf[Int] *:
        encoderOf[String] *:
        KeyEncoders.encodeKey[Name](_.name) *:
        defaultEncoderOptions
    val e = r.make[Encoder[Map[Name, Int]]]
    expectJsonEqual(
      Encoders.encodeString(e, Map(Name("name1") -> 1, Name("name2") -> 2)),
      """{"name1":1,"name2":2}"""
    )
  }

  // ---- shared encoder registry (mirrors aeson's `encoders`) ----

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
      encoder[UntaggedValueSumEncoding] *:
      encoder[ObjectWithSingleFieldSumEncoding] *:
      encoder[TwoElemArraySumEncoding] *:
      encodeOptionOf[Int] *:
      encodeOptionOf[String] *:
      encodeListOf[Person] *:
      encoderOf[String] *:
      encoderOf[Int] *:
      defaultEncoderOptions

  // ---- helpers ----

  private def expectJsonEqual(actual: String, expected: String) =
    (circeParser.parse(actual), circeParser.parse(expected)) match
      case (Right(a), Right(e)) => a === e
      case _                    => actual === expected
