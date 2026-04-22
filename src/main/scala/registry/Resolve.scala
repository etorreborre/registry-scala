package registry

import izumi.reflect.macrortti.LightTypeTag

object Resolve:
  private val OneLineLimit = 60

  def resolve(registry: Registry, want: LightTypeTag): Any =
    go(registry, want, List.empty)

  private def go(registry: Registry, want: LightTypeTag, inFlight: List[LightTypeTag]): Any =
    if inFlight.exists(_.repr == want.repr) then
      sys.error(formatCycle(want, inFlight :+ want))
    val entry = registry.entries.find(_.output.repr == want.repr).getOrElse {
      sys.error(formatMissing(want, registry.entries.map(_.output.repr).distinct))
    }
    val nextInFlight = inFlight :+ want
    val args         = entry.inputs.map(go(registry, _, nextInFlight))
    entry.invoke(args)

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
