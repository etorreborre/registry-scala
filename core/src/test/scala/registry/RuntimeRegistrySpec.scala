package registry

import org.specs2.mutable.Specification
import scala.compiletime.testing.{typeChecks, typeCheckErrors}
import Chain.*

class RuntimeRegistrySpec extends Specification:

  """|Create a registry by appending functions and values.
     |There is no compile-time check that all outputs can be produced when elements are prepended.
     |
     |The *: operator still produces a typed registry that will fail to compile if some inputs are missing
     |when `makeSafe` is used.
     |
     |The -: operator produces an untyped registry that cannot be checked at compile time, errors will surface at runtime."""
    .stripMargin.br

  "make" should {
    "be LIFO — the most recently prepended entry wins for a duplicate output" >> {
      val r =
        value("second") *:
          value("first")

      r.make[String] === "second"
    }

    "distinguish generic outputs — List[Int] and List[String] are separate producers" >> {
      val r =
        value(List(1, 2, 3)) *:
          value(List("a", "b", "c"))

      r.make[List[Int]] === List(1, 2, 3)
      r.make[List[String]] === List("a", "b", "c")
    }

    "resolve a request with a registered subtype (List[Int] satisfies Seq[Int])" >> {
      val r = value(List(1, 2, 3): List[Int]) *: Registry.empty
      r.make[Seq[Int]] === Seq(1, 2, 3)
    }

    "resolve via a function whose input is a supertype (asks for Seq[Int], registered List[Int])" >> {
      val sumSeq: Seq[Int] => Int = _.sum
      val r =
        fun(sumSeq) *:
          value(List(1, 2, 3): List[Int]) *:
          Registry.empty
      r.make[Int] === 6
    }

    "reject the wrong direction — Seq[Int] registered does not satisfy a request for List[Int]" >> {
      val r = value(Seq(1, 2, 3): Seq[Int]) *: Registry.empty
      r.make[List[Int]] must throwA[RuntimeException].like { case e =>
        e.getMessage must contain("No entry produces")
        e.getMessage must contain("List")
      }
    }

    "still distinguish unrelated generic types — List[Int] does not satisfy Seq[String]" >> {
      val r = value(List(1, 2, 3): List[Int]) *: Registry.empty
      r.make[Seq[String]] must throwA[RuntimeException]
    }

    "match a registered class against a requested supertype/interface" >> {
      val r = value(Subtype.Impl("hello"): Subtype.Impl) *: Registry.empty
      r.make[Subtype.Iface].label === "hello"
    }
  }

  "runtime errors" should {
    "throw a clear error at runtime when an input is missing" >> {
      val r =
        fun[DbConfig] *:
          value(Host("h")) // Int missing

      r.make[DbConfig] must throwA[RuntimeException].like { case e =>
        e.getMessage === """No entry produces scala.Int.
                           |Available outputs:
                           |  registry.Chain::DbConfig
                           |  registry.Chain::Host""".stripMargin
      }
    }

    "throw a cycle error with the full dependency path" >> {
      val r =
        fun((b: Cycle.B) => Cycle.A(b)) *:
          fun((a: Cycle.A) => Cycle.B(a))

      r.make[Cycle.A] must throwA[RuntimeException].like { case e =>
        e.getMessage === """Found a cycle while resolving registry.Cycle::A:
                           |  registry.Cycle::A
                           |  registry.Cycle::B
                           |  registry.Cycle::A""".stripMargin
      }
    }
  }; br

  "*:" should {
    "prepend without checking that inputs are satisfied" >> {
      // Building a partial registry with *: doesn't care about coverage — make fails at runtime, not compile.
      val r = fun[DbConfig] *: value(Host("h"))
      r.make[Host] === Host("h")
    }

    "combine two entries into a 2-entry tracked registry (entry *: entry)" >> {
      val r = fun((h: Host) => h.value) *: value(Host("h"))
      r.make[String] === "h"
    }

    "merge two tracked registries (registry *: registry)" >> {
      val consumers = fun[DbConfig] *: Registry.empty
      val deps = value(Host("h")) +: value(1)
      val r = consumers *: deps
      r.make[DbConfig] === DbConfig(Host("h"), 1)
    }

    "prepend a registry above a single entry (registry *: entry)" >> {
      val deps = value(Host("h")) +: value(1)
      val r = deps *: fun[DbConfig]
      r.make[DbConfig] === DbConfig(Host("h"), 1)
    }
  }; br

  "-:" should {
    "add an entry at runtime without updating the type-level accounting" >> {
      val r = value(42) -: Registry.empty
      r.make[Int] === 42
    }

    "accept a raw Entry via its overload" >> {
      val raw = Entry(Nil, izumi.reflect.Tag[Int].tag, _ => 99)
      val r: Registry[EmptyTuple, EmptyTuple] = raw -: Registry.empty
      r.make[Int] === 99
    }

    "combine two entries into a registry whose types reflect only the receiver (entry -: entry)" >> {
      val r = value(Host("dynamic")) -: fun((h: Host) => h.value)
      r.make[String] === "dynamic"
    }

    "merge a registry into another, keeping only the receiver's types (registry -: registry)" >> {
      val hidden = value(7) +: Registry.empty
      val visible = value("v") +: Registry.empty
      val merged = hidden -: visible
      merged.make[Int] === 7 // hidden's entry is still there at runtime
      merged.make[String] === "v"
    }

    "prepend an invisible registry above a single entry (registry -: entry)" >> {
      val hidden = value(Host("h")) +: value(1)
      val r = hidden -: fun[DbConfig]
      r.make[DbConfig] === DbConfig(Host("h"), 1)
    }
  }; br

  "make" should {
    "stay runtime-only — compile even when a dep is missing, fail at runtime" >> {
      val r = fun[DbConfig] *: value(Host("h"))
      r.make[DbConfig] must throwA[RuntimeException]
    }
  }

  "makeSafe" should {
    "compile and run when all deps are satisfied" >> {
      val r =
        fun[DbConfig] *:
          value(Host("h")) *:
          value(1)

      r.makeSafe[DbConfig] === DbConfig(Host("h"), 1)
    }

    "fail to compile naming T and the produced types, one per line" >> {
      val errs = typeCheckErrors("""
        import registry.*
        val r = value(42) *: value("hi")
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
        val r = fun[App] *: fun[Db] *: fun[DbConfig]
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
        val r = fun[DbConfig] *: value(Host("h"))
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
        val r = fun[DbConfig] *: value(Host("h")) *: value(1)
        r.makeSafe[DbConfig]
      """) must beTrue
    }

    "flag *:-added entries that aren't fully covered" >> {
      typeChecks("""
        import registry.*
        import Chain.*
        val r = fun[DbConfig] *: value(Host("h"))
        r.makeSafe[DbConfig]
      """) must beFalse
    }

    "treat -:-added entries as invisible" >> {
      typeChecks("""
        import registry.*
        val r: Registry[EmptyTuple, EmptyTuple] = value(42) -: Registry.empty
        r.makeSafe[Int]
      """) must beFalse
    }
  }; br
