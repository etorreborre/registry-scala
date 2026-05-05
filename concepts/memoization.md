---
title: Memoization
parent: Concepts
nav_order: 4
---

# Sharing and memoization

Two distinct concerns sit under this heading: **within a single `make`**
call (does each consumer of `T` see the same value?) and **across `make`
calls** (does the same registry, called twice, return the same value?).
The library distinguishes them clearly.

## Within a `make` call: shared by default

The resolver caches the value of every entry it invokes for the duration of
one `make` call. Every consumer of the same type gets the same instance —
matching the behavior most DI users expect.

```scala
import registry.*

class Counter:
  override def toString = s"Counter#${System.identityHashCode(this)}"

case class Pair(a: Counter, b: Counter)

val r =
  fun[Pair] +:
  fun(() => new Counter)
```

```scala
val p = r.make[Pair]
// p: Pair = Pair(a = Counter#248541217, b = Counter#248541217)
p.a eq p.b   // true — one Counter for both fields
// res0: Boolean = true
```

The cache lives only for the duration of one `make` call. A second call
gets a fresh cache and a fresh `Counter`:

```scala
val p2 = r.make[Pair]
// p2: Pair = Pair(a = Counter#1878253174, b = Counter#1878253174)
p2.a eq p.a   // false — different make calls
// res1: Boolean = false
```

### Opting out: `fresh`

If a type is genuinely meant to differ per consumer (UUIDs, timestamps,
fresh request IDs), mark the entry `.fresh` to bypass the per-make cache:

```scala
val freshPerConsumer =
  fun[Pair] +:
  fun(() => new Counter()).fresh
```

```scala
val fp = freshPerConsumer.make[Pair]
// fp: Pair = Pair(a = Counter#12212115, b = Counter#1988811265)
fp.a eq fp.b   // false — each consumer triggers a fresh invoke
// res2: Boolean = false
```

You can also opt out at the registry level with `r.fresh[A]`, which sets
the flag on every entry whose output is a subtype of `A`.

## Across `make` calls: `memoize`

`r.memoize[A]` makes every entry whose output is a subtype of `A`
**survive** across `make` calls. The cache is backed by an
`AtomicReference` that travels with the entry through any subsequent
combinator.

```scala
val pooled = r.memoize[Counter]
```

```scala
val q1 = pooled.make[Pair]
// q1: Pair = Pair(a = Counter#782857403, b = Counter#782857403)
val q2 = pooled.make[Pair]
// q2: Pair = Pair(a = Counter#782857403, b = Counter#782857403)
q1.a eq q2.a   // true — Counter pinned across make calls
// res3: Boolean = true
```

`r.memoizeAll` is the shortcut for memoizing every entry. `entry.memoize`
sets it on a single entry inline. Each call to `memoize` returns a **new**
registry with a **fresh** cache; the original is unaffected.

For effectful types (`F[A]`), `memoize` caches the **effect value**, not
the result of running it. Whether re-running yields the same `A` is `F`'s
concern (use `IO.memoize` inside the entry to share the result).

## How the two interact

| You want                                       | Use                                |
| ---------------------------------------------- | ---------------------------------- |
| One value per `make` (default)                 | nothing — it's already shared      |
| Fresh value per consumer in a `make`           | `entry.fresh` or `r.fresh[A]`      |
| Same value across all `make` calls             | `r.memoize[A]` or `entry.memoize`  |
| Fresh per consumer AND per `make`              | `entry.fresh` (no cross-make cache stays) |

## The `shared` flag (scalacheck)

There's a third, orthogonal concept that lives in `registry-scalacheck`:
**sampling-time pinning**.

When you have a `Gen[A]` consumed in two positions of a generated tree,
the resolver's per-make cache shares the `Gen` instance — but `Gen`
samples non-deterministically per `flatMap` step, so each position still
draws an independent value. If you want the two positions to observe the
**same sampled value**, set the `shared` flag on the entry:

```scala
import registry.scalacheck.*
import org.scalacheck.Gen

case class Bundle(a: Int, b: Int)

val r = gen[Bundle] +: gen(Gen.choose(0, 1_000_000)).share

// r.makeGen[Bundle] now samples the Int once and pins it for both fields.
```

Mechanism: when any entry has `shared = true`, `makeGen[T]` takes a
different build path that samples each shared `Gen[A]` once at the outer
level and prepends a value-style entry producing `Gen.const(sampled)`
before resolving the rest. LIFO selection makes the pinned entry win.
Details in [modules/scalacheck.md](../modules/scalacheck.md).

`share` pins one sample per `makeGen` call. Its stronger sibling, `const`,
also pins across separate `makeGen` calls (sample once, hold forever).

## Markers (`Memoize[T]` / `Share[T]` / `Const[T]`)

These are internal value types backing the per-type marker factories you
see in the ScalaCheck integration (`memoize[T]` / `share[T]` / `const[T]`).
At the core level you don't construct them directly — the methods on
`Registry` and `TypedEntry` cover everything core can express on its own.
