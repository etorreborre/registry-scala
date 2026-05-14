package registry.cbor

import io.bullet.borer.{Cbor, Dom}
import io.bullet.borer.Dom.*
import org.specs2.mutable.Specification
import registry.*

/** Primitive `Encoder` / `Decoder` round-trips on simple scalar values. */
class PrimitiveSpec extends Specification:

  "encode/decode a String" >> {
    Encoders.encodeByteString(Encoders.string, "hi")
    val bytes = Encoders.encodeByteString(Encoders.string, "hi")
    Decoders.decodeByteString(Decoders.string, bytes) === Right("hi")
  }

  "encode/decode an Int" >> {
    val bytes = Encoders.encodeByteString(Encoders.int, 42)
    Decoders.decodeByteString(Decoders.int, bytes) === Right(42)
  }

  "encode/decode a Long" >> {
    val bytes = Encoders.encodeByteString(Encoders.long, 99L)
    Decoders.decodeByteString(Decoders.long, bytes) === Right(99L)
  }

  "encode/decode a Boolean" >> {
    val bytes = Encoders.encodeByteString(Encoders.boolean, true)
    Decoders.decodeByteString(Decoders.boolean, bytes) === Right(true)
  }

  "encode/decode a Double" >> {
    val bytes = Encoders.encodeByteString(Encoders.double, 1.5)
    Decoders.decodeByteString(Decoders.double, bytes) === Right(1.5)
  }

  "encode/decode a Byte" >> {
    val bytes = Encoders.encodeByteString(Encoders.byte, 7.toByte)
    Decoders.decodeByteString(Decoders.byte, bytes) === Right(7.toByte)
  }

  "encode/decode a BigInt" >> {
    val bigPositive = BigInt("12345678901234567890")
    val bytes = Encoders.encodeByteString(Encoders.bigInt, bigPositive)
    Decoders.decodeByteString(Decoders.bigInt, bytes) === Right(bigPositive)
  }

  "encode an Int as a Dom.IntElem when round-tripped through Dom.Element" >> {
    val bytes = Encoders.encodeByteString(Encoders.int, 7)
    val asElem = Cbor.decode(bytes).to[Dom.Element].value
    asElem === IntElem(7)
  }
