package registry.circe

import io.circe.{Decoder, Encoder, Json}
import org.specs2.mutable.Specification
import registry.*

/**
 * `makeEncoder[T]` / `makeDecoder[T]` defer to a companion-object `given Encoder[T]` / `given Decoder[T]`
 * when one exists, registering it directly instead of generating a structural codec. This avoids the
 * need to write `encoderOf[T]` / `decoderOf[T]` for types that already provide their own circe codecs.
 */
class CompanionGivenSpec extends Specification:

  "makeEncoder[T] uses a companion-given Encoder[T] when present" >> {
    val r = makeEncoder[CGType]
    val e = r.make[Encoder[CGType]]
    e(CGType("hello")) === Json.fromString("companion:hello")
  }

  "makeDecoder[T] uses a companion-given Decoder[T] when present" >> {
    val r = makeDecoder[CGType]
    val d = r.make[Decoder[CGType]]
    d.decodeJson(Json.fromString("companion:hello")) === Right(CGType("hello"))
  }

  "makeEncoder[T] / makeDecoder[T] fall back to structural derivation when no companion-given exists" >> {
    val r =
      makeEncoder[Structural] *:
        makeDecoder[Structural] *:
        encoderOf[Int] *:
        decoderOf[Int] *:
        defaultEncoderOptions <+>
        defaultDecoderOptions

    val e = r.make[Encoder[Structural]]
    val d = r.make[Decoder[Structural]]
    e(Structural(7)) === Json.obj("value" -> Json.fromInt(7))
    d.decodeJson(Json.obj("value" -> Json.fromInt(7))) === Right(Structural(7))
  }

final case class CGType(value: String)
object CGType:
  given Encoder[CGType] = Encoder.instance(c => Json.fromString(s"companion:${c.value}"))
  given Decoder[CGType] = Decoder.instance(c =>
    c.value.asString match
      case Some(s) if s.startsWith("companion:") => Right(CGType(s.drop("companion:".length)))
      case _                                     => Left(io.circe.DecodingFailure("CGType", c.history))
  )

final case class Structural(value: Int)

// Opaque type whose given codecs live in the enclosing `object` — the companion-given lookup
// needs to fall back to `maybeOwner` here, since opaque types don't have a separate companion.
type CGOpaque = CGOpaqueModule.CGOpaque
object CGOpaqueModule:
  opaque type CGOpaque = Int
  def apply(i: Int): CGOpaque = i
  extension (o: CGOpaque) def value: Int = o
  given Encoder[CGOpaque] = Encoder.encodeInt.contramap(_.value)
  given Decoder[CGOpaque] = Decoder.decodeInt.map(apply)

class CompanionGivenOpaqueSpec extends Specification:
  "makeEncoder[T] / makeDecoder[T] also find givens declared in the surrounding object of an opaque type" >> {
    val r =
      makeEncoder[CGOpaque] *:
        makeDecoder[CGOpaque]

    val e = r.make[Encoder[CGOpaque]]
    val d = r.make[Decoder[CGOpaque]]
    e(CGOpaqueModule(7)) === Json.fromInt(7)
    d.decodeJson(Json.fromInt(7)).map(_.value) === Right(7)
  }

class MakeValueShapeSpec extends Specification:

  "makeDecoder(d: Decoder[S]) registers it as a value entry" >> {
    val explicit: Decoder[Int] = Decoder.decodeInt
    val r = makeDecoder(explicit)
    val d = r.make[Decoder[Int]]
    d.decodeJson(Json.fromInt(42)) === Right(42)
  }

  "makeEncoder(e: Encoder[S]) registers it as a value entry" >> {
    val explicit: Encoder[Int] = Encoder.encodeInt
    val r = makeEncoder(explicit)
    val e = r.make[Encoder[Int]]
    e(42) === Json.fromInt(42)
  }

// Curried-constructor case class with a `using` clause: previously the macro flattened all
// parameter lists into one field list and let the compiler auto-tuple the args, which produced
// a position-less synthetic tuple and crashed dotty. Value params are now treated as fields and
// the using-clause values are summoned at macro time.
final case class WithUsing(value: Int)(using val ord: Ordering[Int])
object WithUsing:
  given Ordering[Int] = scala.math.Ordering.Int

class MakeCurriedSpec extends Specification:
  "makeEncoder[T] / makeDecoder[T] handle a case class with a using clause" >> {
    val r =
      makeEncoder[WithUsing] *:
        makeDecoder[WithUsing] *:
        encoderOf[Int] *:
        decoderOf[Int] *:
        defaultEncoderOptions <+>
        defaultDecoderOptions

    val e = r.make[Encoder[WithUsing]]
    val d = r.make[Decoder[WithUsing]]
    e(WithUsing(7)) === Json.obj("value" -> Json.fromInt(7))
    d.decodeJson(Json.obj("value" -> Json.fromInt(7))).map(_.value) === Right(7)
  }
