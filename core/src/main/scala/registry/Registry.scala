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
    Registry(
      l.entries ++ entries,
      l.tweaks ++ tweaks,
      l.specializations ++ specializations
    )

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
    Registry(
      l.entries ++ entries,
      l.tweaks ++ tweaks,
      l.specializations ++ specializations
    )

  /**
   * Append a [[Refinement]] (path-scoped specialization) to this registry. All three of `+:`, `*:`, `-:`
   * accept refinements and behave identically — a refinement adds no entries and does not change the
   * type-level `AllIns` / `AllOuts` accounting.
   */
  def +:[Path, T](r: Refinement[Path, T]): Registry[AllIns, AllOuts] =
    copy(specializations = specializations :+ (r.pathTags, r.targetTag, r.value))

  /** See [[+:]] for [[Refinement]] — `*:` is identical for refinements. */
  def *:[Path, T](r: Refinement[Path, T]): Registry[AllIns, AllOuts] =
    copy(specializations = specializations :+ (r.pathTags, r.targetTag, r.value))

  /** See [[+:]] for [[Refinement]] — `-:` is identical for refinements. */
  def -:[Path, T](r: Refinement[Path, T]): Registry[AllIns, AllOuts] =
    copy(specializations = specializations :+ (r.pathTags, r.targetTag, r.value))

  /** `memoize[T] +: registry` — memoizes every entry whose output is a subtype of `T`. */
  def +:[T](m: Memoize[T]): Registry[AllIns, AllOuts] =
    copy(entries =
      entries.map(e =>
        if e.output <:< m.targetTag then Registry.withMemoization(e) else e
      )
    )

  /** `share[T] +: registry` — sets the `shared` flag on every entry whose output is a subtype of
   * `T`. The flag is then picked up by share-aware build paths (e.g. `Registry.makeGen` in
   * scalacheck) to pin one sample of `T` across all consumers in a single build. */
  def +:[T](s: Share[T]): Registry[AllIns, AllOuts] =
    copy(entries =
      entries.map(e =>
        if e.output <:< s.targetTag then e.copy(shared = true) else e
      )
    )

  /** `const[T] +: registry` — combination of [[Share]] and [[Memoize]]: sets `shared` AND wraps
   * the matching entries' `invoke` with the marker's `memoizer`. Default memoizer caches the
   * invoke *result*; scalacheck's `const[T]` factory overrides it to also pin the sampled value
   * across separate `makeGen` calls. Roughly: `const[T] +: r ≈ share[T] +: memoize[T] +: r`. */
  def +:[T](c: Const[T]): Registry[AllIns, AllOuts] =
    copy(entries =
      entries.map(e =>
        if e.output <:< c.targetTag then c.memoizer(e.copy(shared = true)) else e
      )
    )

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
    copy(tweaks = tweaks :+ (tag.tag.repr, f.asInstanceOf[Any => Any]))

  /**
   * Memoize every entry whose output is a subtype of `A`: once resolved, the entry's value is cached and
   * returned on subsequent resolutions. For effectful types (e.g. `F[Service]`), this caches the effect
   * value — whether running it yields the same underlying value again is `F`'s concern (use cats-effect
   * `IO.memoize` for true effect-result memoization on top).
   *
   * The cache is stored in the entry's closure (via `AtomicReference`), so it survives all `copy`-based
   * combinators (`+:`, `*:`, `tweak`, `specialize`, etc.). Each call to `memoize[A]` creates a *new*
   * registry with a *fresh* cache; the original is unaffected.
   */
  def memoize[A](using tag: Tag[A]): Registry[AllIns, AllOuts] =
    val targetTag = tag.tag
    copy(entries = entries.map(e => if e.output <:< targetTag then Registry.withMemoization(e) else e))

  /** Memoize every entry in the registry. Equivalent to applying `memoize[T]` once per output type. */
  def memoizeAll: Registry[AllIns, AllOuts] =
    copy(entries = entries.map(Registry.withMemoization))

  /**
   * Context-scoped override: when the resolver is currently *inside* a build of `Ctx` (i.e. `Ctx` appears
   * anywhere in the resolution stack) and we're resolving `T`, return `v` instead of doing a normal
   * lookup. Shorthand for [[specializePath]] with a single-element path.
   */
  def specialize[Ctx, T](v: T)(using ctxTag: Tag[Ctx], tTag: Tag[T]): Registry[AllIns, AllOuts] =
    copy(specializations = specializations :+ (List(ctxTag.tag), tTag.tag, v.asInstanceOf[Any]))

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
    copy(specializations = specializations :+ (pathTags.map(_.tag), tTag.tag, v.asInstanceOf[Any]))

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

  /**
   * Wrap an entry's `invoke` closure with a cache. First call computes and stores the result; subsequent
   * calls return the cached value regardless of `args`. Thread-safe via `AtomicReference`.
   */
  private[registry] def withMemoization(entry: Entry): Entry =
    val ref = new java.util.concurrent.atomic.AtomicReference[Option[Any]](None)
    entry.copy(invoke =
      args =>
        ref.get() match
          case Some(cached) => cached
          case None =>
            val result = entry.invoke(args)
            ref.compareAndSet(None, Some(result))
            ref.get().get // either the value we just set, or another concurrent writer's — both are valid
    )
