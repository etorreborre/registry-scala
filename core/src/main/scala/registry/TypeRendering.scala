package registry

import scala.quoted.*

private[registry] object TypeRendering:

  /** Deduplicate `tpes`, preserving first-seen order. Two reprs are considered equal iff their
    * fully-qualified `Printer.TypeReprCode` rendering matches. We deliberately don't use
    * [[scala.quoted.Quotes.reflect.TypeReprMethods.=:=]] because for path-dependent references to
    * opaque type aliases (e.g. `$proxy1.AddrKeyHash` vs. `$proxy1.PoolKeyHash`, both of which
    * dealias to `Hash[Blake2b_224, _]` instances) `=:=` can spuriously equate different aliases —
    * presumably because the covariant opaque-type bound `<: ByteString = ByteString` makes the
    * compiler treat both as the same underlying type when the variance hides the purpose
    * parameter. The textual-key form sidesteps that.
    */
  def dedupe(using q: Quotes)(tpes: List[q.reflect.TypeRepr]): List[q.reflect.TypeRepr] =
    import q.reflect.*
    val seen = scala.collection.mutable.Set.empty[String]
    tpes.flatMap { t =>
      val key = t.show(using Printer.TypeReprCode)
      if seen(key) then None
      else { seen += key; Some(t) }
    }

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

    val deduped: List[TypeRepr] = dedupe(context)

    val ambiguous: Set[String] =
      deduped
        .groupBy(_.show(using short))
        .collect { case (name, ts) if ts.size > 1 => name }
        .toSet

    (t: TypeRepr) =>
      val s = t.show(using short)
      if ambiguous.contains(s) then t.show(using full) else s
