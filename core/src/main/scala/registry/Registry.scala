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
 * `refinements` is a list of context-sensitive overrides: each refinement says "whenever the
 * resolution stack contains the types of `path` as a subsequence (in order, not necessarily
 * contiguous) and we're resolving `target`, return the given value instead of running the normal
 * lookup".
 */
final case class Registry[AllIns <: Tuple, AllOuts <: Tuple](
    entries: List[Entry],
    refinements: List[(List[LightTypeTag], LightTypeTag, Any)] = Nil
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
    Registry(e.entry :: entries, refinements)

  /**
   * Tracked merge: `left *: right` combines both registries' entries and tracks combined types.
   * No compile-time check — `makeSafe` reports any residual gaps.
   */
  def *:[LIns <: Tuple, LOuts <: Tuple](
      l: Registry[LIns, LOuts]
  ): Registry[Concat[LIns, AllIns], Concat[LOuts, AllOuts]] =
    Registry(l.entries ++ entries, l.refinements ++ refinements)

  /**
   * Untracked prepend: `entry -: registry`. Adds the entry at runtime but does NOT update the type-level
   * `AllIns` / `AllOuts` accounting — the entry is invisible to `makeSafe`. Escape hatch for dynamic use cases.
   */
  def -:[EIns <: Tuple, EOut](e: TypedEntry[EIns, EOut]): Registry[AllIns, AllOuts] =
    Registry(e.entry :: entries, refinements)

  /** Untracked prepend of a raw `Entry`. Like [[-:]] but for manually-constructed entries. */
  def -:(e: Entry): Registry[AllIns, AllOuts] =
    Registry(e :: entries, refinements)

  /**
   * Untracked merge: `left -: right` combines both registries' entries but keeps only the receiver's
   * type-level accounting — the left side is invisible to `makeSafe`.
   */
  def -:[LIns <: Tuple, LOuts <: Tuple](l: Registry[LIns, LOuts]): Registry[AllIns, AllOuts] =
    Registry(l.entries ++ entries, l.refinements ++ refinements)

  /**
   * Append a [[Refinement]] (path-scoped override) to this registry. All three of `+:`, `*:`, `-:`
   * accept refinements and behave identically — a refinement adds no entries and does not change
   * the type-level `AllIns` / `AllOuts` accounting.
   */
  def +:[Path, T](r: Refinement[Path, T]): Registry[AllIns, AllOuts] =
    copy(refinements = refinements :+ (r.pathTags, r.targetTag, r.value))

  /** See [[+:]] for [[Refinement]] — `*:` is identical for refinements. */
  def *:[Path, T](r: Refinement[Path, T]): Registry[AllIns, AllOuts] =
    copy(refinements = refinements :+ (r.pathTags, r.targetTag, r.value))

  /** See [[+:]] for [[Refinement]] — `-:` is identical for refinements. */
  def -:[Path, T](r: Refinement[Path, T]): Registry[AllIns, AllOuts] =
    copy(refinements = refinements :+ (r.pathTags, r.targetTag, r.value))

  /**
   * `marker +: registry` — apply a [[Marker]] to every entry whose output is a subtype of the
   * marker's `targetTag`. Used by `memoize[A] +: r` (core) and module markers like `share[T] +:`
   * and `const[T] +:` (registry-scalacheck), which all extend [[Marker]].
   */
  def +:[T](m: Marker[T]): Registry[AllIns, AllOuts] =
    copy(entries =
      entries.map(e => if e.output <:< m.targetTag then m.transform(e) else e)
    )

  /** Merge two registries. Left operand's entries come first, so on duplicate outputs the left wins. */
  def <+>[OIns <: Tuple, OOuts <: Tuple](
      other: Registry[OIns, OOuts]
  ): Registry[Concat[AllIns, OIns], Concat[AllOuts, OOuts]] =
    Registry(entries ++ other.entries, refinements ++ other.refinements)

  /**
   * Drop all type-level tracking. The entries and refinements are preserved, so [[make]] still
   * works, but [[makeSafe]] can no longer verify anything.
   */
  def erase: Registry[EmptyTuple, EmptyTuple] = Registry(entries, refinements)

  /**
   * Memoize every entry whose output is a subtype of `A`: once resolved, the entry's value is cached and
   * returned on subsequent resolutions. For effectful types (e.g. `F[Service]`), this caches the effect
   * value — whether running it yields the same underlying value again is `F`'s concern (use cats-effect
   * `IO.memoize` for true effect-result memoization on top).
   *
   * The cache is stored in the entry's closure (via `AtomicReference`), so it survives all `copy`-based
   * combinators (`+:`, `*:`, `refine`, etc.). Each call to `memoize[A]` creates a *new* registry with a
   * *fresh* cache; the original is unaffected.
   */
  def memoize[A](using tag: Tag[A]): Registry[AllIns, AllOuts] =
    val targetTag = tag.tag
    copy(entries = entries.map(e => if e.output <:< targetTag then Registry.withMemoization(e) else e))

  /** Memoize every entry in the registry. Equivalent to applying `memoize[T]` once per output type. */
  def memoizeAll: Registry[AllIns, AllOuts] =
    copy(entries = entries.map(Registry.withMemoization))

  /**
   * Opt every entry whose output is a subtype of `A` out of the resolver's per-`make` cache.
   * Each consumer of `A` triggers a fresh `invoke`. The default is to share — use this only for
   * types that should genuinely be distinct per consumer (e.g. UUIDs, fresh request IDs).
   */
  def fresh[A](using tag: Tag[A]): Registry[AllIns, AllOuts] =
    val targetTag = tag.tag
    copy(entries = entries.map(e => if e.output <:< targetTag then e.withFresh() else e))

  /**
   * Context-scoped refinement: when the resolver is currently *inside* a build of `Ctx` (i.e. `Ctx`
   * appears anywhere in the resolution stack) and we're resolving `T`, return `v` instead of doing
   * a normal lookup. Shorthand for [[refinePath]] with a single-element path.
   */
  def refine[Ctx, T](v: T)(using ctxTag: Tag[Ctx], tTag: Tag[T]): Registry[AllIns, AllOuts] =
    copy(refinements = refinements :+ (List(ctxTag.tag), tTag.tag, v.asInstanceOf[Any]))

  /**
   * Path-scoped refinement: when the resolution stack contains the types of `Path` as a subsequence
   * (in order, not necessarily contiguous) and we're resolving `T`, return `v` instead of doing a
   * normal lookup.
   *
   * `refinePath[Ctx *: EmptyTuple, T]` is equivalent to `refine[Ctx, T]`. Multi-element paths let
   * you scope overrides to specific routes through the dependency graph.
   */
  transparent inline def refinePath[Path <: Tuple, T](v: T)(using
      tTag: Tag[T]
  ): Registry[AllIns, AllOuts] =
    val pathTags =
      summonAll[Tuple.Map[Path, [s] =>> Tag[s]]].toList.asInstanceOf[List[Tag[?]]]
    copy(refinements = refinements :+ (pathTags.map(_.tag), tTag.tag, v.asInstanceOf[Any]))

  /** Build a value of type `T`. Runtime-only; throws if a dependency is missing. */
  def make[T](using tag: Tag[T]): T =
    Resolve.resolve(entries, refinements, tag.tag).asInstanceOf[T]

  /**
   * Build a value of type `T` with compile-time checks: `T` must be produced, and every required input
   * of every registered entry must be covered by an output. On failure, the compile error lists the
   * unresolved types one per line.
   */
  inline def makeSafe[T](using tag: Tag[T]): T =
    ${ MakeSafeMacro.impl[T, AllIns, AllOuts]('this, 'tag) }

object Registry:
  val empty: Registry[EmptyTuple, EmptyTuple] = Registry(Nil, Nil)

  /**
   * Wrap an entry's `invoke` closure with a cache. First call computes and stores the result; subsequent
   * calls return the cached value regardless of `args`. Thread-safe via `AtomicReference`.
   */
  private[registry] def withMemoization(entry: Entry): Entry =
    val ref = new java.util.concurrent.atomic.AtomicReference[Option[Any]](None)
    entry.withInvoke(args =>
      ref.get() match
        case Some(cached) => cached
        case None =>
          val result = entry.invoke(args)
          ref.compareAndSet(None, Some(result))
          ref.get().get // either the value we just set, or another concurrent writer's — both are valid
    )
