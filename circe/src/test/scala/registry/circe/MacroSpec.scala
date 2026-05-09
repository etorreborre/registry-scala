package registry.circe

import io.circe.Json
import org.specs2.mutable.Specification
import registry.*

class MacroSpec extends Specification:

  "makeEncoder / makeDecoder on a single-field case class" >> {
    val r =
      makeEncoder[Identifier] *:
        makeDecoder[Identifier] *:
        jsonEncoder[Int] *:
        jsonDecoder[Int] *:
        defaultEncoderOptions <+>
        defaultDecoderOptions

    val e = r.make[Encoder[Identifier]]
    val d = r.make[Decoder[Identifier]]

    // single-constructor, single named field => default drops the wrapper and emits {"value": 1}
    val encoded = e.encode(Identifier(1))
    encoded === Json.obj("value" -> Json.fromInt(1))

    d.decode(encoded) === Right(Identifier(1))
  }

  "makeEncoder / makeDecoder on a multi-field case class" >> {
    val r =
      makeEncoder[Person] *:
        makeDecoder[Person] *:
        makeEncoder[Identifier] *:
        makeDecoder[Identifier] *:
        makeEncoder[Email] *:
        makeDecoder[Email] *:
        jsonEncoder[Int] *:
        jsonDecoder[Int] *:
        jsonEncoder[String] *:
        jsonDecoder[String] *:
        defaultEncoderOptions <+>
        defaultDecoderOptions

    val e = r.make[Encoder[Person]]
    val d = r.make[Decoder[Person]]

    val p = Person(Identifier(1), Email("me@here.com"))
    val encoded = e.encode(p)
    encoded === Json.obj(
      "identifier" -> Json.obj("value" -> Json.fromInt(1)),
      "email" -> Json.obj("email" -> Json.fromString("me@here.com"))
    )

    d.decode(encoded) === Right(p)
  }

  "makeEncoder / makeDecoder on a sealed trait with mixed constructors" >> {
    val r =
      makeEncoder[Delivery] *:
        makeDecoder[Delivery] *:
        makeEncoder[Person] *:
        makeDecoder[Person] *:
        makeEncoder[Identifier] *:
        makeDecoder[Identifier] *:
        makeEncoder[Email] *:
        makeDecoder[Email] *:
        jsonEncoder[Int] *:
        jsonDecoder[Int] *:
        jsonEncoder[String] *:
        jsonDecoder[String] *:
        defaultEncoderOptions <+>
        defaultDecoderOptions

    val e = r.make[Encoder[Delivery]]
    val d = r.make[Decoder[Delivery]]

    // NoDelivery is a nullary case object — allNullaryToStringTag is false when the sum has mixed
    // constructors, so it falls back to TaggedObject.
    val noDeliveryEncoded = e.encode(Delivery.NoDelivery)
    noDeliveryEncoded === Json.obj("tag" -> Json.fromString("NoDelivery"))
    d.decode(noDeliveryEncoded) === Right(Delivery.NoDelivery)

    // ByEmail has a named field (`email`) — Scala enum cases always name their fields — so the JSON
    // inlines it into the tagged object rather than wrapping in "contents".
    val byEmailEncoded = e.encode(Delivery.ByEmail(Email("x@y.z")))
    byEmailEncoded === Json.obj(
      "tag" -> Json.fromString("ByEmail"),
      "email" -> Json.obj("email" -> Json.fromString("x@y.z"))
    )
    d.decode(byEmailEncoded) === Right(Delivery.ByEmail(Email("x@y.z")))
  }

  "makeEncoder[Wrapper[Int]] on a generic class substitutes the type parameter in fields" >> {
    val r =
      makeEncoder[Wrapper[Int]] *:
        makeDecoder[Wrapper[Int]] *:
        jsonEncoder[Int] *:
        jsonDecoder[Int] *:
        defaultEncoderOptions <+>
        defaultDecoderOptions

    val e = r.make[Encoder[Wrapper[Int]]]
    val d = r.make[Decoder[Wrapper[Int]]]
    e.encode(Wrapper(7)) === Json.obj("value" -> Json.fromInt(7))
    d.decode(Json.obj("value" -> Json.fromInt(7))) === Right(Wrapper(7))
  }

  "value-driven makeEncoder(S => T) — single-arg function → contramap mode" >> {
    final case class UserId(value: Long)

    val r =
      makeEncoder((_: UserId).value) *:
        makeDecoder((l: Long) => UserId(l)) *:
        jsonEncoder[Long] *:
        jsonDecoder[Long]

    val e = r.make[Encoder[UserId]]
    val d = r.make[Decoder[UserId]]

    // Single-arg function dispatch — Encoder[UserId] derived from Encoder[Long] via contramap.
    e.encode(UserId(42L)) === Json.fromLong(42L)
    d.decode(Json.fromLong(42L)) === Right(UserId(42L))
  }

case class Identifier(value: Int)
case class Email(email: String)
case class Person(identifier: Identifier, email: Email)

// Generic class with a field that references the type parameter — exercises the macro's
// type-parameter substitution path.
final case class Wrapper[A](value: A)

enum Delivery:
  case NoDelivery
  case ByEmail(email: Email)
  case InPerson(person: Person, at: String)
