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
- _More to come: concepts, per-module guides, recipes._
