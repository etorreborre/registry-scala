package registry.scalacheck

import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag
import org.scalacheck.Gen
import registry.{Registry, Resolve, TypedEntry}

/**
 * Registry wrapper that produces a `Gen[T]` where selected `Gen[A]` outputs yield ONE sample per
 * generated `T`, shared across every consumer. Without this, the default resolver samples `Gen[A]`
 * independently at each position in the dependency tree — decorrelating state that should be
 * shared.
 *
 * Two ways to declare a share:
 *   - Registry-level via `r.share[A]`: names a type to pin across the whole resolution.
 *   - Entry-level via `gen(f).share` / `gen(g).share`: marks a specific entry; its output type
 *     is pinned automatically when the registry is built via `.shared` / `.share[_]`.
 *
 * Sampling is threaded through `Gen.flatMap`: each shared `Gen[A]` is sampled once at the outer
 * level, then a `Gen[A] => Gen.const(sample)` tweak is installed before the rest of the graph is
 * resolved. Shared types are sampled in declaration order (entry-level first, then explicit tags).
 */
final case class SharedRegistry[AllIns <: Tuple, AllOuts <: Tuple](
    underlying: Registry[AllIns, AllOuts],
    sharedTags: List[LightTypeTag]
):

  /** Add another naked type `A` to the shared set. Composes with prior `share` calls and any
    * entries in the underlying registry that were marked via the entry-level `.share`.
    */
  def share[A](using tag: Tag[Gen[A]]): SharedRegistry[AllIns, AllOuts] =
    copy(sharedTags = sharedTags :+ tag.tag)

  /** Build a `Gen[T]` with the accumulated sharing semantics applied. Entry-level `.share` outputs
    * are picked up automatically from the underlying registry; duplicates (same output type
    * requested twice) are de-duplicated by `LightTypeTag.repr`.
    */
  def make[T](using tagT: Tag[Gen[T]]): Gen[T] =
    val entryShared = underlying.entries.filter(_.shared).map(_.output)
    val merged = mergeShared(entryShared, sharedTags)
    build(underlying, merged, tagT.tag).asInstanceOf[Gen[T]]

  private def mergeShared(
      fromEntries: List[LightTypeTag],
      fromTags: List[LightTypeTag]
  ): List[LightTypeTag] =
    val combined = fromEntries ++ fromTags
    combined.foldLeft(List.empty[LightTypeTag]) { (acc, t) =>
      if acc.exists(_.repr == t.repr) then acc else acc :+ t
    }

  private def build(
      r: Registry[AllIns, AllOuts],
      pending: List[LightTypeTag],
      want: LightTypeTag
  ): Gen[Any] =
    pending match
      case Nil =>
        Resolve
          .resolve(r.entries, r.tweaks, r.specializations, want)
          .asInstanceOf[Gen[Any]]

      case head :: rest =>
        val sharedGen = Resolve
          .resolve(r.entries, r.tweaks, r.specializations, head)
          .asInstanceOf[Gen[Any]]
        sharedGen.flatMap { sample =>
          val pinToConst: Any => Any = (_: Any) => Gen.const(sample)
          build(r.copy(tweaks = r.tweaks :+ (head.repr, pinToConst)), rest, want)
        }

extension [AllIns <: Tuple, AllOuts <: Tuple](r: Registry[AllIns, AllOuts])
  /**
   * Lift into [[SharedRegistry]] and name `Gen[A]` as shared. Each top-level sample draws `Gen[A]`
   * once and pins downstream resolutions.
   */
  def share[A](using tag: Tag[Gen[A]]): SharedRegistry[AllIns, AllOuts] =
    SharedRegistry(r, List(tag.tag))

  /**
   * Lift into [[SharedRegistry]] without naming any explicit type. Only entries marked via the
   * entry-level `.share` contribute their outputs as shared. Use when you've marked the sharing
   * at the entry site and don't need to add more types.
   */
  def shared: SharedRegistry[AllIns, AllOuts] =
    SharedRegistry(r, Nil)

extension [Ins <: Tuple, T](e: TypedEntry[Ins, Gen[T]])
  /**
   * Mark an entry's output as shared: whenever its output type `Gen[T]` is requested during a
   * single `make` call, all consumers see the same sampled value. Takes effect when the registry
   * is built via [[SharedRegistry]] (`r.shared.make[...]` or `r.share[_].make[...]`); a plain
   * `Registry.make` ignores the flag.
   */
  def share: TypedEntry[Ins, Gen[T]] =
    TypedEntry(e.entry.copy(shared = true))
