package registry.scalacheck

import org.specs2.mutable.Specification
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import registry.{Registry, Refinement}
import registry.scalacheck.{Address, Person, WithAddress}

class RefineGenSpec extends Specification:

  "refineGen (value)" should {
    "auto-lift a plain value via Gen.const, scoped by Path" >> {
      // Under a Person resolution, the String input is fixed to "eric"; outside it stays varied.
      val base =
        gen[Person] +:
          gen(Gen.alphaStr) +:
          gen(Gen.choose(0, 120))

      val r = base.refineGen[Person, String]("eric")

      val sample = r.makeGen[Person].pureApply(Gen.Parameters.default, Seed(42L))
      sample.name === "eric"
      sample.age must beBetween(0, 120)
    }

    "do not fire when the Path scope is not entered" >> {
      // Asking for a Gen[String] directly: Gen[Person] never appears on the resolution stack, so
      // the refinement is inert.
      val r =
        (gen(Gen.alphaStr) +: Registry.empty)
          .refineGen[Person, String]("eric")

      // Sample a few seeds; with an unrefined Gen.alphaStr none should be exactly "eric".
      val seeds = List(1L, 2L, 3L, 4L, 5L).map(Seed(_))
      seeds
        .map(s => r.makeGen[String].pureApply(Gen.Parameters.default, s))
        .forall(_ != "eric") must beTrue
    }
  }

  "refineGen (Gen passthrough)" should {
    "register the supplied Gen[T] as-is when the value already has type Gen[T]" >> {
      val base =
        gen[Person] +:
          gen(Gen.alphaStr) +:
          gen(Gen.choose(0, 120))

      val r = base.refineGen[Person, Int](Gen.const(99))

      // Many seeds: under Person, age must be 99 every time.
      val seeds = List(1L, 7L, 23L, 100L).map(Seed(_))
      seeds
        .map(s => r.makeGen[Person].pureApply(Gen.Parameters.default, s).age)
        .forall(_ == 99) must beTrue
    }
  }

  "refineGen (tuple Path)" should {
    "fire only when every Path element appears, in order, on the resolution stack" >> {
      // WithAddress -> Address -> (String, Int). Refine zip under (WithAddress, Address) path.
      val base =
        gen[WithAddress] +:
          gen[Address] +:
          gen(Gen.alphaStr) +:
          gen(Gen.choose(10000, 99999))

      val r = base.refineGen[(WithAddress, Address), Int](42)

      val sample = r.makeGen[WithAddress].pureApply(Gen.Parameters.default, Seed(7L))
      sample.address.zip === 42
    }
  }

  "refineGen (standalone factory)" should {
    "compose with +: as a Refinement value" >> {
      val r =
        refineGen[Person, String]("standalone") +:
          gen[Person] +:
          gen(Gen.alphaStr) +:
          gen(Gen.choose(0, 120))

      val sample = r.makeGen[Person].pureApply(Gen.Parameters.default, Seed(99L))
      sample.name === "standalone"
    }
  }
