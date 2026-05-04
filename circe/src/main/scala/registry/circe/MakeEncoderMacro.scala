package registry.circe

import io.circe.Json
import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag
import registry.{Entry, TypedEntry}
import scala.quoted.*

/**
 * Generate an `Encoder[T]` for a case class, a sealed trait, or a Scala 3 enum.
 *
 *   `makeEncoder[Person]` expands to an entry declaring inputs
 *   `(JsonOptions, ConstructorEncoder, Encoder[F1], Encoder[F2], …)` where `F1, F2, …` are the unique
 *   field types across all constructors of `T`. At runtime, the entry's closure matches on the runtime
 *   value and packs a [[FromConstructor]] that the [[ConstructorEncoder]] turns into `Json`.
 *
 *   For sum types (sealed traits / enums), one pattern-match branch is generated per child constructor.
 */
transparent inline def makeEncoder[T]: TypedEntry[? <: Tuple, Encoder[T]] =
  ${ MakeEncoderMacro.implDropQualifier[T] }

/** Same as [[makeEncoder]] but keep the fully-qualified type name in `fromConstructorTypes`. */
transparent inline def makeEncoderQualified[T]: TypedEntry[? <: Tuple, Encoder[T]] =
  ${ MakeEncoderMacro.implFullQualified[T] }

/** Same as [[makeEncoder]] but keep only the last package segment in the type name. */
transparent inline def makeEncoderQualifiedLast[T]: TypedEntry[? <: Tuple, Encoder[T]] =
  ${ MakeEncoderMacro.implLastQualifier[T] }

