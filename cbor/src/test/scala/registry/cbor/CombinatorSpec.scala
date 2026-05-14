package registry.cbor

import io.bullet.borer.{Cbor, Decoder, Dom, Encoder}
import io.bullet.borer.Dom.*
import org.specs2.mutable.Specification
import registry.*

/**
 * Smoke tests for the combinator + bridge layer (no macros). Verifies that:
 *  - `encoderOf` / `decoderOf` bridge a borer instance into the registry
 *  - `encodeListOf` / `decodeListOf` resolve correctly and are runtime-applied
 *  - `encodeMapOf` / `decodeMapOf` use the registered `Encoder[K]` / `Decoder[K]`
 *  - `encodeOptionOf` / `decodeOptionOf` handle CBOR null <-> None
 */
class CombinatorSpec extends Specification:

  "encoder / decoder bridges" >> {
    val r =
      encoderOf[Int] *:
        decoderOf[Int]

    val e = r.make[Encoder[Int]]
    val d = r.make[Decoder[Int]]

    roundtripVia(e, d, 42) === 42
    domOf(e, 42) === IntElem(42)
  }

  "encodeListOf / decodeListOf" >> {
    val r =
      encodeListOf[Int] *:
        decodeListOf[Int] *:
        encoderOf[Int] *:
        decoderOf[Int]

    val e = r.make[Encoder[List[Int]]]
    val d = r.make[Decoder[List[Int]]]

    roundtripVia(e, d, List(1, 2, 3)) === List(1, 2, 3)
    domOf(e, List(1, 2, 3)) === ArrayElem.Sized(IntElem(1), IntElem(2), IntElem(3))
  }

  "encodeOptionOf / decodeOptionOf null <-> None" >> {
    val r =
      encodeOptionOf[Int] *:
        decodeOptionOf[Int] *:
        encoderOf[Int] *:
        decoderOf[Int]

    val e = r.make[Encoder[Option[Int]]]
    val d = r.make[Decoder[Option[Int]]]

    domOf(e, None: Option[Int]) === NullElem
    domOf(e, Some(7)) === IntElem(7)
    roundtripVia(e, d, None: Option[Int]) === None
    roundtripVia(e, d, Some(7)) === Some(7)
  }

  "encodeMapOf / decodeMapOf with String keys" >> {
    val r =
      encodeMapOf[String, Int] *:
        decodeMapOf[String, Int] *:
        encoderOf[String] *:
        decoderOf[String] *:
        encoderOf[Int] *:
        decoderOf[Int]

    val e = r.make[Encoder[Map[String, Int]]]
    val d = r.make[Decoder[Map[String, Int]]]

    val m = Map("a" -> 1, "b" -> 2)
    roundtripVia(e, d, m) === m
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

    domOf(e, (1, "a")) === ArrayElem.Sized(IntElem(1), StringElem("a"))
    roundtripVia(e, d, (1, "a")) === ((1, "a"))
  }

  "encodeTreeMapOf / decodeTreeMapOf preserve key order" >> {
    import scala.collection.immutable.TreeMap
    val r =
      encodeTreeMapOf[String, Int] *:
        decodeTreeMapOf[String, Int] *:
        encoderOf[String] *:
        decoderOf[String] *:
        encoderOf[Int] *:
        decoderOf[Int]

    val e = r.make[Encoder[TreeMap[String, Int]]]
    val d = r.make[Decoder[TreeMap[String, Int]]]

    val tm = TreeMap("b" -> 2, "a" -> 1, "c" -> 3)
    val encoded = domOf(e, tm)
    encoded match
      case m: MapElem => m.stringKeyedMembers.map(_._1).toList === List("a", "b", "c")
      case other      => failure(s"expected MapElem, got: $other")
    roundtripVia(e, d, tm) === tm
  }

  // ---- helpers ----

  private def domOf[A](e: Encoder[A], a: A): Dom.Element =
    val bytes = Cbor.encode(a)(using e).toByteArray
    Cbor.decode(bytes).to[Dom.Element].value

  private def roundtripVia[A](e: Encoder[A], d: Decoder[A], a: A): A =
    val bytes = Cbor.encode(a)(using e).toByteArray
    Cbor.decode(bytes).to[A](using d).value
