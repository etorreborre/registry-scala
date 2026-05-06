---
title: ScalaCheck
parent: Modules
nav_order: 2
has_children: true
---

# `registry-scalacheck`

Derives [ScalaCheck](https://scalacheck.org/) `Gen[T]` instances from
registered leaf generators. Instead of hand-wiring `for { … } yield …` for
every case class, register one `gen[T]` per type and ask the registry for
`Gen[T]`. The macro inspects `T`'s primary constructor (or sealed-trait
variants) and emits the entry that sequences inputs via `flatMap`/`map`.

The walkthrough below builds up a generator registry from the simplest case
to recursive types and shared samples. For an at-a-glance API reference,
see the [Cheat sheet](scalacheck-cheat-sheet.md).

## Setup

The mdoc helper used below samples a `Gen` deterministically with a fixed
seed:

```scala mdoc:silent
import org.scalacheck.{Gen, rng}
def sample[A](g: Gen[A]): A =
  g.pureApply(Gen.Parameters.default, rng.Seed(42L))
```

## Deriving a `Gen[T]` for a case class

```scala mdoc:silent
import registry.*
import registry.scalacheck.*

case class Address(street: String, zip: Int)
case class Person(name: String, age: Int, address: Address)

val r =
  gen[Person] +:
    gen[Address] +:
    gen(Gen.alphaStr) +:
    gen(Gen.choose(0, 120)) +:
    gen(Gen.choose(10000, 99999))
```

```scala mdoc
sample(r.makeGen[Person])
```

`gen[Person]` walks `Person`'s primary constructor (`name: String`,
`age: Int`, `address: Address`) and registers an entry that needs
`Gen[String]`, `Gen[Int]`, `Gen[Address]`. The registry resolves each via
LIFO; the right-hand entries supply the leaves.

For class-internal types you'd usually want a single `Gen[Int]` shared,
but in this case `name` and `zip` both need `Gen[Int]` resolutions of
different ranges. The two `gen(Gen.choose(…))` entries are distinguished
only by registration order — the second one wins for the inner `Address`.

## Value-driven `gen(...)`

```scala mdoc:silent
case class Tagged(label: String, value: Int)

val tagged =
  gen[Tagged] +:
    gen("FIXED") +:                     // constant — Gen.const("FIXED")
    gen(Gen.choose(1, 10))              // existing ScalaCheck Gen
```

```scala mdoc
sample(tagged.makeGen[Tagged])
```

The macro inspects the value passed to `gen(...)`:

- a function value → its parameter types become `Gen[…]` inputs;
- a `Gen[T]` → registered as a zero-input `Gen[T]` entry;
- anything else → wrapped via `Gen.const`.

You can also pass an eta-expanded constructor reference: `gen(Person.apply)`.

## `refineGen[Path](v)` — path-scoped generator overrides

`refineGen` is the ScalaCheck-flavored version of core `refine`: each
element of `Path` is interpreted as a generated type, and the refined
payload type is inferred from the value you pass.

```scala mdoc:silent
case class User(name: String, age: Int)

val users =
  gen[User] +:
    gen(Gen.alphaStr) +:
    gen(Gen.choose(0, 120))
```

```scala mdoc
sample(users.refineGen[User]("eric").makeGen[User])
sample(users.refineGen[User](Gen.choose(18, 99)).makeGen[User])
```

Plain values are lifted with `Gen.const`; existing `Gen[T]` values are
used as-is. The standalone form composes like any other refinement:

```scala mdoc
sample((refineGen[User]("standalone") +: users).makeGen[User])
```

For multi-step scopes, use a tuple path just like core `refine`:
`r.refineGen[(Outer, User)]("nested")`.

## `arb[T]` — from an in-scope `Arbitrary`

```scala mdoc:silent
import org.scalacheck.Arbitrary

case class Stamp(id: Int)

val stamps =
  gen[Stamp] +: arb[Int]
```

```scala mdoc
sample(stamps.makeGen[Stamp])
```

Equivalent to `gen(Arbitrary.arbitrary[Int])` but lighter at the call
site — the typical pattern when leaves are already supplied by implicit
`Arbitrary` instances.

## Sealed traits, enums, sum types

`gen[T]` on a sealed type expands into a registry bundling `genTrait`,
one entry per variant, and a default `Chooser.uniform`.

```scala mdoc:silent
sealed trait Animal
case class Dog(name: String) extends Animal
case class Cat(lives: Int)   extends Animal

val zoo =
  gen[Animal] +:
    gen(Gen.alphaStr) +:
    gen(Gen.choose(1, 9))
```

```scala mdoc
sample(zoo.makeGen[Animal])
```

To control the variant distribution, swap the registered `Chooser` —
`genTrait[T]` consumes whatever `Chooser` is in scope:

```scala mdoc:silent
val mostlyDogs =
  genTrait[Animal] +:
    gen[Dog] +:
    gen[Cat] +:
    value(Chooser.weighted(9, 1)) +:    // 9× more Dogs than Cats
    gen(Gen.alphaStr) +:
    gen(Gen.choose(1, 9))
```

```scala mdoc:silent
val samples =
  (0 until 50).map(i =>
    mostlyDogs.makeGen[Animal].pureApply(Gen.Parameters.default, rng.Seed(i.toLong))
  )
```

```scala mdoc
samples.count(_.isInstanceOf[Dog])
samples.count(_.isInstanceOf[Cat])
```

`Chooser.weighted(...)` matches `Mirror.SumOf[T].MirroredElemTypes` order;
`Chooser.only(i)` always picks the i-th variant (useful for deterministic
tests of one branch).