private[circe] object MakeEncoderMacro:

  private inline val DropQualifier = "drop"
  private inline val FullQualified = "full"
  private inline val LastQualifier = "last"

  def implDropQualifier[T: Type](using Quotes): Expr[TypedEntry[? <: Tuple, Encoder[T]]] = impl[T](DropQualifier)
  def implFullQualified[T: Type](using Quotes): Expr[TypedEntry[? <: Tuple, Encoder[T]]] = impl[T](FullQualified)
  def implLastQualifier[T: Type](using Quotes): Expr[TypedEntry[? <: Tuple, Encoder[T]]] = impl[T](LastQualifier)

  def impl[T: Type](mode: String)(using q: Quotes): Expr[TypedEntry[? <: Tuple, Encoder[T]]] =
    import q.reflect.*

    val tpe = TypeRepr.of[T]
    val sym = tpe.typeSymbol

    val constructors: List[Symbol] = discoverConstructors(sym)
    if constructors.isEmpty then
      report.errorAndAbort(
        s"makeEncoder: ${tpe.show} has no constructors to encode (not a case class, sealed hierarchy, or enum)"
      )

    // Extract per-constructor data as plain Scala values (no path-dependent types).
    case class CtorData(
        ctorSym: Symbol,
        displayName: String,
        isSingleton: Boolean,
        fieldNames: List[String],
        fieldTypes: List[TypeRepr]
    )

    def mkCtorData(childSym: Symbol): CtorData =
      val displayName = applyQualifierMode(childSym.fullName, mode)
      val isModule = childSym.flags.is(Flags.Module) || childSym.isTerm
      if isModule then
        CtorData(childSym, displayName, true, Nil, Nil)
      else
        val ctor = childSym.primaryConstructor
        if ctor == Symbol.noSymbol then
          CtorData(childSym, displayName, true, Nil, Nil)
        else
          val valueParamLists: List[List[Symbol]] = ctor.paramSymss.filterNot(_.headOption.exists(_.isType))
          val flat: List[Symbol] = valueParamLists.flatten
          val childTpe: TypeRepr =
            tpe match
              case AppliedType(_, args) =>
                val childClassSym = childSym.typeRef.typeSymbol
                val childTypeParams = childClassSym.typeRef.widen match
                  case AppliedType(_, tps) => tps
                  case _                   => Nil
                if childTypeParams.size == args.size then AppliedType(childSym.typeRef, args)
                else childSym.typeRef
              case _ => childSym.typeRef
          val fieldNames = flat.map(_.name)
          val fieldTypes = flat.map(childTpe.memberType)
          CtorData(childSym, displayName, flat.isEmpty, fieldNames, fieldTypes)

    val ctorData: List[CtorData] = constructors.map(mkCtorData)

    // Deduplicate all field types across all constructors for the Encoder[...] input list.
    val allFieldTypes: List[TypeRepr] = ctorData.flatMap(_.fieldTypes).foldLeft(List.empty[TypeRepr]): (acc, t) =>
      if acc.exists(_ =:= t) then acc else acc :+ t

    val encoderParamTypes: List[TypeRepr] = allFieldTypes.map: pt =>
      pt.asType match
        case '[p] => TypeRepr.of[Encoder[p]]

    val prefixInputTypes: List[TypeRepr] = List(TypeRepr.of[JsonOptions], TypeRepr.of[ConstructorEncoder])
    val allInputTypes: List[TypeRepr] = prefixInputTypes ++ encoderParamTypes

    val inputTagExprs: List[Expr[LightTypeTag]] = allInputTypes.map: t =>
      t.asType match
        case '[p] => '{ summon[Tag[p]].tag }

    val outputTagExpr: Expr[LightTypeTag] = '{ summon[Tag[Encoder[T]]].tag }

    // Precompute String metadata.
    val constructorNamesExpr: Expr[List[String]] = Expr(ctorData.map(_.displayName))
    val constructorTypesListExpr: Expr[List[String]] = Expr(allFieldTypes.map(t => typeDisplayName(t, mode)))

    // Build the match expression as a function Expr.
    def buildMatch(value: Expr[T], encoders: Expr[Seq[Encoder[Any]]]): Expr[FromConstructor] =
      val cases: List[CaseDef] = ctorData.map: c =>
        val displayNameExpr: Expr[String] = Expr(c.displayName)

        if c.isSingleton then
          val pat: Tree =
            if c.ctorSym.flags.is(Flags.Module) then Ref(c.ctorSym.companionModule)
            else Ref(c.ctorSym)
          CaseDef(
            pat,
            None,
            '{
              FromConstructor(
                $constructorNamesExpr,
                $constructorTypesListExpr,
                $displayNameExpr,
                Nil,
                Nil
              )
            }.asTerm
          )
        else
          val childType: TypeRepr = c.ctorSym.typeRef
          val bindSym: Symbol = Symbol.newBind(Symbol.spliceOwner, "a", Flags.EmptyFlags, childType)
          val bindRef: Term = Ref(bindSym)
          val typedPat: Tree = Typed(Wildcard(), TypeIdent(c.ctorSym))
          val pat: Tree = Bind(bindSym, typedPat)

          val fieldNamesExpr: Expr[List[String]] = Expr(c.fieldNames)

          val valueExprs: List[Expr[Json]] = c.fieldNames.zip(c.fieldTypes).map { (fn, ft) =>
            val idx = allFieldTypes.indexWhere(_ =:= ft)
            val encIdxExpr = Expr(idx)
            val selected: Term = Select.unique(bindRef, fn)
            val fieldExpr: Expr[Any] = selected.asExprOf[Any]
            '{ ${ encoders }.apply(${ encIdxExpr }).encode(${ fieldExpr }) }
          }

          val valueListExpr: Expr[List[Json]] = Expr.ofList(valueExprs)

          val resultExpr: Expr[FromConstructor] = '{
            FromConstructor(
              $constructorNamesExpr,
              $constructorTypesListExpr,
              $displayNameExpr,
              $fieldNamesExpr,
              $valueListExpr
            )
          }
          CaseDef(pat, None, resultExpr.asTerm)

      Match(value.asTerm, cases).asExprOf[FromConstructor]

    val closure: Expr[Seq[Any] => Any] = '{ (args: Seq[Any]) =>
      val opts = args(0).asInstanceOf[JsonOptions]
      val ce = args(1).asInstanceOf[ConstructorEncoder]
      val encoders: Seq[Encoder[Any]] = args.drop(2).asInstanceOf[Seq[Encoder[Any]]]

      Encoder[T]: (value: T) =>
        val fc: FromConstructor = ${ buildMatch('value, 'encoders) }
        ce.encodeConstructor(opts, fc)
    }

    val entryExpr: Expr[Entry] =
      '{ Entry(${ Expr.ofList(inputTagExprs) }, $outputTagExpr, $closure) }

    val insTpe = buildTupleType(allInputTypes)
    insTpe.asType match
      case '[ins] =>
        '{ TypedEntry[ins & Tuple, Encoder[T]]($entryExpr) }

  // -----------------------------------------------------------------------------

  private def discoverConstructors(using q: Quotes)(sym: q.reflect.Symbol): List[q.reflect.Symbol] =
    import q.reflect.*
    val children = sym.children
    if children.nonEmpty then children
    else if sym.isClassDef && !sym.flags.is(Flags.Abstract) && !sym.flags.is(Flags.Trait) then List(sym)
    else Nil

  private def typeDisplayName(using q: Quotes)(tpe: q.reflect.TypeRepr, mode: String): String =
    import q.reflect.*
    tpe.dealias match
      case AppliedType(tycon, args) =>
        val head = applyQualifierMode(tycon.typeSymbol.fullName, mode)
        val tail = args.map(a => applyQualifierMode(a.typeSymbol.fullName, mode)).mkString(" ")
        if args.isEmpty then head else s"$head $tail"
      case other => applyQualifierMode(other.typeSymbol.fullName, mode)

  private def applyQualifierMode(fq: String, mode: String): String =
    val cleaned = if fq.endsWith("$") then fq.dropRight(1) else fq
    mode match
      case "drop" => cleaned.split('.').last
      case "full" => cleaned
      case "last" =>
        val parts = cleaned.split('.')
        if parts.length >= 2 then parts.takeRight(2).mkString(".") else cleaned
      case _ => cleaned

  private def buildTupleType(using q: Quotes)(types: List[q.reflect.TypeRepr]): q.reflect.TypeRepr =
    import q.reflect.*
    types.foldRight(TypeRepr.of[EmptyTuple]): (h, acc) =>
      ((h.asType, acc.asType): @unchecked) match
        case ('[ht], '[tt]) => TypeRepr.of[ht *: (tt & Tuple)]
