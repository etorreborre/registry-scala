package registry.scalacheck

import scala.quoted.*
import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag
import org.scalacheck.Gen
import registry.{Entry, Registry, TypeRendering}

/** Builds a fully type-tracked `Registry` for a sealed `T`.
  *
  * Output side: every `Gen[V_i]` for each variant plus `Gen[T]` itself plus `Chooser` appears in
  * the registry's `AllOuts`. Input side: every variant constructor's `Gen[FieldType]` appears in
  * `AllIns`, deduplicated; the per-variant entries' "internal" needs (Chooser, Gen[V_i] consumed
  * by the genTrait combinator) are filtered out so they don't show up as external requirements.
  *
  * Singletons (case objects, no-arg enum cases) are detected via `summon[ValueOf[V]]` and registered
  * with no inputs.
  */
private[scalacheck] object GenSumMacro:

  def impl[T: Type](using Quotes): Expr[Registry[? <: Tuple, ? <: Tuple]] =
    import quotes.reflect.*

    val tTpe = TypeRepr.of[T]
    val tSym = tTpe.typeSymbol
    val childSyms = tSym.children
    if childSyms.isEmpty then
      report.errorAndAbort(
        s"genSum[T] expects a sealed trait / sealed abstract class / Scala 3 enum with subtypes; got ${tTpe.show}"
      )

    val childTpes: List[TypeRepr] = childSyms.map(mkChildTpe(tTpe, _))

    case class VariantData(
        childTpe: TypeRepr,
        inputTpes: List[TypeRepr],
        // Some(...) when this variant has its own internal entry (case class / case object /
        // enum singleton). None when the variant is itself a sealed (sub-)trait — its `Gen[…]`
        // is consumed by `genTrait[T]` but expected to come from the surrounding registry
        // (typically a sibling `genSum[V]`).
        entryExpr: Option[Expr[Entry]]
    )

    val variants: List[VariantData] = childTpes.map { ct =>
      ct.asType match
        case '[c] =>
          // Order matters: check `ValueOf` FIRST so Scala 3 enum no-arg cases (whose `typeSymbol`
          // is the enum's class — and thus has children of its own) are still detected as
          // singletons. Only fall through to the sub-sum check when there's no `ValueOf`.
          Expr.summon[ValueOf[c]] match
            case Some(valueOfExpr) =>
              VariantData(ct, Nil, Some(buildSingletonEntry[c](valueOfExpr)))
            case None if ct.typeSymbol.children.nonEmpty =>
              // Sub-sealed trait / abstract class: leave Gen[V] for the surrounding registry
              // (typically a sibling `genSum[V]`).
              VariantData(ct, Nil, None)
            case None =>
              val (genFieldTpes, expr) = buildParametrizedEntry[c](ct)
              VariantData(ct, genFieldTpes, Some(expr))
    }

    val chooserTpe   = TypeRepr.of[Chooser]
    val childGenTpes = childTpes.map(genOf)
    val tGenTpe      = genOf(tTpe)

    // genTrait[T] consumes Chooser + Gen[V_i] for EVERY child (including sub-sum traits),
    // so they all end up in genTrait's input list.
    val genTraitInputs = chooserTpe :: childGenTpes
    val genTraitEntry  = buildGenTraitEntry(tTpe, childTpes)
    val chooserEntry   = buildChooserEntry()

    // Outputs the registry actually produces internally:
    //   - Gen[T] (genTrait), Chooser, plus Gen[V_i] for variants we built an entry for.
    //   - sub-sum-trait variants are NOT in outputs; they must come from the outer registry.
    val producedVariantGens = variants.collect { case v if v.entryExpr.isDefined => genOf(v.childTpe) }
    val outsTpes: List[TypeRepr] = tGenTpe :: producedVariantGens ::: List(chooserTpe)

    // External Ins = union of all entries' inputs, deduped, minus what we produce internally.
    // Note: for sub-sum-trait children, Gen[V_i] is in `genTraitInputs` but NOT in `outsTpes`,
    // so the subtraction correctly leaves Gen[V_i] as an external requirement.
    val allInputTpes: List[TypeRepr] = genTraitInputs ::: variants.flatMap(_.inputTpes)
    val internalReprs: Set[String]   = outsTpes.map(reprKey).toSet
    val externalIns: List[TypeRepr] =
      TypeRendering.dedupe(allInputTpes).filterNot(t => internalReprs.contains(reprKey(t)))

    val insTuple  = buildTupleType(externalIns)
    val outsTuple = buildTupleType(TypeRendering.dedupe(outsTpes))

    val allEntries: List[Expr[Entry]] =
      genTraitEntry :: variants.flatMap(_.entryExpr) ::: List(chooserEntry)
    val entriesExpr: Expr[List[Entry]] = Expr.ofList(allEntries)

    ((insTuple.asType, outsTuple.asType): @unchecked) match
      case ('[ins], '[outs]) =>
        '{ Registry[ins & Tuple, outs & Tuple]($entriesExpr, Nil, Nil) }

  // ---- helpers -----------------------------------------------------------------------------------

  /** Resolve the type to use for `childSym`:
    *   - if the child is a term symbol (e.g. a Scala 3 enum no-arg case), the singleton type
    *     `childSym.termRef` so `ValueOf[…]` auto-derives;
    *   - if the child is a `case object` (class with `Module` flag), its companion module's
    *     singleton type;
    *   - otherwise the class's type, reapplying the parent type's args when arities match
    *     (mirrors `MakeDecoderMacro`'s logic for Aux-style sealed hierarchies).
    */
  private def mkChildTpe(using q: Quotes)(
      parentTpe: q.reflect.TypeRepr,
      childSym: q.reflect.Symbol
  ): q.reflect.TypeRepr =
    import q.reflect.*
    if childSym.isTerm then childSym.termRef
    else if childSym.flags.is(Flags.Module) then childSym.companionModule.termRef
    else
      parentTpe match
        case AppliedType(_, args) =>
          val childClassSym = childSym.typeRef.typeSymbol
          val childTypeParams = childClassSym.typeRef.widen match
            case AppliedType(_, tps) => tps
            case _                   => Nil
          if childTypeParams.size == args.size then AppliedType(childSym.typeRef, args)
          else childSym.typeRef
        case _ => childSym.typeRef

  private def reprKey(using q: Quotes)(t: q.reflect.TypeRepr): String =
    import q.reflect.*
    t.show(using Printer.TypeReprCode)

  private def genOf(using q: Quotes)(t: q.reflect.TypeRepr): q.reflect.TypeRepr =
    import q.reflect.*
    t.asType match
      case '[x] => TypeRepr.of[Gen[x]]

  private def buildTupleType(using
      q: Quotes
  )(types: List[q.reflect.TypeRepr]): q.reflect.TypeRepr =
    import q.reflect.*
    types.foldRight(TypeRepr.of[EmptyTuple]) { (h, acc) =>
      ((h.asType, acc.asType): @unchecked) match
        case ('[ht], '[tt]) => TypeRepr.of[ht *: (tt & Tuple)]
    }

  private def buildSingletonEntry[V: Type](valueOf: Expr[ValueOf[V]])(using Quotes): Expr[Entry] =
    '{
      Entry(
        inputs = Nil,
        output = summon[Tag[Gen[V]]].tag,
        invoke = _ => Gen.const($valueOf.value)
      )
    }

  private def buildChooserEntry()(using Quotes): Expr[Entry] =
    '{
      Entry(
        inputs = Nil,
        output = summon[Tag[Chooser]].tag,
        invoke = _ => Chooser.uniform
      )
    }

  private def buildGenTraitEntry(using
      q: Quotes
  )(tTpe: q.reflect.TypeRepr, childTpes: List[q.reflect.TypeRepr]): Expr[Entry] =
    import q.reflect.*
    val variantTagExprs: List[Expr[LightTypeTag]] = childTpes.map { ct =>
      ct.asType match
        case '[c] => '{ summon[Tag[Gen[c]]].tag }
    }
    tTpe.asType match
      case '[t] =>
        '{
          Entry(
            inputs = summon[Tag[Chooser]].tag :: ${ Expr.ofList(variantTagExprs) },
            output = summon[Tag[Gen[t]]].tag,
            invoke = args =>
              val chooser = args.head.asInstanceOf[Chooser]
              val gens    = args.tail.map(_.asInstanceOf[Gen[t]])
              chooser.pickOne(gens)
          )
        }

  /** Build a `Gen[V]` entry by inspecting V's primary constructor. Returns
    * (input `Gen[FieldType]` reprs, the entry expression).
    */
  private def buildParametrizedEntry[V: Type](using q: Quotes)(
      childTpe: q.reflect.TypeRepr
  ): (List[q.reflect.TypeRepr], Expr[Entry]) =
    import q.reflect.*

    val sym = childTpe.typeSymbol
    val ctor = sym.primaryConstructor
    val valueParamLists: List[List[Symbol]] =
      ctor.paramSymss.filterNot(_.headOption.exists(_.isType))
    val flatParams: List[Symbol] = valueParamLists.flatten
    val paramTypes: List[TypeRepr] = flatParams.map(childTpe.memberType)

    val genParamTypes: List[TypeRepr] = paramTypes.map { pt =>
      pt.asType match
        case '[p] => TypeRepr.of[Gen[p]]
    }

    val inputTagExprs: List[Expr[LightTypeTag]] = genParamTypes.map { gt =>
      gt.asType match
        case '[gp] => '{ summon[Tag[gp]].tag }
    }
    val outputTagExpr: Expr[LightTypeTag] = '{ summon[Tag[Gen[V]]].tag }

    val buildFn: Expr[Seq[Any] => V] = '{ (vs: Seq[Any]) =>
      ${
        val innerTpe = TypeRepr.of[V]
        val innerSym = innerTpe.typeSymbol
        val innerCtor = innerSym.primaryConstructor
        val innerValueParamLists: List[List[Symbol]] =
          innerCtor.paramSymss.filterNot(_.headOption.exists(_.isType))
        val innerFlat: List[Symbol] = innerValueParamLists.flatten
        val innerParamTypes: List[TypeRepr] = innerFlat.map(innerTpe.memberType)

        val argTerms: List[Term] = innerParamTypes.zipWithIndex.map { (pt, i) =>
          pt.asType match
            case '[p] => '{ vs(${ Expr(i) }).asInstanceOf[p] }.asTerm
        }

        val grouped: List[List[Term]] =
          var remaining = argTerms
          innerValueParamLists.map { pl =>
            val (take, rest) = remaining.splitAt(pl.length)
            remaining = rest
            take
          }

        val ctorSelect: Term = Select(New(TypeTree.of[V]), innerCtor)
        val ctorTyped: Term = innerTpe match
          case AppliedType(_, targs) =>
            val targTrees = targs.map { tg =>
              tg.asType match
                case '[tt] => TypeTree.of[tt]
            }
            TypeApply(ctorSelect, targTrees)
          case _ => ctorSelect

        grouped.foldLeft(ctorTyped)((acc, args) => Apply(acc, args)).asExprOf[V]
      }
    }

    val closure: Expr[Seq[Any] => Any] = '{ (args: Seq[Any]) =>
      GenCombine.combineGens[V](args.asInstanceOf[Seq[Gen[?]]], $buildFn)
    }

    val entryExpr: Expr[Entry] = '{
      Entry(${ Expr.ofList(inputTagExprs) }, $outputTagExpr, $closure)
    }

    (genParamTypes, entryExpr)
