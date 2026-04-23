package registry

import org.specs2.mutable.Specification
import scala.compiletime.testing.{typeChecks, typeCheckErrors}
import Chain.*

class RegistrySpec extends Specification:

  """|Create a registry by appending functions and values.
     |If the inputs of a function cannot be produced by the outputs of other functions or values already in the
     |registry, the code fails to compile.""".stripMargin.br

  "fun[T]" should {
    "register a case class primary constructor so inputs resolve recursively" >> {
      val r =
        fun[App] +:
          fun[Db] +:
          fun[DbConfig] +:
          value(Host("localhost")) +:
          value(5432) +:
          value(AppName("my-app"))

      r.make[App] ===
        App(Db(DbConfig(Host("localhost"), 5432)), AppName("my-app"))
    }

    "work for a regular (non-case) class with val parameters" >> {
      val r =
        fun[Plain.Service] +:
          value(Host("h")) +:
          value(9000)

      val s = r.make[Plain.Service]
      s.host === Host("h")
      s.port === 9000
    }

    "work for a regular class with bare constructor parameters" >> {
      val r =
        fun[Plain.Bare] +:
          value(Host("h")) +:
          value(42)

      r.make[Plain.Bare].describe === "Host(h)@42"
    }

    "handle constructors with multiple parameter lists" >> {
      val r =
        fun[Plain.Multi] +:
          value(Host("h")) +:
          value(7)

      r.make[Plain.Multi].describe === "h-7"
    }

    "handle a using clause — parameters are resolved from the registry" >> {
      val r =
        fun[Plain.WithUsing] +:
          value(Host("h")) +:
          value(42)

      r.make[Plain.WithUsing].describe === "h:42"
    }

    "handle an old-style implicit parameter list" >> {
      val r =
        fun[Plain.WithImplicit] +:
          value(Host("h")) +:
          value(99)

      r.make[Plain.WithImplicit].describe === "h#99"
    }
  }; br

  "fun(f)" should {
    "register a lambda" >> {
      val r =
        fun((n: Int) => s"n=$n") +:
          value(7)

      r.make[String] === "n=7"
    }

    "accept an eta-expanded constructor reference and be equivalent to fun[Ctor]" >> {
      val r =
        fun(DbConfig.apply) +:
          value(Host("h")) +:
          value(1)

      r.make[DbConfig] === DbConfig(Host("h"), 1)
    }
  }; br

  "value" should {
    "register a constant as a zero-input entry" >> {
      val r = value(42) +: Registry.empty
      r.make[Int] === 42
    }
  }; br

  "+:" should {
    "compile and build when the entry's inputs are already produced (dependencies first, closest to empty)" >> {
      val r =
        fun[DbConfig] +:
          value(Host("h")) +:
          value(1)

      r.make[DbConfig] === DbConfig(Host("h"), 1)
    }

    "fail to compile naming the missing inputs" >> {
      val errs = typeCheckErrors("""
        import registry.*
        import Chain.*
        // fun[DbConfig] +: empty — DbConfig needs Host + Int, empty produces nothing
        val r = fun[DbConfig] +: Registry.empty
      """)
      errs must haveSize(1)
      errs.head.message === """+: cannot prepend this entry because some inputs cannot be produced by the rest of the registry.
                              |
                              |Missing inputs:
                              |  Host
                              |  Int
                              |
                              |Produced outputs: (none)""".stripMargin
    }
  }; br

  "+: (polymorphic)" should {
    "combine two TypedEntries into a 2-entry registry when the left's inputs are covered by the right's output" >> {
      val r =
        fun((h: Chain.Host) => h.value) +: // input Host, output String
          value(Host("h")) // output Host — covers the left's Host need

      r.make[String] === "h"
    }

    "reject combining two entries when the left's inputs are not covered by the right's output" >> {
      val errs = typeCheckErrors("""
        import registry.*
        import Chain.*
        val r = fun[DbConfig] +: value(Host("h")) // DbConfig needs Host + Int, right gives only Host
      """)
      errs must haveSize(1)
      errs.head.message must contain("Missing inputs:")
      errs.head.message must contain("Int")
    }

    "merge two registries when all inputs are covered by combined outputs" >> {
      // consumers is built with *: (tracked, not strict) because its pieces aren't self-contained.
      val consumers = fun[App] *: fun[Db] *: fun[DbConfig]
      val deps = value(Host("h")) +: value(1) +: value(AppName("x"))
      val r = consumers +: deps // strict merge: consumers' ins covered by consumers.outs ++ deps.outs

      r.make[App] === App(Db(DbConfig(Host("h"), 1)), AppName("x"))
    }

    "reject merging two registries when some input is not covered" >> {
      val errs = typeCheckErrors("""
        import registry.*
        import Chain.*
        val consumers = fun[DbConfig] *: Registry.empty    // needs Host + Int
        val deps      = value(Host("h")) +: Registry.empty // provides only Host
        val r = consumers +: deps
      """)
      errs must haveSize(1)
      errs.head.message must contain("Missing inputs:")
      errs.head.message must contain("Int")
    }

    "prepend a registry above a single entry (registry +: entry)" >> {
      val deps =
        value(Host("h")) +:
          value(1)

      val r = deps +: fun[DbConfig] // right side is a TypedEntry
      r.make[DbConfig] === DbConfig(Host("h"), 1)
    }
  }; br

  "<+>" should {
    "merge two registries, left operand winning on duplicate outputs" >> {
      val left = value("from-left") +: Registry.empty
      val right = value("from-right") +: value(7)
      val r = left <+> right

      r.make[String] === "from-left"
      r.make[Int] === 7
    }

    "supply inputs across a merge boundary" >> {
      val producers = fun[DbConfig] *: Registry.empty
      val values = value(Host("h")) +: value(1)

      (producers <+> values).make[DbConfig] === DbConfig(Host("h"), 1)
    }
  }; br

  "make" should {
    "be LIFO — the most recently prepended entry wins for a duplicate output" >> {
      val r =
        value("second") +:
          value("first")

      r.make[String] === "second"
    }

    "distinguish generic outputs — List[Int] and List[String] are separate producers" >> {
      val r =
        value(List(1, 2, 3)) +:
          value(List("a", "b", "c"))

      r.make[List[Int]] === List(1, 2, 3)
      r.make[List[String]] === List("a", "b", "c")
    }
  }; br

  "erase" should {
    "drop type-level tracking while keeping all entries for runtime use" >> {
      val r =
        fun[DbConfig] +:
          value(Host("h")) +:
          value(1)

      val erased: Registry[EmptyTuple, EmptyTuple] = r.erase
      erased.make[DbConfig] === DbConfig(Host("h"), 1)
    }

    "make makeSafe unable to prove anything after erasure" >> {
      typeChecks("""
        import registry.*
        import Chain.*
        val r = fun[DbConfig] +: value(Host("h")) +: value(1)
        r.erase.makeSafe[DbConfig]
      """) must beFalse
    }
  }
