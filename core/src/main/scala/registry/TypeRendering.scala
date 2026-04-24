package registry

import scala.quoted.*

private[registry] object TypeRendering:

  /** A renderer for `TypeRepr` values that uses short names by default but switches to fully-qualified
   * names for any short name that maps to multiple distinct types in `context`. This way two types
   * that happen to share a short name (e.g. `Coin` from two different packages) are not displayed
   * identically — which would otherwise make a "missing input matches a produced output" message
   * look like a registry bug.
   */
  def disambiguating(using
      q: Quotes
  )(context: List[q.reflect.TypeRepr]): q.reflect.TypeRepr => String =
    import q.reflect.*
    val short = Printer.TypeReprShortCode
    val full = Printer.TypeReprCode

    val deduped: List[TypeRepr] = context.foldLeft(List.empty[TypeRepr]): (acc, t) =>
      if acc.exists(_ =:= t) then acc else acc :+ t

    val ambiguous: Set[String] =
      deduped
        .groupBy(_.show(using short))
        .collect { case (name, ts) if ts.size > 1 => name }
        .toSet

    (t: TypeRepr) =>
      val s = t.show(using short)
      if ambiguous.contains(s) then t.show(using full) else s
