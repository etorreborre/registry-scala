package registry.cats

import scala.quoted.*
import _root_.cats.Applicative
import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag
import registry.{Entry, TypedEntry}

private[cats] object FunToMacro:

  def typeImpl[F[_]: Type, T: Type](app: Expr[Applicative[F]])(using Quotes): Expr[TypedEntry[? <: Tuple, F[T]]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val sym = tpe.typeSymbol
    if !sym.isClassDef then
      report.errorAndAbort(s"funTo[F, T] expects a class type, got ${tpe.show}")
    if sym.flags.is(Flags.Trait) then
      report.errorAndAbort(s"funTo[F, T] cannot instantiate trait ${tpe.show}")
    if sym.flags.is(Flags.Abstract) then
      report.errorAndAbort(s"funTo[F, T] cannot instantiate abstract class ${tpe.show}")
    if sym.flags.is(Flags.Module) then
      report.errorAndAbort(
        s"funTo[F, T] cannot register an object; use valTo[F, ${tpe.show}](...) instead"
      )
    val ctor = sym.primaryConstructor
    if ctor == Symbol.noSymbol then
      report.errorAndAbort(s"funTo[F, T]: ${tpe.show} has no primary constructor")

    val valueParamLists: List[List[Symbol]] =
      ctor.paramSymss.filterNot(_.headOption.exists(_.isType))
    val flatParams: List[Symbol]   = valueParamLists.flatten
    val paramTypes: List[TypeRepr] = flatParams.map(tpe.memberType)

    // Wrap each input type in F[_] — the entry's declared inputs.
    val effectParamTypes: List[TypeRepr] = paramTypes.map { pt =>
      pt.asType match
        case '[p] => TypeRepr.of[F[p]]
    }

    val inputTagExprs: List[Expr[LightTypeTag]] = effectParamTypes.map { gt =>
      gt.asType match
        case '[gp] => '{ summon[Tag[gp]].tag }
    }
    val outputTagExpr: Expr[LightTypeTag] = '{ summon[Tag[F[T]]].tag }

    // Build the `build: Seq[Any] => T` function that invokes the primary constructor.
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
      Combine.combineF[F, T](args, $buildFn)(using $app)
    }

    val entryExpr: Expr[Entry] =
      '{ Entry(${ Expr.ofList(inputTagExprs) }, $outputTagExpr, $closure) }

    val insTpe = buildTupleType(effectParamTypes)
    ((insTpe.asType): @unchecked) match
      case '[ins] =>
        '{ TypedEntry[ins & Tuple, F[T]]($entryExpr) }

  private def buildTupleType(using
      Quotes
  )(types: List[quotes.reflect.TypeRepr]): quotes.reflect.TypeRepr =
    import quotes.reflect.*
    types.foldRight(TypeRepr.of[EmptyTuple]) { (h, acc) =>
      ((h.asType, acc.asType): @unchecked) match
        case ('[ht], '[tt]) => TypeRepr.of[ht *: (tt & Tuple)]
    }
