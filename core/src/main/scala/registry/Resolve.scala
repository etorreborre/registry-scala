package registry

import izumi.reflect.macrortti.LightTypeTag

object Resolve:
  private val OneLineLimit = 60

  def resolve(
      entries: List[Entry],
      tweaks: List[(String, Any => Any)],
      specializations: List[(List[LightTypeTag], LightTypeTag, Any)],
      want: LightTypeTag
  ): Any =
    val cache = scala.collection.mutable.Map.empty[String, Any]
    go(entries, tweaks, specializations, want, List.empty, List.empty, cache)._1

  /** `inFlightEntries` tracks specific [[Entry]] instances already consumed in the current resolution
   * path — used to skip them when resolving inputs of the same type, which is what makes recursive
   * entries (an entry whose input and output types coincide) work.
   *
   * `inFlightTypes` tracks the requested types along the path — used for cycle error messages and
   * specialization-path matching.
   *
   * `cache` holds the *pre-tweak* invoke result of every non-`fresh` entry whose resolution did not
   * involve a specialization, keyed on the chosen entry's output `repr`. A cache hit returns the
   * stored value (tweaks are then re-applied per consumer based on `want`). Entries marked `fresh
   * = true` bypass the cache on both read and write.
   *
   * The second component of the return tuple is `specialized`: `true` if a specialization fired
   * during this resolution (directly or in any descendant). Specialized values are path-dependent
   * — caching them under a global key would poison subsequent unrelated lookups — so we propagate
   * the flag up and refuse to cache anywhere it's set.
   */
  private def go(
      entries: List[Entry],
      tweaks: List[(String, Any => Any)],
      specializations: List[(List[LightTypeTag], LightTypeTag, Any)],
      want: LightTypeTag,
      inFlightEntries: List[Entry],
      inFlightTypes: List[LightTypeTag],
      cache: scala.collection.mutable.Map[String, Any]
  ): (Any, Boolean) =
    // Check specializations first: if any applies for this path + target, short-circuit.
    val spec = specializations.find { case (path, target, _) =>
      want.repr == target.repr && isSubsequence(path, inFlightTypes)
    }

    spec match
      case Some((_, _, v)) =>
        (applyTweaks(v, want, tweaks), true)
      case None            =>
        // Subtype-aware entry lookup — skip entries already in flight so that a recursive entry
        // (same input/output type) picks a *different* entry for its input, enabling `genRecursive`.
        val candidate = entries.find(e => (e.output <:< want) && !inFlightEntries.contains(e))
        candidate match
          case None =>
            // No candidate. If the same type is already in flight it's a genuine cycle; otherwise
            // the type just isn't produced.
            if inFlightTypes.exists(_.repr == want.repr) then
              sys.error(formatCycle(want, inFlightTypes :+ want))
            else sys.error(formatMissing(want, entries.map(_.output.repr).distinct))
          case Some(entry) =>
            // Cache the *pre-tweak* invoke result keyed on the chosen entry's output. This way,
            // two consumers asking for `Cat` vs `Animal` that both resolve to the same Cat entry
            // share the underlying instance, but each gets its own by-type tweaks applied.
            val key = entry.output.repr
            if !entry.fresh && cache.contains(key) then
              (applyTweaks(cache(key), want, tweaks), false)
            else
              val nextEntries = inFlightEntries :+ entry
              val nextTypes   = inFlightTypes :+ want
              val resolved =
                entry.inputs.map(go(entries, tweaks, specializations, _, nextEntries, nextTypes, cache))
              val args              = resolved.map(_._1)
              val anyChildSpecialized = resolved.exists(_._2)
              val invoked           = entry.invoke(args)
              if !entry.fresh && !anyChildSpecialized then cache.update(key, invoked)
              (applyTweaks(invoked, want, tweaks), anyChildSpecialized)

  /** Apply every tweak whose key matches `want.repr`, in registration order. */
  private def applyTweaks(base: Any, want: LightTypeTag, tweaks: List[(String, Any => Any)]): Any =
    val key = want.repr
    tweaks.foldLeft(base) { case (acc, (tweakKey, f)) =>
      if tweakKey == key then f(acc) else acc
    }

  /** True iff the elements of `needle` appear (in order, not necessarily contiguous) in `haystack`,
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
    val reprs  = path.map(_.repr)
    val head   = s"Found a cycle while resolving ${want.repr}"
    val inline = s"$head: ${reprs.mkString(" -> ")}"
    if inline.length <= OneLineLimit then inline
    else s"$head:\n${reprs.map(r => s"  $r").mkString("\n")}"