## Container helpers

```scala mdoc:silent
val ints =
  listOfN[Int](5) +:
    gen(Gen.choose(0, 100))
```

```scala mdoc
sample(ints.makeGen[List[Int]])
```

Each helper registers a 1-input entry that consumes `Gen[T]` (or two
inputs for `eitherOf` / `pairOf` / `mapOf` etc.) and produces the container
`Gen`. Mix them freely with `gen[T]` and the rest of the API.

## Recursive generators

`genRec[T]` registers a `Gen[T] → Gen[T]` entry. Its recursive input
is satisfied by **another** `Gen[T]` producer below — typically a base
case (see [Resolution](../concepts/resolution.md) for the
"skip-in-flight-entries" mechanism).

```scala mdoc:silent
sealed trait Tree
case object Leaf                              extends Tree
case class Node(left: Tree, right: Tree)      extends Tree

val trees =
  genRec[Tree](maxSize = 3) { self =>
    Gen.zip(self, self).map((l, r) => Node(l, r): Tree)
  } +:
    gen(Leaf: Tree)
```

```scala mdoc
sample(trees.makeGen[Tree])
```

Without the base entry the recursive lookup would cycle. With it, the
resolver picks the base when the recursive entry is in flight, and
ScalaCheck's `Gen.recursive` + `Gen.sized` does the depth control.

### Tuning the recursion: `Sized`

`genRec[T]` doesn't just register the recursive entry — it bundles
a `value(Sized.default)` alongside it. `Sized` exposes two knobs:

| Knob              | Type                | What it controls                                                |
| ----------------- | ------------------- | --------------------------------------------------------------- |
| `pickBase(size)`  | `Int => Gen[Boolean]` | At the current size, return `true` for "use base" or `false` for "recurse via grow". |
| `nextSize(size)`  | `Int => Gen[Int]`   | Compute the size to pass to `Gen.resize` for the recursive call. |

`Sized.default` matches the behavior of earlier versions: at `size <= 0`
always pick the base (terminates), otherwise a 1:3 weighted choice in
favor of recursion; size shrinks by 1 each step.

Override by prepending your own `value(mySized)` — LIFO selection picks
it over the default that `genRec` injects:

```scala mdoc:silent
val onlyBase = Sized(
  pickBase = _ => Gen.const(true),
  nextSize = size => Gen.const((size - 1).max(0))
)

val flatTrees =
  value(onlyBase) +:
    genRec[Tree] { self =>
      Gen.zip(self, self).map((l, r) => Node(l, r): Tree)
    } +:
    gen(Leaf: Tree)
```

```scala mdoc
sample(flatTrees.makeGen[Tree])
```

Common shapes:

- `Sized.default` — 1:3 base/grow, size − 1.
- "Always grow" — `pickBase = size => if size <= 0 then Gen.const(true) else Gen.const(false)` to always recurse until the size guard kicks in.
- Wider distribution — `nextSize = size => Gen.choose(0, size - 1)` for varied recursive depths.
- Halve-on-recurse — `nextSize = size => Gen.const(size / 2)`.

A custom `Sized.pickBase` is responsible for terminating at low sizes; the
recursion has no other depth guard. Always returning `false` from
`pickBase` will overflow the stack.

## Sharing one sample across a tree

By default, `Gen[T]` consumed in two positions of a generated value
samples independently — each `flatMap` step uses its own seed segment.
When two positions should observe the *same* sampled value, mark the
underlying entry shared:

```scala mdoc:silent
case class Bundle(a: Int, b: Int)

val pinned =
  gen[Bundle] +: gen(Gen.choose(0, 1_000_000)).share
```

```scala mdoc
sample(pinned.makeGen[Bundle])
```

Both fields draw the same `Int`. The registry-level form
`share[Int] +: r` flips the flag retroactively on any matching entry; use
it when the shared entry is registered elsewhere (e.g. via `gen[T]`):

```scala mdoc:silent
val pinnedRetro =
  share[Int] +:
    gen[Bundle] +:
    gen(Gen.choose(0, 1_000_000))
```

```scala mdoc
sample(pinnedRetro.makeGen[Bundle])
```

`const[T]` is `share[T]` with cross-build pinning: every `makeGen` call on
the registry observes the **same** sampled value, regardless of seed —
useful for "fixture" data shared across an entire test run.

`memoize[T]` is the lightest — it caches the produced `Gen` instance
across `makeGen` calls but does no sample-time pinning.

See [Memoization](../concepts/memoization.md) for the underlying machinery
(per-make resolver cache, the `shared` flag on `GenEntry`, the build path
that prepends a `Gen.const(sample)` entry per shared type).

## `makeGen[T]` vs `make[Gen[T]]`

`makeGen[T]` is share-aware. When any entry is a `GenEntry` with
`shared = true`, it samples each shared `Gen[A]` once at the outer level
and prepends a value-style entry producing `Gen.const(sample)` before
resolving the rest. When no entry is shared, it delegates to plain
`r.make[Gen[T]]`.

In practice, always reach for `makeGen[T]` — it's a no-op on registries
without sharing and the right thing on registries with it.

## Where to read next

- [Cheat sheet](scalacheck-cheat-sheet.md) — every factory and combinator
  in one place, for quick lookup once you know the shape.
- [Memoization](../concepts/memoization.md) — sharing across consumers,
  per-make resolver cache, the `shared` flag and the share build path.
- [Resolution](../concepts/resolution.md) — the recursive-entry mechanism
  that powers `genRec`.
- [Customization](../concepts/customization.md) — core `refine` for
  context-scoped overrides; use `refineGen` for generated payloads.
