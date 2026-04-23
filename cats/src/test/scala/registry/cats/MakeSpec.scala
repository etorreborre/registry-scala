package registry.cats

import org.specs2.mutable.Specification
import _root_.cats.data.Validated
import registry.*
import registry.cats.*

class MakeSpec extends Specification:

  "makeEither" should {
    "return Right on success" >> {
      val r = value(42) +: Registry.empty
      r.makeEither[Int] === Right(42)
    }

    "return Left on a missing-input runtime failure" >> {
      val r = fun[Target] *: Registry.empty // Int is not produced
      r.makeEither[Target] must beLeft[Throwable].like { case t =>
        t.getMessage must contain("No entry produces")
      }
    }

    "return Left on a cycle" >> {
      val r = fun((b: B) => A(b)) *: fun((a: A) => B(a)) *: Registry.empty
      r.makeEither[A] must beLeft[Throwable].like { case t =>
        t.getMessage must contain("cycle")
      }
    }
  }

  "makeValidated" should {
    "return Valid on success" >> {
      val r = value(42) +: Registry.empty
      r.makeValidated[Int] must beEqualTo(Validated.Valid(42))
    }

    "return Invalid on a missing-input runtime failure" >> {
      val r = fun[Target] *: Registry.empty
      r.makeValidated[Target] must beLike { case Validated.Invalid(t) =>
        t.getMessage must contain("No entry produces")
      }
    }
  }

case class Target(n: Int)
case class A(b: B)
case class B(a: A)
