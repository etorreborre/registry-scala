# `registry-scalacheck`

Derives [ScalaCheck](https://scalacheck.org/) `Gen[T]` instances from
registered leaf generators. Instead of hand-wiring `for { … } yield …` for
every case class, register one `gen[T]` per type and ask the registry for
`Gen[T]`. The macro inspects `T`'s primary constructor (or sealed-trait
variants) and emits the entry that sequences inputs via `flatMap`/`map`.

## Cheat sheet

### Registration

| Factory                       | Use                                                              |
| ----------------------------- | ---------------------------------------------------------------- |
| `gen[T]`                      | derive a `Gen[T]` from `T`'s primary constructor                 |
| `gen[T]` (sealed)             | derive a `Gen[T]` for a sealed trait / abstract class / enum     |
| `gen(g: Gen[T])`              | register an existing ScalaCheck `Gen[T]`                         |
| `gen(f: (A, B, …) => T)`      | register a function lifted into `Gen` via `combineGens`          |
| `gen(x: T)`                   | register a constant; resolves to `Gen.const(x)`                  |
| `arb[T]`                      | register `Gen[T]` sourced from an in-scope `Arbitrary[T]`        |

### Sealed types

| Factory                      | Use                                                       |
|------------------------------|-----------------------------------------------------------|
| `gen[T]`                     | combine per-variant `Gen[Sub]` values into a `Gen[Trait]` |
| `Chooser.uniform`            | uniform random pick (default)                             |
| `Chooser.weighted(ws*)`      | weighted pick by Mirror-element order                     |
| `Chooser.only(i)`            | always pick the i-th variant (deterministic)              |

### Containers

`listOf[T]`, `nonEmptyListOf[T]`, `listOfN[T](n)`, `listOfMinMax[T](min, max)`,
`optionOf[T]`, `setOf[T]`, `setOfN[T](n)`, `eitherOf[L, R]`, `pairOf[A, B]`,
`tripleOf[A, B, C]`, `mapOf[K, V]`, `mapOfN[K, V](n)`, plus
`indexedSeqOf` / `iArrayOf` variants. Each registers a 1-input entry that
wraps the underlying ScalaCheck combinator.

### Recursion

| Factory                                  | Use                                                  |
| ---------------------------------------- | ---------------------------------------------------- |
| `genRec[T](grow)`                  | size-bounded recursive `Gen[T]`; needs a base case; bundles `Sized.default` |
| `genRec[T](maxSize)(grow)`         | same, capped at `maxSize`                            |
| `Sized(pickBase, nextSize)`              | per-step termination + size-shrink strategy; override by `value(mySized) +:` |

### Sharing and memoization

| Factory                       | Effect                                                                 |
| ----------------------------- | ---------------------------------------------------------------------- |
| `memoize[T] +: r`             | cache the *Gen instance* across `makeGen` calls                        |
| `share[T] +: r`               | pin one *sampled value* per `makeGen` build                            |
| `const[T] +: r`               | pin one *sampled value* for the registry's lifetime                    |
| `entry.share`, `entry.const`  | apply the same flags inline at registration                            |

### Building

| Method            | Use                                                  |
| ----------------- | ---------------------------------------------------- |
| `r.makeGen[T]`    | build a `Gen[T]` (share-aware; routes through plain `make` if no entry is shared) |

## Setup

The mdoc helper used below samples a `Gen` deterministically with a fixed
seed:

```scala
import org.scalacheck.{Gen, rng}
def sample[A](g: Gen[A]): A =
  g.pureApply(Gen.Parameters.default, rng.Seed(42L))
```

## Deriving a `Gen[T]` for a case class

```scala
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

```scala
sample(r.makeGen[Person])
// res0: Person = Person(
//   name = "FvlhjKViMdsWQsLnxhZBfGsJOjZFcubqkzndbmKTlYENcyNUcbgpVXYouhedbBNtsWQcLx",
//   age = 71,
//   address = Address(street = "oDfVhuQapx", zip = 29)
// )
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

```scala
case class Tagged(label: String, value: Int)

val tagged =
  gen[Tagged] +:
    gen("FIXED") +:                     // constant — Gen.const("FIXED")
    gen(Gen.choose(1, 10))              // existing ScalaCheck Gen
```

```scala
sample(tagged.makeGen[Tagged])
// res1: Tagged = Tagged(label = "FIXED", value = 2)
```

The macro inspects the value passed to `gen(...)`:

- a function value → its parameter types become `Gen[…]` inputs;
- a `Gen[T]` → registered as a zero-input `Gen[T]` entry;
- anything else → wrapped via `Gen.const`.

You can also pass an eta-expanded constructor reference: `gen(Person.apply)`.

