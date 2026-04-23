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
          valTo[Option]("Alice") *:
          valTo[Option](30) *:
          Registry.empty

      r.make[Option[Person]] === Some(Person("Alice", 30))
    }

    "short-circuit to None when any field is None (Option's Applicative)" >> {
      val r =
        funTo[Option, Person] *:
          value(None: Option[String]) *:
          valTo[Option](30) *:
          Registry.empty

      r.make[Option[Person]] === None
    }

    "accumulate errors with Validated / Either Right-only via Applicative" >> {
      // Using Either[String, _] — Monad, so Applicative runs left-to-right and returns first Left.
      val r =
        funTo[[a] =>> Either[String, a], Person] *:
          valTo[[a] =>> Either[String, a]]("Alice") *:
          value(Left("bad age"): Either[String, Int]) *:
          Registry.empty

      r.make[Either[String, Person]] === Left("bad age")
    }

    "work with cats.Id — essentially pure resolution via Applicative" >> {
      val r =
        funTo[Id, Person] *:
          valTo[Id]("Bob") *:
          valTo[Id](42) *:
          Registry.empty

      r.make[Id[Person]] === Person("Bob", 42)
    }

    "lift an arbitrary lambda: funTo[F]((a, b) => …)" >> {
      // Output type differs from input types so there's no LIFO cycle resolving
      // a type that overlaps with an input.
      val formatPerson: (String, Int) => Greeting = (n, a) => Greeting(s"$n ($a)")
      val r =
        funTo[Option](formatPerson) *:
          valTo[Option]("Alice") *:
          valTo[Option](30) *:
          Registry.empty

      r.make[Option[Greeting]] === Some(Greeting("Alice (30)"))
    }

    "lift an eta-expanded constructor via funTo[F](Foo.apply)" >> {
      val r =
        funTo[Option](Person.apply) *:
          valTo[Option]("Bob") *:
          valTo[Option](42) *:
          Registry.empty

      r.make[Option[Person]] === Some(Person("Bob", 42))
    }

    "lift a single-arg function" >> {
      val r =
        funTo[Option]((n: Int) => Greeting(s"n=$n")) *:
          valTo[Option](21) *:
          Registry.empty

      r.make[Option[Greeting]] === Some(Greeting("n=21"))
    }

    "short-circuit via Applicative when one of the lambda's inputs is None" >> {
      val r =
        funTo[Option]((n: String, a: Int) => Greeting(s"$n/$a")) *:
          value(None: Option[String]) *:
          valTo[Option](30) *:
          Registry.empty

      r.make[Option[Greeting]] === None
    }

    "compose nested effectful case classes" >> {
      val r =
        funTo[Option, Outer] *:
          funTo[Option, Inner] *:
          valTo[Option]("x") *:
          valTo[Option](7) *:
          Registry.empty

      r.make[Option[Outer]] === Some(Outer(Inner("x"), 7))
    }
  }

case class Person(name: String, age: Int)
case class Greeting(text: String)
case class Inner(label: String)
case class Outer(inner: Inner, count: Int)
