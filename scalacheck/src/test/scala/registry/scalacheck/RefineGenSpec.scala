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

      val r = base.refineGen[Person]("eric")

      val sample = r.makeGen[Person].pureApply(Gen.Parameters.default, Seed(42L))
      sample.name === "eric"
      sample.age must beBetween(0, 120)
    }

    "do not fire when the Path scope is not entered" >> {
      // Asking for a Gen[String] directly: Gen[Person] never appears on the resolution stack, so
      // the refinement is inert.
      val r =
        (gen(Gen.alphaStr) +: Registry.empty)
          .refineGen[Person]("eric")

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

      val r = base.refineGen[Person](Gen.const(99))

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

      val r = base.refineGen[(WithAddress, Address)](42)

      val sample = r.makeGen[WithAddress].pureApply(Gen.Parameters.default, Seed(7L))
      sample.address.zip === 42
    }
  }

  "refineGen (standalone factory)" should {
    "compose with +: as a Refinement value" >> {
      val r =
        refineGen[Person]("standalone") +:
          gen[Person] +:
          gen(Gen.alphaStr) +:
          gen(Gen.choose(0, 120))

      val sample = r.makeGen[Person].pureApply(Gen.Parameters.default, Seed(99L))
      sample.name === "standalone"
    }

    "infer the payload type for a supplied Gen" >> {
      val r =
        refineGen[Person](Gen.const("standalone")) +:
          gen[Person] +:
          gen(Gen.alphaStr) +:
          gen(Gen.choose(0, 120))

      val sample = r.makeGen[Person].pureApply(Gen.Parameters.default, Seed(99L))
      sample.name === "standalone"
    }

    "support the explicit standalone form when the payload type is ascribed" >> {
      val users =
        gen[Person] +:
          gen(Gen.alphaStr) +:
          gen(Gen.choose(0, 120))

      val r = refineGen[Person, String]("explicit") +: users

      val sample = r.makeGen[Person].pureApply(Gen.Parameters.default, Seed(99L))
      sample.name === "explicit"
    }
  }

  "refineGen (function value)" should {
    "accept a function whose parameters are resolved from the surrounding registry" >> {
      // f: CoinW => Output. The refinement should pull a CoinW from the registry and apply f.
      // The pinned `gen(Gen.const(7L))` makes the registry deliver CoinW(7) deterministically.
      def f(c: CoinW): Output = Output(c)
      val base =
        gen[Output] +:        // auto-derived — will be shadowed by the refinement
          gen[CoinW] +:
          gen(Gen.const(7L))

      val r = refineGen[Output](f) +: base

      val sample = r.makeGen[Output].pureApply(Gen.Parameters.default, Seed(1L))
      sample.coin.value === 7L
    }

    "accept a function returning Gen[T] without double-wrapping into Gen[Gen[T]]" >> {
      def f(c: CoinW): Gen[Output] = Gen.const(Output(c))
      val base =
        gen[Output] +:
          gen[CoinW] +:
          gen(Gen.const(11L))

      val r = refineGen[Output](f) +: base

      val sample = r.makeGen[Output].pureApply(Gen.Parameters.default, Seed(2L))
      sample.coin.value === 11L
    }

    "terminate when the refinement function takes its own target type as an input" >> {
      // The refinement targets Gen[CoinW] under path Gen[Output] and consumes a CoinW itself —
      // doubling the underlying value. Without the in-flight refinement guard the resolver would
      // re-fire this same refinement on its own input, looping forever.
      def doubleCoin(c: CoinW): CoinW = CoinW(c.value * 2)
      val r =
        refineGen[Output](doubleCoin) +:
          gen[Output] +:
          gen[CoinW] +:
          gen(Gen.const(7L))

      val sample = r.makeGen[Output].pureApply(Gen.Parameters.default, Seed(1L))
      sample.coin.value === 14L
    }
  }
