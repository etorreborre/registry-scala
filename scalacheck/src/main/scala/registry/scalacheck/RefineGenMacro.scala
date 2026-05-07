package registry.scalacheck

import scala.compiletime.summonAll
import scala.quoted.*
import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag
import org.scalacheck.Gen
import registry.Refinement as RegRefinement

private[scalacheck] object RefineGenMacro:

  /**
   * Build a `Refinement[Path, Gen[T]]` from a value of type `T` or `Gen[T]`. T is fixed at the
   * call site (via the `refineGen[Path, T]` form), so functions are not supported here — use the
   * partially-applied `refineGen[Path](v)` form for that.
   */
  def refinementExpr[Path: Type, T: Type](v: Expr[T | Gen[T]])(using
      Quotes
  ): Expr[RegRefinement[Path, Gen[T]]] =
    import quotes.reflect.*
    val tTpe = TypeRepr.of[T]
    val genT = TypeRepr.of[Gen[T]]
    val vTpe = v.asTerm.tpe.widen.dealias

    if vTpe <:< genT then valueLikeRefinementExpr[Path, T](v.asExprOf[Gen[T]], wrap = false)
    else if vTpe <:< tTpe then valueLikeRefinementExpr[Path, T](v.asExprOf[T], wrap = true)
    else
      report.errorAndAbort(
        s"refineGen[Path, ${tTpe.show}](v): expected v to have type ${tTpe.show} or Gen[${tTpe.show}], got ${vTpe.show}"
      )

  /**
   * Partially-applied form: `refineGen[Path](v)` — dispatches on the inferred type of `v`:
   *
   *   - Function `(A, ...) => T` or `(A, ...) => Gen[T]`: build a function-style refinement whose
   *     `inputs` are the function's parameter types (each wrapped in `Gen[_]` if not already) and
   *     whose `invoke` applies the function via [[GenCombine.combineGens]] / `combineGensFlat`.
   *     The refinement's target is `Gen[T]` (the function's return type, unwrapped if it's already
   *     `Gen[T]`).
   *   - `Gen[T]`: register the supplied generator as-is (zero inputs).
   *   - Plain value `T`: wrap with `Gen.const(v)` (zero inputs).
   *
   * Mirrors the dispatch logic of [[GenMacro.valueImpl]], producing a `Refinement` instead of a
   * `TypedEntry`.
   */
  /**
   * Apply a partially-applied refinement to an existing registry, returning a new registry with
   * the refinement appended. Mirrors [[partialAppliedExpr]] but composes directly with the
   * registry's `+:` so that the inferred precise refinement type doesn't have to round-trip
   * through a `transparent inline` boundary.
   */
  def registryAppliedExpr[AllIns <: Tuple: Type, AllOuts <: Tuple: Type, Path: Type, V: Type](
      r: Expr[registry.Registry[AllIns, AllOuts]],
      v: Expr[V]
  )(using Quotes): Expr[registry.Registry[AllIns, AllOuts]] =
    import quotes.reflect.*
    val refinementExpr = partialAppliedExpr[Path, V](v)
    '{
      val refinement = ${ refinementExpr }.asInstanceOf[RegRefinement[Path, Gen[Any]]]
      $r.copy(refinements = $r.refinements :+ refinement)
    }

  def partialAppliedExpr[Path: Type, V: Type](v: Expr[V])(using Quotes): Expr[Any] =
    import quotes.reflect.*
    val vTpe = TypeRepr.of[V].dealias
    val effectiveTpe = vTpe match
      case ConstantType(_) => vTpe.widen
      case _               => vTpe
    val genSym = TypeRepr.of[Gen[Any]].typeSymbol

    effectiveTpe match
      case AppliedType(tycon, _) if isFunctionType(tycon) =>
        functionRefinementExpr[Path](effectiveTpe, v)
      case AppliedType(tycon, List(inner)) if tycon.typeSymbol == genSym =>
        inner.asType match
          case '[t] => valueLikeRefinementExpr[Path, t](v.asExprOf[Gen[t]], wrap = false)
      case _ =>
        effectiveTpe.asType match
          case '[t] => valueLikeRefinementExpr[Path, t](v.asExprOf[t], wrap = true)

  /**
   * Build a value-style refinement (zero inputs). When `wrap = false`, `v` is already a `Gen[T]`;
   * when `wrap = true`, `v: T` and the closure wraps it in `Gen.const`.
   */
  private def valueLikeRefinementExpr[Path: Type, T: Type](
      v: Expr[Any],
      wrap: Boolean
  )(using Quotes): Expr[RegRefinement[Path, Gen[T]]] =
    if wrap then
      val tExpr = v.asExprOf[T]
      '{
        val pathTags =
          summonAll[GenPathTags[Path]].toList.asInstanceOf[List[Tag[?]]]
        RegRefinement[Path, Gen[T]](
          pathTags.map(_.tag),
          summon[Tag[Gen[T]]].tag,
          Nil,
          _ => Gen.const($tExpr)
        )
      }
    else
      val genExpr = v.asExprOf[Gen[T]]
      '{
        val pathTags =
          summonAll[GenPathTags[Path]].toList.asInstanceOf[List[Tag[?]]]
        RegRefinement[Path, Gen[T]](
          pathTags.map(_.tag),
          summon[Tag[Gen[T]]].tag,
          Nil,
          _ => $genExpr
        )
      }

  /**
   * Function-style refinement: parameter types become the refinement's `inputs` (each Gen-wrapped
   * unless already `Gen[X]`), and `invoke` sequences them via `combineGens` / `combineGensFlat`
   * before applying the function — exactly like [[GenMacro.functionValueImpl]] for `gen(f)`, but
   * registered as a path-scoped refinement instead of a free entry.
   */
  private def functionRefinementExpr[Path: Type](using Quotes)(
      fnTpe: quotes.reflect.TypeRepr,
      f: Expr[?]
  ): Expr[Any] =
    import quotes.reflect.*

    val (paramTypes, retType) = fnTpe match
      case AppliedType(_, targs) =>
        (targs.init, targs.last)
      case other =>
        report.errorAndAbort(
          s"refineGen[Path](f): expected a FunctionN value, got ${other.show}"
        )

    val (genParamTypes, passthroughMask): (List[TypeRepr], List[Boolean]) =
      paramTypes.map { pt =>
        if isGenType(pt) then (pt, true)
        else
          pt.asType match
            case '[p] => (TypeRepr.of[Gen[p]], false)
      }.unzip

    val inputTagExprs: List[Expr[LightTypeTag]] = genParamTypes.map { gt =>
      gt.asType match
        case '[gp] => '{ summon[Tag[gp]].tag }
    }

    // If retType is Gen[X], the refinement target payload is X (not Gen[X]) so the refinement
    // doesn't double-wrap into Gen[Gen[X]].
    val retIsGen: Option[TypeRepr] = retType.dealias match
      case AppliedType(_, List(inner)) if isGenType(retType) => Some(inner)
      case _                                                 => None
    val outputPayloadType: TypeRepr = retIsGen.getOrElse(retType)

    retType.asType match
      case '[r] =>
        val buildFn: Expr[Seq[Any] => r] = '{ (vs: Seq[Any]) =>
          ${
            import quotes.reflect.*
            val argTerms: List[Term] = paramTypes.zipWithIndex.map { (pt, i) =>
              pt.asType match
                case '[p] => '{ vs(${ Expr(i) }).asInstanceOf[p] }.asTerm
            }
            Select.unique(f.asTerm, "apply").appliedToArgs(argTerms).asExprOf[r]
          }
        }

        val passthroughExpr: Expr[List[Boolean]] = Expr(passthroughMask)
        val closure: Expr[Seq[Any] => Any] = retIsGen match
          case Some(innerTpe) =>
            innerTpe.asType match
              case '[inner] =>
                '{ (args: Seq[Any]) =>
                  GenCombine.combineGensFlat[inner](
                    args.asInstanceOf[Seq[Gen[?]]],
                    $passthroughExpr,
                    $buildFn.asInstanceOf[Seq[Any] => Gen[inner]]
                  )
                }
          case None =>
            '{ (args: Seq[Any]) =>
              GenCombine.combineGens[r](
                args.asInstanceOf[Seq[Gen[?]]],
                $passthroughExpr,
                $buildFn
              )
            }

        outputPayloadType.asType match
          case '[o] =>
            '{
              val pathTags =
                summonAll[GenPathTags[Path]].toList.asInstanceOf[List[Tag[?]]]
              RegRefinement[Path, Gen[o]](
                pathTags.map(_.tag),
                summon[Tag[Gen[o]]].tag,
                ${ Expr.ofList(inputTagExprs) },
                $closure
              )
            }

  private def isFunctionType(using q: Quotes)(tycon: q.reflect.TypeRepr): Boolean =
    val name = tycon.typeSymbol.fullName
    name.startsWith("scala.Function") || name.startsWith("scala.ContextFunction")

  private def isGenType(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean =
    import q.reflect.*
    val genSym = TypeRepr.of[Gen[Any]].typeSymbol
    tpe.dealias match
      case AppliedType(tycon, List(_)) if tycon.typeSymbol == genSym => true
      case _                                                         => false
