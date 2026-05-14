package registry.cbor

import io.bullet.borer.{Cbor, Decoder, Dom, Encoder}
import io.bullet.borer.Dom.*
import org.specs2.mutable.Specification
import registry.*

class MacroSpec extends Specification:

  "encoder / decoder on a single-field case class" >> {
    val r =
      encoder[Identifier] *:
        decoder[Identifier] *:
        encoderOf[Int] *:
        decoderOf[Int] *:
        defaultEncoderOptions <+>
        defaultDecoderOptions

    val e = r.make[Encoder[Identifier]]
    val d = r.make[Decoder[Identifier]]

    domOf(e, Identifier(1)) === MapElem.Sized(IntElem(0) -> IntElem(1))
    roundtripVia(e, d, Identifier(1)) === Identifier(1)
  }

  "encoder / decoder on a multi-field case class" >> {
    val r =
      encoder[Person] *:
        decoder[Person] *:
        encoder[Identifier] *:
        decoder[Identifier] *:
        encoder[Email] *:
        decoder[Email] *:
        encoderOf[Int] *:
        decoderOf[Int] *:
        encoderOf[String] *:
        decoderOf[String] *:
        defaultEncoderOptions <+>
        defaultDecoderOptions

    val e = r.make[Encoder[Person]]
    val d = r.make[Decoder[Person]]

    val p = Person(Identifier(1), Email("me@here.com"))
    domOf(e, p) === MapElem.Sized(
      IntElem(0) -> MapElem.Sized(IntElem(0) -> IntElem(1)),
      IntElem(1) -> MapElem.Sized(IntElem(0) -> StringElem("me@here.com"))
    )
    roundtripVia(e, d, p) === p
  }

  "encoder / decoder on a sealed trait with mixed constructors" >> {
    val r =
      encoder[Delivery] *:
        decoder[Delivery] *:
        encoder[Person] *:
        decoder[Person] *:
        encoder[Identifier] *:
        decoder[Identifier] *:
        encoder[Email] *:
        decoder[Email] *:
        encoderOf[Int] *:
        decoderOf[Int] *:
        encoderOf[String] *:
        decoderOf[String] *:
        defaultEncoderOptions <+>
        defaultDecoderOptions

    val e = r.make[Encoder[Delivery]]
    val d = r.make[Decoder[Delivery]]

    roundtripVia(e, d, Delivery.NoDelivery) === Delivery.NoDelivery
    val be = Delivery.ByEmail(Email("x@y.z"))
    roundtripVia(e, d, be) === be
  }

  "encoder[Wrapper[Int]] on a generic class substitutes the type parameter in fields" >> {
    val r =
      encoder[Wrapper[Int]] *:
        decoder[Wrapper[Int]] *:
        encoderOf[Int] *:
        decoderOf[Int] *:
        defaultEncoderOptions <+>
        defaultDecoderOptions

    val e = r.make[Encoder[Wrapper[Int]]]
    val d = r.make[Decoder[Wrapper[Int]]]
    roundtripVia(e, d, Wrapper(7)) === Wrapper(7)
  }

  "value-driven encoder(S => T) — single-arg function → contramap mode" >> {
    final case class UserId(value: Long)

    val r =
      encoder((_: UserId).value) *:
        decoder((l: Long) => UserId(l)) *:
        encoderOf[Long] *:
        decoderOf[Long]

    val e = r.make[Encoder[UserId]]
    val d = r.make[Decoder[UserId]]

    domOf(e, UserId(42L)) === IntElem(42)
    roundtripVia(e, d, UserId(42L)) === UserId(42L)
  }

  // ---- helpers ----

  private def domOf[A](e: Encoder[A], a: A): Dom.Element =
    val bytes = Cbor.encode(a)(using e).toByteArray
    Cbor.decode(bytes).to[Dom.Element].value

  private def roundtripVia[A](e: Encoder[A], d: Decoder[A], a: A): A =
    val bytes = Cbor.encode(a)(using e).toByteArray
    Cbor.decode(bytes).to[A](using d).value

case class Identifier(value: Int)
case class Email(email: String)
case class Person(identifier: Identifier, email: Email)

final case class Wrapper[A](value: A)

enum Delivery:
  case NoDelivery
  case ByEmail(email: Email)
  case InPerson(person: Person, at: String)
