---
title: Safety
parent: Concepts
nav_order: 3
---

# Safety

The library has two kinds of safety nets: a compile-time check at the prepend
site (`+:`) and a compile-time check at the build site (`makeSafe`). They
overlap but disagree on subtypes — keep the difference in mind.

## `make` vs `makeSafe`

| Operation     | When checked  | Check kind                         |
| ------------- | ------------- | ---------------------------------- |
| `make[T]`     | runtime       | none — throws on missing or cycle  |
| `makeSafe[T]` | compile time  | `T` produced **and** every entry's inputs covered |

```scala mdoc:silent
import registry.*

case class Host(value: String)
case class Port(value: Int)
case class DbConfig(host: Host, port: Port)

val r =
  fun[DbConfig] +:
    value(Host("localhost")) +:
    value(Port(5432))
```

```scala mdoc
val a = r.make[DbConfig]      // runs
val b = r.makeSafe[DbConfig]  // also runs, plus a static check
```

If a type isn't produced, `makeSafe` refuses to compile:

```scala mdoc:fail
case class NotRegistered()
r.makeSafe[NotRegistered]
```

## Strict prepend, in detail

`+:` does its own check at the prepend site: every input the new entry
declares must already be produced by the entries to its right. This is what
forces bottom-up construction.

```scala mdoc:fail
fun[DbConfig] +:
  value(Host("only")) +:
  Registry.empty       // missing Port — compiler refuses
```

If you want to defer the check, use `*:` and rely on `makeSafe` later. If
you want no check at all, use `-:`.

## The `=:=` vs `<:<` asymmetry

The big subtlety: **`makeSafe` checks types by exact equality (`=:=`),
while `make` looks them up by subtype (`<:<`)**. A registry can pass `make`
but be rejected by `makeSafe`, or vice versa, when subtypes are involved.

Concrete example. An entry that produces `Cat` satisfies a *runtime* request
for `Animal`:

```scala mdoc:silent
trait Animal { def name: String }
case class Cat(name: String) extends Animal

val zoo =
  fun[Cat] +: 
  value("Felix")
```

```scala mdoc
zoo.make[Animal] // returns the Cat — subtype match
```

But asking `makeSafe[Animal]` won't compile, because `Animal` is not in
the registry's set of produced types (`Cat` is, and `Cat =:= Animal` is
false):

```scala mdoc:fail
zoo.makeSafe[Animal]
```

The same rule applies to entry inputs. If an entry needs `Animal` and
something else produces `Cat`, `make` will wire it up at runtime; `makeSafe`
won't accept it at compile time. When you rely on subtyping, prefer `make`,
or align the static type by widening the entry's signature.

## When to use which

- **Iterating quickly** — `make`. Fast feedback; runtime errors are clear.
- **Locking the wiring** — `makeSafe` at the call site that matters (e.g. an
  application's `main`). Catches "you forgot to register X" at compile time.
- **Subtype-heavy graphs** — stick with `make`; `makeSafe` will fight you.
- **Mid-build sketching** — `*:` defers the prepend-site check while keeping
  the type-level accounting. Switch to `+:` once the layout settles.
