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
| `refine[Ctx, T](v)`                 | when building inside `Ctx`, return `v` for `T`        |
| `refinePath[(A, B, ...), T](v)`     | same, but require the path as a subsequence           |
| `refine[Path, T](v)` (factory)      | refinement as a value; composes with `+:`/`*:`/`-:`   |
| `memoize[A]`                        | cache every entry whose output is `A` (subtype-aware) |
| `memoizeAll`                        | cache every entry                                     |
| `entry.memoize`                     | cache one entry, inline                               |
| `erase`                             | drop type-level tracking                              |

## A worked example

```scala mdoc:silent
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

```scala mdoc
app.makeSafe[App]
```

Override the host without rewriting the registry:

```scala mdoc
app.refine[Db, Host](Host("override")).make[App]
```

Override the host only when reached via `App` → `Db`:

```scala mdoc
app.refinePath[(App, Db), Host](Host("via-app-db")).make[App]
```

Memoize `Db` so two consumers share one connection (mock here):

```scala mdoc
val pooled = app.memoize[Db]
val a1 = pooled.make[Db]
val a2 = pooled.make[Db]
a1 eq a2
```

## Where to read next

- [Registry and entries](../concepts/registry-and-entries.md) — how the
  pieces fit, prepend operators, LIFO precedence.
- [Resolution](../concepts/resolution.md) — runtime algorithm, subtype
  lookup, cycle detection.
- [Safety](../concepts/safety.md) — `make` vs `makeSafe`, the `=:=` vs
  `<:<` asymmetry.
- [Customization](../concepts/customization.md) — refine, refinePath,
  erase.
- [Memoization](../concepts/memoization.md) — sharing instances across the
  graph.
