package registry.circe

import io.circe.Json
import org.specs2.mutable.Specification
import registry.*

/**
 * Smoke tests for the combinator + bridge layer (no macros). Verifies that:
 *  - [[jsonEncoder]] / [[jsonDecoder]] bridge a circe instance into the registry
 *  - [[encodeListOf]] / [[decodeListOf]] resolve correctly and are runtime-applied
 *  - [[encodeMapOf]] / [[decodeMapOf]] use the registered [[KeyEncoder]] / [[KeyDecoder]]
 *  - [[encodeOptionOf]] / [[decodeOptionOf]] handle null <-> None
 */
class CombinatorSpec extends Specification:

  "jsonEncoder / jsonDecoder bridges" >> {
    val r =
      jsonEncoder[Int] *:
        jsonDecoder[Int]

    val e = r.make[Encoder[Int]]
    val d = r.make[Decoder[Int]]

    e.encode(42) === Json.fromInt(42)
    d.decode(Json.fromInt(42)) === Right(42)
  }

  "encodeListOf / decodeListOf" >> {
    val r =
      encodeListOf[Int] *:
        decodeListOf[Int] *:
        jsonEncoder[Int] *:
        jsonDecoder[Int]

    val e = r.make[Encoder[List[Int]]]
    val d = r.make[Decoder[List[Int]]]

    e.encode(List(1, 2, 3)) === Json.arr(Json.fromInt(1), Json.fromInt(2), Json.fromInt(3))
    d.decode(Json.arr(Json.fromInt(1), Json.fromInt(2))) === Right(List(1, 2))
  }

  "encodeOptionOf / decodeOptionOf" >> {
    val r =
      encodeOptionOf[Int] *:
        decodeOptionOf[Int] *:
        jsonEncoder[Int] *:
        jsonDecoder[Int]

    val e = r.make[Encoder[Option[Int]]]
    val d = r.make[Decoder[Option[Int]]]

    e.encode(None) === Json.Null
    e.encode(Some(7)) === Json.fromInt(7)
    d.decode(Json.Null) === Right(None)
    d.decode(Json.fromInt(7)) === Right(Some(7))
  }

  "encodeMapOf / decodeMapOf with String keys" >> {
    val r =
      encodeMapOf[String, Int] *:
        decodeMapOf[String, Int] *:
        bridgeKeyEncoder[String] *:
        bridgeKeyDecoder[String] *:
        jsonEncoder[Int] *:
        jsonDecoder[Int]

    val e = r.make[Encoder[Map[String, Int]]]
    val d = r.make[Decoder[Map[String, Int]]]

    val encoded = e.encode(Map("a" -> 1, "b" -> 2))
    encoded.hcursor.downField("a").as[Int].toOption === Some(1)
    encoded.hcursor.downField("b").as[Int].toOption === Some(2)

    d.decode(encoded) === Right(Map("a" -> 1, "b" -> 2))
  }

  "encodePairOf / decodePairOf" >> {
    val r =
      encodePairOf[Int, String] *:
        decodePairOf[Int, String] *:
        jsonEncoder[Int] *:
        jsonDecoder[Int] *:
        jsonEncoder[String] *:
        jsonDecoder[String]

    val e = r.make[Encoder[(Int, String)]]
    val d = r.make[Decoder[(Int, String)]]

    val j = Json.arr(Json.fromInt(1), Json.fromString("a"))
    e.encode((1, "a")) === j
    d.decode(j) === Right((1, "a"))
  }
