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

    val insTpes = TypeRendering.dedupe(tupleElems(TypeRepr.of[Ins]))
    val outsTpes = TypeRendering.dedupe(tupleElems(TypeRepr.of[Outs]))
    val tTpe = TypeRepr.of[T]

    val produced = outsTpes.exists(_ =:= tTpe)
    val missing = insTpes.filterNot(i => outsTpes.exists(_ =:= i))

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
        // `Concat[A, B]` (registry.TypeChecks.Concat) sometimes appears un-reduced when one of
        // its arguments comes from a `transparent inline` macro that itself produces a tuple type.
        // Manually walk both halves so the chain isn't truncated.
        case AppliedType(tycon, List(a, b))
            if tycon.typeSymbol.fullName.endsWith("TypeChecks$.Concat") ||
               tycon.typeSymbol.fullName.endsWith("TypeChecks.Concat") =>
          tupleElems(a) ++ tupleElems(b)
        case _ => Nil

  private def formatNotProduced(using
      q: Quotes
  )(t: q.reflect.TypeRepr, outs: List[q.reflect.TypeRepr]): String =
    import q.reflect.*
    val render = TypeRendering.disambiguating(t :: outs)
    val outsPart =
      if outs.isEmpty then "Produced types: (none)"
      else s"Produced types:\n${outs.map(render).sorted.map(s => s"  $s").mkString("\n")}"
    val tail = s"No entry in this registry produces the type ${render(t)}."
    s"$outsPart\n\n$tail"

  private def formatMissing(using
      q: Quotes
  )(missing: List[q.reflect.TypeRepr], outs: List[q.reflect.TypeRepr]): String =
    import q.reflect.*
    val render = TypeRendering.disambiguating(missing ++ outs)
    val outsPart =
      if outs.isEmpty then "Produced outputs: (none)"
      else s"Produced outputs:\n${outs.map(render).sorted.map(s => s"  $s").mkString("\n")}"
    val missingPart =
      s"Missing inputs:\n${missing.map(render).sorted.map(s => s"  $s").mkString("\n")}"
    val tail = "Some registered entries require inputs that are not produced by this registry."
    s"$outsPart\n\n$missingPart\n\n$tail"
