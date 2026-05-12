---
title: Home
nav_order: 1
---

# registry

A small dependency-injection / wiring library for Scala 3, ported from the
Haskell [`registry`](https://github.com/etorreborre/registry) library.

A `Registry` is a list of value-producing functions ("entries") plus the
machinery to invoke them in the right order. You assemble a graph by
prepending entries, then ask for a value of some type.

```scala mdoc:silent
import registry.*

case class Host(value: String)
case class Port(value: Int)
case class DbConfig(host: Host, port: Port)
case class App(db: DbConfig)

val r =
  fun[App] +:
    fun[DbConfig] +:
    value(Host("localhost")) +:
    value(Port(5432))
```

```scala mdoc
val app: App = r.make[App]
```

Read on:

- [Getting started](getting-started.md) — install, first registry, `make` vs
  `makeSafe`.
- [Concepts](concepts.md) — how a `Registry` actually works:
  - [Registry and entries](concepts/registry-and-entries.md) — the four
    prepend operators and LIFO precedence.
  - [Resolution](concepts/resolution.md) — how `make[T]` walks the graph.
  - [Safety](concepts/safety.md) — what `+:` and `makeSafe[T]` check.
  - [Memoization](concepts/memoization.md) — `share` vs `const`, per-call
    vs per-registry caching.
  - [Customization](concepts/customization.md) — refinements, `erase`, and
    wrapping `fun`s.
- [Modules](modules.md) — per-artifact guides:
  - [`registry`](modules/core.md) — the dependency-injection core.
  - [`registry-scalacheck`](modules/scalacheck.md) — derive `Gen[T]`
    instances from a registry.
  - [`registry-circe`](modules/circe.md) — derive `Encoder[T]` /
    `Decoder[T]` instances from a registry.
