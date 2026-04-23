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
    go(entries, tweaks, specializations, want, List.empty)

  private def go(
      entries: List[Entry],
      tweaks: List[(String, Any => Any)],
      specializations: List[(List[LightTypeTag], LightTypeTag, Any)],
      want: LightTypeTag,
      inFlight: List[LightTypeTag]
  ): Any =
    if inFlight.exists(_.repr == want.repr) then
      sys.error(formatCycle(want, inFlight :+ want))

    // Check specializations first: if any applies for this path + target, short-circuit.
    val spec = specializations.find { case (path, target, _) =>
      want.repr == target.repr && isSubsequence(path, inFlight)
    }

    val base = spec match
      case Some((_, _, v)) => v
      case None            =>
        // Subtype-aware entry lookup: a registered `List[Int]` matches a request for `Seq[Int]`.
        // LIFO order means the head wins when multiple entries are subtypes of `want`.
        val entry = entries.find(_.output <:< want).getOrElse {
          sys.error(formatMissing(want, entries.map(_.output.repr).distinct))
        }
        val nextInFlight = inFlight :+ want
        val args = entry.inputs.map(go(entries, tweaks, specializations, _, nextInFlight))
        entry.invoke(args)

    applyTweaks(base, want, tweaks)

  /** Apply every tweak whose key matches `want.repr`, in registration order. */
  private def applyTweaks(base: Any, want: LightTypeTag, tweaks: List[(String, Any => Any)]): Any =
    val key = want.repr
    tweaks.foldLeft(base) { case (acc, (tweakKey, f)) =>
      if tweakKey == key then f(acc) else acc
    }

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
