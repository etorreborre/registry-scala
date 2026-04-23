package registry.scalacheck

import org.specs2.mutable.Specification
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import registry.*
import registry.scalacheck.*

class ContainersSpec extends Specification:

  "listOf[T]" should {
    "build a Gen[List[T]] from a registered Gen[T]" >> {
      val r =
        listOf[Int] +:
          value(Gen.choose(1, 9): Gen[Int]) +:
          Registry.empty

      val sample = r.make[Gen[List[Int]]].pureApply(Gen.Parameters.default, Seed(3L))
      sample must contain(beBetween(1, 9)).foreach
    }
  }

  "nonEmptyListOf[T]" should {
    "always produce a non-empty list" >> {
      val r =
        nonEmptyListOf[Int] +:
          value(Gen.choose(1, 9): Gen[Int]) +:
          Registry.empty

      val sample = r.make[Gen[List[Int]]].pureApply(Gen.Parameters.default.withSize(5), Seed(2L))
      sample must not(beEmpty)
    }
  }

  "listOfN[T](n)" should {
    "produce a list of exactly n elements" >> {
      val r =
        listOfN[Int](4) +:
          value(Gen.choose(0, 100): Gen[Int]) +:
          Registry.empty

      r.make[Gen[List[Int]]].pureApply(Gen.Parameters.default, Seed(1L)) must haveSize(4)
    }
  }

  "listOfMinMax[T]" should {
    "produce lists with size in [min, max]" >> {
      val r =
        listOfMinMax[Int](2, 5) +:
          value(Gen.choose(0, 100): Gen[Int]) +:
          Registry.empty

      val gen     = r.make[Gen[List[Int]]]
      val samples = (0 until 30).map(i => gen.pureApply(Gen.Parameters.default, Seed(i.toLong)))
      samples.map(_.size) must contain(beBetween(2, 5)).foreach
    }
  }

  "optionOf[T]" should {
    "produce Option[T] values — mix of Some and None" >> {
      val r =
        optionOf[Int] +:
          value(Gen.const(7): Gen[Int]) +:
          Registry.empty

      val gen     = r.make[Gen[Option[Int]]]
      val samples = (0 until 50).map(i => gen.pureApply(Gen.Parameters.default, Seed(i.toLong)))
      samples.exists(_.isDefined) must beTrue
      samples.exists(_.isEmpty) must beTrue
      samples.flatten must contain(7).forall
    }
  }

  "setOf[T]" should {
    "produce Set[T] values — no duplicates" >> {
      val r =
        setOf[Int] +:
          value(Gen.choose(1, 5): Gen[Int]) +:
          Registry.empty

      val sample = r.make[Gen[Set[Int]]].pureApply(Gen.Parameters.default, Seed(4L))
      sample must contain(beBetween(1, 5)).foreach
    }
  }

  "eitherOf[L, R]" should {
    "combine two Gens into a Gen[Either]" >> {
      val r =
        eitherOf[String, Int] +:
          value(Gen.const("oops"): Gen[String]) +:
          value(Gen.const(42): Gen[Int]) +:
          Registry.empty

      val gen     = r.make[Gen[Either[String, Int]]]
      val samples = (0 until 30).map(i => gen.pureApply(Gen.Parameters.default, Seed(i.toLong)))
      samples.exists(_.isLeft) must beTrue
      samples.exists(_.isRight) must beTrue
      samples must contain((e: Either[String, Int]) => e.fold(_ === "oops", _ === 42)).foreach
    }
  }

  "mapOf[K, V]" should {
    "build a Gen[Map[K, V]] from Gens for both sides" >> {
      val r =
        mapOf[String, Int] +:
          value(Gen.alphaLowerStr.suchThat(_.nonEmpty): Gen[String]) +:
          value(Gen.choose(0, 100): Gen[Int]) +:
          Registry.empty

      val sample = r.make[Gen[Map[String, Int]]].pureApply(Gen.Parameters.default, Seed(8L))
      sample.values must contain(beBetween(0, 100)).foreach
    }
  }

  "nonEmptyListOfN[T](n)" should {
    "produce a list of exactly n elements (n >= 1)" >> {
      val r =
        nonEmptyListOfN[Int](3) +:
          value(Gen.choose(0, 100): Gen[Int]) +:
          Registry.empty

      r.make[Gen[List[Int]]].pureApply(Gen.Parameters.default, Seed(1L)) must haveSize(3)
    }

    "reject n < 1" >> {
      nonEmptyListOfN[Int](0) must throwAn[IllegalArgumentException]
    }
  }

  "setOfN[T](n)" should {
    "produce sets of exactly n elements when the element gen supplies enough distinct values" >> {
      val r =
        setOfN[Int](5) +:
          value(Gen.choose(1, 100): Gen[Int]) +:
          Registry.empty

      r.make[Gen[Set[Int]]].pureApply(Gen.Parameters.default, Seed(2L)) must haveSize(5)
    }
  }

  "mapOfN[K, V](n)" should {
    "produce maps of exactly n distinct keys" >> {
      val r =
        mapOfN[Int, String](4) +:
          value(Gen.choose(1, 1000): Gen[Int]) +:
          value(Gen.alphaStr: Gen[String]) +:
          Registry.empty

      r.make[Gen[Map[Int, String]]].pureApply(Gen.Parameters.default, Seed(3L)) must haveSize(4)
    }
  }

  "container helpers compose with genFun" >> {
    case class Bag(items: List[String])
    val r =
      genFun[Bag] +:
        listOf[String] +:
        value(Gen.alphaStr: Gen[String]) +:
        Registry.empty

    r.make[Gen[Bag]].pureApply(Gen.Parameters.default, Seed(1L)) must beAnInstanceOf[Bag]
  }
