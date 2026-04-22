package registry

import scala.quoted.*
import scala.collection.mutable.ListBuffer
import izumi.reflect.Tag

private[registry] object MakeSafeMacro:

  def impl[T: Type, Ins <: Tuple: Type, Outs <: Tuple: Type](
      self: Expr[Registry[Ins, Outs]],
      tag: Expr[Tag[T]]
  )(using Quotes): Expr[T] =
    import quotes.reflect.*

    val insTpes  = tupleElems(TypeRepr.of[Ins])
    val outsTpes = tupleElems(TypeRepr.of[Outs])
    val tTpe     = TypeRepr.of[T]

    val produced = outsTpes.exists(_ =:= tTpe)
    val missing  = insTpes.filterNot(i => outsTpes.exists(_ =:= i))

    val errors = ListBuffer.empty[String]
    if !produced then errors += formatNotProduced(tTpe, outsTpes)
    if missing.nonEmpty then errors += formatMissing(missing, outsTpes)

    if errors.nonEmpty then report.errorAndAbort(errors.mkString("\n\n"))

    '{ $self.make[T](using $tag) }

  private def tupleElems(using Quotes)(tpe: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] =
    import quotes.reflect.*
    if tpe =:= TypeRepr.of[EmptyTuple] then Nil
    else
      tpe.dealias match
        case AppliedType(tycon, List(h, t)) if tycon.typeSymbol.fullName == "scala.*:" =>
          h :: tupleElems(t)
        case _ => Nil

  private def formatNotProduced(using
      q: Quotes
  )(t: q.reflect.TypeRepr, outs: List[q.reflect.TypeRepr]): String =
    import q.reflect.*
    val printer = Printer.TypeReprShortCode
    val head    = s"No entry in this registry produces the type ${t.show(using printer)}."
    if outs.isEmpty then s"$head\nProduced types: (none)"
    else s"$head\nProduced types:\n${outs.map(o => s"  ${o.show(using printer)}").mkString("\n")}"

  private def formatMissing(using
      q: Quotes
  )(missing: List[q.reflect.TypeRepr], outs: List[q.reflect.TypeRepr]): String =
    import q.reflect.*
    val printer = Printer.TypeReprShortCode
    val head    = "Some registered entries require inputs that are not produced by this registry."
    val missingPart = s"Missing inputs:\n${missing.map(m => s"  ${m.show(using printer)}").mkString("\n")}"
    val outsPart =
      if outs.isEmpty then "Produced outputs: (none)"
      else s"Produced outputs:\n${outs.map(o => s"  ${o.show(using printer)}").mkString("\n")}"
    s"$head\n$missingPart\n$outsPart"
