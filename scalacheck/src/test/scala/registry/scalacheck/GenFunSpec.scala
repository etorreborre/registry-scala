package registry.scalacheck

import org.specs2.mutable.Specification
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import registry.*
import registry.scalacheck.*

class GenFunSpec extends Specification:

  "genFun[T]" should {
    "build a Gen[T] for a 2-field case class from registered Gens" >> {
      val r =
        genFun[Person] +:
          value(Gen.alphaStr: Gen[String]) +:
          value(Gen.choose(0, 120): Gen[Int])

      val gen = r.make[Gen[Person]]
      val sample = gen.pureApply(Gen.Parameters.default, Seed(42L))
      sample must beAnInstanceOf[Person]
      sample.age must beBetween(0, 120)
    }

    "compose nested case class generators" >> {
      val r =
        genFun[WithAddress] +:
          genFun[Address] +:
          value(Gen.alphaStr: Gen[String]) +:
          value(Gen.choose(10000, 99999): Gen[Int])

      val gen = r.make[Gen[WithAddress]]
      val sample = gen.pureApply(Gen.Parameters.default, Seed(7L))
      sample must beAnInstanceOf[WithAddress]
      sample.address must beAnInstanceOf[Address]
      sample.address.zip must beBetween(10000, 99999)
    }

    "produce distinct values when sampled with different seeds" >> {
      val r =
        genFun[Person] +:
          value(Gen.alphaStr: Gen[String]) +:
          value(Gen.choose(0, 120): Gen[Int])

      val gen = r.make[Gen[Person]]
      val s1 = gen.pureApply(Gen.Parameters.default, Seed(1L))
      val s2 = gen.pureApply(Gen.Parameters.default, Seed(2L))
      s1 !== s2
    }

    "compose with a plain value(Gen.const(...)) for a singleton field" >> {
      val r =
        genFun[Tagged] +:
          value(Gen.const("FIXED"): Gen[String]) +:
          value(Gen.choose(1, 10): Gen[Int])

      val gen = r.make[Gen[Tagged]]
      val sample = gen.pureApply(Gen.Parameters.default, Seed(3L))
      sample.label === "FIXED"
      sample.value must beBetween(1, 10)
    }
  }

  "genFun(f) (value-driven)" should {
    "lift an arbitrary lambda: genFun((a, b) => …)" >> {
      // Output type differs from input types so there's no LIFO cycle resolving
      // a type that overlaps with an input.
      val toGreeting: (String, Int) => Greeting = (n, a) => Greeting(s"$n ($a)")
      val r =
        genFun(toGreeting) *:
          value(Gen.const("Alice"): Gen[String]) *:
          value(Gen.const(30): Gen[Int]) *:
          Registry.empty

      r.make[Gen[Greeting]].pureApply(Gen.Parameters.default, Seed(1L)) === Greeting("Alice (30)")
    }

    "accept an eta-expanded constructor reference: genFun(Ctor.apply)" >> {
      val r =
        genFun(Person.apply) *:
          value(Gen.const("Bob"): Gen[String]) *:
          value(Gen.const(42): Gen[Int]) *:
          Registry.empty

      r.make[Gen[Person]].pureApply(Gen.Parameters.default, Seed(3L)) === Person("Bob", 42)
    }

    "lift a single-arg function" >> {
      val r =
        genFun((n: Int) => Greeting(s"n=$n")) *:
          value(Gen.const(21): Gen[Int]) *:
          Registry.empty

      r.make[Gen[Greeting]].pureApply(Gen.Parameters.default, Seed(5L)) === Greeting("n=21")
    }
  }

case class Person(name: String, age: Int)
case class Address(street: String, zip: Int)
case class WithAddress(name: String, address: Address)
case class Tagged(label: String, value: Int)
case class Greeting(text: String)
