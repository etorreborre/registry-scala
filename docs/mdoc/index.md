---
title: Home
nav_order: 1
---

# registry

A small dependency-injection / wiring library for Scala 3.

A `Registry` is a list of functions and values. The `Registry` main function, `make[T]`, can be called to make
a value of type `T` by invoking the first function that returns a `T`.
The function parameters are retrieved by recursively calling `make` on the `Registry`. That's it!  

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

Learn more:

- [Getting started](getting-started.md) install, first registry, `make` vs
  `makeSafe`.

- [Concepts](concepts.md) how a `Registry` actually works:
  - [Registry and entries](concepts/registry-and-entries.md) the four
    prepend operators and LIFO precedence.
  - [Resolution](concepts/resolution.md) how `make[T]` walks the dependency graph.
  - [Safety](concepts/safety.md) what `+:` and `makeSafe[T]` check.
  - [Memoization](concepts/memoization.md) `share` vs `const`, ...
  - [Customization](concepts/customization.md) refinements, `erase`, ...

- [Modules](modules.md) per-artifact guides:
  - [`registry`](modules/core.md) the dependency-injection core.
  - [`registry-scalacheck`](modules/scalacheck.md) derive `Gen[T]`
    instances from a registry.
  - [`registry-circe`](modules/circe.md) derive `Encoder[T]` /
    `Decoder[T]` instances from a registry.
  - [`registry-cats`](modules/cats.md) lift constructors, functions,
    and values into any `Applicative[F]`
