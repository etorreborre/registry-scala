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
      newIns = tupleElems(TypeRepr.of[EIns]),
      producedBelow = tupleElems(TypeRepr.of[AllOuts])
    )
    '{ Registry[Concat[EIns, AllIns], EOut *: AllOuts](${ e }.entry :: ${ self }.entries) }

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
      newIns = tupleElems(TypeRepr.of[LIns]),
      producedBelow = tupleElems(TypeRepr.of[LOuts]) ++ tupleElems(TypeRepr.of[ROuts])
    )
    '{ Registry[Concat[LIns, RIns], Concat[LOuts, ROuts]](${ l }.entries ++ ${ self }.entries) }

  /** entryL +: entryR — treat the right as a 1-entry registry and prepend the left. */
  def entryIntoEntry[LIns <: Tuple: Type, LOut: Type, RIns <: Tuple: Type, ROut: Type](
      self: Expr[TypedEntry[RIns, ROut]],
      l: Expr[TypedEntry[LIns, LOut]]
  )(using Quotes): Expr[Registry[Concat[LIns, RIns], LOut *: ROut *: EmptyTuple]] =
    import quotes.reflect.*
    checkOrError(
      newIns = tupleElems(TypeRepr.of[LIns]),
      producedBelow = List(TypeRepr.of[ROut])
    )
    '{
      Registry[Concat[LIns, RIns], LOut *: ROut *: EmptyTuple](
        ${ l }.entry :: ${ self }.entry :: Nil
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
      newIns = tupleElems(TypeRepr.of[LIns]),
      producedBelow = tupleElems(TypeRepr.of[LOuts]) :+ TypeRepr.of[ROut]
    )
    '{
      Registry[Concat[LIns, RIns], Concat[LOuts, ROut *: EmptyTuple]](
        ${ l }.entries ++ (${ self }.entry :: Nil)
      )
    }

  // ---- shared helpers ----

  private def checkOrError(using
      q: Quotes
  )(newIns: List[q.reflect.TypeRepr], producedBelow: List[q.reflect.TypeRepr]): Unit =
    import q.reflect.*
    val missing = newIns.filterNot(i => producedBelow.exists(_ =:= i))
    if missing.nonEmpty then report.errorAndAbort(formatError(missing, producedBelow))

  private def tupleElems(using Quotes)(tpe: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] =
    import quotes.reflect.*
    if tpe =:= TypeRepr.of[EmptyTuple] then Nil
    else
      tpe.dealias match
        case AppliedType(tycon, List(h, t)) if tycon.typeSymbol.fullName == "scala.*:" =>
          h :: tupleElems(t)
        case _ => Nil

  private def formatError(using
      q: Quotes
  )(missing: List[q.reflect.TypeRepr], outs: List[q.reflect.TypeRepr]): String =
    import q.reflect.*
    val printer = Printer.TypeReprShortCode
    val head = "+: cannot prepend this entry because some inputs cannot be produced by the rest of the registry."
    val missingPart = s"Missing inputs:\n${missing.map(m => s"  ${m.show(using printer)}").mkString("\n")}"
    val outsPart =
      if outs.isEmpty then "Produced outputs: (none)"
      else s"Produced outputs:\n${outs.map(o => s"  ${o.show(using printer)}").mkString("\n")}"
    s"$head\n\n$missingPart\n\n$outsPart"
