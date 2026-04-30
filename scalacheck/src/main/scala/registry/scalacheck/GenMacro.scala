package registry.scalacheck

import scala.quoted.*
import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag
import org.scalacheck.Gen
import registry.{Entry, TypedEntry}

private[scalacheck] object GenMacro:

  def typeImpl[T: Type](using Quotes): Expr[TypedEntry[? <: Tuple, Gen[T]]] =
    import quotes.reflect.*

    // Dealias so that nested / path-dependent references like `Outer.Inner` resolve to the underlying
    // class symbol. Without this, `TypeRepr.of[Outer.Inner].typeSymbol.isClassDef` can return false
    // for case classes declared inside a companion object.
    val tpe = TypeRepr.of[T].dealias
    val sym = tpe.typeSymbol
    if !sym.isClassDef then
      report.errorAndAbort(s"gen[T] expects a class type, got ${tpe.show}")
    if sym.flags.is(Flags.Trait) then
      report.errorAndAbort(s"gen[T] cannot instantiate trait ${tpe.show}")
    if sym.flags.is(Flags.Abstract) then
      report.errorAndAbort(s"gen[T] cannot instantiate abstract class ${tpe.show}")
    if sym.flags.is(Flags.Module) then
      report.errorAndAbort(
        s"gen[T] cannot register an object; use gen(theObject) instead"
      )
    val ctor = sym.primaryConstructor
    if ctor == Symbol.noSymbol then
      report.errorAndAbort(s"gen[T]: ${tpe.show} has no primary constructor")

    val valueParamLists: List[List[Symbol]] =
      ctor.paramSymss.filterNot(_.headOption.exists(_.isType))
    val flatParams: List[Symbol] = valueParamLists.flatten
    val paramTypes: List[TypeRepr] = flatParams.map(tpe.memberType)

    // Wrap each input type in Gen[_] — the entry's declared inputs.
    val genParamTypes: List[TypeRepr] = paramTypes.map { pt =>
      pt.asType match
        case '[p] => TypeRepr.of[Gen[p]]
    }

    val inputTagExprs: List[Expr[LightTypeTag]] = genParamTypes.map { gt =>
      gt.asType match
        case '[gp] => '{ summon[Tag[gp]].tag }
    }
    val outputTagExpr: Expr[LightTypeTag] = '{ summon[Tag[Gen[T]]].tag }

    // Build the `build: Seq[Any] => T` function that invokes the primary constructor. This is the same
    // constructor-call shape as FunMacros.funTypeImpl; we wrap it in Gen.combineGens at the outer level.
    // Using the already-dealiased `tpe` + quoted `Type[t]` variant so that nested / path-dependent
    // types (e.g. `Outer.Inner`) resolve to their underlying class symbol consistently across both
    // the outer and inner quotes.
    val tpeType: Type[?] = tpe.asType
    val buildFn: Expr[Seq[Any] => T] = (tpeType: @unchecked) match
      case '[t] =>
        '{ (vs: Seq[Any]) =>
          ${
            import quotes.reflect.*
            val innerTpe = TypeRepr.of[t].dealias
            val innerCtor = innerTpe.typeSymbol.primaryConstructor
            val innerValueParamLists: List[List[Symbol]] =
              innerCtor.paramSymss.filterNot(_.headOption.exists(_.isType))
            val innerFlat: List[Symbol] = innerValueParamLists.flatten
            val innerParamTypes: List[TypeRepr] = innerFlat.map(innerTpe.memberType)

            val argTerms: List[Term] = innerParamTypes.zipWithIndex.map { (pt, i) =>
              pt.asType match
                case '[p] => '{ vs(${ Expr(i) }).asInstanceOf[p] }.asTerm
            }

            val grouped: List[List[Term]] = {
              var remaining = argTerms
              innerValueParamLists.map { pl =>
                val (take, rest) = remaining.splitAt(pl.length)
                remaining = rest
                take
              }
            }

            val ctorSelect: Term = Select(New(TypeIdent(innerTpe.typeSymbol)), innerCtor)
            val ctorTyped: Term = innerTpe match
              case AppliedType(_, targs) =>
                val targTrees = targs.map { t =>
                  t.asType match
                    case '[tt] => TypeTree.of[tt]
                }
                TypeApply(ctorSelect, targTrees)
              case _ => ctorSelect

            grouped.foldLeft(ctorTyped)((acc, argList) => Apply(acc, argList)).asExprOf[t]
          }
        }.asInstanceOf[Expr[Seq[Any] => T]]

    val closure: Expr[Seq[Any] => Any] = '{ (args: Seq[Any]) =>
      GenCombine.combineGens[T](args.asInstanceOf[Seq[Gen[?]]], $buildFn)
    }

    val entryExpr: Expr[Entry] =
      '{ Entry(${ Expr.ofList(inputTagExprs) }, $outputTagExpr, $closure) }

    val insTpe = buildTupleType(genParamTypes)
    (insTpe.asType: @unchecked) match
      case '[ins] =>
        '{ TypedEntry[ins & Tuple, Gen[T]]($entryExpr) }

  /** Value-driven: `gen(x)`. Dispatches on the inferred type:
   *
   *   - Function `(A, ...) => R`: builds an entry whose closure sequences the per-input `Gen`s and
   *     applies `f`. If `R = Gen[T]` for some `T`, the entry's output is `Gen[T]` — the closure uses
   *     `combineGensFlat` and the last step is a `flatMap` so we don't double-wrap into `Gen[Gen[T]]`.
   *   - `Gen[T]`: registered as a zero-input entry of output `Gen[T]`.
   *   - Any other value `T`: wrapped via `Gen.const` into a zero-input entry of output `Gen[T]`.
   *
   * Literal constant types (e.g. `gen(42)` ⇒ `Gen[Int]`, not `Gen[42]`) are widened to their base
   * type so the registered Gen is discoverable by its natural type.
   */
  def valueImpl[X: Type](x: Expr[X])(using Quotes): Expr[TypedEntry[? <: Tuple, ?]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[X].dealias
    val effectiveTpe = tpe match
      case ConstantType(_) => tpe.widen
      case _               => tpe
    val genSym = TypeRepr.of[Gen[Any]].typeSymbol

    effectiveTpe match
      case AppliedType(tycon, _) if isFunctionType(tycon) =>
        functionValueImpl(effectiveTpe, x)
      case AppliedType(tycon, List(inner)) if tycon.typeSymbol == genSym =>
        genPassthroughImpl(inner, x)
      case _ =>
        constLiftImpl(effectiveTpe, x)

  /** `gen(g: Gen[T])` — register the existing Gen as a zero-input entry. */
  private def genPassthroughImpl(using Quotes)(
      inner: quotes.reflect.TypeRepr,
      x: Expr[?]
  ): Expr[TypedEntry[? <: Tuple, ?]] =
    inner.asType match
      case '[t] =>
        val genExpr = x.asExprOf[Gen[t]]
        '{
          TypedEntry[EmptyTuple, Gen[t]](
            Entry(Nil, summon[Tag[Gen[t]]].tag, _ => $genExpr)
          )
        }

  /** `gen(v: T)` for non-function, non-Gen `v` — wrap in `Gen.const`. The passed `tpe` may have been
   * widened from the original argument type (e.g. literal `42` widened from `42` to `Int`); the
   * subtyping cast in `asExprOf` is safe because the widened type is always a supertype.
   */
  private def constLiftImpl(using Quotes)(
      tpe: quotes.reflect.TypeRepr,
      x: Expr[?]
  ): Expr[TypedEntry[? <: Tuple, ?]] =
    tpe.asType match
      case '[t] =>
        val xt = x.asExprOf[t]
        '{
          TypedEntry[EmptyTuple, Gen[t]](
            Entry(Nil, summon[Tag[Gen[t]]].tag, _ => Gen.const($xt))
          )
        }

  /** `gen(f)` where `f: (A, B, ...) => R`. Extracts the function's parameter types + return type,
   * wraps each in `Gen`, and emits an entry whose closure sequences the per-input `Gen`s.
   *
   * If `R = Gen[T]` for some `T`, the entry's output is `Gen[T]` (not `Gen[Gen[T]]`): the closure
   * uses `combineGensFlat` and the last step is a `flatMap` into `f(a, b, ...)`. This lets you
   * register Gen-returning helpers directly, e.g.
   * {{{
   *   def genShelleyAddress(network: Network): Gen[ShelleyAddress] = ...
   *   val registry = gen(genShelleyAddress) +: gen(network: Network)
   *   registry.make[Gen[ShelleyAddress]]
   * }}}
   *
   * Otherwise (the common case, `R` is a plain type), the closure uses `combineGens` with a `map`
   * at the end and the entry's output is `Gen[R]`.
   */
  private def functionValueImpl(using Quotes)(
      fnTpe: quotes.reflect.TypeRepr,
      f: Expr[?]
  ): Expr[TypedEntry[? <: Tuple, ?]] =
    import quotes.reflect.*

    val (paramTypes, retType) = fnTpe match
      case AppliedType(tycon, targs) if isFunctionType(tycon) =>
        (targs.init, targs.last)
      case other =>
        report.errorAndAbort(
          s"gen(f) expects a FunctionN value (lambda or eta-expanded method), got ${other.show}"
        )

    val genParamTypes: List[TypeRepr] = paramTypes.map { pt =>
      pt.asType match
        case '[p] => TypeRepr.of[Gen[p]]
    }

    val inputTagExprs: List[Expr[LightTypeTag]] = genParamTypes.map { gt =>
      gt.asType match
        case '[gp] => '{ summon[Tag[gp]].tag }
    }

    // If retType is Gen[X], the output type of the entry is Gen[X] — X is the inner payload.
    // Otherwise, the output is Gen[retType].
    val genSym = TypeRepr.of[Gen[Any]].typeSymbol
    val retIsGen: Option[TypeRepr] = retType.dealias match
      case AppliedType(tycon, List(inner)) if tycon.typeSymbol == genSym => Some(inner)
      case _                                                             => None

    val outputPayloadType: TypeRepr = retIsGen.getOrElse(retType)
    val outputTagExpr: Expr[LightTypeTag] = outputPayloadType.asType match
      case '[o] => '{ summon[Tag[Gen[o]]].tag }

    val entryTypedExpr: Expr[TypedEntry[? <: Tuple, ?]] = retType.asType match
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

        val closure: Expr[Seq[Any] => Any] = retIsGen match
          case Some(innerTpe) =>
            innerTpe.asType match
              case '[inner] =>
                // buildFn: Seq[Any] => Gen[inner]; `r` = Gen[inner] structurally
                '{ (args: Seq[Any]) =>
                  GenCombine.combineGensFlat[inner](
                    args.asInstanceOf[Seq[Gen[?]]],
                    $buildFn.asInstanceOf[Seq[Any] => Gen[inner]]
                  )
                }
          case None =>
            '{ (args: Seq[Any]) =>
              GenCombine.combineGens[r](args.asInstanceOf[Seq[Gen[?]]], $buildFn)
            }

        val entryExpr: Expr[Entry] =
          '{ Entry(${ Expr.ofList(inputTagExprs) }, $outputTagExpr, $closure) }

        val insTpe = buildTupleType(genParamTypes)
        (insTpe.asType: @unchecked) match
          case '[ins] =>
            outputPayloadType.asType match
              case '[o] =>
                '{ TypedEntry[ins & Tuple, Gen[o]]($entryExpr) }

    entryTypedExpr

  private def isFunctionType(using Quotes)(tycon: quotes.reflect.TypeRepr): Boolean =
    val name = tycon.typeSymbol.fullName
    name.startsWith("scala.Function") || name.startsWith("scala.ContextFunction")

  private def buildTupleType(using
      Quotes
  )(types: List[quotes.reflect.TypeRepr]): quotes.reflect.TypeRepr =
    import quotes.reflect.*
    types.foldRight(TypeRepr.of[EmptyTuple]) { (h, acc) =>
      ((h.asType, acc.asType): @unchecked) match
        case ('[ht], '[tt]) => TypeRepr.of[ht *: (tt & Tuple)]
    }
