package registry.scalacheck

import org.specs2.mutable.Specification
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import registry.*
import registry.scalacheck.*

class GenOpaqueSpec extends Specification:
  import GenOpaqueSpec.*

  "gen[T] for newtype-shaped types" should {
    "derive a Gen for an opaque with a same-named companion `object` (apply on the companion)" >> {
      // Pattern A: opaque type and an `object` of the same name in the same scope. The compiler
      // treats the same-named object as the opaque type's companion.
      val r =
        gen[Wrap.Tag] +:
          gen(Gen.alphaStr)

      val sample = r.make[Gen[Wrap.Tag]].pureApply(Gen.Parameters.default, Seed(7L))
      Wrap.unwrap(sample) must beAnInstanceOf[String]
    }

    "derive a Gen for an opaque whose `apply` lives on the enclosing object" >> {
      // Pattern B: HeaderSignature shape — opaque inside an object, no separate inner companion;
      // the factory `apply` lives on the enclosing object itself.
      val r =
        gen[SignatureBox.Signature] +:
          gen(Gen.const(IArray.fill[Byte](8)(0)))

      val sample =
        r.make[Gen[SignatureBox.Signature]].pureApply(Gen.Parameters.default, Seed(13L))
      SignatureBox.bytes(sample).length === 8
    }

    "compose a derived opaque Gen as a field of a case class" >> {
      val r =
        gen[Tagged] +:
          gen[Wrap.Tag] +:
          gen(Gen.alphaStr)

      val sample = r.make[Gen[Tagged]].pureApply(Gen.Parameters.default, Seed(11L))
      sample must beAnInstanceOf[Tagged]
      Wrap.unwrap(sample.tag) must beAnInstanceOf[String]
    }
  }

object GenOpaqueSpec:
  case class Tagged(label: String, tag: Wrap.Tag)

  object Wrap:
    opaque type Tag = String
    object Tag:
      def apply(s: String): Tag = s
    def unwrap(t: Tag): String = t

  object SignatureBox:
    opaque type Signature = IArray[Byte]
    def apply(b: IArray[Byte]): Signature = b
    def bytes(s: Signature): IArray[Byte] = s
