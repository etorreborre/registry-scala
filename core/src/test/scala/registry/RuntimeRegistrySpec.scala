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

  "tweak" should {
    "post-process a value of the tweaked type" >> {
      val r = (value(42) +: Registry.empty).tweak[Int](_ + 1)
      r.make[Int] === 43
    }

    "compose multiple tweaks in registration order (first-registered runs first)" >> {
      val r = (value(42) +: Registry.empty)
        .tweak[Int](_ + 1) // 42 -> 43
        .tweak[Int](_ * 2) // 43 -> 86
      r.make[Int] === 86
    }

    "apply to values resolved recursively as inputs to a larger build" >> {
      // Tweak the Host, then build a DbConfig that uses it. The tweak runs on the Host value
      // that feeds into DbConfig's constructor, so the final config has the upper-cased host.
      val r =
        (fun[DbConfig] +: value(Host("h")) +: value(5432) +: Registry.empty)
          .tweak[Host](h => Host(h.value.toUpperCase))

      r.make[DbConfig] === DbConfig(Host("H"), 5432)
    }

    "leave unrelated types untouched" >> {
      val r = (value("hi") +: value(42) +: Registry.empty).tweak[Int](_ + 1000)
      r.make[Int] === 1042
      r.make[String] === "hi"
    }

    "be preserved across +: prepends" >> {
      val base = (value(1) +: Registry.empty).tweak[Int](_ * 10)
      val r = fun[Wrap] +: base
      r.make[Wrap] === Wrap(10) // the tweak fires on the Int input before Wrap's ctor runs
    }

    "merge tweaks across <+>" >> {
      val left = (value(5) +: Registry.empty).tweak[Int](_ + 1) // 5 -> 6
      val right = Registry.empty.tweak[Int](_ * 10) // applied second; 6 -> 60
      val merged = left <+> right
      merged.make[Int] === 60
    }
  }; br

  "memoize" should {
    "cache the resolved value of the memoized type (same instance on repeated make)" >> {
      var calls = 0
      val r = (fun { (_: Int) =>
        calls += 1; Wrap(calls)
      } +: value(0) +: Registry.empty)
        .memoize[Wrap]

      val a = r.make[Wrap]
      val b = r.make[Wrap]
      a must beTheSameAs(b)
      calls === 1 // the underlying function ran only once
    }

    "not affect types that weren't memoized" >> {
      var calls = 0
      val r = (fun { (_: Int) =>
        calls += 1; Wrap(calls)
      } +: value(0) +: Registry.empty)
        .memoize[Int] // memoize Int (the leaf), not Wrap

      val a = r.make[Wrap]
      val b = r.make[Wrap]
      a !== b // Wrap is rebuilt each time
      calls === 2
    }

    "apply when the memoized type is consumed as an input (cached Wrap shared across makes)" >> {
      var calls = 0
      case class User(wrap: Wrap)
      val r =
        (fun[User] +:
          fun { (_: Int) =>
            calls += 1; Wrap(calls)
          } +:
          value(0) +:
          Registry.empty).memoize[Wrap]

      val u1 = r.make[User]
      val u2 = r.make[User]
      u1.wrap must beTheSameAs(u2.wrap)
      calls === 1
    }

    "memoizeAll caches every entry in the registry" >> {
      var wrapCalls = 0
      val r = (fun { (_: Int) =>
        wrapCalls += 1; Wrap(wrapCalls)
      } +: value(99) +: Registry.empty).memoizeAll

      r.make[Wrap]
      r.make[Wrap]
      r.make[Int] // also cached (no-op effect since value() already returns the same constant)
      wrapCalls === 1
    }

    "each memoize call returns an independent cache" >> {
      // calling .memoize[Wrap] twice on the same base produces two Registries that do NOT share caches.
      var calls = 0
      val base = fun { (_: Int) =>
        calls += 1; Wrap(calls)
      } +: value(0) +: Registry.empty
      val mem1 = base.memoize[Wrap]
      val mem2 = base.memoize[Wrap]
      val fromM1a = mem1.make[Wrap]
      val fromM1b = mem1.make[Wrap]
      val fromM2 = mem2.make[Wrap]
      fromM1a must beTheSameAs(fromM1b) // within mem1
      fromM1a !== fromM2 // across memoize calls → different caches
      calls === 2
    }
  }; br

  "specialize" should {
    "override a type's value only when building inside the given context" >> {
      // Default Host is "default"; inside a DbConfig build, use "specialized".
      val r = (fun[DbConfig] +: value(Host("default")) +: value(5432) +: Registry.empty)
        .specialize[DbConfig, Host](Host("specialized"))

      r.make[DbConfig] === DbConfig(Host("specialized"), 5432)
      r.make[Host] === Host("default") // direct make, no DbConfig context → default
    }

    "not fire when the context type isn't in the resolution stack" >> {
      val r = (value(Host("h")) +: Registry.empty)
        .specialize[DbConfig, Host](Host("X")) // context never entered

      r.make[Host] === Host("h")
    }

    "compose with later entries: inputs down the chain see the specialized value" >> {
      // App -> Db -> DbConfig -> Host. Specialize Host in the App context.
      val r =
        (fun[Chain.App] +:
          fun[Chain.Db] +:
          fun[Chain.DbConfig] +:
          value(Chain.Host("base")) +:
          value(5432) +:
          value(Chain.AppName("x")) +:
          Registry.empty).specialize[Chain.App, Chain.Host](Chain.Host("in-app"))

      r.make[Chain.App].db.config.host === Chain.Host("in-app")
    }
  }; br

  "specializePath" should {
    "apply only when every type in Path appears in order in the resolution stack" >> {
      // Specialize Host along the [App, Db] path.
      val r =
        (fun[Chain.App] +:
          fun[Chain.Db] +:
          fun[Chain.DbConfig] +:
          value(Chain.Host("base")) +:
          value(5432) +:
          value(Chain.AppName("x")) +:
          Registry.empty).specializePath[(Chain.App, Chain.Db), Chain.Host](Chain.Host("via-db"))

      // Full path: App -> Db -> DbConfig -> Host. [App, Db] is a subsequence → use specialized.
      r.make[Chain.App].db.config.host === Chain.Host("via-db")
    }

    "does not fire if the path elements don't appear in order" >> {
      val r =
        (fun[Chain.DbConfig] +: value(Chain.Host("base")) +: value(5432) +: Registry.empty)
          .specializePath[(Chain.App, Chain.Db), Chain.Host](Chain.Host("never"))
      // We never build an App or Db, so [App, Db] is not a subsequence of the resolution stack.
      r.make[Chain.DbConfig].host === Chain.Host("base")
    }

    "equivalence: specialize[Ctx, T](v) behaves identically to specializePath[Ctx *: EmptyTuple, T](v)" >> {
      val base = fun[DbConfig] +: value(Host("base")) +: value(5432) +: Registry.empty
      val a = base.specialize[DbConfig, Host](Host("specialized"))
      val b = base.specializePath[DbConfig *: EmptyTuple, Host](Host("specialized"))

      a.make[DbConfig] === b.make[DbConfig]
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
