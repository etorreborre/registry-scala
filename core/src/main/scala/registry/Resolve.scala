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
    go(entries, tweaks, specializations, want, List.empty, List.empty)

  /** `inFlightEntries` tracks specific [[Entry]] instances already consumed in the current resolution
   * path — used to skip them when resolving inputs of the same type, which is what makes recursive
   * entries (an entry whose input and output types coincide) work.
   *
   * `inFlightTypes` tracks the requested types along the path — used for cycle error messages and
   * specialization-path matching.
   */
  private def go(
      entries: List[Entry],
      tweaks: List[(String, Any => Any)],
      specializations: List[(List[LightTypeTag], LightTypeTag, Any)],
      want: LightTypeTag,
      inFlightEntries: List[Entry],
      inFlightTypes: List[LightTypeTag]
  ): Any =
    // Check specializations first: if any applies for this path + target, short-circuit.
    val spec = specializations.find { case (path, target, _) =>
      want.repr == target.repr && isSubsequence(path, inFlightTypes)
    }

    val base = spec match
      case Some((_, _, v)) => v
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
            val nextEntries = inFlightEntries :+ entry
            val nextTypes   = inFlightTypes :+ want
            val args        = entry.inputs.map(go(entries, tweaks, specializations, _, nextEntries, nextTypes))
            entry.invoke(args)

    applyTweaks(base, want, tweaks)

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
