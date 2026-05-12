package registry.circe

import io.circe.{Encoder, Decoder}
import io.circe.Json
import org.specs2.mutable.Specification

/**
 * Smoke tests for the primitive `Encoder.{string,int,long,boolean,double}` and matching `Decoder`
 * built-ins. They exist purely as a quality-of-life shortcut over `jsonEncoderOf` / `jsonDecoderOf`,
 * so the contract is just "behaves like the corresponding circe encoder/decoder".
 */
class PrimitiveSpec extends Specification:

  "Encoders.string / Decoders.string" >> {
    Encoders.string("hi") === Json.fromString("hi")
    Decoders.string.decodeJson(Json.fromString("hi")) === Right("hi")
    Decoders.string.decodeJson(Json.fromInt(1)).isLeft must beTrue
  }

  "Encoders.int / Decoders.int" >> {
    Encoders.int(42) === Json.fromInt(42)
    Decoders.int.decodeJson(Json.fromInt(42)) === Right(42)
    Decoders.int.decodeJson(Json.fromString("nope")).isLeft must beTrue
  }

  "Encoders.long / Decoders.long" >> {
    val big = 9_000_000_000L
    Encoders.long(big) === Json.fromLong(big)
    Decoders.long.decodeJson(Json.fromLong(big)) === Right(big)
  }

  "Encoders.boolean / Decoders.boolean" >> {
    Encoders.boolean(true) === Json.fromBoolean(true)
    Decoders.boolean.decodeJson(Json.fromBoolean(false)) === Right(false)
    Decoders.boolean.decodeJson(Json.fromInt(1)).isLeft must beTrue
  }

  "Encoders.double / Decoders.double" >> {
    Encoders.double(1.5) === Json.fromDoubleOrNull(1.5)
    Decoders.double.decodeJson(Json.fromDoubleOrNull(1.5)) === Right(1.5)
  }

  "primitives compose with contramap" >> {
    final case class UserId(value: Int)
    val e: Encoder[UserId] = Encoders.int.contramap(_.value)
    e(UserId(7)) === Json.fromInt(7)
  }

  "registry's Encoder/Decoder ARE io.circe.Encoder/Decoder — no bridge needed" >> {
    val ce: io.circe.Encoder[Int] = Encoders.int
    val cd: io.circe.Decoder[Int] = Decoders.int
    ce(42) === Json.fromInt(42)
    cd.decodeJson(Json.fromInt(42)) === Right(42)
    cd.decodeJson(Json.fromString("nope")).isLeft must beTrue
  }
