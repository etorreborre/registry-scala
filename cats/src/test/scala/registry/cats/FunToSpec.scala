package registry.cats

import org.specs2.mutable.Specification
import _root_.cats.Id
import _root_.cats.implicits.*
import registry.*
import registry.cats.*

class FunToSpec extends Specification:

  "funTo[F, T]" should {
    "lift a case class constructor into Option (happy path)" >> {
      val r =
        funTo[Option, Person] *:
          valTo[Option, String]("Alice") *:
          valTo[Option, Int](30) *:
          Registry.empty

      r.make[Option[Person]] === Some(Person("Alice", 30))
    }

    "short-circuit to None when any field is None (Option's Applicative)" >> {
      val r =
        funTo[Option, Person] *:
          value(None: Option[String]) *:
          valTo[Option, Int](30) *:
          Registry.empty

      r.make[Option[Person]] === None
    }

    "accumulate errors with Validated / Either Right-only via Applicative" >> {
      // Using Either[String, _] — Monad, so Applicative runs left-to-right and returns first Left.
      val r =
        funTo[[a] =>> Either[String, a], Person] *:
          valTo[[a] =>> Either[String, a], String]("Alice") *:
          value(Left("bad age"): Either[String, Int]) *:
          Registry.empty

      r.make[Either[String, Person]] === Left("bad age")
    }

    "work with cats.Id — essentially pure resolution via Applicative" >> {
      val r =
        funTo[Id, Person] *:
          valTo[Id, String]("Bob") *:
          valTo[Id, Int](42) *:
          Registry.empty

      r.make[Id[Person]] === Person("Bob", 42)
    }

    "compose nested effectful case classes" >> {
      val r =
        funTo[Option, Outer] *:
          funTo[Option, Inner] *:
          valTo[Option, String]("x") *:
          valTo[Option, Int](7) *:
          Registry.empty

      r.make[Option[Outer]] === Some(Outer(Inner("x"), 7))
    }
  }

case class Person(name: String, age: Int)
case class Inner(label: String)
case class Outer(inner: Inner, count: Int)
