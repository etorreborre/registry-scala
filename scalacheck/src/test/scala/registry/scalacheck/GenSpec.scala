package registry.scalacheck

import org.specs2.mutable.Specification
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import registry.*
import registry.scalacheck.*

class GenSpec extends Specification:

  "gen[T]" should {
    "build a Gen[T] for a 2-field case class from registered Gens" >> {
      val r =
        gen[Person] +:
          gen(Gen.alphaStr) +:
          gen(Gen.choose(0, 120))

      val genPerson = r.make[Gen[Person]]
      val sample = genPerson.pureApply(Gen.Parameters.default, Seed(42L))
      sample must beAnInstanceOf[Person]
      sample.age must beBetween(0, 120)
    }

    "compose nested case class generators" >> {
      val r =
        gen[WithAddress] +:
          gen[Address] +:
          gen(Gen.alphaStr) +:
          gen(Gen.choose(10000, 99999))

      val genWithAddress = r.make[Gen[WithAddress]]
      val sample = genWithAddress.pureApply(Gen.Parameters.default, Seed(7L))
      sample must beAnInstanceOf[WithAddress]
      sample.address must beAnInstanceOf[Address]
      sample.address.zip must beBetween(10000, 99999)
    }

    "produce distinct values when sampled with different seeds" >> {
      val r =
        gen[Person] +:
          gen(Gen.alphaStr) +:
          gen(Gen.choose(0, 120))

      val genPerson = r.make[Gen[Person]]
      val s1 = genPerson.pureApply(Gen.Parameters.default, Seed(1L))
      val s2 = genPerson.pureApply(Gen.Parameters.default, Seed(2L))
      s1 !== s2
    }

    "compose with a constant `gen(value)` for a singleton field" >> {
      val r =
        gen[Tagged] +:
          gen("FIXED") +:
          gen(Gen.choose(1, 10))

      val genTagged = r.make[Gen[Tagged]]
      val sample = genTagged.pureApply(Gen.Parameters.default, Seed(3L))
      sample.label === "FIXED"
      sample.value must beBetween(1, 10)
    }

    "derive a Gen for a case class nested inside its companion object" >> {
      // Regression: before dealias was added, TypeRepr.of[Outer.Inner].typeSymbol.isClassDef returned
      // false for case classes declared inside a companion, forcing the user to fall back to gen(f).
      val r =
        gen[Outer] +:
          gen[Outer.Inner] +:
          gen(Gen.alphaStr) +:
          gen(Gen.choose(1, 100))

      val genOuter = r.make[Gen[Outer]]
      val sample = genOuter.pureApply(Gen.Parameters.default, Seed(11L))
      sample must beAnInstanceOf[Outer]
      sample.inner must beAnInstanceOf[Outer.Inner]
      sample.inner.count must beBetween(1, 100)
    }
  }

  "gen(f) (value-driven)" should {
    "lift an arbitrary lambda: gen((a, b) => …)" >> {
      // Output type differs from input types so there's no LIFO cycle resolving
      // a type that overlaps with an input.
      val toGreeting: (String, Int) => Greeting = (n, a) => Greeting(s"$n ($a)")
      val r =
        gen(toGreeting) *:
          gen("Alice") *:
          gen(30)

      r.make[Gen[Greeting]].pureApply(Gen.Parameters.default, Seed(1L)) === Greeting("Alice (30)")
    }

    "accept an eta-expanded constructor reference: gen(Ctor.apply)" >> {
      val r =
        gen(Person.apply) *:
          gen("Bob") *:
          gen(42)

      r.make[Gen[Person]].pureApply(Gen.Parameters.default, Seed(3L)) === Person("Bob", 42)
    }

    "lift a single-arg function" >> {
      val r =
        gen((n: Int) => Greeting(s"n=$n")) *:
          gen(21)

      r.make[Gen[Greeting]].pureApply(Gen.Parameters.default, Seed(5L)) === Greeting("n=21")
    }

    "accept a Gen-returning function: entry output is Gen[T] (not Gen[Gen[T]])" >> {
      // Registering `f: A => Gen[B]` should produce a `Gen[B]` entry — the final combining step
      // flatMaps into `f(...)` instead of wrapping it.
      def mkPersonGen(age: Int): Gen[Person] =
        Gen.alphaStr.map(name => Person(name, age))

      val r =
        gen(mkPersonGen) +:
          gen(42)

      // Resolves as Gen[Person], not Gen[Gen[Person]].
      val sample = r.make[Gen[Person]].pureApply(Gen.Parameters.default, Seed(9L))
      sample must beAnInstanceOf[Person]
      sample.age === 42
    }

    "Gen-returning function with multiple args chains both Gen inputs and internal Gen" >> {
      def mkTaggedGen(label: String, max: Int): Gen[Tagged] =
        Gen.choose(1, max).map(Tagged(label, _))

      val r =
        gen(mkTaggedGen) +:
          gen("T") +:
          gen(10)

      val sample = r.make[Gen[Tagged]].pureApply(Gen.Parameters.default, Seed(13L))
      sample.label === "T"
      sample.value must beBetween(1, 10)
    }

    "accept a Gen[T] value directly (passthrough)" >> {
      val r =
        gen[Person] +:
          gen(Gen.alphaStr) +:
          gen(Gen.choose(0, 120))

      val sample = r.make[Gen[Person]].pureApply(Gen.Parameters.default, Seed(17L))
      sample must beAnInstanceOf[Person]
    }

    "wrap a non-Gen value via Gen.const" >> {
      val r =
        gen[Tagged] +:
          gen("HELLO") +:
          gen(7)

      val sample = r.make[Gen[Tagged]].pureApply(Gen.Parameters.default, Seed(19L))
      sample.label === "HELLO"
      sample.value === 7
    }
  }

  "arb[T]" should {
    "register Arbitrary.arbitrary[T] as a zero-input Gen[T] entry" >> {
      val r =
        gen[Person] +:
          arb[String] +:
          arb[Int]

      val sample = r.make[Gen[Person]].pureApply(Gen.Parameters.default, Seed(23L))
      sample must beAnInstanceOf[Person]
    }
  }

case class Person(name: String, age: Int)
case class Address(street: String, zip: Int)
case class WithAddress(name: String, address: Address)
case class Tagged(label: String, value: Int)
case class Greeting(text: String)

case class Outer(label: String, inner: Outer.Inner)
object Outer:
  case class Inner(count: Int)
