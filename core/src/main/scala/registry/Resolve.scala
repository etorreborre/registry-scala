package registry

import izumi.reflect.macrortti.LightTypeTag

object Resolve:
  private val OneLineLimit = 60

  def resolve(
      entries: List[Entry],
      refinements: List[Refinement[?, ?]],
      want: LightTypeTag
  ): Any =
    val cache = scala.collection.mutable.Map.empty[String, Any]
    go(entries, refinements, want, List.empty, List.empty, List.empty, cache)._1

  /**
   * `inFlightEntries` tracks specific [[Entry]] instances already consumed in the current resolution
   * path — used to skip them when resolving inputs of the same type, which is what makes recursive
   * entries (an entry whose input and output types coincide) work.
   *
   * `inFlightRefinements` plays the same role for [[Refinement]] instances: a function-style
   * refinement whose `inputs` includes its own target type would otherwise re-fire on its own
   * input and loop forever (the path scope is still active during input resolution). Skipping the
   * already-firing refinement lets the input fall through to a different refinement or to the
   * underlying entry list — the same mechanism that makes recursive entries terminate.
   *
   * `inFlightTypes` tracks the requested types along the path — used for cycle error messages and
   * refinement-path matching.
   *
   * `cache` holds the invoke result of every non-`fresh` entry whose resolution did not involve a
   * refinement, keyed on the chosen entry's output `repr`. A cache hit returns the stored value
   * directly. Entries marked `fresh = true` bypass the cache on both read and write.
   *
   * The second component of the return tuple is `refined`: `true` if a refinement fired during this
   * resolution (directly or in any descendant). Refined values are path-dependent — caching them
   * under a global key would poison subsequent unrelated lookups — so we propagate the flag up and
   * refuse to cache anywhere it's set.
   */
  private def go(
      entries: List[Entry],
      refinements: List[Refinement[?, ?]],
      want: LightTypeTag,
      inFlightEntries: List[Entry],
      inFlightRefinements: List[Refinement[?, ?]],
      inFlightTypes: List[LightTypeTag],
      cache: scala.collection.mutable.Map[String, Any]
  ): (Any, Boolean) =
    // Check refinements first: if any applies for this path + target, short-circuit. The target
    // match uses `<:<` (subtype-aware) — same as the entry lookup below — so a refinement
    // targeting `Gen[Resolved]` can satisfy a `Gen[Datum]` request via Gen's covariance, mirroring
    // how an Entry producing `Gen[Resolved]` would be picked up. A refinement already in flight is
    // skipped so its own inputs can resolve through a different refinement or through the entry
    // list, preventing infinite recursion when a function-style refinement's inputs include its
    // own target type.
    val refinement = refinements.find { r =>
      (r.targetTag <:< want) && isSubsequence(r.pathTags, inFlightTypes) &&
      !inFlightRefinements.contains(r)
    }

    refinement match
      case Some(r) =>
        // Function-style refinements declare `inputs` to be resolved from the surrounding registry,
        // mirroring how an `Entry` resolves its inputs. Value/Gen-style refinements have empty
        // inputs and `invoke` ignores its argument. Either way, propagate `refined = true` so the
        // caller refuses to cache the resulting value (refinement results are path-dependent).
        val nextTypes = inFlightTypes :+ want
        val nextRefinements = inFlightRefinements :+ r
        val resolved =
          r.inputs.map(in => go(entries, refinements, in, inFlightEntries, nextRefinements, nextTypes, cache))
        val args = resolved.map(_._1)
        (r.invoke(args), true)
      case None =>
        // Subtype-aware entry lookup — skip entries already in flight so that a recursive entry
        // (same input/output type) picks a *different* entry for its input, enabling `genRec`.
        val candidate = entries.find(e => (e.output <:< want) && !inFlightEntries.contains(e))
        candidate match
          case None =>
            // No candidate. If the same type is already in flight it's a genuine cycle; otherwise
            // the type just isn't produced.
            if inFlightTypes.exists(_.repr == want.repr) then
              sys.error(formatCycle(want, inFlightTypes :+ want))
            else sys.error(formatMissing(want, entries.map(_.output.repr).distinct))
          case Some(entry) =>
            // Cache the invoke result keyed on the chosen entry's output. Two consumers asking for
            // `Cat` vs `Animal` that resolve to the same Cat entry share the underlying instance.
            val key = entry.output.repr
            if !entry.fresh && cache.contains(key) then
              (cache(key), false)
            else
              val nextEntries = inFlightEntries :+ entry
              val nextTypes = inFlightTypes :+ want
              val resolved =
                entry.inputs.map(go(entries, refinements, _, nextEntries, inFlightRefinements, nextTypes, cache))
              val args = resolved.map(_._1)
              val anyChildRefined = resolved.exists(_._2)
              val invoked = entry.invoke(args)
              if !entry.fresh && !anyChildRefined then cache.update(key, invoked)
              (invoked, anyChildRefined)

  /**
   * True iff the elements of `needle` appear (in order, not necessarily contiguous) in `haystack`,
   * compared by `LightTypeTag.repr`.
   */
  private def isSubsequence(needle: List[LightTypeTag], haystack: List[LightTypeTag]): Boolean =
    needle match
      case Nil => true
      case n :: rest =>
        val idx = haystack.indexWhere(_.repr == n.repr)
        if idx < 0 then false
        else isSubsequence(rest, haystack.drop(idx + 1))

  private def formatMissing(want: LightTypeTag, outputs: List[String]): String =
    val head = s"No entry produces ${want.repr}"
    if outputs.isEmpty then s"$head. Available outputs: (none)"
    else
      val inline = s"$head. Available outputs: ${outputs.mkString(", ")}"
      if inline.length <= OneLineLimit then inline
      else s"$head.\nAvailable outputs:\n${outputs.map(o => s"  $o").mkString("\n")}"

  private def formatCycle(want: LightTypeTag, path: List[LightTypeTag]): String =
    val reprs = path.map(_.repr)
    val head = s"Found a cycle while resolving ${want.repr}"
    val inline = s"$head: ${reprs.mkString(" -> ")}"
    if inline.length <= OneLineLimit then inline
    else s"$head:\n${reprs.map(r => s"  $r").mkString("\n")}"
