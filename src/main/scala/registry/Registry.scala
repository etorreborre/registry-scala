package registry

import izumi.reflect.Tag
import registry.TypeChecks.*

/** A registry of type-erased functions that tracks, at the type level, the union of all inputs required by
 * registered entries (`AllIns`) and the union of all outputs they produce (`AllOuts`).
 *
 * The phantom type parameters power the compile-time-checked [[makeSafe]]; the runtime-only [[make]]
 * ignores them.
 */
final case class Registry[AllIns <: Tuple, AllOuts <: Tuple](entries: List[Entry]):
  /** Right-associative prepend: `entry +: registry`. LIFO — the head wins for a given output. */
  def +:[EIns <: Tuple, EOut](e: TypedEntry[EIns, EOut]): Registry[Concat[EIns, AllIns], EOut *: AllOuts] =
    Registry(e.entry :: entries)

  /** Merge two registries. Left operand's entries come first, so on duplicate outputs the left wins. */
  def <+>[OIns <: Tuple, OOuts <: Tuple](
      other: Registry[OIns, OOuts]
  ): Registry[Concat[AllIns, OIns], Concat[AllOuts, OOuts]] =
    Registry(entries ++ other.entries)

  /** Build a value of type `T`. Runtime-only; throws if a dependency is missing. */
  def make[T](using tag: Tag[T]): T =
    Resolve.resolve(entries, tag.tag).asInstanceOf[T]

  /** Build a value of type `T` with compile-time checks: `T` must be produced, and every required input
   * of every registered entry must be covered by an output. On failure, the compile error lists the
   * unresolved types one per line.
   */
  inline def makeSafe[T](using tag: Tag[T]): T =
    ${ MakeSafeMacro.impl[T, AllIns, AllOuts]('this, 'tag) }

object Registry:
  val empty: Registry[EmptyTuple, EmptyTuple] = Registry(Nil)