## `arb[T]` — from an in-scope `Arbitrary`

```scala
import org.scalacheck.Arbitrary

case class Stamp(id: Int)

val stamps =
  gen[Stamp] +: arb[Int]
```

```scala
sample(stamps.makeGen[Stamp])
// res2: Stamp = Stamp(2147483647)
```

Equivalent to `gen(Arbitrary.arbitrary[Int])` but lighter at the call
site — the typical pattern when leaves are already supplied by implicit
`Arbitrary` instances.

## Sealed traits, enums, sum types

`gen[T]` on a sealed type expands into a registry bundling `genTrait`,
one entry per variant, and a default `Chooser.uniform`.

```scala
sealed trait Animal
case class Dog(name: String) extends Animal
case class Cat(lives: Int)   extends Animal

val zoo =
  gen[Animal] +:
    gen(Gen.alphaStr) +:
    gen(Gen.choose(1, 9))
```

```scala
sample(zoo.makeGen[Animal])
// res3: Animal = Cat(7)
```

To control the variant distribution, swap the registered `Chooser` —
`genTrait[T]` consumes whatever `Chooser` is in scope:

```scala
val mostlyDogs =
  genTrait[Animal] +:
    gen[Dog] +:
    gen[Cat] +:
    value(Chooser.weighted(9, 1)) +:    // 9× more Dogs than Cats
    gen(Gen.alphaStr) +:
    gen(Gen.choose(1, 9))
```

```scala
val samples =
  (0 until 50).map(i =>
    mostlyDogs.makeGen[Animal].pureApply(Gen.Parameters.default, rng.Seed(i.toLong))
  )
```

```scala
samples.count(_.isInstanceOf[Dog])
// res4: Int = 48
samples.count(_.isInstanceOf[Cat])
// res5: Int = 2
```

`Chooser.weighted(...)` matches `Mirror.SumOf[T].MirroredElemTypes` order;
`Chooser.only(i)` always picks the i-th variant (useful for deterministic
tests of one branch).

## Container helpers

```scala
val ints =
  listOfN[Int](5) +:
    gen(Gen.choose(0, 100))
```

```scala
sample(ints.makeGen[List[Int]])
// res6: List[Int] = List(70, 100, 94, 72, 100)
```

Each helper registers a 1-input entry that consumes `Gen[T]` (or two
inputs for `eitherOf` / `pairOf` / `mapOf` etc.) and produces the container
`Gen`. Mix them freely with `gen[T]` and the rest of the API.

## Recursive generators

`genRec[T]` registers a `Gen[T] → Gen[T]` entry. Its recursive input
is satisfied by **another** `Gen[T]` producer below — typically a base
case (see [Resolution](../concepts/resolution.md) for the
"skip-in-flight-entries" mechanism).

```scala
sealed trait Tree
case object Leaf                              extends Tree
case class Node(left: Tree, right: Tree)      extends Tree

val trees =
  genRec[Tree](maxSize = 3) { self =>
    Gen.zip(self, self).map((l, r) => Node(l, r): Tree)
  } +:
    gen(Leaf: Tree)
```

```scala
sample(trees.makeGen[Tree])
// res7: Tree = Node(
//   left = Node(
//     left = Node(left = Leaf, right = Leaf),
//     right = Node(left = Leaf, right = Leaf)
//   ),
//   right = Node(
//     left = Node(left = Leaf, right = Leaf),
//     right = Node(left = Leaf, right = Leaf)
//   )
// )
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

```scala
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

```scala
sample(flatTrees.makeGen[Tree])
// res8: Tree = Leaf
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

```scala
case class Bundle(a: Int, b: Int)

val pinned =
  gen[Bundle] +: gen(Gen.choose(0, 1_000_000)).share
```

```scala
sample(pinned.makeGen[Bundle])
// res9: Bundle = Bundle(a = 235804, b = 235804)
```

Both fields draw the same `Int`. The registry-level form
`share[Int] +: r` flips the flag retroactively on any matching entry; use
it when the shared entry is registered elsewhere (e.g. via `gen[T]`):

```scala
val pinnedRetro =
  share[Int] +:
    gen[Bundle] +:
    gen(Gen.choose(0, 1_000_000))
```

```scala
sample(pinnedRetro.makeGen[Bundle])
// res10: Bundle = Bundle(a = 235804, b = 235804)
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

- [Memoization](../concepts/memoization.md) — sharing across consumers,
  per-make resolver cache, the `shared` flag and the share build path.
- [Resolution](../concepts/resolution.md) — the recursive-entry mechanism
  that powers `genRec`.
- [Customization](../concepts/customization.md) — `refine` for context-
  scoped overrides; useful for swapping in a different `Chooser` only
  when generating one specific type.
