package registry

import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag
import scala.compiletime.summonAll

/**
 * A path-scoped refinement. When the resolution stack contains the types of `Path` (as a subsequence,
 * in order) and the resolver is looking for `T`, the registry returns [[value]] instead of doing the
 * normal lookup.
 *
 * Equivalent to [[Registry.refine]] / [[Registry.refinePath]] but produced as a standalone value that
 * composes with entries via the `+:`, `*:`, and `-:` operators (all three behave identically — a
 * refinement adds nothing to the type-level `AllIns` / `AllOuts` accounting).
 */
final case class Refinement[Path, T](
    pathTags: List[LightTypeTag],
    targetTag: LightTypeTag,
    value: Any
)

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
 * `Path` may be a single type (1-element path) or a tuple of types — `refine[Foo, String]("x")` is the
 * standalone equivalent of `r.refine[Foo, String]("x")`, and `refine[(A, B), String]("x")` is the
 * equivalent of `r.refinePath[(A, B), String]("x")`.
 */
inline def refine[Path, T](v: T)(using tTag: Tag[T]): Refinement[Path, T] =
  val tags = summonAll[PathTags[Path]].toList.asInstanceOf[List[Tag[?]]]
  Refinement(tags.map(_.tag), tTag.tag, v)
