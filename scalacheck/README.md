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

### Sealed traits via `genSum` (convenience) or `genTrait` + `Chooser` (explicit)

For a sealed trait whose variants are all case classes, the easiest path is `genSum[T]` — it bundles
`genTrait[T]` + `genFun[VariantN]` for every variant + a default `Chooser.uniform`:

```scala
sealed trait Animal
case class Dog(name: String) extends Animal
case class Cat(lives: Int)   extends Animal

val r =
  genSum[Animal] +:
  value(Gen.alphaStr: Gen[String]) +:
  value(Gen.choose(1, 9): Gen[Int])

val gen: Gen[Animal] = r.make[Gen[Animal]]
```

To override the default chooser — e.g., skew distribution — prepend your own `value(Chooser.xxx)`
above `genSum[T]` (LIFO resolves the most recent entry):

```scala
val skewed =
  value(Chooser.weighted(9, 1)) +:   // 9x more Dogs than Cats
  genSum[Animal] +:
  value(Gen.alphaStr: Gen[String]) +:
  value(Gen.choose(1, 9): Gen[Int])
```

For more control — or when variants include case objects / enum cases — use the lower-level form:

```scala
val r =
  genTrait[Animal] +:
  genFun[Dog] +:
  genFun[Cat] +:
  value(Chooser.uniform) +:
  value(Gen.alphaStr: Gen[String]) +:
  value(Gen.choose(1, 9): Gen[Int])
```

`genSum[T]` is limited to sealed traits with case-class variants only — enums and case objects need
the explicit form plus `value(Gen.const(theCase): Gen[theCase.type])` per no-arg variant.

Built-in Choosers:

| Chooser                      | Behaviour                                                                                                        |
| ---------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `Chooser.uniform`            | Uniform random pick.                                                                                             |
| `Chooser.weighted(ws: Int*)` | Relative frequencies by position — positions match `Mirror.SumOf[T].MirroredElemTypes` order.                    |
| `Chooser.only(i: Int)`       | Always pick the i-th variant. Useful for deterministic tests.                                                    |
| custom                       | Implement `trait Chooser { def pickOne[T](gens: Seq[Gen[T]]): Gen[T] }` and register it with `value(myChooser)`. |

Because the `Chooser` is a plain registry value, you can also `specialize[Ctx, Chooser](...)` to
scope a chooser to a particular build context — e.g., uniform everywhere, but weighted inside
`make[Gen[SpecialModel]]`.

## Implemented

| Combinator                         | Purpose                                                                                                                                                                                                                                         |
| ---------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `genFun[T]`                        | Register a case class / plain class primary constructor as a generator. Returns `TypedEntry[(Gen[P0], Gen[P1], …), Gen[T]]`.                                                                                                                    |
| `genFun(f)`                        | Register an arbitrary function value (lambda or eta-expanded method ref) as a generator. Param / return types inferred from `f`.                                                                                                                |
| `genTrait[T]`                      | Combine per-subtype `Gen[Sub_i]` entries into a `Gen[T]` for a sealed trait / abstract class / enum. Consumes a `Chooser`.                                                                                                                      |
| `genSum[T]`                        | Bundle: `genTrait[T]` + `genFun[V_i]` for each variant + default `Chooser.uniform`. Case-class variants only.                                                                                                                                   |
| `Chooser`                          | Pluggable pick strategy for `genTrait`. Built-ins: `uniform`, `weighted(ws*)`, `only(i)`; users can implement the trait directly.                                                                                                               |
| Container helpers                  | `listOf[T]`, `nonEmptyListOf[T]`, `listOfN[T](n)`, `optionOf[T]`, `setOf[T]`, etc. Each registered entry wraps a ScalaCheck combinator and resolves element generators from the rest of the registry. See `Containers.scala` for the full list. |
| `genRecursive[T](grow)`            | Size-bounded recursive `Gen[T]`. `grow` receives the self-reference; the base case is resolved *from the registry* (e.g. a `value(Gen.const(Leaf))` entry). Overload `genRecursive[T](maxSize)(grow)` caps depth regardless of ambient ScalaCheck size. |
| `value(gen: Gen[T])`               | Register a leaf generator (uses core `value` directly — no new machinery).                                                                                                                                                                      |

All the core registry operators work unchanged — `+:` (strict), `*:` (tracked), `-:`
(untyped), `<+>` (merge), `make`, `makeSafe`, `erase`, `tweak`, `specialize`, `memoize`.
Subtype-aware resolution is inherited from core: `Gen[List[Int]]` satisfies a request for
`Gen[Seq[Int]]` because `Gen[+T]` is covariant.

## Running

```
sbt scalacheck/test
```
