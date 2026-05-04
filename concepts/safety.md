# Safety

The library has two kinds of safety nets: a compile-time check at the prepend
site (`+:`) and a compile-time check at the build site (`makeSafe`). They
overlap but disagree on subtypes — keep the difference in mind.

## `make` vs `makeSafe`

| Operation     | When checked  | Check kind                         |
| ------------- | ------------- | ---------------------------------- |
| `make[T]`     | runtime       | none — throws on missing or cycle  |
| `makeSafe[T]` | compile time  | `T` produced **and** every entry's inputs covered |

```scala
import registry.*

case class Host(value: String)
case class Port(value: Int)
case class DbConfig(host: Host, port: Port)

val r =
  fun[DbConfig] +:
    value(Host("localhost")) +:
    value(Port(5432))
```

```scala
val a = r.make[DbConfig]      // runs
// a: DbConfig = DbConfig(host = Host("localhost"), port = Port(5432))
val b = r.makeSafe[DbConfig]  // also runs, plus a static check
// b: DbConfig = DbConfig(host = Host("localhost"), port = Port(5432))
```

If a type isn't produced, `makeSafe` refuses to compile:

```scala
case class NotRegistered()
r.makeSafe[NotRegistered]
// error:
// Produced types:
//   DbConfig
//   Host
//   Port
// 
// No entry in this registry produces the type NotRegistered.
// r.makeSafe[NotRegistered]
// ^^^^^^^^^^^^^^^^^^^^^^^^^
```

## Strict prepend, in detail

`+:` does its own check at the prepend site: every input the new entry
declares must already be produced by the entries to its right. This is what
forces bottom-up construction.

```scala
fun[DbConfig] +:
  value(Host("only")) +:
  Registry.empty       // missing Port — compiler refuses
// error: 
// +: cannot prepend this entry because some inputs cannot be produced by the rest of the registry:
//     fun[DbConfig]
// 
// Missing inputs:
//   Port
// 
// Produced outputs:
//   Host
```

If you want to defer the check, use `*:` and rely on `makeSafe` later. If
you want no check at all, use `-:`.

## The `=:=` vs `<:<` asymmetry

The big subtlety: **`makeSafe` checks types by exact equality (`=:=`),
while `make` looks them up by subtype (`<:<`)**. A registry can pass `make`
but be rejected by `makeSafe`, or vice versa, when subtypes are involved.

Concrete example. An entry that produces `Cat` satisfies a *runtime* request
for `Animal`:

```scala
trait Animal { def name: String }
case class Cat(name: String) extends Animal

val zoo =
  fun[Cat] +: 
  value("Felix")
```

```scala
zoo.make[Animal] // returns the Cat — subtype match
// res2: Animal = Cat("Felix")
```

But asking `makeSafe[Animal]` won't compile, because `Animal` is not in
the registry's set of produced types (`Cat` is, and `Cat =:= Animal` is
false):

```scala
zoo.makeSafe[Animal]
// error: 
// Produced types:
//   Cat
//   String
// 
// No entry in this registry produces the type Animal.
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
