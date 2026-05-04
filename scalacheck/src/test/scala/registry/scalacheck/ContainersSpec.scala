package registry.scalacheck

import org.specs2.mutable.Specification
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import registry.*
import registry.scalacheck.*

import scala.language.implicitConversions

class ContainersSpec extends Specification:

  "listOf[T]" should {
    "build a Gen[List[T]] from a registered Gen[T]" >> {
      val r =
        listOf[Int] +:
          gen(Gen.choose(1, 9))

      val sample = r.makeGen[List[Int]].pureApply(Gen.Parameters.default, Seed(3L))
      sample must contain(beBetween(1, 9)).foreach
    }
  }

  "nonEmptyListOf[T]" should {
    "always produce a non-empty list" >> {
      val r =
        nonEmptyListOf[Int] +:
          gen(Gen.choose(1, 9))

      val sample = r.makeGen[List[Int]].pureApply(Gen.Parameters.default.withSize(5), Seed(2L))
      sample must not(beEmpty)
    }
  }

  "listOfN[T](n)" should {
    "produce a list of exactly n elements" >> {
      val r =
        listOfN[Int](4) +:
          gen(Gen.choose(0, 100))

      r.makeGen[List[Int]].pureApply(Gen.Parameters.default, Seed(1L)) must haveSize(4)
    }
  }

  "listOfMinMax[T]" should {
    "produce lists with size in [min, max]" >> {
      val r =
        listOfMinMax[Int](2, 5) +:
          gen(Gen.choose(0, 100))

      val genList = r.makeGen[List[Int]]
      val samples = (0 until 30).map(i => genList.pureApply(Gen.Parameters.default, Seed(i.toLong)))
      samples.map(_.size) must contain(beBetween(2, 5)).foreach
    }
  }

  "optionOf[T]" should {
    "produce Option[T] values — mix of Some and None" >> {
      val r =
        optionOf[Int] +:
          gen(7)

      val genOpt = r.makeGen[Option[Int]]
      val samples = (0 until 50).map(i => genOpt.pureApply(Gen.Parameters.default, Seed(i.toLong)))
      samples.exists(_.isDefined) must beTrue
      samples.exists(_.isEmpty) must beTrue
      samples.flatten must contain(7).forall
    }
  }

  "setOf[T]" should {
    "produce Set[T] values — no duplicates" >> {
      val r =
        setOf[Int] +:
          gen(Gen.choose(1, 5))

      val sample = r.makeGen[Set[Int]].pureApply(Gen.Parameters.default, Seed(4L))
      sample must contain(beBetween(1, 5)).foreach
    }
  }

  "eitherOf[L, R]" should {
    "combine two Gens into a Gen[Either]" >> {
      val r =
        eitherOf[String, Int] +:
          gen("oops") +:
          gen(42)

      val genE = r.makeGen[Either[String, Int]]
      val samples = (0 until 30).map(i => genE.pureApply(Gen.Parameters.default, Seed(i.toLong)))
      samples.exists(_.isLeft) must beTrue
      samples.exists(_.isRight) must beTrue
      samples must contain((e: Either[String, Int]) => e.fold(_ === "oops", _ === 42)).foreach
    }
  }

  "mapOf[K, V]" should {
    "build a Gen[Map[K, V]] from Gens for both sides" >> {
      val r =
        mapOf[String, Int] +:
          gen(Gen.alphaLowerStr.suchThat(_.nonEmpty)) +:
          gen(Gen.choose(0, 100))

      val sample = r.makeGen[Map[String, Int]].pureApply(Gen.Parameters.default, Seed(8L))
      sample.values must contain(beBetween(0, 100)).foreach
    }
  }

  "nonEmptyListOfN[T](n)" should {
    "produce a list of exactly n elements (n >= 1)" >> {
      val r =
        nonEmptyListOfN[Int](3) +:
          gen(Gen.choose(0, 100))

      r.makeGen[List[Int]].pureApply(Gen.Parameters.default, Seed(1L)) must haveSize(3)
    }

    "reject n < 1" >> {
      nonEmptyListOfN[Int](0) must throwAn[IllegalArgumentException]
    }
  }

  "setOfN[T](n)" should {
    "produce sets of exactly n elements when the element gen supplies enough distinct values" >> {
      val r =
        setOfN[Int](5) +:
          gen(Gen.choose(1, 100))

      r.makeGen[Set[Int]].pureApply(Gen.Parameters.default, Seed(2L)) must haveSize(5)
    }
  }

  "mapOfN[K, V](n)" should {
    "produce maps of exactly n distinct keys" >> {
      val r =
        mapOfN[Int, String](4) +:
          gen(Gen.choose(1, 1000)) +:
          gen(Gen.alphaStr)

      r.makeGen[Map[Int, String]].pureApply(Gen.Parameters.default, Seed(3L)) must haveSize(4)
    }
  }

  "iArrayOf[T]" should {
    "build a Gen[IArray[T]] from a registered Gen[T]" >> {
      val r =
        iArrayOf[Int] +:
          gen(Gen.choose(1, 9))

      val sample = r.makeGen[IArray[Int]].pureApply(Gen.Parameters.default, Seed(3L))
      sample.toSeq must contain(beBetween(1, 9)).foreach
    }
  }

  "nonEmptyIArrayOf[T]" should {
    "always produce a non-empty IArray" >> {
      val r =
        nonEmptyIArrayOf[Int] +:
          gen(Gen.choose(1, 9))

      val sample = r.makeGen[IArray[Int]].pureApply(Gen.Parameters.default.withSize(5), Seed(2L))
      sample.length must beGreaterThan(0)
    }
  }

  "iArrayOfN[T](n)" should {
    "produce an IArray of exactly n elements" >> {
      val r =
        iArrayOfN[Int](4) +:
          gen(Gen.choose(0, 100))

      r.makeGen[IArray[Int]].pureApply(Gen.Parameters.default, Seed(1L)).length === 4
    }
  }

  "iArrayOfMinMax[T]" should {
    "produce IArrays with size in [min, max]" >> {
      val r =
        iArrayOfMinMax[Int](2, 5) +:
          gen(Gen.choose(0, 100))

      val genArr = r.makeGen[IArray[Int]]
      val samples =
        (0 until 30).map(i => genArr.pureApply(Gen.Parameters.default, Seed(i.toLong)))
      samples.map(_.length) must contain(beBetween(2, 5)).foreach
    }
  }

  "container helpers compose with gen" >> {
    case class Bag(items: List[String])
    val r =
      gen[Bag] +:
        listOf[String] +:
        gen(Gen.alphaStr)

    r.makeGen[Bag].pureApply(Gen.Parameters.default, Seed(1L)) must beAnInstanceOf[Bag]
  }
