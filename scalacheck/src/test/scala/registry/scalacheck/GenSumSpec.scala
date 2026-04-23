package registry.scalacheck

import org.specs2.mutable.Specification
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import registry.*
import registry.scalacheck.*

class GenSumSpec extends Specification:

  "genSum[T]" should {
    "combine per-subtype gens into a Gen[T] for a sealed trait" >> {
      val r =
        genSum[Animal] *:
          genFun[Dog] *:
          genFun[Cat] *:
          value(Gen.alphaStr: Gen[String]) *:
          value(Gen.choose(1, 9): Gen[Int]) *:
          Registry.empty

      val gen = r.make[Gen[Animal]]

      // Sample many times; we should see both Dog and Cat instances.
      val samples = (0 until 50).map(i => gen.pureApply(Gen.Parameters.default, Seed(i.toLong)))
      samples.exists(_.isInstanceOf[Dog]) must beTrue
      samples.exists(_.isInstanceOf[Cat]) must beTrue
    }

    "work for a Scala 3 enum of no-arg cases" >> {
      val r =
        genSum[Color] *:
          value(Gen.const(Color.Red): Gen[Color.Red.type]) *:
          value(Gen.const(Color.Green): Gen[Color.Green.type]) *:
          value(Gen.const(Color.Blue): Gen[Color.Blue.type]) *:
          Registry.empty

      val gen     = r.make[Gen[Color]]
      val samples = (0 until 30).map(i => gen.pureApply(Gen.Parameters.default, Seed(i.toLong)))
      samples.toSet must contain(Color.Red, Color.Green, Color.Blue)
    }

    "produce a single subtype reliably when the sum has only one case" >> {
      val r =
        genSum[Single] *:
          genFun[OnlyCase] *:
          value(Gen.const(99): Gen[Int]) *:
          Registry.empty

      val gen    = r.make[Gen[Single]]
      val sample = gen.pureApply(Gen.Parameters.default, Seed(1L))
      sample must beAnInstanceOf[OnlyCase]
    }
  }

sealed trait Animal
case class Dog(name: String)  extends Animal
case class Cat(lives: Int)    extends Animal

enum Color:
  case Red, Green, Blue

sealed trait Single
case class OnlyCase(v: Int) extends Single
