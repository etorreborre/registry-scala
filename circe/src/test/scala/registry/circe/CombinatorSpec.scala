package registry.circe

import io.circe.{Encoder, Decoder}
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
      encoderOf[Int] *:
        decoderOf[Int]

    val e = r.make[Encoder[Int]]
    val d = r.make[Decoder[Int]]

    e(42) === Json.fromInt(42)
    d.decodeJson(Json.fromInt(42)) === Right(42)
  }

  "encodeListOf / decodeListOf" >> {
    val r =
      encodeListOf[Int] *:
        decodeListOf[Int] *:
        encoderOf[Int] *:
        decoderOf[Int]

    val e = r.make[Encoder[List[Int]]]
    val d = r.make[Decoder[List[Int]]]

    e(List(1, 2, 3)) === Json.arr(Json.fromInt(1), Json.fromInt(2), Json.fromInt(3))
    d.decodeJson(Json.arr(Json.fromInt(1), Json.fromInt(2))) === Right(List(1, 2))
  }

  "encodeOptionOf / decodeOptionOf" >> {
    val r =
      encodeOptionOf[Int] *:
        decodeOptionOf[Int] *:
        encoderOf[Int] *:
        decoderOf[Int]

    val e = r.make[Encoder[Option[Int]]]
    val d = r.make[Decoder[Option[Int]]]

    e(None) === Json.Null
    e(Some(7)) === Json.fromInt(7)
    d.decodeJson(Json.Null) === Right(None)
    d.decodeJson(Json.fromInt(7)) === Right(Some(7))
  }

  "encodeMapOf / decodeMapOf with String keys" >> {
    val r =
      encodeMapOf[String, Int] *:
        decodeMapOf[String, Int] *:
        keyEncoderOf[String] *:
        keyDecoderOf[String] *:
        encoderOf[Int] *:
        decoderOf[Int]

    val e = r.make[Encoder[Map[String, Int]]]
    val d = r.make[Decoder[Map[String, Int]]]

    val encoded = e(Map("a" -> 1, "b" -> 2))
    encoded.hcursor.downField("a").as[Int].toOption === Some(1)
    encoded.hcursor.downField("b").as[Int].toOption === Some(2)

    d.decodeJson(encoded) === Right(Map("a" -> 1, "b" -> 2))
  }

  "encodePairOf / decodePairOf" >> {
    val r =
      encodePairOf[Int, String] *:
        decodePairOf[Int, String] *:
        encoderOf[Int] *:
        decoderOf[Int] *:
        encoderOf[String] *:
        decoderOf[String]

    val e = r.make[Encoder[(Int, String)]]
    val d = r.make[Decoder[(Int, String)]]

    val j = Json.arr(Json.fromInt(1), Json.fromString("a"))
    e((1, "a")) === j
    d.decodeJson(j) === Right((1, "a"))
  }

  "encodeTreeMapOf / decodeTreeMapOf preserve key order" >> {
    import scala.collection.immutable.TreeMap
    val r =
      encodeTreeMapOf[String, Int] *:
        decodeTreeMapOf[String, Int] *:
        keyEncoderOf[String] *:
        keyDecoderOf[String] *:
        encoderOf[Int] *:
        decoderOf[Int]

    val e = r.make[Encoder[TreeMap[String, Int]]]
    val d = r.make[Decoder[TreeMap[String, Int]]]

    val tm = TreeMap("b" -> 2, "a" -> 1, "c" -> 3)
    val encoded = e(tm)
    encoded.asObject.map(_.keys.toList) === Some(List("a", "b", "c"))
    d.decodeJson(encoded) === Right(tm)
  }
