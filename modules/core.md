---
title: Core
parent: Modules
nav_order: 1
---

# `registry` — core

The core module is enough on its own. Everything else (`registry-scalacheck`,
`registry-cats`, `registry-circe`) is an integration that lifts the same
primitives into a specific shape — generators, applicative effects, JSON
codecs.

## Cheat sheet

### Entries

| Factory    | Use                                | File        |
|------------|------------------------------------|-------------|
| `fun[T]`   | Register `T`'s primary constructor | `Fun.scala` |
| `fun(f)`   | Register a function or lambda      | `Fun.scala` |
| `value(x)` | Register a constant (zero inputs)  | `Fun.scala` |

### Combinators

| Op    | Tracks types | Compile-checked | Notes                                 |
|-------|--------------|-----------------|---------------------------------------|
| `+:`  | yes          | yes             | strict; bottom-up build order         |
| `*:`  | yes          | no              | sketch mode; defer to `makeSafe`      |
| `-:`  | no           | no              | escape hatch; invisible to `makeSafe` |
| `<+>` | yes          | no              | merge; left wins on duplicates        |

### Building

| Method        | When checked | What it does                                     |
|---------------|--------------|--------------------------------------------------|
| `make[T]`     | runtime      | resolve and return; throws on failure            |
| `makeSafe[T]` | compile time | check `T` produced + inputs covered, then `make` |

### Customization

| Method                              | Effect                                                |
|-------------------------------------|-------------------------------------------------------|
| `refine[Path, T](v)`                | path-scoped override; `Path` is a single type or tuple |
| `refine[Path](v)`                   | same override, with `T` inferred from `v`              |
| `refine[Path, T](v)` (factory)      | refinement as a value; composes with `+:`/`*:`/`-:`    |
| `memoize[A]`                        | cache every entry whose output is `A` (subtype-aware) |
| `memoizeAll`                        | cache every entry                                     |
| `entry.memoize`                     | cache one entry, inline                               |
| `erase`                             | drop type-level tracking                              |

## A worked example

```scala
import registry.*

case class Host(value: String)
case class Port(value: Int)
case class DbConfig(host: Host, port: Port)
case class Db(config: DbConfig)
case class App(db: Db, env: String)

val app =
  fun[App] +:
    fun[Db] +:
    fun[DbConfig] +:
    value(Host("localhost")) +:
    value(Port(5432)) +:
    value("dev")
```

```scala
app.makeSafe[App]
// res0: App = App(
//   db = Db(DbConfig(host = Host("localhost"), port = Port(5432))),
//   env = "dev"
// )
```

Override the host without rewriting the registry:

```scala
app.refine[Db](Host("override")).make[App]
// res1: App = App(
//   db = Db(DbConfig(host = Host("override"), port = Port(5432))),
//   env = "dev"
// )
```

Override the host only when reached via `App` → `Db`:

```scala
app.refine[(App, Db)](Host("via-app-db")).make[App]
// res2: App = App(
//   db = Db(DbConfig(host = Host("via-app-db"), port = Port(5432))),
//   env = "dev"
// )
```

Memoize `Db` so two consumers share one connection (mock here):

```scala
val pooled = app.memoize[Db]
// pooled: Registry[*:[Db, *:[String, *:[DbConfig, *:[Host, *:[Port, EmptyTuple]]]]], *:[App, *:[Db, *:[DbConfig, *:[Host, *:[Port, *:[String, EmptyTuple]]]]]]] = Registry(
//   entries = List(
//     Basic(
//       inputs = List(MdocSession::MdocApp::Db, String),
//       output = MdocSession::MdocApp::App,
//       invoke = repl.MdocSession$MdocApp$$Lambda/0x00007f92d9ac55b8@3ff01d5e,
//       fresh = false,
//       resetFn = registry.Entry$Basic$$$Lambda/0x00007f92d9a1a768@4d80783a
//     ),
//     Basic(
//       inputs = List(MdocSession::MdocApp::DbConfig),
//       output = MdocSession::MdocApp::Db,
//       invoke = registry.Registry$$$Lambda/0x00007f92d9a76608@69e01994,
//       fresh = false,
//       resetFn = registry.Entry$Basic$$Lambda/0x00007f92d9a76cb8@3497284d
//     ),
//     Basic(
//       inputs = List(MdocSession::MdocApp::Host, MdocSession::MdocApp::Port),
//       output = MdocSession::MdocApp::DbConfig,
//       invoke = repl.MdocSession$MdocApp$$Lambda/0x00007f92d9ac66a0@5a510ab2,
//       fresh = false,
//       resetFn = registry.Entry$Basic$$$Lambda/0x00007f92d9a1a768@4d80783a
//     ),
//     Basic(
//       inputs = List(),
//       output = MdocSession::MdocApp::Host,
//       invoke = registry.Fun$package$$$Lambda/0x00007f92d9a1b190@23895ae1,
//       fresh = false,
//       resetFn = registry.Entry$Basic$$$Lambda/0x00007f92d9a1a768@4d80783a
//     ),
//     Basic(
//       inputs = List(),
//       output = MdocSession::MdocApp::Port,
//       invoke = registry.Fun$package$$$Lambda/0x00007f92d9a1b190@1cacb923,
//       fresh = false,
//       resetFn = registry.Entry$Basic$$$Lambda/0x00007f92d9a1a768@4d80783a
//     ),
//     Basic(
//       inputs = List(),
//       output = String,
//       invoke = registry.Fun$package$$$Lambda/0x00007f92d9a1b190@2a798fda,
//       fresh = false,
//       resetFn = registry.Entry$Basic$$$Lambda/0x00007f92d9a1a768@4d80783a
//     )
//   ),
//   refinements = List()
// )
val a1 = pooled.make[Db]
// a1: Db = Db(DbConfig(host = Host("localhost"), port = Port(5432)))
val a2 = pooled.make[Db]
// a2: Db = Db(DbConfig(host = Host("localhost"), port = Port(5432)))
a1 eq a2
// res3: Boolean = true
```

## Where to read next

- [Registry and entries](../concepts/registry-and-entries.md) — how the
  pieces fit, prepend operators, LIFO precedence.
- [Resolution](../concepts/resolution.md) — runtime algorithm, subtype
  lookup, cycle detection.
- [Safety](../concepts/safety.md) — `make` vs `makeSafe`, the `=:=` vs
  `<:<` asymmetry.
- [Customization](../concepts/customization.md) — refine, erase.
- [Memoization](../concepts/memoization.md) — sharing instances across the
  graph.
