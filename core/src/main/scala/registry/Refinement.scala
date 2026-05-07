package registry

import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag
import scala.compiletime.summonAll

/**
 * A path-scoped refinement. When the resolution stack contains the types of `Path` (as a subsequence,
 * in order) and the resolver is looking for `T`, the registry calls [[invoke]] (after resolving
 * each entry of [[inputs]] from the surrounding registry) instead of doing the normal lookup.
 *
 * For value/Gen-style refinements `inputs` is empty and `invoke` ignores its argument; for
 * function-style refinements (e.g. `refineGen[Path](f: A => T)`), `inputs` lists the function's
 * parameter types — each looked up in the registry like a regular [[Entry]] input would be —
 * and `invoke` applies the function (or Gen.combine equivalent) to those resolved arguments.
 *
 * Equivalent to [[Registry.refine]] but produced as a standalone value that composes with entries
 * via the `+:`, `*:`, and `-:` operators (all three behave identically — a refinement adds nothing
 * to the type-level `AllIns` / `AllOuts` accounting).
 */
final case class Refinement[Path, T](
    pathTags: List[LightTypeTag],
    targetTag: LightTypeTag,
    inputs: List[LightTypeTag],
    invoke: Seq[Any] => Any
)

final class RefinePartiallyApplied[Path](private val dummy: Boolean = true) extends AnyVal:

  inline def apply[T](v: T)(using tTag: Tag[T]): Refinement[Path, T] =
    refine[Path, T](v)

/** Build a value-style [[Refinement]] — empty inputs, the static value is returned as-is. */
private[registry] def valueRefinement[Path, T](
    pathTags: List[LightTypeTag],
    targetTag: LightTypeTag,
    v: T
): Refinement[Path, T] =
  Refinement(pathTags, targetTag, Nil, _ => v.asInstanceOf[Any])

/**
 * Tuple of `Tag` instances corresponding to the elements of `P`.
 *  - if `P` is `EmptyTuple`, the result is `EmptyTuple`;
 *  - if `P` is `H *: T`, recurse;
 *  - otherwise `P` is treated as a single-element path.
 */
type PathTags[P] <: Tuple = P match
  case EmptyTuple => EmptyTuple
  case h *: t     => Tag[h] *: PathTags[t]
  case _          => Tag[P] *: EmptyTuple

/**
 * Build a [[Refinement]] for the given `Path` and target type `T`.
 *
 * `Path` may be a single type (1-element path) or a tuple of types — `refine[Foo, String]("x")`
 * scopes the override to a `Foo`-context, and `refine[(A, B), String]("x")` scopes it to the
 * `A → … → B` subsequence of the resolution stack. Mirrors `Registry.refine` exactly.
 */
inline def refine[Path, T](v: T)(using tTag: Tag[T]): Refinement[Path, T] =
  val tags = summonAll[PathTags[Path]].toList.asInstanceOf[List[Tag[?]]]
  valueRefinement(tags.map(_.tag), tTag.tag, v)

/**
 * Build a [[Refinement]] while letting the target type `T` be inferred from the value.
 *
 * Equivalent to `refine[Path, T](v)`, but written as `refine[Path](v)`.
 */
inline def refine[Path]: RefinePartiallyApplied[Path] =
  new RefinePartiallyApplied[Path]
