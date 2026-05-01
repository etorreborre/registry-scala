package registry.scalacheck

import izumi.reflect.Tag
import org.scalacheck.{Arbitrary, Gen}
import registry.{Entry, TypedEntry}

/**
 * Register a type as a ScalaCheck generator. The macro inspects `T` and dispatches:
 *
 *   - **Class type** (`gen[Foo]`): emits a [[registry.TypedEntry]] whose `Ins` tuple is
 *     `(Gen[P0], Gen[P1], …)` (the primary constructor's parameter types, each wrapped in `Gen`)
 *     and whose `Out` is `Gen[T]`. At runtime the closure sequences the generators via
 *     `flatMap` / `map` (through [[GenCombine.combineGens]]) and applies the primary constructor
 *     to the collected sample values.
 *   - **Sealed trait / sealed abstract class / Scala 3 `enum`** (`gen[Animal]`): expands into a
 *     [[registry.Registry]] bundling `genTrait[T]` + one entry per variant (auto-built from each
 *     variant's primary constructor or its singleton value) + a default `Chooser.uniform`. This
 *     subsumes the standalone `sum[T]` form.
 *
 * Analogous to the Haskell `registry-hedgehog`'s `genFun = funTo @Gen`. Mirrors `registry-cats`'s
 * `funTo[F](f)` for the value-driven overload, with `Gen` hard-coded as the effect.
 */
transparent inline def gen[T]: Any =
  ${ GenMacro.typeImpl[T] }

/**
 * Register a value-shaped generator. The macro inspects the inferred type and dispatches:
 *
 *   - Function value (`(A, B, ...) => R`): the parameter types become the entry's `Ins` (each wrapped
 *     in `Gen`); the return type becomes the `Out` (wrapped in `Gen`). Eta-expanded method references
 *     work too — e.g. `gen(Foo.apply)`. If `R = Gen[T]`, the entry's output is `Gen[T]` (the closure
 *     `flatMap`s into the function instead of double-wrapping into `Gen[Gen[T]]`).
 *   - `Gen[T]` value: registered as-is, producing a zero-input entry of output `Gen[T]`.
 *   - Any other value `x: T`: lifted via `Gen.const(x)` into a zero-input entry of output `Gen[T]`.
 *
 * `X` is inferred at the call site, so Scala's normal widening applies — literals (`42`,
 * `"FIXED"`) widen to their base type (`Int`, `String`), and stable refs widen to their declared
 * type. To preserve a singleton type explicitly (e.g. for manual sum-type variant registration),
 * pass the type argument: `gen[Color.Red.type](Color.Red)`. In practice, prefer `sum[Color]` for
 * sum-type bundles.
 */
transparent inline def gen[X](inline x: X): TypedEntry[? <: Tuple, ?] =
  ${ GenMacro.valueImpl[X]('x) }

/**
 * Register a `Gen[T]` sourced from an in-scope `Arbitrary[T]`: `arb[Foo]`.
 *
 * Equivalent to `gen(Arbitrary.arbitrary[T])` but lighter at the call site — the typical pattern
 * when leaf generators are already supplied via implicit `Arbitrary` instances.
 */
def arb[T](using arbT: Arbitrary[T], tag: Tag[Gen[T]]): TypedEntry[EmptyTuple, Gen[T]] =
  TypedEntry(Entry(Nil, tag.tag, _ => arbT.arbitrary))

/**
 * `registry.makeGen[T]` is shorthand for `registry.make[Gen[T]]`. Lets you read out a generator
 * directly without writing the `Gen[…]` wrapper at every call site.
 */
extension [AllIns <: Tuple, AllOuts <: Tuple](r: registry.Registry[AllIns, AllOuts])
  def makeGen[T](using tag: Tag[Gen[T]]): Gen[T] = r.make[Gen[T]]
