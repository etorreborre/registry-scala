package registry

import scala.quoted.*
import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag

private[registry] object FunMacros:

  def funValueImpl[T: Type](f: Expr[T])(using Quotes): Expr[Entry] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T].dealias
    val (paramTypes, retType) = tpe match
      case AppliedType(tycon, targs) if isFunctionType(tycon) =>
        (targs.init, targs.last)
      case other =>
        report.errorAndAbort(
          s"fun(...) expects a FunctionN value (lambda or eta-expanded method), got ${other.show}"
        )

    val inputTagExprs: List[Expr[LightTypeTag]] = paramTypes.map { pt =>
      pt.asType match
        case '[p] => '{ summon[Tag[p]].tag }
    }
    val outputTagExpr: Expr[LightTypeTag] = retType.asType match
      case '[r] => '{ summon[Tag[r]].tag }

    val closure: Expr[Seq[Any] => Any] = '{ (args: Seq[Any]) =>
      ${
        import quotes.reflect.*
        val innerParams = TypeRepr.of[T].dealias match
          case AppliedType(_, targs) => targs.init
          case _                     => Nil
        val argTerms: List[Term] = innerParams.zipWithIndex.map { (pt, i) =>
          pt.asType match
            case '[p] => '{ args(${ Expr(i) }).asInstanceOf[p] }.asTerm
        }
        Select.unique(f.asTerm, "apply").appliedToArgs(argTerms).asExprOf[Any]
      }
    }

    '{
      Entry(
        ${ Expr.ofList(inputTagExprs) },
        $outputTagExpr,
        $closure
      )
    }

  def funTypeImpl[T: Type](using Quotes): Expr[Entry] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val sym = tpe.typeSymbol
    if !sym.isClassDef then
      report.errorAndAbort(s"fun[T] expects a class type, got ${tpe.show}")
    if sym.flags.is(Flags.Trait) then
      report.errorAndAbort(s"fun[T] cannot instantiate trait ${tpe.show}")
    if sym.flags.is(Flags.Abstract) then
      report.errorAndAbort(s"fun[T] cannot instantiate abstract class ${tpe.show}")
    if sym.flags.is(Flags.Module) then
      report.errorAndAbort(s"fun[T] cannot register an object; use value(${tpe.show}) instead")
    val ctor = sym.primaryConstructor
    if ctor == Symbol.noSymbol then
      report.errorAndAbort(s"fun[T]: ${tpe.show} has no primary constructor")

    val valueParamLists: List[List[Symbol]] =
      ctor.paramSymss.filterNot(_.headOption.exists(_.isType))
    val flatParams: List[Symbol] = valueParamLists.flatten
    val paramTypes: List[TypeRepr] = flatParams.map(tpe.memberType)

    val inputTagExprs: List[Expr[LightTypeTag]] = paramTypes.map { pt =>
      pt.asType match
        case '[p] => '{ summon[Tag[p]].tag }
    }
    val outputTagExpr: Expr[LightTypeTag] = '{ summon[Tag[T]].tag }

    val closure: Expr[Seq[Any] => Any] = '{ (args: Seq[Any]) =>
      ${
        import quotes.reflect.*
        val innerTpe  = TypeRepr.of[T]
        val innerSym  = innerTpe.typeSymbol
        val innerCtor = innerSym.primaryConstructor
        val innerValueParamLists: List[List[Symbol]] =
          innerCtor.paramSymss.filterNot(_.headOption.exists(_.isType))
        val innerFlat: List[Symbol]  = innerValueParamLists.flatten
        val innerParamTypes: List[TypeRepr] = innerFlat.map(innerTpe.memberType)

        val argTerms: List[Term] = innerParamTypes.zipWithIndex.map { (pt, i) =>
          pt.asType match
            case '[p] => '{ args(${ Expr(i) }).asInstanceOf[p] }.asTerm
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

        grouped.foldLeft(ctorTyped)((acc, argList) => Apply(acc, argList)).asExprOf[Any]
      }
    }

    '{
      Entry(
        ${ Expr.ofList(inputTagExprs) },
        $outputTagExpr,
        $closure
      )
    }

  private def isFunctionType(using Quotes)(tycon: quotes.reflect.TypeRepr): Boolean =
    val name = tycon.typeSymbol.fullName
    name.startsWith("scala.Function") || name.startsWith("scala.ContextFunction")
