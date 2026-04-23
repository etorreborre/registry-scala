package registry

import izumi.reflect.Tag
import registry.TypeChecks.*

/**
 * A registry of type-erased functions that tracks, at the type level, the union of all inputs required by
 * registered entries (`AllIns`) and the union of all outputs they produce (`AllOuts`).
 *
 * The phantom type parameters power the compile-time-checked [[makeSafe]]; the runtime-only [[make]]
 * ignores them.
 */
final case class Registry[AllIns <: Tuple, AllOuts <: Tuple](entries: List[Entry]):

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
    Registry(e.entry :: entries)

  /**
   * Tracked merge: `left *: right` combines both registries' entries and tracks combined types.
   * No compile-time check — `makeSafe` reports any residual gaps.
   */
  def *:[LIns <: Tuple, LOuts <: Tuple](
      l: Registry[LIns, LOuts]
  ): Registry[Concat[LIns, AllIns], Concat[LOuts, AllOuts]] =
    Registry(l.entries ++ entries)

  /**
   * Untracked prepend: `entry -: registry`. Adds the entry at runtime but does NOT update the type-level
   * `AllIns` / `AllOuts` accounting — the entry is invisible to `makeSafe`. Escape hatch for dynamic use cases.
   */
  def -:[EIns <: Tuple, EOut](e: TypedEntry[EIns, EOut]): Registry[AllIns, AllOuts] =
    Registry(e.entry :: entries)

  /** Untracked prepend of a raw `Entry`. Like [[-:]] but for manually-constructed entries. */
  def -:(e: Entry): Registry[AllIns, AllOuts] =
    Registry(e :: entries)

  /**
   * Untracked merge: `left -: right` combines both registries' entries but keeps only the receiver's
   * type-level accounting — the left side is invisible to `makeSafe`.
   */
  def -:[LIns <: Tuple, LOuts <: Tuple](l: Registry[LIns, LOuts]): Registry[AllIns, AllOuts] =
    Registry(l.entries ++ entries)

  /** Merge two registries. Left operand's entries come first, so on duplicate outputs the left wins. */
  def <+>[OIns <: Tuple, OOuts <: Tuple](
      other: Registry[OIns, OOuts]
  ): Registry[Concat[AllIns, OIns], Concat[AllOuts, OOuts]] =
    Registry(entries ++ other.entries)

  /**
   * Drop all type-level tracking. The entries are preserved, so [[make]] still works, but [[makeSafe]]
   * can no longer verify anything (every type appears both unresolved and unproduced).
   */
  def erase: Registry[EmptyTuple, EmptyTuple] = Registry(entries)

  /** Build a value of type `T`. Runtime-only; throws if a dependency is missing. */
  def make[T](using tag: Tag[T]): T =
    Resolve.resolve(entries, tag.tag).asInstanceOf[T]

  /**
   * Build a value of type `T` with compile-time checks: `T` must be produced, and every required input
   * of every registered entry must be covered by an output. On failure, the compile error lists the
   * unresolved types one per line.
   */
  inline def makeSafe[T](using tag: Tag[T]): T =
    ${ MakeSafeMacro.impl[T, AllIns, AllOuts]('this, 'tag) }

object Registry:
  val empty: Registry[EmptyTuple, EmptyTuple] = Registry(Nil)
