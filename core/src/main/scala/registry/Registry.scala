package registry

import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag
import scala.compiletime.summonAll
import registry.TypeChecks.*

/**
 * A registry of type-erased functions that tracks, at the type level, the union of all inputs required by
 * registered entries (`AllIns`) and the union of all outputs they produce (`AllOuts`).
 *
 * The phantom type parameters power the compile-time-checked [[makeSafe]]; the runtime-only [[make]]
 * ignores them.
 *
 * `tweaks` is a list of post-resolution transformations keyed by the stable `LightTypeTag.repr` of the
 * *requested* type, applied in registration order.
 *
 * `specializations` is a list of context-sensitive overrides: each entry says "whenever the resolution
 * stack contains the types of `path` as a subsequence (in order, not necessarily contiguous) and we're
 * resolving `target`, return the given value instead of running the normal lookup".
 */
final case class Registry[AllIns <: Tuple, AllOuts <: Tuple](
    entries: List[Entry],
    tweaks: List[(String, Any => Any)] = Nil,
    specializations: List[(List[LightTypeTag], LightTypeTag, Any)] = Nil
):

  /**
   * Strict prepend: `entry +: registry`. Fails to compile if the entry's inputs are not already
   * produced by the rest of the registry. Forces bottom-up construction. LIFO — the head wins.
   */
  inline def +:[EIns <: Tuple, EOut](
      e: TypedEntry[EIns, EOut]
  ): Registry[Concat[EIns, AllIns], EOut *: AllOuts] =
    ${ StrictPrependMacro.entryIntoRegistry[EIns, EOut, AllIns, AllOuts]('this, 'e) }

  /**
   * Strict merge: `left +: right` merges two registries. Fails to compile if any input required by the
   * left registry is not produced by the right.
   */
  inline def +:[LIns <: Tuple, LOuts <: Tuple](
      l: Registry[LIns, LOuts]
  ): Registry[Concat[LIns, AllIns], Concat[LOuts, AllOuts]] =
    ${ StrictPrependMacro.registryIntoRegistry[LIns, LOuts, AllIns, AllOuts]('this, 'l) }

  /**
   * Tracked prepend: `entry *: registry`. Updates the type-level accounting but performs no prepend-time
   * check — missing dependencies surface at `makeSafe` time. LIFO.
   */
  def *:[EIns <: Tuple, EOut](e: TypedEntry[EIns, EOut]): Registry[Concat[EIns, AllIns], EOut *: AllOuts] =
    Registry(e.entry :: entries, tweaks, specializations)

  /**
   * Tracked merge: `left *: right` combines both registries' entries and tracks combined types.
   * No compile-time check — `makeSafe` reports any residual gaps.
   */
  def *:[LIns <: Tuple, LOuts <: Tuple](
      l: Registry[LIns, LOuts]
  ): Registry[Concat[LIns, AllIns], Concat[LOuts, AllOuts]] =
    Registry(l.entries ++ entries, l.tweaks ++ tweaks, l.specializations ++ specializations)

  /**
   * Untracked prepend: `entry -: registry`. Adds the entry at runtime but does NOT update the type-level
   * `AllIns` / `AllOuts` accounting — the entry is invisible to `makeSafe`. Escape hatch for dynamic use cases.
   */
  def -:[EIns <: Tuple, EOut](e: TypedEntry[EIns, EOut]): Registry[AllIns, AllOuts] =
    Registry(e.entry :: entries, tweaks, specializations)

  /** Untracked prepend of a raw `Entry`. Like [[-:]] but for manually-constructed entries. */
  def -:(e: Entry): Registry[AllIns, AllOuts] =
    Registry(e :: entries, tweaks, specializations)

  /**
   * Untracked merge: `left -: right` combines both registries' entries but keeps only the receiver's
   * type-level accounting — the left side is invisible to `makeSafe`.
   */
  def -:[LIns <: Tuple, LOuts <: Tuple](l: Registry[LIns, LOuts]): Registry[AllIns, AllOuts] =
    Registry(l.entries ++ entries, l.tweaks ++ tweaks, l.specializations ++ specializations)

  /** Merge two registries. Left operand's entries come first, so on duplicate outputs the left wins. */
  def <+>[OIns <: Tuple, OOuts <: Tuple](
      other: Registry[OIns, OOuts]
  ): Registry[Concat[AllIns, OIns], Concat[AllOuts, OOuts]] =
    Registry(
      entries ++ other.entries,
      tweaks ++ other.tweaks,
      specializations ++ other.specializations
    )

  /**
   * Drop all type-level tracking. The entries, tweaks, and specializations are preserved, so [[make]]
   * still works, but [[makeSafe]] can no longer verify anything.
   */
  def erase: Registry[EmptyTuple, EmptyTuple] = Registry(entries, tweaks, specializations)

  /**
   * Register a post-resolution transformation on any resolved value of type `A`. Applied every time the
   * registry resolves an `A` — directly or as an input to another entry. Multiple `tweak[A]` calls compose
   * in registration order (the first-registered tweak runs first, later tweaks wrap its result).
   */
  def tweak[A](f: A => A)(using tag: Tag[A]): Registry[AllIns, AllOuts] =
    Registry(entries, tweaks :+ (tag.tag.repr, f.asInstanceOf[Any => Any]), specializations)

  /**
   * Context-scoped override: when the resolver is currently *inside* a build of `Ctx` (i.e. `Ctx` appears
   * anywhere in the resolution stack) and we're resolving `T`, return `v` instead of doing a normal
   * lookup. Shorthand for [[specializePath]] with a single-element path.
   */
  def specialize[Ctx, T](v: T)(using ctxTag: Tag[Ctx], tTag: Tag[T]): Registry[AllIns, AllOuts] =
    Registry(
      entries,
      tweaks,
      specializations :+ (List(ctxTag.tag), tTag.tag, v.asInstanceOf[Any])
    )

  /**
   * Path-scoped override: when the resolution stack contains the types of `Path` as a subsequence (in
   * order, not necessarily contiguous) and we're resolving `T`, return `v` instead of doing a normal
   * lookup.
   *
   * `[specializePath](_, _)` where `Path` is `Ctx *: EmptyTuple` is equivalent to
   * `[specialize](_, _)[Ctx, T]`. Multi-element paths let you scope overrides to specific routes through
   * the dependency graph.
   */
  transparent inline def specializePath[Path <: Tuple, T](v: T)(using
      tTag: Tag[T]
  ): Registry[AllIns, AllOuts] =
    val pathTags =
      summonAll[Tuple.Map[Path, [s] =>> Tag[s]]].toList.asInstanceOf[List[Tag[?]]]
    Registry(
      entries,
      tweaks,
      specializations :+ (pathTags.map(_.tag), tTag.tag, v.asInstanceOf[Any])
    )

  /** Build a value of type `T`. Runtime-only; throws if a dependency is missing. */
  def make[T](using tag: Tag[T]): T =
    Resolve.resolve(entries, tweaks, specializations, tag.tag).asInstanceOf[T]

  /**
   * Build a value of type `T` with compile-time checks: `T` must be produced, and every required input
   * of every registered entry must be covered by an output. On failure, the compile error lists the
   * unresolved types one per line.
   */
  inline def makeSafe[T](using tag: Tag[T]): T =
    ${ MakeSafeMacro.impl[T, AllIns, AllOuts]('this, 'tag) }

object Registry:
  val empty: Registry[EmptyTuple, EmptyTuple] = Registry(Nil, Nil, Nil)
