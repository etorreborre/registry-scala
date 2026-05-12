package registry.circe

import io.circe.Json
import org.specs2.mutable.Specification

/**
 * Smoke tests for the primitive `Encoder.{string,int,long,boolean,double}` and matching `Decoder`
 * built-ins. They exist purely as a quality-of-life shortcut over `jsonEncoderOf` / `jsonDecoderOf`,
 * so the contract is just "behaves like the corresponding circe encoder/decoder".
 */
class PrimitiveSpec extends Specification:

  "Encoder.string / Decoder.string" >> {
    Encoder.string.encode("hi") === Json.fromString("hi")
    Decoder.string.decodeJson(Json.fromString("hi")) === Right("hi")
    Decoder.string.decodeJson(Json.fromInt(1)) must beLeft.like { case f => f.message === "String" }
  }

  "Encoder.int / Decoder.int" >> {
    Encoder.int.encode(42) === Json.fromInt(42)
    Decoder.int.decodeJson(Json.fromInt(42)) === Right(42)
    Decoder.int.decodeJson(Json.fromString("nope")) must beLeft.like { case f => f.message === "Int" }
  }

  "Encoder.long / Decoder.long" >> {
    val big = 9_000_000_000L
    Encoder.long.encode(big) === Json.fromLong(big)
    Decoder.long.decodeJson(Json.fromLong(big)) === Right(big)
  }

  "Encoder.boolean / Decoder.boolean" >> {
    Encoder.boolean.encode(true) === Json.fromBoolean(true)
    Decoder.boolean.decodeJson(Json.fromBoolean(false)) === Right(false)
    Decoder.boolean.decodeJson(Json.fromInt(1)) must beLeft.like { case f => f.message === "Boolean" }
  }

  "Encoder.double / Decoder.double" >> {
    Encoder.double.encode(1.5) === Json.fromDoubleOrNull(1.5)
    Decoder.double.decodeJson(Json.fromDoubleOrNull(1.5)) === Right(1.5)
  }

  "primitives compose with contramap" >> {
    final case class UserId(value: Int)
    val e: Encoder[UserId] = Encoder.int.contramap(_.value)
    e.encode(UserId(7)) === Json.fromInt(7)
  }

  "asCirce bridges registry encoder/decoder to io.circe" >> {
    val ce: io.circe.Encoder[Int] = Encoder.int.asCirce
    val cd: io.circe.Decoder[Int] = Decoder.int.asCirce
    ce(42) === Json.fromInt(42)
    cd.decodeJson(Json.fromInt(42)) === Right(42)
    cd.decodeJson(Json.fromString("nope")).isLeft must beTrue
  }
