package registry.scalacheck

import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag
import org.scalacheck.Gen
import registry.{Entry, Registry, Resolve, TypedEntry}

/**
 * Sharing for generators: when a `Gen[A]` should yield ONE sample per generated `T`, shared across
 * every consumer in the dependency tree. Without sharing, the resolver samples `Gen[A]`
 * independently at each position — decorrelating state that should be shared.
 *
 * Two ways to declare a share:
 *   - Registry-level via `r.share[A]`: names a type to pin across the whole resolution.
 *   - Entry-level via `gen(g).share`: marks a specific entry; its output type is pinned
 *     automatically when `makeGen[T]` is called.
 *
 * Sampling is threaded through `Gen.flatMap`: each shared `Gen[A]` is sampled once at the outer
 * level, then a fresh value-style entry producing `Gen.const(sample)` is prepended to the
 * registry before the rest of the graph is resolved (LIFO selection makes the pinned entry win).
 * Shared types are sampled in **dependency order**: if a shared type `B` appears in the
 * resolution chain of another shared type `A` (i.e. `A`'s generator transitively consumes `B`),
 * `B` is sampled and pinned first so `A`'s sample observes the pinned `B`.
 */

extension [AllIns <: Tuple, AllOuts <: Tuple](r: Registry[AllIns, AllOuts])

  /**
   * Build a `Gen[T]`. When any entry is a [[GenEntry]] with `shared = true` (set via the entry-
   * level `.share` / `.const` or via the standalone `share[T] +:` / `const[T] +:` factory), the
   * resolution runs through the share build path so each shared `Gen` is sampled once and pinned
   * across all consumers in the dependency tree. When no sharing is declared, delegates to plain
   * `Resolve.resolve`.
   */
  def makeGen[T](using tag: Tag[Gen[T]]): Gen[T] =
    val entryShared = r.entries.collect { case g: GenEntry if g.shared => g.output }
    if entryShared.isEmpty then r.make[Gen[T]]
    else
      val ordered = topoSortShared(dedupe(entryShared), r.entries)
      build(r, ordered, tag.tag).asInstanceOf[Gen[T]]

private def dedupe(tags: List[LightTypeTag]): List[LightTypeTag] =
  tags.foldLeft(List.empty[LightTypeTag]) { (acc, t) =>
    if acc.exists(_.repr == t.repr) then acc else acc :+ t
  }

/**
 * Order shared types so that dependencies come first: if `A`'s resolution chain transitively
 * consumes another shared type `B`, then `B` is placed before `A`. Without this ordering, `A`
 * would be sampled (and pinned) using a fresh `B` before `B`'s pin is installed — producing a
 * value where `A`'s embedded `B` differs from the directly-pinned `B`. Cycles are tolerated:
 * any types still tied at the end are taken in their original order.
 */
private def topoSortShared(
    shared: List[LightTypeTag],
    entries: List[Entry]
): List[LightTypeTag] =
  val sharedReprs: Set[String] = shared.map(_.repr).toSet
  val tagByRepr: Map[String, LightTypeTag] = shared.map(t => t.repr -> t).toMap

  // For each shared output, find the set of OTHER shared outputs that appear in its resolution
  // chain (mirroring `Resolve.resolve`'s "first matching subtype, head wins" lookup so we follow
  // the same path the resolver will).
  def consumesShared(target: LightTypeTag, seen: Set[String]): Set[String] =
    if seen.contains(target.repr) then Set.empty
    else
      entries.find(_.output <:< target) match
        case None => Set.empty
        case Some(entry) =>
          entry.inputs.foldLeft(Set.empty[String]) { (acc, in) =>
            val here = if sharedReprs.contains(in.repr) then Set(in.repr) else Set.empty
            acc ++ here ++ consumesShared(in, seen + target.repr)
          }

  val deps: Map[String, Set[String]] =
    shared.map(t => t.repr -> (consumesShared(t, Set.empty) - t.repr)).toMap

  // Kahn's algorithm preserving the input order among ready nodes.
  val sorted = scala.collection.mutable.ListBuffer.empty[LightTypeTag]
  val remaining = scala.collection.mutable.LinkedHashSet.empty[String]
  remaining ++= shared.map(_.repr)
  while remaining.nonEmpty do
    val readyOpt = remaining.iterator.find(r => deps(r).forall(d => !remaining.contains(d)))
    val pick = readyOpt.getOrElse(remaining.head) // cycle: fall back to declaration order
    sorted += tagByRepr(pick)
    remaining -= pick
  sorted.toList

private def build[AllIns <: Tuple, AllOuts <: Tuple](
    r: Registry[AllIns, AllOuts],
    pending: List[LightTypeTag],
    want: LightTypeTag
): Gen[Any] =
  pending match
    case Nil =>
      Resolve.resolve(r.entries, r.refinements, want).asInstanceOf[Gen[Any]]

    case head :: rest =>
      val sharedGen =
        Resolve.resolve(r.entries, r.refinements, head).asInstanceOf[Gen[Any]]
      sharedGen.flatMap { sample =>
        // Pin the sampled `Gen[A]` for the rest of the build by prepending a value-style entry
        // that produces `Gen.const(sample)`. LIFO selection makes this entry win over any other
        // `Gen[A]` producer further down the chain.
        val pinned = Entry(Nil, head, _ => Gen.const(sample))
        build(r.copy(entries = pinned :: r.entries), rest, want)
      }

/**
 * Memoizer for `const[T]` in scalacheck: one fixed sampled value of `T` for the registry's
 * lifetime, regardless of how many `makeGen` calls or which seeds are used.
 *
 * Wraps the entry's `invoke` in two layers:
 *   1. The returned `Gen[T]` flatMaps through an `AtomicReference[Option[T]]`. The first sample
 *      wins the compare-and-set and pins the value; every subsequent sample short-circuits to
 *      `Gen.const(pinnedValue)`.
 *   2. The `invoke` itself is memoized so the SAME wrapped `Gen` instance (with the same atomic
 *      reference) is returned across every resolution — including across separate `makeGen` calls
 *      on the same registry.
 *
 * This is what distinguishes `const[T]` from `share[T]` in scalacheck: `share[T]` pins inside one
 * `makeGen` tree only (via per-call tweaks); `const[T]` additionally pins ACROSS calls.
 */
private[scalacheck] def withConstSampling(entry: Entry): Entry =
  val sample = new java.util.concurrent.atomic.AtomicReference[Option[Any]](None)
  val pinningInvoke: Seq[Any] => Any = args =>
    val underlying = entry.invoke(args).asInstanceOf[Gen[Any]]
    underlying.flatMap { fresh =>
      sample.compareAndSet(None, Some(fresh))
      Gen.const(sample.get.get)
    }
  Registry.withMemoization(entry.withInvoke(pinningInvoke))

// Entry-level `.share` and `.const` extensions live in `Gen.scala` so they're co-located with
// the `share[T]` / `const[T]` factories.
