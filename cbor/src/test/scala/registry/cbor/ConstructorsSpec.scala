package registry.cbor

import io.bullet.borer.Dom.*
import org.specs2.mutable.Specification

/**
 * Tests the sum-encoding engine ([[ConstructorEncoder]] / [[ConstructorsDecoder]]) in isolation, by
 * feeding it hand-constructed [[FromConstructor]] / [[ConstructorDef]] data. Covers each
 * [[SumEncoding]] branch × relevant option flag without going through the macros.
 */
class ConstructorsSpec extends Specification:

  private val ce = ConstructorEncoder.default
  private val cd = ConstructorsDecoder.default

  // ---- nullary constructors ----

  "nullary constructor — allNullaryToTag=true encodes as the constructor's integer index" >> {
    val fc = FromConstructor(List("A", "B"), Nil, "A", 0, Nil, Nil)
    ce.encodeConstructor(CborOptions.default, fc) === IntElem(0)
  }

  "nullary constructor — StringTags mode encodes as the constructor name" >> {
    val fc = FromConstructor(List("A", "B"), Nil, "A", 0, Nil, Nil)
    val opts = CborOptions.default.copy(constructorTagMode = ConstructorTagMode.StringTags)
    ce.encodeConstructor(opts, fc) === StringElem("A")
  }

  // ---- TwoElemArray (default) ----

  "TwoElemArray — single-field named constructor (single constructor, no tagging)" >> {
    val fc = FromConstructor(List("C"), List("Int"), "C", 0, List("x"), List(IntElem(1)))
    // tagSingleConstructors=false → no tag emitted
    ce.encodeConstructor(CborOptions.default, fc) === MapElem.Sized(IntElem(0) -> IntElem(1))
  }

  "TwoElemArray — multi-constructor sum with a named single field" >> {
    val fc = FromConstructor(List("A", "B"), List("Int"), "A", 0, List("x"), List(IntElem(1)))
    ce.encodeConstructor(CborOptions.default, fc) ===
      ArrayElem.Sized(IntElem(0), MapElem.Sized(IntElem(0) -> IntElem(1)))
  }

  "TwoElemArray — sum with single positional field wraps directly" >> {
    val fc = FromConstructor(List("A", "B"), List("Int"), "B", 1, Nil, List(IntElem(42)))
    ce.encodeConstructor(CborOptions.default, fc) === ArrayElem.Sized(IntElem(1), IntElem(42))
  }

  // ---- Untagged ----

  "Untagged — named single-field constructor emits just the map" >> {
    val fc = FromConstructor(List("A", "B"), List("Int"), "A", 0, List("x"), List(IntElem(1)))
    val opts = CborOptions.default.copy(sumEncoding = SumEncoding.Untagged)
    ce.encodeConstructor(opts, fc) === MapElem.Sized(IntElem(0) -> IntElem(1))
  }

  // ---- SingleKeyMap ----

  "SingleKeyMap — wraps a named-fields record" >> {
    val fc = FromConstructor(List("A", "B"), List("Int"), "A", 0, List("x"), List(IntElem(1)))
    val opts = CborOptions.default.copy(sumEncoding = SumEncoding.SingleKeyMap)
    ce.encodeConstructor(opts, fc) ===
      MapElem.Sized(IntElem(0) -> MapElem.Sized(IntElem(0) -> IntElem(1)))
  }

  // ---- omitNothingFields ----

  "omitNothingFields drops null values while preserving remaining integer keys" >> {
    val fc = FromConstructor(
      List("A"),
      List("Option[Int]", "Int"),
      "A",
      0,
      List("opt", "x"),
      List(NullElem, IntElem(1))
    )
    val opts = CborOptions.default.copy(omitNothingFields = true)
    ce.encodeConstructor(opts, fc) === MapElem.Sized(IntElem(1) -> IntElem(1))
  }

  // ---- unwrapUnaryRecords ----

  "unwrapUnaryRecords encodes a unary-record as the inner value" >> {
    val fc = FromConstructor(List("A"), List("Int"), "A", 0, List("x"), List(IntElem(1)))
    val opts = CborOptions.default.copy(unwrapUnaryRecords = true)
    ce.encodeConstructor(opts, fc) === IntElem(1)
  }

  // ---- Decoder side ----

  "TwoElemArray decoder picks the right constructor + extracts named field" >> {
    val defs = List(
      ConstructorDef("A", List("x"), List("Int")),
      ConstructorDef("B", List("y"), List("String"))
    )
    val elem = ArrayElem.Sized(IntElem(0), MapElem.Sized(IntElem(0) -> IntElem(7)))
    cd.decodeConstructors(CborOptions.default, defs, elem) must beLike { case Right(List(tc)) =>
      tc.constructorName === "A"
      tc.values.map(_._2) === List(IntElem(7))
    }
  }

  "TwoElemArray decoder fails with an expected-tag error when the tag value is unknown" >> {
    val defs = List(
      ConstructorDef("A", List("x"), List("Int")),
      ConstructorDef("B", List("y"), List("String"))
    )
    val elem = ArrayElem.Sized(IntElem(99), MapElem.Sized(IntElem(0) -> IntElem(7)))
    cd.decodeConstructors(CborOptions.default, defs, elem) must beLeft.like { case err =>
      err.message must contain("expected the tag to be one of: 0, 1, found: 99")
    }
  }

  "Enumeration decoder (all nullary, allNullaryToTag, IntegerTags) succeeds from a plain integer" >> {
    val defs = List(
      ConstructorDef("A", Nil, Nil),
      ConstructorDef("B", Nil, Nil)
    )
    cd.decodeConstructors(CborOptions.default, defs, IntElem(1)) must beLike { case Right(List(tc)) =>
      tc.constructorName === "B"
    }
  }
