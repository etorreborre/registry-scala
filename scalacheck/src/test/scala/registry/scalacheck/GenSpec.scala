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

      val genPerson = r.makeGen[Person]
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

      val genWithAddress = r.makeGen[WithAddress]
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

      val genPerson = r.makeGen[Person]
      val s1 = genPerson.pureApply(Gen.Parameters.default, Seed(1L))
      val s2 = genPerson.pureApply(Gen.Parameters.default, Seed(2L))
      s1 !== s2
    }

    "compose with a constant `gen(value)` for a singleton field" >> {
      val r =
        gen[Tagged] +:
          gen("FIXED") +:
          gen(Gen.choose(1, 10))

      val genTagged = r.makeGen[Tagged]
      val sample = genTagged.pureApply(Gen.Parameters.default, Seed(3L))
      sample.label === "FIXED"
      sample.value must beBetween(1, 10)
    }

    "substitute type parameters when deriving a parameterized case class" >> {
      // Regression: gen[Box[Int]] should require Gen[Int] as input, not Gen[T] (the unsubstituted
      // type parameter name from Box's primary constructor).
      val r =
        gen[Box[Int]] +:
          gen(Gen.choose(1, 99))

      val sample = r.makeGen[Box[Int]].pureApply(Gen.Parameters.default, Seed(5L))
      sample.item must beBetween(1, 99)
    }

    "substitute nested type parameters when one parameterized type contains another" >> {
      // Regression: gen[Outer2[Int]] depends on Inner2[Int] (where Outer2 holds an Inner2[T]). The
      // macro must substitute T at every level when computing the entry's input types — otherwise
      // it asks for `Gen[Inner2[T]]` and won't unify with the registered `Gen[Inner2[Int]]`.
      val r =
        gen[Outer2[Int]] +:
          gen[Inner2[Int]] +:
          gen(Gen.choose(1, 99))

      val sample = r.makeGen[Outer2[Int]].pureApply(Gen.Parameters.default, Seed(7L))
      sample.inner.value must beBetween(1, 99)
    }

    "derive a Gen for a case class nested inside its companion object" >> {
      // Regression: before dealias was added, TypeRepr.of[Outer.Inner].typeSymbol.isClassDef returned
      // false for case classes declared inside a companion, forcing the user to fall back to gen(f).
      val r =
        gen[Outer] +:
          gen[Outer.Inner] +:
          gen(Gen.alphaStr) +:
          gen(Gen.choose(1, 100))

      val genOuter = r.makeGen[Outer]
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

      r.makeGen[Greeting].pureApply(Gen.Parameters.default, Seed(1L)) === Greeting("Alice (30)")
    }

    "accept an eta-expanded constructor reference: gen(Ctor.apply)" >> {
      val r =
        gen(Person.apply) *:
          gen("Bob") *:
          gen(42)

      r.makeGen[Person].pureApply(Gen.Parameters.default, Seed(3L)) === Person("Bob", 42)
    }

    "lift a single-arg function" >> {
      val r =
        gen((n: Int) => Greeting(s"n=$n")) *:
          gen(21)

      r.makeGen[Greeting].pureApply(Gen.Parameters.default, Seed(5L)) === Greeting("n=21")
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
      val sample = r.makeGen[Person].pureApply(Gen.Parameters.default, Seed(9L))
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

      val sample = r.makeGen[Tagged].pureApply(Gen.Parameters.default, Seed(13L))
      sample.label === "T"
      sample.value must beBetween(1, 10)
    }

    "accept a Gen[T] value directly (passthrough)" >> {
      val r =
        gen[Person] +:
          gen(Gen.alphaStr) +:
          gen(Gen.choose(0, 120))

      val sample = r.makeGen[Person].pureApply(Gen.Parameters.default, Seed(17L))
      sample must beAnInstanceOf[Person]
    }

    "wrap a non-Gen value via Gen.const" >> {
      val r =
        gen[Tagged] +:
          gen("HELLO") +:
          gen(7)

      val sample = r.makeGen[Tagged].pureApply(Gen.Parameters.default, Seed(19L))
      sample.label === "HELLO"
      sample.value === 7
    }

    "accept a function whose parameter is already Gen[X] (no Gen[Gen[X]] double-wrap)" >> {
      // Regression: `gen(f)` for `f: Gen[Int] => Tagged` should register `Gen[Int]` as the input,
      // not `Gen[Gen[Int]]` — otherwise the registry searches for a producer of `Gen[Gen[Int]]`
      // and fails with "No entry produces Gen[Gen[Int]]".
      def mkTagged(g: Gen[Int]): Gen[Tagged] = g.map(Tagged("g", _))

      val r =
        gen(mkTagged) +:
          gen(Gen.choose(1, 5))

      val sample = r.makeGen[Tagged].pureApply(Gen.Parameters.default, Seed(101L))
      sample.label === "g"
      sample.value must beBetween(1, 5)
    }

    "derive gen[T] for a case class whose field is already Gen[X] (passthrough)" >> {
      // Regression: a case class field of type `Gen[Int]` should resolve via the registered
      // `Gen[Int]` directly, not require a non-existent `Gen[Gen[Int]]` producer.
      val r =
        gen[HasGen] +:
          gen("k") +:
          gen(Gen.choose(1, 5))

      val sample = r.makeGen[HasGen].pureApply(Gen.Parameters.default, Seed(103L))
      sample.label === "k"
      sample.gen.pureApply(Gen.Parameters.default, Seed(0L)) must beBetween(1, 5)
    }
  }

  "arb[T]" should {
    "register Arbitrary.arbitrary[T] as a zero-input Gen[T] entry" >> {
      val r =
        gen[Person] +:
          arb[String] +:
          arb[Int]

      val sample = r.makeGen[Person].pureApply(Gen.Parameters.default, Seed(23L))
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

case class Box[T](item: T)
case class Inner2[T](value: T)
case class Outer2[T](inner: Inner2[T])

case class HasGen(label: String, gen: Gen[Int])
