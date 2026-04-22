package registry

import org.specs2.mutable.Specification
import Chain.*

class RegistrySpec extends Specification:

  "fun[T] registers a case class primary constructor, inputs resolve recursively" >> {
    val r =
      fun[App]                 +:
      fun[Db]                  +:
      fun[DbConfig]            +:
      value(Host("localhost")) +:
      value(5432)              +:
      value(AppName("my-app")) +:
      Registry.empty

    r.make[App] ===
      App(Db(DbConfig(Host("localhost"), 5432)), AppName("my-app"))
  }

  "fun[T] works for a regular (non-case) class with val parameters" >> {
    val r =
      fun[Plain.Service] +:
      value(Host("h"))   +:
      value(9000)        +:
      Registry.empty

    val s = r.make[Plain.Service]
    s.host === Host("h")
    s.port === 9000
  }

  "fun[T] works for a regular class with bare constructor parameters" >> {
    val r =
      fun[Plain.Bare]    +:
      value(Host("h"))   +:
      value(42)          +:
      Registry.empty

    r.make[Plain.Bare].describe === "Host(h)@42"
  }

  "fun[T] handles constructors with multiple parameter lists" >> {
    val r =
      fun[Plain.Multi] +:
      value(Host("h")) +:
      value(7)         +:
      Registry.empty

    r.make[Plain.Multi].describe === "h-7"
  }

  "fun[T] handles a using clause — parameters are resolved from the registry" >> {
    val r =
      fun[Plain.WithUsing] +:
      value(Host("h"))     +:
      value(42)            +:
      Registry.empty

    r.make[Plain.WithUsing].describe === "h:42"
  }

  "fun[T] handles an old-style implicit parameter list" >> {
    val r =
      fun[Plain.WithImplicit] +:
      value(Host("h"))        +:
      value(99)               +:
      Registry.empty

    r.make[Plain.WithImplicit].describe === "h#99"
  }

  "fun(f) registers a lambda" >> {
    val r =
      fun((n: Int) => s"n=$n") +:
      value(7)                 +:
      Registry.empty

    r.make[String] === "n=7"
  }

  "fun(Ctor.apply) via eta-expansion is equivalent to fun[Ctor]" >> {
    val r =
      fun(DbConfig.apply)      +:
      value(Host("h"))         +:
      value(1)                 +:
      Registry.empty

    r.make[DbConfig] === DbConfig(Host("h"), 1)
  }

  "value() registers a constant" >> {
    val r = value(42) +: Registry.empty
    r.make[Int] === 42
  }

  "LIFO — the most recently prepended entry wins for a duplicate output" >> {
    val r =
      value("second") +:
      value("first")  +:
      Registry.empty

    r.make[String] === "second"
  }

  "generic outputs — List[Int] and List[String] are distinct producers" >> {
    val r =
      value(List(1, 2, 3))       +:
      value(List("a", "b", "c")) +:
      Registry.empty

    r.make[List[Int]] === List(1, 2, 3)
    r.make[List[String]] === List("a", "b", "c")
  }

  "missing input — clear error naming the unresolved type" >> {
    val r =
      fun[DbConfig]    +:
      value(Host("h")) +: // Int missing
      Registry.empty

    r.make[DbConfig] must throwA[RuntimeException].like { case e =>
      e.getMessage === """No entry produces scala.Int.
                         |Available outputs:
                         |  registry.Chain::DbConfig
                         |  registry.Chain::Host""".stripMargin
    }
  }

  "<+> merges two registries, left operand wins on duplicate outputs" >> {
    val left  = value("from-left")  +: Registry.empty
    val right = value("from-right") +: value(7) +: Registry.empty
    val r     = left <+> right

    r.make[String] === "from-left"
    r.make[Int]    === 7
  }

  "<+> can supply inputs across a merge boundary" >> {
    val producers = fun[DbConfig] +: Registry.empty
    val values    = value(Host("h")) +: value(1) +: Registry.empty

    (producers <+> values).make[DbConfig] === DbConfig(Host("h"), 1)
  }

  "cycle — A depends on B, B depends on A" >> {
    val r =
      fun((b: Cycle.B) => Cycle.A(b)) +:
      fun((a: Cycle.A) => Cycle.B(a)) +:
      Registry.empty

    r.make[Cycle.A] must throwA[RuntimeException].like { case e =>
      e.getMessage === """Found a cycle while resolving registry.Cycle::A:
                         |  registry.Cycle::A
                         |  registry.Cycle::B
                         |  registry.Cycle::A""".stripMargin
    }
  }

object Chain:
  case class Host(value: String)
  case class AppName(value: String)
  case class DbConfig(host: Host, port: Int)
  case class Db(config: DbConfig)
  case class App(db: Db, name: AppName)

object Plain:
  class Service(val host: Host, val port: Int)
  class Bare(host: Host, port: Int):
    def describe: String = s"Host(${host.value})@$port"
  class Multi(host: Host)(port: Int):
    def describe: String = s"${host.value}-$port"
  class WithUsing(host: Host)(using port: Int):
    def describe: String = s"${host.value}:$port"
  class WithImplicit(host: Host)(implicit port: Int):
    def describe: String = s"${host.value}#$port"

object Cycle:
  case class A(b: B)
  case class B(a: A)
