---
title: Getting started
nav_order: 2
---

# Getting started

## Add the dependency

The library is published as four artifacts. Pick the ones you need:

```scala
libraryDependencies ++= Seq(
  "org.atnos" %% "registry"            % "0.1.5", // core
  "org.atnos" %% "registry-scalacheck" % "0.1.5", // optional
  "org.atnos" %% "registry-cats"       % "0.1.5", // optional
  "org.atnos" %% "registry-circe"      % "0.1.5"  // optional
)
```

Only `registry` is required; the others are integrations.

## Your first registry

Entries are added with the strict prepend operator `+:`. The compiler checks
that every input on the left is produced by something on the right.

```scala mdoc:silent
import registry.*

case class Host(value: String)
case class Port(value: Int)
case class DbConfig(host: Host, port: Port)
case class Db(config: DbConfig)
case class App(db: Db)

val r =
  fun[App] +:
    fun[Db] +:
    fun[DbConfig] +:
    value(Host("localhost")) +:
    value(Port(5432))
```

`fun[T]` registers `T`'s primary constructor as a builder for `T`. `value(x)`
registers a constant. The order matters only for resolution precedence (LIFO):
entries near the head of the chain win when several entries produce the same
type.

## Building a value

Two extractors:

```scala mdoc
val app = r.make[App]
```

`make[T]` resolves at runtime. If `T` is missing or there is a cycle, it
throws with a typed error message.

`makeSafe[T]` checks at compile time that `T` is produced and that every
declared input can be produced by the registry:

```scala mdoc
val safeApp = r.makeSafe[App]
```

## What's next

- Concepts: `+:` vs `*:` vs `-:`, resolution rules, `make` vs `makeSafe`,
  memoization.
- Modules: [ScalaCheck](modules/scalacheck.md), cats, [circe](modules/circe.md).
- Recipes: overriding by context, sealed traits, effectful wiring.
