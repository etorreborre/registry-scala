package registry.scalacheck

import org.specs2.mutable.Specification
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import registry.*
import registry.scalacheck.*

class GenTraitSpec extends Specification:

  "genTrait[T]" should {
    "combine per-subtype gens into a Gen[T] for a sealed trait" >> {
      val r =
        genTrait[Animal] +:
          gen[Dog] +:
          gen[Cat] +:
          value(Chooser.uniform) +:
          gen(Gen.alphaStr) +:
          gen(Gen.choose(1, 9))

      val genAnimal = r.makeGen[Animal]
      val samples = (0 until 50).map(i => genAnimal.pureApply(Gen.Parameters.default, Seed(i.toLong)))
      samples.exists(_.isInstanceOf[Dog]) must beTrue
      samples.exists(_.isInstanceOf[Cat]) must beTrue
    }

    "work for a Scala 3 enum of no-arg cases" >> {
      val r =
        genTrait[Color] +:
          value(Chooser.uniform) +:
          gen[Color.Red.type](Color.Red) +:
          gen[Color.Green.type](Color.Green) +:
          gen[Color.Blue.type](Color.Blue)

      val genColor = r.makeGen[Color]
      val samples = (0 until 30).map(i => genColor.pureApply(Gen.Parameters.default, Seed(i.toLong)))
      samples.toSet must contain(Color.Red, Color.Green, Color.Blue)
    }

    "produce a single subtype reliably when the sum has only one case" >> {
      val r =
        genTrait[Single] +:
          gen[OnlyCase] +:
          value(Chooser.uniform) +:
          gen(99)

      val genSingle = r.makeGen[Single]
      val sample = genSingle.pureApply(Gen.Parameters.default, Seed(1L))
      sample must beAnInstanceOf[OnlyCase]
    }
  }

  "Chooser" should {
    "skew the distribution when weighted — more of the heavier-weighted variant appears" >> {
      // weights match Mirror.SumOf order: (Dog, Cat) for sealed trait Animal below.
      val r =
        genTrait[Animal] +:
          gen[Dog] +:
          gen[Cat] +:
          value(Chooser.weighted(9, 1)) +: // 9x more dogs than cats
          gen(Gen.alphaStr) +:
          gen(Gen.choose(1, 9))

      val genAnimal = r.makeGen[Animal]
      val samples = (0 until 200).map(i => genAnimal.pureApply(Gen.Parameters.default, Seed(i.toLong)))
      val dogs = samples.count(_.isInstanceOf[Dog])
      val cats = samples.count(_.isInstanceOf[Cat])
      // Expect roughly 180 / 20, but allow wide margins for sampling noise.
      dogs must be_>(cats * 3) // dogs at least 3x more common
    }

    "Chooser.only(i) always picks the i-th variant" >> {
      // genTrait[Animal] gets (Dog, Cat) via Mirror — index 1 = Cat.
      val r =
        genTrait[Animal] +:
          gen[Dog] +:
          gen[Cat] +:
          value(Chooser.only(1)) +:
          gen(Gen.alphaStr) +:
          gen(Gen.choose(1, 9))

      val genAnimal = r.makeGen[Animal]
      val samples = (0 until 20).map(i => genAnimal.pureApply(Gen.Parameters.default, Seed(i.toLong)))
      samples must contain(beAnInstanceOf[Cat]).foreach
    }

    "a custom Chooser (implementing the trait directly) works too" >> {
      // Custom chooser: biased "pick index N every time pickOne is called" — tested by observing
      // which variant the produced Gen yields.
      val lastOnly: Chooser = new Chooser:
        def pickOne[T](gens: Seq[Gen[T]]): Gen[T] = gens.last

      val r =
        genTrait[Animal] +:
          gen[Dog] +:
          gen[Cat] +:
          value(lastOnly) +:
          gen(Gen.alphaStr) +:
          gen(Gen.choose(1, 9))

      val genAnimal = r.makeGen[Animal]
      val sample = genAnimal.pureApply(Gen.Parameters.default, Seed(1L))
      // Last variant in Mirror.SumOf[Animal] order is Cat.
      sample must beAnInstanceOf[Cat]
    }
  }; br

  "gen[T] (convenience bundle)" should {
    "bundle genTrait[T] + gen[Sub_i] for every variant + Chooser.uniform — user only adds leaf gens" >> {
      val r =
        gen[Animal] +:
          gen(Gen.alphaStr) +:
          gen(Gen.choose(1, 9))

      val genAnimal = r.makeGen[Animal]
      val samples = (0 until 50).map(i => genAnimal.pureApply(Gen.Parameters.default, Seed(i.toLong)))
      samples.exists(_.isInstanceOf[Dog]) must beTrue
      samples.exists(_.isInstanceOf[Cat]) must beTrue
    }

    "let users override the default Chooser by prepending their own" >> {
      val r =
        value(Chooser.only(0)) +: // overrides sum's internal Chooser.uniform (LIFO: head wins)
          gen[Animal] +:
          gen(Gen.alphaStr) +:
          gen(Gen.choose(1, 9))

      val genAnimal = r.makeGen[Animal]
      val samples = (0 until 10).map(i => genAnimal.pureApply(Gen.Parameters.default, Seed(i.toLong)))
      samples must contain(beAnInstanceOf[Dog]).foreach // index 0 = Dog per Mirror.SumOf[Animal] order
    }

    "work for a Scala 3 enum of no-arg cases" >> {
      val r = gen[Color]
      val genColor = r.makeGen[Color]
      val samples = (0 until 60).map(i => genColor.pureApply(Gen.Parameters.default, Seed(i.toLong)))
      samples.toSet must contain(Color.Red, Color.Green, Color.Blue)
    }

    "work for a mixed enum (case-class cases + no-arg cases)" >> {
      val r =
        gen[Shape] +:
          gen(Gen.choose(1.0, 10.0))

      val genShape = r.makeGen[Shape]
      val samples = (0 until 60).map(i => genShape.pureApply(Gen.Parameters.default, Seed(i.toLong)))
      samples.exists(_.isInstanceOf[Shape.Circle]) must beTrue
      samples.exists(_ == Shape.Square)            must beTrue
    }

    "work for a sealed trait whose variants are case objects" >> {
      val r = gen[Status]
      val genStatus = r.makeGen[Status]
      val samples = (0 until 40).map(i => genStatus.pureApply(Gen.Parameters.default, Seed(i.toLong)))
      samples.toSet must contain(Status.Active, Status.Inactive)
    }
  }

sealed trait Animal
case class Dog(name: String) extends Animal
case class Cat(lives: Int) extends Animal

enum Color:
  case Red, Green, Blue

enum Shape:
  case Circle(radius: Double)
  case Square

sealed trait Status
object Status:
  case object Active   extends Status
  case object Inactive extends Status

sealed trait Single
case class OnlyCase(v: Int) extends Single
