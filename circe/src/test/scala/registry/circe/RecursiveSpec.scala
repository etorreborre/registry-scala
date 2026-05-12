package registry.circe

import io.circe.{Decoder, Encoder}
import io.circe.parser as circeParser
import org.specs2.mutable.Specification
import registry.*
import registry.circe.DataTypes.*

/**
 * Recursive types via [[encoder]] / [[decoder]] — the macros detect self-references in field
 * types and emit an extra forwarder entry that picks up the in-flight `Encoder[T]` resolution. The
 * user writes the same registry chain as for any other type.
 */
class RecursiveSpec extends Specification:

  "encode and decode a directly recursive enum (Cons)" >> {
    val encoders =
      encoder[Cons] *:
        encoderOf[Int] *:
        defaultEncoderOptions

    val decoders =
      decoder[Cons] *:
        decoderOf[Int] *:
        defaultDecoderOptions

    val e = encoders.make[Encoder[Cons]]
    val d = decoders.make[Decoder[Cons]]

    val sample: Cons = Cons.Item(1, Cons.Item(2, Cons.Item(3, Cons.End)))
    val encoded = Encoders.encodeString(e, sample)
    val parsed = circeParser.parse(encoded).toOption.get
    d.decodeJson(parsed) === Right(sample)
  }

  "encode and decode a tree where children is List[Tree]" >> {
    val encoders =
      encoder[Tree] *:
        encodeListOf[Tree] *:
        encoderOf[Int] *:
        defaultEncoderOptions

    val decoders =
      decoder[Tree] *:
        decodeListOf[Tree] *:
        decoderOf[Int] *:
        defaultDecoderOptions

    val e = encoders.make[Encoder[Tree]]
    val d = decoders.make[Decoder[Tree]]

    val sample =
      Tree(1, List(
        Tree(2, List(Tree(4, Nil), Tree(5, Nil))),
        Tree(3, Nil)
      ))

    val encoded = Encoders.encodeString(e, sample)
    val parsed = circeParser.parse(encoded).toOption.get
    d.decodeJson(parsed) === Right(sample)
  }
