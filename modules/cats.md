---
title: Cats
parent: Modules
nav_order: 4
---

# `registry-cats`

Lift constructors, functions, and values into any `Applicative[F]` so
the registry resolves `F[T]` instead of `T`. Where the core registry
wires `T`s together by calling each constructor directly,
`registry-cats` wires `F[T]`s together by sequencing them through
`Applicative[F].product`.

The same shape — entries, LIFO precedence, `+:` / `*:` / `-:`,
`make` / `makeSafe` — applies; only the entry factories change.

## Setup

```scala
import registry.*
import registry.cats.*
import cats.Applicative
import cats.Id
import cats.implicits.*
```

`registry.cats.*` brings in `funTo`, `valTo`, and the non-throwing
resolution extensions `makeEither` / `makeValidated`.

## Lifting a constructor: `funTo[F, T]`

```scala
case class Person(name: String, age: Int)

val r =
  funTo[Option, Person] *:
    valTo[Option]("Alice") *:
    valTo[Option](30)
```

```scala
r.make[Option[Person]]
// Some(Person("Alice", 30))
```

`funTo[Option, Person]` walks `Person`'s primary constructor and
registers an entry whose declared inputs are `Option[String]` and
`Option[Int]`, and whose output is `Option[Person]`. At runtime each
input is resolved as an `Option`, the effects are sequenced via
`Applicative[Option].product`, and the primary constructor is applied
to the collected values.

`valTo[F](x)` lifts a pure value via `Applicative[F].pure` — it is the
`F`-shaped equivalent of `value(x)`.

## Applicative semantics, for free

Because resolution goes through `Applicative[F].product`, the effect's
own short-circuiting and accumulation rules apply with no extra
wiring. The `Option`-based registry above returns `None` as soon as
any field is missing:

```scala
val maybeMissing =
  funTo[Option, Person] *:
    value(None: Option[String]) *:
    valTo[Option](30)

maybeMissing.make[Option[Person]]
// None
```

`Either[String, _]` follows the same pattern — its `Applicative`
returns the first `Left`:

```scala
val withError =
  funTo[[a] =>> Either[String, a], Person] *:
    valTo[[a] =>> Either[String, a]]("Alice") *:
    value(Left("bad age"): Either[String, Int])

withError.make[Either[String, Person]]
// Left("bad age")
```

`cats.Id` makes the whole thing pure — useful when an existing graph
mixes `Id` and other effects:

```scala
val pure =
  funTo[Id, Person] *:
    valTo[Id]("Bob") *:
    valTo[Id](42)

pure.make[Id[Person]]
// Person("Bob", 42)
```

## Lifting an arbitrary function: `funTo[F](f)`

`funTo[F]` also accepts a value of some `FunctionN` type. The
function's parameter types become `F[…]` inputs, the return type
becomes `F[…]` output. Useful for smart constructors, eta-expanded
method references, and any case where there isn't a class to derive
from:

```scala
case class Greeting(text: String)

val g =
  funTo[Option]((n: String, a: Int) => Greeting(s"$n ($a)")) *:
    valTo[Option]("Alice") *:
    valTo[Option](30)

g.make[Option[Greeting]]
// Some(Greeting("Alice (30)"))
```

```scala
val viaApply =
  funTo[Option](Person.apply) *:
    valTo[Option]("Bob") *:
    valTo[Option](42)

viaApply.make[Option[Person]]
// Some(Person("Bob", 42))
```

Single-arg lambdas are accepted too — there's no special
contramap/map form to remember:

```scala
val one =
  funTo[Option]((n: Int) => Greeting(s"n=$n")) *:
    valTo[Option](21)

one.make[Option[Greeting]]
// Some(Greeting("n=21"))
```

## Composing nested effectful types

`funTo[F, T]` entries compose: an outer entry that needs `F[Inner]`
resolves against an inner entry that produces `F[Inner]`, and so on.

```scala
case class Inner(label: String)
case class Outer(inner: Inner, count: Int)

val nested =
  funTo[Option, Outer] *:
    funTo[Option, Inner] *:
    valTo[Option]("x") *:
    valTo[Option](7)

nested.make[Option[Outer]]
// Some(Outer(Inner("x"), 7))
```

## Non-throwing resolution: `makeEither` / `makeValidated`

`Registry.make[T]` throws on resolution failure (missing input, cycle,
type mismatch, user code throwing inside a registered function). The
`cats` module adds two extension methods that catch any
`NonFatal` exception and return it through the result type:

```scala
val ok = value(42) +: Registry.empty
ok.makeEither[Int]
// Right(42)

case class Target(n: Int)
val broken = fun[Target] *: Registry.empty // no Int producer
broken.makeEither[Target]
// Left(java.lang.RuntimeException: No entry produces …)
```

`makeValidated[T]` returns a `Validated[Throwable, T]`. The error
channel is a single `Throwable`, not a `NonEmptyList` — registry
resolution produces at most one error (the first missing input, cycle,
or user exception). `Validated` is offered over `Either` so multiple
`makeValidated` calls can be combined applicatively at the call site:

```scala
import cats.data.Validated

val a = ok.makeValidated[Int]
val b = ok.makeValidated[Int]
(a, b).mapN(_ + _) // Valid(84)
```

## Memoization and `cats.effect.IO`

`memoize[T]` on a `Registry` caches the **value** that an entry
produces. With `IO`, the cached value is an `IO[Service]` — running
that `IO` still re-executes the effect. This is usually surprising;
the test suite spells out both layers:

```scala
import cats.effect.IO
import cats.effect.unsafe.implicits.global

val counter = new java.util.concurrent.atomic.AtomicInteger(0)
val acquire: IO[Service] =
  IO.delay { counter.incrementAndGet(); new Service() }

val r = (value(acquire) +: Registry.empty).memoize[IO[Service]]

val io1 = r.make[IO[Service]]
val io2 = r.make[IO[Service]]
io1 eq io2          // true  — same IO value from the registry cache
io1.unsafeRunSync() // counter = 1
io1.unsafeRunSync() // counter = 2 — IO itself is not memoized
```

To also memoize the **result** of running the effect, stack
cats-effect's `IO.memoize` on top before registering. `acquire.memoize`
returns `IO[IO[Service]]` — an `IO` that creates a fresh memoized cell
each time it runs. Run it once to materialize the cell, then register
the inner `IO`:

```scala
val memoizedIO: IO[Service] = acquire.memoize.unsafeRunSync()

val rr = value(memoizedIO) +: Registry.empty
val io = rr.make[IO[Service]]

io.unsafeRunSync() // counter = 1
io.unsafeRunSync() // counter = 1 — cached
```

The rule: pick which layer you want to share, and apply memoization
there. `registry.memoize` shares the `IO` *value*; `IO.memoize` shares
the *result* of running it.

## Why `*:` in the examples

The `cats` examples above use `*:` (the sketch prepend) rather than
`+:`. `funTo[F, T]` returns a `TypedEntry[? <: Tuple, F[T]]` with an
existentially-quantified `Ins` tuple, so the compile-time input/output
check that `+:` performs can't see through it. Use `*:` while
composing and defer the check to `makeSafe` at the top of the graph.

See [Safety](../concepts/safety.md) for the tradeoff between `+:` and
`*:` and when each makes sense.

## What to read next

- [Registry and entries](../concepts/registry-and-entries.md) — prepend
  operators, LIFO precedence, what `<+>` does.
- [Resolution](../concepts/resolution.md) — runtime algorithm; how
  inputs declared as `F[A]` are looked up.
- [Memoization](../concepts/memoization.md) — sharing instances across
  the graph; complements the `IO` discussion above.
