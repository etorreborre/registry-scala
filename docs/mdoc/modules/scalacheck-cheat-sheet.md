---
title: Cheat sheet
parent: ScalaCheck
grand_parent: Modules
nav_order: 1
---

# `registry-scalacheck` cheat sheet

Quick reference for the API surface. For a guided walkthrough — what to
register first, how each piece composes, recursion, sharing — see
[ScalaCheck](scalacheck.md).

## Registration

| Factory                       | Use                                                              |
| ----------------------------- | ---------------------------------------------------------------- |
| `gen[T]`                      | derive a `Gen[T]` from `T`'s primary constructor                 |
| `gen[T]` (sealed)             | derive a `Gen[T]` for a sealed trait / abstract class / enum     |
| `gen(g: Gen[T])`              | register an existing ScalaCheck `Gen[T]`                         |
| `gen(f: (A, B, …) => T)`      | register a function lifted into `Gen` via `combineGens`          |
| `gen(x: T)`                   | register a constant; resolves to `Gen.const(x)`                  |
| `arb[T]`                      | register `Gen[T]` sourced from an in-scope `Arbitrary[T]`        |

## Sealed types

| Factory                      | Use                                                       |
|------------------------------|-----------------------------------------------------------|
| `gen[T]`                     | combine per-variant `Gen[Sub]` values into a `Gen[Trait]` |
| `Chooser.uniform`            | uniform random pick (default)                             |
| `Chooser.weighted(ws*)`      | weighted pick by Mirror-element order                     |
| `Chooser.only(i)`            | always pick the i-th variant (deterministic)              |

## Containers

Each factory below registers a 1-input entry that wraps the corresponding
ScalaCheck combinator.

| Factory                          | Produces                                              |
| -------------------------------- | ----------------------------------------------------- |
| `listOf[T]`                      | `Gen[List[T]]` of arbitrary length                    |
| `nonEmptyListOf[T]`              | `Gen[List[T]]`, never empty                           |
| `listOfN[T](n)`                  | `Gen[List[T]]` of exactly `n` elements                |
| `listOfMinMax[T](min, max)`      | `Gen[List[T]]` with size in `[min, max]`              |
| `optionOf[T]`                    | `Gen[Option[T]]`                                      |
| `setOf[T]`                       | `Gen[Set[T]]` of arbitrary size                       |
| `setOfN[T](n)`                   | `Gen[Set[T]]` of exactly `n` elements                 |
| `eitherOf[L, R]`                 | `Gen[Either[L, R]]`                                   |
| `pairOf[A, B]`                   | `Gen[(A, B)]`                                         |
| `tripleOf[A, B, C]`              | `Gen[(A, B, C)]`                                      |
| `mapOf[K, V]`                    | `Gen[Map[K, V]]` of arbitrary size                    |
| `mapOfN[K, V](n)`                | `Gen[Map[K, V]]` with exactly `n` entries             |
| `indexedSeqOf[T]` (and variants) | `Gen[IndexedSeq[T]]` — same shape as the `listOf*` family |
| `iArrayOf[T]` (and variants)     | `Gen[IArray[T]]` — same shape as the `listOf*` family |

## Recursion

| Factory                                  | Use                                                  |
| ---------------------------------------- | ---------------------------------------------------- |
| `genRec[T](grow)`                  | size-bounded recursive `Gen[T]`; needs a base case; bundles `Sized.default` |
| `genRec[T](maxSize)(grow)`         | same, capped at `maxSize`                            |
| `Sized(pickBase, nextSize)`              | per-step termination + size-shrink strategy; override by `value(mySized) +:` |

## Sharing and memoization

| Factory                       | Effect                                                                 |
| ----------------------------- | ---------------------------------------------------------------------- |
| `memoize[T] +: r`             | cache the *Gen instance* across `makeGen` calls                        |
| `share[T] +: r`               | pin one *sampled value* per `makeGen` build                            |
| `const[T] +: r`               | pin one *sampled value* for the registry's lifetime                    |
| `entry.share`, `entry.const`  | apply the same flags inline at registration                            |

## Refinements

| Factory / method              | Effect                                                                 |
| ----------------------------- | ---------------------------------------------------------------------- |
| `r.refineGen[Path](v)`        | override the generated payload type inferred from `v` under `Path`     |
| `refineGen[Path](v) +: r`     | standalone refinement added to `r`; plain `v` becomes `Gen.const(v)`   |
| `refineGen[Path](gen) +: r`   | standalone refinement added to `r` using the supplied `Gen[T]` as-is   |
| `refineGen[Path, T](v) +: r`  | explicit standalone form added to `r` when `T` needs ascription        |

## Building

| Method            | Use                                                  |
| ----------------- | ---------------------------------------------------- |
| `r.makeGen[T]`    | build a `Gen[T]` (share-aware; routes through plain `make` if no entry is shared) |
