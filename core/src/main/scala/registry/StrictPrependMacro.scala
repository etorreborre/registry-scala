package registry

import scala.quoted.*
import registry.TypeChecks.*

private[registry] object StrictPrependMacro:

  /** entry +: registry */
  def entryIntoRegistry[EIns <: Tuple: Type, EOut: Type, AllIns <: Tuple: Type, AllOuts <: Tuple: Type](
      self: Expr[Registry[AllIns, AllOuts]],
      e: Expr[TypedEntry[EIns, EOut]]
  )(using Quotes): Expr[Registry[Concat[EIns, AllIns], EOut *: AllOuts]] =
    import quotes.reflect.*
    checkOrError(
      newIns = TypeRendering.dedupe(tupleElems(TypeRepr.of[EIns])),
      producedBelow = TypeRendering.dedupe(tupleElems(TypeRepr.of[AllOuts])),
      subject = sourceOf(e)
    )
    '{
      Registry[Concat[EIns, AllIns], EOut *: AllOuts](
        ${ e }.entry :: ${ self }.entries,
        ${ self }.refinements
      )
    }

  /** leftRegistry +: rightRegistry — merge; left's needs must be covered by left's own outs or right's. */
  def registryIntoRegistry[
      LIns <: Tuple: Type,
      LOuts <: Tuple: Type,
      RIns <: Tuple: Type,
      ROuts <: Tuple: Type
  ](
      self: Expr[Registry[RIns, ROuts]],
      l: Expr[Registry[LIns, LOuts]]
  )(using Quotes): Expr[Registry[Concat[LIns, RIns], Concat[LOuts, ROuts]]] =
    import quotes.reflect.*
    checkOrError(
      newIns = TypeRendering.dedupe(tupleElems(TypeRepr.of[LIns])),
      producedBelow =
        TypeRendering.dedupe(tupleElems(TypeRepr.of[LOuts]) ++ tupleElems(TypeRepr.of[ROuts])),
      subject = sourceOf(l)
    )
    '{
      Registry[Concat[LIns, RIns], Concat[LOuts, ROuts]](
        ${ l }.entries ++ ${ self }.entries,
        ${ l }.refinements ++ ${ self }.refinements
      )
    }

  /** entryL +: entryR — treat the right as a 1-entry registry and prepend the left. */
  def entryIntoEntry[LIns <: Tuple: Type, LOut: Type, RIns <: Tuple: Type, ROut: Type](
      self: Expr[TypedEntry[RIns, ROut]],
      l: Expr[TypedEntry[LIns, LOut]]
  )(using Quotes): Expr[Registry[Concat[LIns, RIns], LOut *: ROut *: EmptyTuple]] =
    import quotes.reflect.*
    checkOrError(
      newIns = TypeRendering.dedupe(tupleElems(TypeRepr.of[LIns])),
      producedBelow = List(TypeRepr.of[ROut]),
      subject = sourceOf(l)
    )
    '{
      Registry[Concat[LIns, RIns], LOut *: ROut *: EmptyTuple](
        ${ l }.entry :: ${ self }.entry :: Nil,
        Nil
      )
    }

  /** leftRegistry +: rightEntry — left's needs must be covered by left's own outs or the right entry's output. */
  def registryIntoEntry[
      LIns <: Tuple: Type,
      LOuts <: Tuple: Type,
      RIns <: Tuple: Type,
      ROut: Type
  ](
      self: Expr[TypedEntry[RIns, ROut]],
      l: Expr[Registry[LIns, LOuts]]
  )(using Quotes): Expr[Registry[Concat[LIns, RIns], Concat[LOuts, ROut *: EmptyTuple]]] =
    import quotes.reflect.*
    checkOrError(
      newIns = TypeRendering.dedupe(tupleElems(TypeRepr.of[LIns])),
      producedBelow = TypeRendering.dedupe(tupleElems(TypeRepr.of[LOuts]) :+ TypeRepr.of[ROut]),
      subject = sourceOf(l)
    )
    '{
      Registry[Concat[LIns, RIns], Concat[LOuts, ROut *: EmptyTuple]](
        ${ l }.entries ++ (${ self }.entry :: Nil),
        ${ l }.refinements
      )
    }

  // ---- shared helpers ----

  /** Best-effort source-text snippet for the entry being prepended (left side of `+:`). Used by
    * the error message to show users *which* entry is failing the check.
    */
  /** Best-effort source-text snippet for the entry being prepended (left side of `+:`). The Expr
    * itself often has a zero-width / synthesized position because of `transparent inline`
    * expansion; instead we slice the macro call site (`Position.ofMacroExpansion` — the entire
    * `<left> +: <right>` expression) up to the first `+:` token, which is the textual left
    * operand. Naive but works for the common case where the left operand doesn't itself contain
    * a `+:` substring.
    */
  private def sourceOf(using q: Quotes)(e: Expr[Any]): Option[String] =
    import q.reflect.*
    val pos = Position.ofMacroExpansion
    pos.sourceFile.content.flatMap: content =>
      val start = pos.start
      val end = pos.end
      if start >= 0 && end > start && end <= content.length then
        val slice = content.substring(start, end)
        val idx = slice.indexOf("+:")
        val left = if idx > 0 then slice.substring(0, idx) else slice
        Some(left.trim).filter(_.nonEmpty)
      else None

  private def checkOrError(using q: Quotes)(
      newIns: List[q.reflect.TypeRepr],
      producedBelow: List[q.reflect.TypeRepr],
      subject: Option[String]
  ): Unit =
    import q.reflect.*
    // Subtype-aware: an output `o` satisfies an input slot of type `i` whenever `o <:< i`. This
    // mirrors the runtime resolver in `Resolve.go` and lets a `Gen[Sub]` cover a `Gen[Super]` slot
    // (Gen is covariant), instead of demanding strict type equality.
    val missing = newIns.filterNot(i => producedBelow.exists(_ <:< i))
    if missing.nonEmpty then report.errorAndAbort(formatError(missing, producedBelow, subject))

  private def tupleElems(using Quotes)(tpe: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] =
    import quotes.reflect.*
    if tpe =:= TypeRepr.of[EmptyTuple] then Nil
    else
      tpe.dealias match
        case AppliedType(tycon, List(h, t)) if tycon.typeSymbol.fullName == "scala.*:" =>
          h :: tupleElems(t)
        // `Concat[A, B]` (registry.TypeChecks.Concat) sometimes appears un-reduced when one of
        // its arguments comes from a `transparent inline` macro that itself produces a tuple type.
        // Manually walk both halves so the chain isn't truncated.
        case AppliedType(tycon, List(a, b))
            if tycon.typeSymbol.fullName.endsWith("TypeChecks$.Concat") ||
               tycon.typeSymbol.fullName.endsWith("TypeChecks.Concat") =>
          tupleElems(a) ++ tupleElems(b)
        case _ => Nil

  /** Maximum line count for the compact "header-first" layout. Beyond this we switch to the
    * long form (outputs first, then missing, then header) so the most actionable info — the
    * missing inputs — is closer to the bottom and easier to spot when the produced-outputs list
    * is huge.
    */
  private inline val CompactLayoutMaxLines = 25

  private def formatError(using
      q: Quotes
  )(
      missing: List[q.reflect.TypeRepr],
      outs: List[q.reflect.TypeRepr],
      subject: Option[String]
  ): String =
    import q.reflect.*
    val render = TypeRendering.disambiguating(missing ++ outs)
    val outsPart =
      if outs.isEmpty then "Produced outputs: (none)"
      else s"Produced outputs:\n${outs.map(render).sorted.map(s => s"  $s").mkString("\n")}"
    val missingPart =
      s"Missing inputs:\n${missing.map(render).sorted.map(s => s"  $s").mkString("\n")}"
    val header = subject match
      case Some(src) =>
        s"+: cannot prepend this entry because some inputs cannot be produced by the rest of the registry:\n    $src"
      case None =>
        "+: cannot prepend this entry because some inputs cannot be produced by the rest of the registry."

    val compact = s"$header\n\n$missingPart\n\n$outsPart"
    val long    = s"$outsPart\n\n$missingPart\n\n$header\n\n"
    val totalLines = compact.count(_ == '\n') + 1

    if totalLines <= CompactLayoutMaxLines then compact else long
