package registry

import org.specs2.mutable.Specification
import scala.compiletime.testing.{typeChecks, typeCheckErrors}
import Chain.*

class RegistrySpec extends Specification:

  "fun[T]" should {
    "register a case class primary constructor so inputs resolve recursively" >> {
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

    "work for a regular (non-case) class with val parameters" >> {
      val r =
        fun[Plain.Service] +:
        value(Host("h"))   +:
        value(9000)        +:
        Registry.empty

      val s = r.make[Plain.Service]
      s.host === Host("h")
      s.port === 9000
    }

    "work for a regular class with bare constructor parameters" >> {
      val r =
        fun[Plain.Bare]  +:
        value(Host("h")) +:
        value(42)        +:
        Registry.empty

      r.make[Plain.Bare].describe === "Host(h)@42"
    }

    "handle constructors with multiple parameter lists" >> {
      val r =
        fun[Plain.Multi] +:
        value(Host("h")) +:
        value(7)         +:
        Registry.empty

      r.make[Plain.Multi].describe === "h-7"
    }

    "handle a using clause — parameters are resolved from the registry" >> {
      val r =
        fun[Plain.WithUsing] +:
        value(Host("h"))     +:
        value(42)            +:
        Registry.empty

      r.make[Plain.WithUsing].describe === "h:42"
    }

    "handle an old-style implicit parameter list" >> {
      val r =
        fun[Plain.WithImplicit] +:
        value(Host("h"))        +:
        value(99)               +:
        Registry.empty

      r.make[Plain.WithImplicit].describe === "h#99"
    }
  }; br

  "fun(f)" should {
    "register a lambda" >> {
      val r =
        fun((n: Int) => s"n=$n") +:
        value(7)                 +:
        Registry.empty

      r.make[String] === "n=7"
    }

    "accept an eta-expanded constructor reference and be equivalent to fun[Ctor]" >> {
      val r =
        fun(DbConfig.apply) +:
        value(Host("h"))    +:
        value(1)            +:
        Registry.empty

      r.make[DbConfig] === DbConfig(Host("h"), 1)
    }
  }; br

  "value" should {
    "register a constant as a zero-input entry" >> {
      val r = value(42) +: Registry.empty
      r.make[Int] === 42
    }
  }; br

  "resolution" should {
    "be LIFO — the most recently prepended entry wins for a duplicate output" >> {
      val r =
        value("second") +:
        value("first")  +:
        Registry.empty

      r.make[String] === "second"
    }

    "distinguish generic outputs — List[Int] and List[String] are separate producers" >> {
      val r =
        value(List(1, 2, 3))       +:
        value(List("a", "b", "c")) +:
        Registry.empty

      r.make[List[Int]] === List(1, 2, 3)
      r.make[List[String]] === List("a", "b", "c")
    }

    "throw a clear error at runtime when an input is missing" >> {
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

    "throw a cycle error with the full dependency path" >> {
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
  }; br

  "<+>" should {
    "merge two registries, left operand winning on duplicate outputs" >> {
      val left  = value("from-left")  +: Registry.empty
      val right = value("from-right") +: value(7) +: Registry.empty
      val r     = left <+> right

      r.make[String] === "from-left"
      r.make[Int]    === 7
    }

    "supply inputs across a merge boundary" >> {
      val producers = fun[DbConfig] +: Registry.empty
      val values    = value(Host("h")) +: value(1) +: Registry.empty

      (producers <+> values).make[DbConfig] === DbConfig(Host("h"), 1)
    }
  }; br

  "makeSafe" should {
    "compile and run when all deps are satisfied" >> {
      val r =
        fun[DbConfig]    +:
        value(Host("h")) +:
        value(1)         +:
        Registry.empty

      r.makeSafe[DbConfig] === DbConfig(Host("h"), 1)
    }

    "fail to compile naming T and the produced types, one per line" >> {
      val errs = typeCheckErrors("""
        import registry.*
        val r = value(42) +: value("hi") +: Registry.empty
        r.makeSafe[Long]
      """)
      errs must haveSize(1)
      errs.head.message === """No entry in this registry produces the type Long.
                              |
                              |Produced types:
                              |  Int
                              |  String""".stripMargin
    }

    "fail to compile listing missing inputs and produced outputs, one per line" >> {
      val errs = typeCheckErrors("""
        import registry.*
        import Chain.*
        val r = fun[App] +: fun[Db] +: fun[DbConfig] +: Registry.empty
        r.makeSafe[App]
      """)
      errs must haveSize(1)
      errs.head.message === """Some registered entries require inputs that are not produced by this registry.
                              |
                              |Missing inputs:
                              |  AppName
                              |  Host
                              |  Int
                              |
                              |Produced outputs:
                              |  App
                              |  Db
                              |  DbConfig""".stripMargin
    }

    "use the same layout for a single missing input" >> {
      val errs = typeCheckErrors("""
        import registry.*
        import Chain.*
        val r = fun[DbConfig] +: value(Host("h")) +: Registry.empty
        r.makeSafe[DbConfig]
      """)
      errs must haveSize(1)
      errs.head.message === """Some registered entries require inputs that are not produced by this registry.
                              |
                              |Missing inputs:
                              |  Int
                              |
                              |Produced outputs:
                              |  DbConfig
                              |  Host""".stripMargin
    }

    "compile once the missing input is added" >> {
      typeChecks("""
        import registry.*
        import Chain.*
        val r = fun[DbConfig] +: value(Host("h")) +: value(1) +: Registry.empty
        r.makeSafe[DbConfig]
      """) must beTrue
    }
  }; br

  "make" should {
    "stay runtime-only — compile even when a dep is missing, fail at runtime" >> {
      val r = fun[DbConfig] +: value(Host("h")) +: Registry.empty
      r.make[DbConfig] must throwA[RuntimeException]
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
