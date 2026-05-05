package registry.scalacheck

import izumi.reflect.Tag
import org.scalacheck.Gen
import registry.{Refinement, Registry}

/**
 * Path tags for [[refineGen]]. Each element of the user-supplied `Path` is wrapped in `Gen[_]`
 * before being summoned, because the resolution stack in `makeGen[T]` contains
 * `Gen[T]` types rather than the bare `T` (the resolver only ever asks for `Gen[…]`). Mirrors how
 * `share[T]` / `const[T]` are keyed on `Tag[Gen[T]]` rather than `Tag[T]`.
 *
 *   - `EmptyTuple`   → `EmptyTuple`
 *   - `H *: T`       → `Tag[Gen[H]] *: GenPathTags[T]`
 *   - any single `P` → `Tag[Gen[P]] *: EmptyTuple`
 */
type GenPathTags[P] <: Tuple = P match
  case EmptyTuple => EmptyTuple
  case h *: t     => Tag[Gen[h]] *: GenPathTags[t]
  case _          => Tag[Gen[P]] *: EmptyTuple

/**
 * Path-scoped refinement, ScalaCheck-flavored.
 *
 * Two pieces of auto-lifting on top of core [[registry.refine]]:
 *
 *  1. Each element of `Path` is wrapped in `Gen[_]` before lookup, so
 *     `refineGen[Person, Int](42)` reads naturally as "when generating a `Person`, …" — under the
 *     hood the path is `[Tag[Gen[Person]]]`, matching what ends up on the resolver's stack during
 *     `makeGen[Person]`.
 *
 *  2. The value is auto-lifted to `Gen[T]` if it isn't already one (mirroring [[gen]] on a value
 *     argument):
 *      - `refineGen[Person, Int](42)`              ⇒ `Gen.const(42)`
 *      - `refineGen[Person, Int](Gen.choose(1,9))` ⇒ the supplied `Gen` as-is
 *
 * The type parameter `T` is always the *payload* (e.g. `Int`), not `Gen[Int]`. `Path` may be a
 * single type or a tuple of types — same convention as core [[registry.Registry.refine]]. The
 * result composes via `+:` / `*:` / `-:` like any other `Refinement`.
 */
transparent inline def refineGen[Path, T](inline v: T | Gen[T]): Refinement[Path, Gen[T]] =
  ${ RefineGenMacro.refinementExpr[Path, T]('v) }

extension [AllIns <: Tuple, AllOuts <: Tuple](r: Registry[AllIns, AllOuts])
  /**
   * `r.refineGen[Path, T](v)` — apply a [[refineGen]] refinement to this registry. Equivalent to
   * `refineGen[Path, T](v) +: r`.
   */
  transparent inline def refineGen[Path, T](inline v: T | Gen[T]): Registry[AllIns, AllOuts] =
    ${ RefineGenMacro.registryExpr[AllIns, AllOuts, Path, T]('r, 'v) }
