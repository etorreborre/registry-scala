# registry-scalacheck

ScalaCheck integration for the `registry` library. A Scala 3 port of the Haskell
[`registry-hedgehog`](https://github.com/etorreborre/registry-hedgehog).

Compose ScalaCheck `Gen[T]` values through the same registry that composes ordinary
functions and values: register per-type generators, let the registry assemble them
into complex generators for your data model.

## Usage

```scala
import org.scalacheck.Gen
import registry.*
import registry.scalacheck.*

case class Address(street: String, zip: Int)
case class Person(name: String, address: Address)

val r =
  genFun[Person] +:
  genFun[Address] +:
  value(Gen.alphaStr: Gen[String]) +:
  value(Gen.choose(10000, 99999): Gen[Int])

val genPerson: Gen[Person] = r.make[Gen[Person]]
```

## Implemented

| Combinator           | Purpose                                                                                                                                    |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `genFun[T]`          | Register a case class / plain class primary constructor as a generator. Returns `TypedEntry[(Gen[P0], Gen[P1], …), Gen[T]]`.               |
| `genSum[T]`          | Derive `Gen[T]` for a sealed trait / sealed abstract class / Scala 3 `enum` from per-subtype `Gen[Sub_i]` entries. Uses `Mirror.SumOf[T]`. |
| `value(gen: Gen[T])` | Register a leaf generator (uses core `value` directly — no new machinery).                                                                 |

All the core registry operators work unchanged — `+:` (strict), `*:` (tracked), `-:`
(untyped), `<+>` (merge), `make`, `makeSafe`, `erase`. Subtype-aware resolution is
inherited from core: `Gen[List[Int]]` satisfies a request for `Gen[Seq[Int]]` because
`Gen[+T]` is covariant.

## Not yet implemented

| Feature                 | Haskell name                                                                                   | Notes                                                                                                                                          |
| ----------------------- | ---------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| Lambdas / method refs   | `genFun = funTo @Gen` on any function                                                          | Currently only `genFun[T]` for class constructors. `genFun(f)` for `(A, B) => C` is a straightforward extension.                               |
| Pluggable pick strategy | `Chooser` / `chooseOne`                                                                        | `genSum[T]` currently picks uniformly. A `Gen[Chooser]`-like injection would let users register weighted / deterministic strategies.           |
| Contextual overrides    | `specializeGen`                                                                                | e.g. "use a shorter `Gen[String]` only inside `Gen[Department]`". Core has no `specialize` yet either.                                         |
| Tweaks                  | `tweakGen`, `setGen`                                                                           | Post-process or replace a registered `Gen[T]` without rebuilding the registry.                                                                 |
| Container helpers       | `listOf`, `maybeOf`, `eitherOf`, `tuple2Of`, `nonEmptyOf`, `setOf`, `mapOf`, `listOfMinMax`, … | Each is a one-liner `Gen[T] => Gen[F[T]]` wrapped in `fun`; we can add the whole set at once when needed.                                      |
| Recursion helpers       | Hand-rolled in registry-hedgehog via `Gen.recursive`                                           | Not registry-specific; users can hand-write the recursive `Gen` and register it with `value(...)`. A convenience wrapper could be added later. |

## Running

```
sbt scalacheck/test
```
