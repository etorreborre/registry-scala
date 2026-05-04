# registry

A small dependency-injection / wiring library for Scala 3, ported from the
Haskell [`registry`](https://github.com/etorreborre/registry) library.

A `Registry` is a list of value-producing functions ("entries") plus the
machinery to invoke them in the right order. You assemble a graph by
prepending entries, then ask for a value of some type.

```scala
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

```scala
val app: App = r.make[App]
// app: App = App(DbConfig(host = Host("localhost"), port = Port(5432)))
```

Read on:

- [Getting started](getting-started.md) — install, first registry, `make` vs
  `makeSafe`.
- _More to come: concepts, per-module guides, recipes._
