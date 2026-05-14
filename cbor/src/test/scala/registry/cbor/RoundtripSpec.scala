package registry.cbor

import io.bullet.borer.{Cbor, Decoder, Encoder}
import org.specs2.mutable.Specification
import registry.{Person as _, *}
import registry.cbor.DataTypes.*

/** Encode → bytes → decode equals the original, for every nontrivial type. */
class RoundtripSpec extends Specification:

  "Identifier" >> { roundtrip(Identifier(7)) === Identifier(7) }

  "Email" >> { roundtrip(email1) === email1 }

  "DateTime" >> { roundtrip(datetime1) === datetime1 }

  "Person" >> { roundtrip(person1) === person1 }

  "Delivery NoDelivery" >> { roundtrip[Delivery](delivery0) === delivery0 }

  "Delivery ByEmail" >> { roundtrip[Delivery](delivery1) === delivery1 }

  "Delivery InPerson" >> { roundtrip[Delivery](delivery2) === delivery2 }

  "AllNullary value" >> { roundtrip[AllNullary](AllNullary.AllNullary1) === AllNullary.AllNullary1 }

  "OmitNothingFields with omitNothingFields=true" >> {
    val opts = value(CborOptions.default.copy(omitNothingFields = true))
    val enc = (opts -: encoders).make[Encoder[OmitNothingFields]]
    val dec = (opts -: decoders).make[Decoder[OmitNothingFields]]
    val v = OmitNothingFields.OmitNothingFields1(None, 7)
    decode(dec, encode(enc, v)) === v
  }

  "Team with empty members and Some leaderName" >> {
    val t = Team("alpha", List(person1, person1), Some("captain"))
    roundtrip(t) === t
  }

  "Team with None leaderName" >> {
    val t = Team("beta", Nil, None)
    roundtrip(t) === t
  }

  // ---- helpers ----

  private def roundtrip[A](a: A)(using
      encTag: izumi.reflect.Tag[Encoder[A]],
      decTag: izumi.reflect.Tag[Decoder[A]]
  ): A =
    val enc = encoders.make[Encoder[A]]
    val dec = decoders.make[Decoder[A]]
    decode(dec, encode(enc, a))

  private def encode[A](e: Encoder[A], a: A): Array[Byte] = Cbor.encode(a)(using e).toByteArray

  private def decode[A](d: Decoder[A], bs: Array[Byte]): A =
    Cbor.decode(bs).to[A](using d).value

  lazy val encoders =
    encoder[Delivery] *:
      encoder[Team] *:
      encoder[Person] *:
      encoder[Email] *:
      encoder[Identifier] *:
      encoder[DateTime] *:
      encoder[AllNullary] *:
      encoder[OmitNothingFields] *:
      encodeOptionOf[Int] *:
      encodeOptionOf[String] *:
      encodeListOf[Person] *:
      encoderOf[String] *:
      encoderOf[Int] *:
      defaultEncoderOptions

  lazy val decoders =
    decoder[Delivery] *:
      decoder[Team] *:
      decodeListOf[Person] *:
      decoder[Person] *:
      decoder[Email] *:
      decoder[Identifier] *:
      decoder[DateTime] *:
      decoder[AllNullary] *:
      decoder[OmitNothingFields] *:
      decodeOptionOf[Int] *:
      decodeOptionOf[String] *:
      decoderOf[String] *:
      decoderOf[Int] *:
      defaultDecoderOptions
