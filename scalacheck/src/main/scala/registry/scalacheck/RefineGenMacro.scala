package registry.scalacheck

import scala.compiletime.summonAll
import scala.quoted.*
import izumi.reflect.Tag
import org.scalacheck.Gen
import registry.{Refinement as RegRefinement, Registry}

private[scalacheck] object RefineGenMacro:

  /**
   * Build a `Refinement[Path, Gen[T]]` from a value of type `T` or `Gen[T]`, dispatching on the
   * actual call-site type of `v`:
   *
   *   - `v: Gen[T]` → register the supplied generator as-is.
   *   - `v: T`      → wrap with `Gen.const(v)`.
   *
   * Mirrors [[GenMacro.valueImpl]]'s value-vs-Gen split, but the user always writes the *payload*
   * type `T` (never `Gen[T]`) for the type parameter — the auto-lift handles the rest.
   */
  def refinementExpr[Path: Type, T: Type](v: Expr[T | Gen[T]])(using
      Quotes
  ): Expr[RegRefinement[Path, Gen[T]]] =
    import quotes.reflect.*
    val tTpe = TypeRepr.of[T]
    val genT = TypeRepr.of[Gen[T]]
    val vTpe = v.asTerm.tpe.widen.dealias

    if vTpe <:< genT then
      val genExpr = v.asExprOf[Gen[T]]
      '{
        val pathTags =
          summonAll[GenPathTags[Path]].toList.asInstanceOf[List[Tag[?]]]
        RegRefinement[Path, Gen[T]](
          pathTags.map(_.tag),
          summon[Tag[Gen[T]]].tag,
          $genExpr
        )
      }
    else if vTpe <:< tTpe then
      val tExpr = v.asExprOf[T]
      '{
        val pathTags =
          summonAll[GenPathTags[Path]].toList.asInstanceOf[List[Tag[?]]]
        RegRefinement[Path, Gen[T]](
          pathTags.map(_.tag),
          summon[Tag[Gen[T]]].tag,
          Gen.const($tExpr)
        )
      }
    else
      report.errorAndAbort(
        s"refineGen[Path, ${tTpe.show}](v): expected v to have type ${tTpe.show} or Gen[${tTpe.show}], got ${vTpe.show}"
      )

  /**
   * `r.refineGen[Path, T](v)` macro: emit `refineGen[Path, T](v) +: r`. Reuses [[refinementExpr]]
   * so the value-vs-Gen dispatch is identical to the standalone factory.
   */
  def registryExpr[Ins <: Tuple: Type, Outs <: Tuple: Type, Path: Type, T: Type](
      r: Expr[Registry[Ins, Outs]],
      v: Expr[T | Gen[T]]
  )(using Quotes): Expr[Registry[Ins, Outs]] =
    val refExpr = refinementExpr[Path, T](v)
    '{ $refExpr +: $r }
