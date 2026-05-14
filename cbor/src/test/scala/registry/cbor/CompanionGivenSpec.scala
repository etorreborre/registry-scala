package registry.cbor

import io.bullet.borer.{Cbor, Decoder, Dom, Encoder, Reader, Writer}
import io.bullet.borer.Dom.*
import org.specs2.mutable.Specification
import registry.*

/**
 * `encoder[T]` / `decoder[T]` defer to a companion-object `given Encoder[T]` / `given Decoder[T]`
 * when one exists, registering it directly instead of generating a structural codec.
 */
class CompanionGivenSpec extends Specification:

  "encoder[T] uses a companion-given Encoder[T] when present" >> {
    val r = encoder[CGType]
    val e = r.make[Encoder[CGType]]
    val bytes = Cbor.encode(CGType("hello"))(using e).toByteArray
    val asElem = Cbor.decode(bytes).to[Dom.Element].value
    asElem === StringElem("companion:hello")
  }

  "decoder[T] uses a companion-given Decoder[T] when present" >> {
    val r = decoder[CGType]
    val d = r.make[Decoder[CGType]]
    val bytes = Cbor.encode(StringElem("companion:hello")).toByteArray
    Cbor.decode(bytes).to[CGType](using d).value === CGType("hello")
  }

  "encoder[T] / decoder[T] fall back to structural derivation when no companion-given exists" >> {
    val r =
      encoder[Structural] *:
        decoder[Structural] *:
        encoderOf[Int] *:
        decoderOf[Int] *:
        defaultEncoderOptions <+>
        defaultDecoderOptions

    val e = r.make[Encoder[Structural]]
    val d = r.make[Decoder[Structural]]
    val bytes = Cbor.encode(Structural(7))(using e).toByteArray
    Cbor.decode(bytes).to[Structural](using d).value === Structural(7)
  }

final case class CGType(value: String)

object CGType:
  given Encoder[CGType] = Encoder[CGType]((w, c) => w.writeString(s"companion:${c.value}"))

  given Decoder[CGType] = Decoder[CGType] { r =>
    val s = r.readString()
    if s.startsWith("companion:") then CGType(s.drop("companion:".length))
    else r.validationFailure("CGType")
  }

final case class Structural(value: Int)

// Opaque type whose given codecs live in the enclosing `object`.
type CGOpaque = CGOpaqueModule.CGOpaque

object CGOpaqueModule:
  opaque type CGOpaque = Int
  def apply(i: Int): CGOpaque = i
  extension (o: CGOpaque) def value: Int = o
  given Encoder[CGOpaque] = Encoder.forInt.contramap(_.value)
  given Decoder[CGOpaque] = Decoder.forInt.map(apply)

class CompanionGivenOpaqueSpec extends Specification:

  "encoder[T] / decoder[T] also find givens declared in the surrounding object of an opaque type" >> {
    val r =
      encoder[CGOpaque] *:
        decoder[CGOpaque]

    val e = r.make[Encoder[CGOpaque]]
    val d = r.make[Decoder[CGOpaque]]
    val bytes = Cbor.encode(CGOpaqueModule(7))(using e).toByteArray
    Cbor.decode(bytes).to[CGOpaque](using d).value.value === 7
  }

class MakeValueShapeSpec extends Specification:

  "decoder(d: Decoder[S]) registers it as a value entry" >> {
    val explicit: Decoder[Int] = Decoder.forInt
    val r = decoder(explicit)
    val d = r.make[Decoder[Int]]
    val bytes = Cbor.encode(42).toByteArray
    Cbor.decode(bytes).to[Int](using d).value === 42
  }

  "encoder(e: Encoder[S]) registers it as a value entry" >> {
    val explicit: Encoder[Int] = Encoder.forInt
    val r = encoder(explicit)
    val e = r.make[Encoder[Int]]
    val bytes = Cbor.encode(42)(using e).toByteArray
    Cbor.decode(bytes).to[Int].value === 42
  }

// Case class with a `using` clause: macro must handle value vs using params correctly.
final case class WithUsing(value: Int)(using val ord: Ordering[Int])

object WithUsing:
  given Ordering[Int] = scala.math.Ordering.Int

class MakeCurriedSpec extends Specification:

  "encoder[T] / decoder[T] handle a case class with a using clause" >> {
    val r =
      encoder[WithUsing] *:
        decoder[WithUsing] *:
        encoderOf[Int] *:
        decoderOf[Int] *:
        defaultEncoderOptions <+>
        defaultDecoderOptions

    val e = r.make[Encoder[WithUsing]]
    val d = r.make[Decoder[WithUsing]]
    val bytes = Cbor.encode(WithUsing(7))(using e).toByteArray
    Cbor.decode(bytes).to[WithUsing](using d).value.value === 7
  }
