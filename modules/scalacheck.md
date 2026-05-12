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

## `refineGen[Path](v)` — path-scoped generator overrides

`refineGen` is the ScalaCheck-flavored version of core `refine`: each
element of `Path` is interpreted as a generated type, and the refined
payload type is inferred from the value you pass.

```scala
case class User(name: String, age: Int)

val users =
  gen[User] +:
    gen(Gen.alphaStr) +:
    gen(Gen.choose(0, 120))
```

```scala
sample(users.refineGen[User]("eric").makeGen[User])
// res2: User = User(name = "eric", age = 103)
sample(users.refineGen[User](Gen.choose(18, 99)).makeGen[User])
// res3: User = User(
//   name = "FvlhjKViMdsWQsLnxhZBfGsJOjZFcubqkzndbmKTlYENcyNUcbgpVXYouhedbBNtsWQcLx",
//   age = 58
// )
```

Plain values are lifted with `Gen.const`; existing `Gen[T]` values are
used as-is. The standalone form composes like any other refinement:

```scala
sample((refineGen[User]("standalone") +: users).makeGen[User])
// res4: User = User(name = "standalone", age = 103)
```

For multi-step scopes, use a tuple path just like core `refine`:
`r.refineGen[(Outer, User)]("nested")`.

## `arb[T]` — from an in-scope `Arbitrary`

```scala
import org.scalacheck.Arbitrary

case class Stamp(id: Int)

val stamps =
  gen[Stamp] +: arb[Int]
```

```scala
sample(stamps.makeGen[Stamp])
// res5: Stamp = Stamp(2147483647)
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
// res6: Animal = Cat(7)
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
// res7: Int = 48
samples.count(_.isInstanceOf[Cat])
// res8: Int = 2
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
// res9: List[Int] = List(70, 100, 94, 72, 100)
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
// res10: Tree = Node(
//   left = Node(left = Leaf, right = Leaf),
//   right = Node(left = Leaf, right = Leaf)
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
// res11: Tree = Leaf
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
// res12: Bundle = Bundle(a = 235804, b = 235804)
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
// res13: Bundle = Bundle(a = 235804, b = 235804)
```

`const[T]` is `share[T]` with cross-build pinning: every `makeGen` call on
the registry observes the **same** sampled value, regardless of seed —
useful for "fixture" data shared across an entire test run.

`memoize[T]` is the lightest — it caches the produced `Gen` instance
across `makeGen` calls but does no sample-time pinning.

The same flags are also available as **call-site extensions** on the
registry, useful when the registry is built once (e.g. as a `def`) and
each consumer chooses which types to pin without modifying the construction
site:

```scala
val gens = baseRegistry.const[MultiNodeConfig].share[Onchain]
// equivalent to:
val gens2 = const[MultiNodeConfig] +: share[Onchain] +: baseRegistry
```

See [Memoization](../concepts/memoization.md) for the underlying machinery
(per-make resolver cache, the `shared` flag on `GenEntry`, the build path
that prepends a `Gen.const(sample)` entry per shared type).

### Resetting pinned state with `reset()`

`memoize[T]` and `const[T]` install `AtomicReference`-backed caches inside
the registry's entries. Those caches survive the registry value (closures
capture them) — so a `lazy val voteGens = ...` shared across multiple
property tests would carry the **first** test's pinned values into every
subsequent test.

`r.reset()` mutates each entry in place, clearing every memoized / const-
pinned value without rebuilding the registry. Call it at the start of a
property to give that property fresh fixture samples while still letting
the const pins do their job for the property's iterations:

```scala
class MyTest extends Properties("…"):
  val gens = voteGens                  // shared registry — see voteGens above
  val _ = property("Vote Tx") = {
    gens.reset()                       // fresh MultiNodeConfig, etc., for this property
    runDefault(/* … forAll … */)
  }
  val _ = property("Tally Tx") = {
    gens.reset()                       // and again, independent of the previous property
    runDefault(/* … forAll … */)
  }
```

Entries that don't hold mutable state (the default `Basic` / `GenEntry`
without memoize / const wrapping) are unaffected — `reset()` is a no-op
on them.

### `share` vs `const` when both are used

`share` and `const` are NOT interchangeable when one type transitively
contains another. Suppose `Onchain` has a `versionMajor: V` field built
via `gen(genOnchainBlockHeader)` that consumes `Gen[V]`:

```scala
const[Onchain] +:    // pin Onchain's value across the entire run
share[V]      +:     // re-sample V per makeGen build
gen(genOnchainBlockHeader) +:
gen(genV)
```

`const[Onchain]` pins one Onchain forever — including the V baked into
its `versionMajor`. `share[V]` re-samples V per build. Other consumers
of `V` see fresh samples; the const-pinned Onchain keeps the iter-1 V
forever. The two views diverge.

If you only need *intra-tree* consistency (the same Onchain inside one
`makeGen` call, fresh per call), use `share[Onchain]` — it composes with
`share[V]` cleanly. Reach for `const` only when you genuinely want one
value to outlive all builds. `r.reset()` lets you step back when needed.

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
