package registry.scalacheck

import scala.quoted.*
import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag
import org.scalacheck.Gen
import registry.{Entry, TypedEntry}

private[scalacheck] object GenFunMacro:

  def typeImpl[T: Type](using Quotes): Expr[TypedEntry[? <: Tuple, Gen[T]]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val sym = tpe.typeSymbol
    if !sym.isClassDef then
      report.errorAndAbort(s"genFun[T] expects a class type, got ${tpe.show}")
    if sym.flags.is(Flags.Trait) then
      report.errorAndAbort(s"genFun[T] cannot instantiate trait ${tpe.show}")
    if sym.flags.is(Flags.Abstract) then
      report.errorAndAbort(s"genFun[T] cannot instantiate abstract class ${tpe.show}")
    if sym.flags.is(Flags.Module) then
      report.errorAndAbort(
        s"genFun[T] cannot register an object; use value(Gen.const(...)) instead"
      )
    val ctor = sym.primaryConstructor
    if ctor == Symbol.noSymbol then
      report.errorAndAbort(s"genFun[T]: ${tpe.show} has no primary constructor")

    val valueParamLists: List[List[Symbol]] =
      ctor.paramSymss.filterNot(_.headOption.exists(_.isType))
    val flatParams: List[Symbol]  = valueParamLists.flatten
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
    val buildFn: Expr[Seq[Any] => T] = '{ (vs: Seq[Any]) =>
      ${
        import quotes.reflect.*
        val innerTpe  = TypeRepr.of[T]
        val innerCtor = innerTpe.typeSymbol.primaryConstructor
        val innerValueParamLists: List[List[Symbol]] =
          innerCtor.paramSymss.filterNot(_.headOption.exists(_.isType))
        val innerFlat: List[Symbol]         = innerValueParamLists.flatten
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

        val ctorSelect: Term = Select(New(TypeTree.of[T]), innerCtor)
        val ctorTyped: Term = innerTpe match
          case AppliedType(_, targs) =>
            val targTrees = targs.map { t =>
              t.asType match
                case '[tt] => TypeTree.of[tt]
            }
            TypeApply(ctorSelect, targTrees)
          case _ => ctorSelect

        grouped.foldLeft(ctorTyped)((acc, argList) => Apply(acc, argList)).asExprOf[T]
      }
    }

    val closure: Expr[Seq[Any] => Any] = '{ (args: Seq[Any]) =>
      GenCombine.combineGens[T](args.asInstanceOf[Seq[Gen[?]]], $buildFn)
    }

    val entryExpr: Expr[Entry] =
      '{ Entry(${ Expr.ofList(inputTagExprs) }, $outputTagExpr, $closure) }

    val insTpe = buildTupleType(genParamTypes)
    ((insTpe.asType): @unchecked) match
      case '[ins] =>
        '{ TypedEntry[ins & Tuple, Gen[T]]($entryExpr) }

  private def buildTupleType(using
      Quotes
  )(types: List[quotes.reflect.TypeRepr]): quotes.reflect.TypeRepr =
    import quotes.reflect.*
    types.foldRight(TypeRepr.of[EmptyTuple]) { (h, acc) =>
      ((h.asType, acc.asType): @unchecked) match
        case ('[ht], '[tt]) => TypeRepr.of[ht *: (tt & Tuple)]
    }
