package registry.circe

import io.circe.Json
import io.circe.parser as circeParser
import org.specs2.mutable.Specification
import registry.*
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
    Encoder.encodeString(e, Identifier(123)) === """{"value":123}"""
  }

  "encode Email as {\"email\":...}" >> {
    val e = encoders.make[Encoder[Email]]
    Encoder.encodeString(e, email1) === """{"email":"me@here.com"}"""
  }

  "encode NoDelivery with the default TaggedObject sum encoding" >> {
    val e = encoders.make[Encoder[Delivery]]
    Encoder.encodeString(e, delivery0) === """{"tag":"NoDelivery"}"""
  }

  "encode ByEmail with its named field inlined into the tagged object" >> {
    val e = encoders.make[Encoder[Delivery]]
    expectJsonEqual(Encoder.encodeString(e, delivery1), """{"tag":"ByEmail","email":{"email":"me@here.com"}}""")
  }

  "encode Person as a multi-field record" >> {
    val e = encoders.make[Encoder[Person]]
    expectJsonEqual(
      Encoder.encodeString(e, person1),
      """{"identifier":{"value":123},"email":{"email":"me@here.com"}}"""
    )
  }

  "encode InPerson as a multi-field sum constructor" >> {
    val e = encoders.make[Encoder[Delivery]]
    expectJsonEqual(
      Encoder.encodeString(e, delivery2),
      """{"tag":"InPerson","person":{"identifier":{"value":123},"email":{"email":"me@here.com"}},"datetime":{"datetime":"2022-04-18T00:00:12Z"}}"""
    )
  }

  // ---- option-driven behavior ----

  "allNullaryToStringTag emits pure enumerations as strings" >> {
    val r = value(JsonOptions.default.copy(allNullaryToStringTag = true)) -: encoders
    val e = r.make[Encoder[AllNullary]]
    Encoder.encodeString(e, AllNullary.AllNullary1) === "\"AllNullary1\""
    Encoder.encodeString(e, AllNullary.AllNullary2) === "\"AllNullary2\""
  }

  "fieldLabelModifier rewrites field names" >> {
    val r = value(JsonOptions.default.copy(fieldLabelModifier = "__" + _)) -: encoders
    val e = r.make[Encoder[FieldLabelModifier]]
    expectJsonEqual(Encoder.encodeString(e, FieldLabelModifier.FieldLabelModifier1(123)), """{"tag":"FieldLabelModifier1","__field1":123}""")
  }

  "constructorTagModifier rewrites the constructor tag" >> {
    val r = value(JsonOptions.default.copy(constructorTagModifier = "__" + _)) -: encoders
    val e = r.make[Encoder[ConstructorTagModifier]]
    expectJsonEqual(
      Encoder.encodeString(e, ConstructorTagModifier.ConstructorTagModifier1(123)),
      """{"tag":"__ConstructorTagModifier1","ctField1":123}"""
    )
  }

  "omitNothingFields drops null fields" >> {
    val r = value(JsonOptions.default.copy(omitNothingFields = true)) -: encoders
    val e = r.make[Encoder[OmitNothingFields]]
    expectJsonEqual(
      Encoder.encodeString(e, OmitNothingFields.OmitNothingFields1(None, 123)),
      """{"tag":"OmitNothingFields1","onField2":123}"""
    )
  }

  "unwrapUnaryRecords encodes a single-field record as the wrapped value" >> {
    val r = value(JsonOptions.default.copy(unwrapUnaryRecords = true)) -: encoders
    val e = r.make[Encoder[UnwrapUnaryRecords]]
    Encoder.encodeString(e, UnwrapUnaryRecords(123)) === "123"
  }

  "tagSingleConstructors tags even a single-constructor type" >> {
    val r = value(JsonOptions.default.copy(tagSingleConstructors = true)) -: encoders
    val e = r.make[Encoder[TagSingleConstructors]]
    expectJsonEqual(
      Encoder.encodeString(e, TagSingleConstructors(123)),
      """{"tag":"TagSingleConstructors","tsField1":123}"""
    )
  }

  "UntaggedValue emits no tag" >> {
    val r = value(JsonOptions.default.copy(sumEncoding = SumEncoding.UntaggedValue)) -: encoders
    val e = r.make[Encoder[UntaggedValueSumEncoding]]
    expectJsonEqual(
      Encoder.encodeString(e, UntaggedValueSumEncoding.UntaggedValueSumEncoding1(123)),
      """{"uvField1":123}"""
    )
  }

  "ObjectWithSingleField wraps under the constructor name" >> {
    val r = value(JsonOptions.default.copy(sumEncoding = SumEncoding.ObjectWithSingleField)) -: encoders
    val e = r.make[Encoder[ObjectWithSingleFieldSumEncoding]]
    expectJsonEqual(
      Encoder.encodeString(e, ObjectWithSingleFieldSumEncoding.ObjectWithSingleFieldSumEncoding1(123)),
      """{"ObjectWithSingleFieldSumEncoding1":{"owsfField1":123}}"""
    )
  }

  "TwoElemArray wraps as [tag, contents]" >> {
    val r = value(JsonOptions.default.copy(sumEncoding = SumEncoding.TwoElemArray)) -: encoders
    val e = r.make[Encoder[TwoElemArraySumEncoding]]
    expectJsonEqual(
      Encoder.encodeString(e, TwoElemArraySumEncoding.TwoElemArraySumEncoding1(123)),
      """["TwoElemArraySumEncoding1",{"teaField1":123}]"""
    )
  }

  "makeEncoder compiles when two fields have the same type" >> {
    // If it compiles the test passes — runtime behavior is not the concern here.
    val _ =
      makeEncoder[Stats] *:
        jsonEncoder[Int] *:
        defaultEncoderOptions
    success
  }

  "encodeMapOf produces a JSON object" >> {
    val r =
      encodeMapOf[Name, Int] *:
        jsonEncoder[Int] *:
        jsonEncoder[String] *:
        KeyEncoder.encodeKey[Name](_.name) *:
        defaultEncoderOptions
    val e = r.make[Encoder[Map[Name, Int]]]
    expectJsonEqual(
      Encoder.encodeString(e, Map(Name("name1") -> 1, Name("name2") -> 2)),
      """{"name1":1,"name2":2}"""
    )
  }

  // ---- shared encoder registry (mirrors aeson's `encoders`) ----

  lazy val encoders =
    makeEncoder[Delivery] *:
      makeEncoder[Person] *:
      makeEncoder[Team] *:
      makeEncoder[Email] *:
      makeEncoder[Identifier] *:
      makeEncoder[DateTime] *:
      makeEncoder[AllNullary] *:
      makeEncoder[FieldLabelModifier] *:
      makeEncoder[ConstructorTagModifier] *:
      makeEncoder[OmitNothingFields] *:
      makeEncoder[UnwrapUnaryRecords] *:
      makeEncoder[TagSingleConstructors] *:
      makeEncoder[UntaggedValueSumEncoding] *:
      makeEncoder[ObjectWithSingleFieldSumEncoding] *:
      makeEncoder[TwoElemArraySumEncoding] *:
      encodeOptionOf[Int] *:
      encodeOptionOf[String] *:
      encodeListOf[Person] *:
      jsonEncoder[String] *:
      jsonEncoder[Int] *:
      defaultEncoderOptions

  // ---- helpers ----

  private def expectJsonEqual(actual: String, expected: String) =
    (circeParser.parse(actual), circeParser.parse(expected)) match
      case (Right(a), Right(e)) => a === e
      case _                    => actual === expected
