package registry.circe

import io.circe.Json
import org.specs2.mutable.Specification

/**
 * Tests the sum-encoding engine ([[ConstructorEncoder]] / [[ConstructorsDecoder]]) in isolation, by
 * feeding it hand-constructed [[FromConstructor]] / [[ConstructorDef]] data. This covers every
 * [[SumEncoding]] branch × relevant option flag without relying on the macros to generate the metadata.
 */
class ConstructorsSpec extends Specification:

  private val ce = ConstructorEncoder.default
  private val cd = ConstructorsDecoder.default

  // ---- nullary constructors ----

  "nullary constructor — allNullaryToStringTag=true encodes as a string" >> {
    val fc = FromConstructor(List("A", "B"), Nil, "A", Nil, Nil)
    ce.encodeConstructor(JsonOptions.default, fc) === Json.fromString("A")
  }

  "nullary constructor — allNullaryToStringTag=false falls back to sum encoding" >> {
    val fc = FromConstructor(List("A", "B"), Nil, "A", Nil, Nil)
    val opts = JsonOptions.default.copy(allNullaryToStringTag = false)
    val j = ce.encodeConstructor(opts, fc)
    j.hcursor.downField("tag").as[String].toOption === Some("A")
  }

  // ---- TaggedObject (default) ----

  "TaggedObject — single-field named constructor" >> {
    val fc = FromConstructor(List("C"), List("Int"), "C", List("x"), List(Json.fromInt(1)))
    // tagSingleConstructors defaults to false, so no tag is emitted for single-constructor types
    ce.encodeConstructor(JsonOptions.default, fc) ===
      Json.obj("x" -> Json.fromInt(1))
  }

  "TaggedObject — multi-constructor sum with a named single field" >> {
    val fc = FromConstructor(List("A", "B"), List("Int"), "A", List("x"), List(Json.fromInt(1)))
    ce.encodeConstructor(JsonOptions.default, fc) ===
      Json.obj("tag" -> Json.fromString("A"), "x" -> Json.fromInt(1))
  }

  "TaggedObject — sum with single positional field uses contents" >> {
    val fc = FromConstructor(List("A", "B"), List("Int"), "B", Nil, List(Json.fromInt(42)))
    ce.encodeConstructor(JsonOptions.default, fc) ===
      Json.obj("tag" -> Json.fromString("B"), "contents" -> Json.fromInt(42))
  }

  "TaggedObject — sum with multi positional fields uses contents array" >> {
    val fc = FromConstructor(List("A", "B"), List("Int", "String"), "B", Nil, List(Json.fromInt(42), Json.fromString("x")))
    ce.encodeConstructor(JsonOptions.default, fc) ===
      Json.obj("tag" -> Json.fromString("B"), "contents" -> Json.arr(Json.fromInt(42), Json.fromString("x")))
  }

  // ---- UntaggedValue ----

  "UntaggedValue — named single-field constructor emits just the object" >> {
    val fc = FromConstructor(List("A", "B"), List("Int"), "A", List("x"), List(Json.fromInt(1)))
    val opts = JsonOptions.default.copy(sumEncoding = SumEncoding.UntaggedValue)
    ce.encodeConstructor(opts, fc) === Json.obj("x" -> Json.fromInt(1))
  }

  // ---- ObjectWithSingleField ----

  "ObjectWithSingleField — wraps named-fields record" >> {
    val fc = FromConstructor(List("A", "B"), List("Int"), "A", List("x"), List(Json.fromInt(1)))
    val opts = JsonOptions.default.copy(sumEncoding = SumEncoding.ObjectWithSingleField)
    ce.encodeConstructor(opts, fc) ===
      Json.obj("A" -> Json.obj("x" -> Json.fromInt(1)))
  }

  // ---- TwoElemArray ----

  "TwoElemArray — wraps named-fields record" >> {
    val fc = FromConstructor(List("A", "B"), List("Int"), "A", List("x"), List(Json.fromInt(1)))
    val opts = JsonOptions.default.copy(sumEncoding = SumEncoding.TwoElemArray)
    ce.encodeConstructor(opts, fc) ===
      Json.arr(Json.fromString("A"), Json.obj("x" -> Json.fromInt(1)))
  }

  // ---- fieldLabelModifier / constructorTagModifier ----

  "fieldLabelModifier rewrites field names" >> {
    val fc = FromConstructor(List("A"), List("Int"), "A", List("x"), List(Json.fromInt(1)))
    val opts = JsonOptions.default.copy(fieldLabelModifier = "__" + _)
    ce.encodeConstructor(opts, fc) === Json.obj("__x" -> Json.fromInt(1))
  }

  "constructorTagModifier rewrites the constructor tag" >> {
    val fc = FromConstructor(List("A", "B"), List("Int"), "A", List("x"), List(Json.fromInt(1)))
    val opts = JsonOptions.default.copy(constructorTagModifier = "__" + _)
    ce.encodeConstructor(opts, fc) ===
      Json.obj("tag" -> Json.fromString("__A"), "x" -> Json.fromInt(1))
  }

  // ---- omitNothingFields ----

  "omitNothingFields drops null values" >> {
    val fc = FromConstructor(List("A"), List("Option[Int]", "Int"), "A", List("opt", "x"), List(Json.Null, Json.fromInt(1)))
    val opts = JsonOptions.default.copy(omitNothingFields = true)
    ce.encodeConstructor(opts, fc) === Json.obj("x" -> Json.fromInt(1))
  }

  // ---- unwrapUnaryRecords ----

  "unwrapUnaryRecords encodes a unary-record as the inner value" >> {
    val fc = FromConstructor(List("A"), List("Int"), "A", List("x"), List(Json.fromInt(1)))
    val opts = JsonOptions.default.copy(unwrapUnaryRecords = true)
    ce.encodeConstructor(opts, fc) === Json.fromInt(1)
  }

  // ---- Decoder side: roundtrip via TaggedObject ----

  "TaggedObject decoder picks the right constructor + extracts named field" >> {
    val defs = List(
      ConstructorDef("A", List("x"), List("Int")),
      ConstructorDef("B", List("y"), List("String"))
    )
    val j = Json.obj("tag" -> Json.fromString("A"), "x" -> Json.fromInt(7))

    cd.decodeConstructors(JsonOptions.default, defs, j) must beLike:
      case Right(List(tc)) =>
        tc.constructorName === "A"
        tc.values.map(_._2) === List(Json.fromInt(7))
  }

  "TaggedObject decoder fails with an expected-tag error when the tag value is unknown" >> {
    val defs = List(
      ConstructorDef("A", List("x"), List("Int")),
      ConstructorDef("B", List("y"), List("String"))
    )
    val j = Json.obj("tag" -> Json.fromString("Z"))

    cd.decodeConstructors(JsonOptions.default, defs, j) must beLeft.like { case err =>
      err must contain("expected the tag field to be one of: A, B, found: Z")
    }
  }

  "Enumeration decoder (all nullary, allNullaryToStringTag) succeeds from a plain string" >> {
    val defs = List(
      ConstructorDef("A", Nil, Nil),
      ConstructorDef("B", Nil, Nil)
    )
    cd.decodeConstructors(JsonOptions.default, defs, Json.fromString("B")) must beLike:
      case Right(List(tc)) => tc.constructorName === "B"
  }
