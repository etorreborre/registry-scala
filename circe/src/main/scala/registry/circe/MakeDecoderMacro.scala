package registry.circe

import io.circe.Json
import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag
import registry.{Entry, TypedEntry}
import scala.quoted.*

/**
 * Generate a `Decoder[T]` for a case class, a sealed trait, or a Scala 3 enum.
 *
 *   `makeDecoder[Person]` expands to an entry declaring inputs
 *   `(JsonOptions, ConstructorsDecoder, Decoder[F1], Decoder[F2], …)` where `F1, F2, …` are the unique
 *   field types across all constructors of `T`. At runtime, the entry's closure builds a list of
 *   [[ConstructorDef]]s, calls [[decodeFromDefinitions]] to pick the right constructor and decode each
 *   field, and applies the constructor to the decoded values.
 */
transparent inline def makeDecoder[T]: TypedEntry[? <: Tuple, Decoder[T]] =
  ${ MakeDecoderMacro.implDropQualifier[T] }

/** Same as [[makeDecoder]] but keep the fully-qualified type name in `fieldTypes`. */
transparent inline def makeDecoderQualified[T]: TypedEntry[? <: Tuple, Decoder[T]] =
  ${ MakeDecoderMacro.implFullQualified[T] }

/** Same as [[makeDecoder]] but keep only the last package segment in the type name. */
transparent inline def makeDecoderQualifiedLast[T]: TypedEntry[? <: Tuple, Decoder[T]] =
  ${ MakeDecoderMacro.implLastQualifier[T] }

private[circe] object MakeDecoderMacro:

  private inline val DropQualifier = "drop"
  private inline val FullQualified = "full"
  private inline val LastQualifier = "last"

  def implDropQualifier[T: Type](using Quotes): Expr[TypedEntry[? <: Tuple, Decoder[T]]] = impl[T](DropQualifier)
  def implFullQualified[T: Type](using Quotes): Expr[TypedEntry[? <: Tuple, Decoder[T]]] = impl[T](FullQualified)
  def implLastQualifier[T: Type](using Quotes): Expr[TypedEntry[? <: Tuple, Decoder[T]]] = impl[T](LastQualifier)

  def impl[T: Type](mode: String)(using q: Quotes): Expr[TypedEntry[? <: Tuple, Decoder[T]]] =
    import q.reflect.*

    val tpe = TypeRepr.of[T]
    val sym = tpe.typeSymbol

    val constructors: List[Symbol] = discoverConstructors(sym)
    if constructors.isEmpty then
      report.errorAndAbort(s"makeDecoder: ${tpe.show} has no constructors to decode (not a case class, sealed hierarchy, or enum)")

    // ----- Per-constructor data (kept local: referencing q.reflect types) -----

    final case class CtorData(
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

    // Deduplicate all field types across all constructors for the Decoder[...] input list.
    val allFieldTypes: List[TypeRepr] = ctorData.flatMap(_.fieldTypes).foldLeft(List.empty[TypeRepr]): (acc, t) =>
      if acc.exists(_ =:= t) then acc else acc :+ t

    val decoderParamTypes: List[TypeRepr] = allFieldTypes.map: pt =>
      pt.asType match
        case '[p] => TypeRepr.of[Decoder[p]]

    val prefixInputTypes: List[TypeRepr] = List(TypeRepr.of[JsonOptions], TypeRepr.of[ConstructorsDecoder])
    val allInputTypes: List[TypeRepr] = prefixInputTypes ++ decoderParamTypes

    val inputTagExprs: List[Expr[LightTypeTag]] = allInputTypes.map: t =>
      t.asType match
        case '[p] => '{ summon[Tag[p]].tag }

    val outputTagExpr: Expr[LightTypeTag] = '{ summon[Tag[Decoder[T]]].tag }

    val typeDisplayNameStr: String = applyQualifierMode(sym.fullName, mode)
    val typeDisplayNameExpr: Expr[String] = Expr(typeDisplayNameStr)

    val constructorDefsExpr: Expr[List[ConstructorDef]] = {
      val pairs: List[Expr[ConstructorDef]] = ctorData.map: c =>
        val nameExpr = Expr(c.displayName)
        val fieldNamesExpr = Expr(c.fieldNames)
        val fieldTypesExpr = Expr(c.fieldTypes.map(t => typeDisplayName(t, mode)))
        '{ ConstructorDef($nameExpr, $fieldNamesExpr, $fieldTypesExpr) }
      Expr.ofList(pairs)
    }

    // ----- Build a (ToConstructor => Either[String, T]) function by matching on
    //       tc.constructorName (a plain String) and applying the appropriate constructor.

    def buildOneCtorApplication(
        c: CtorData,
        tcValuesExpr: Expr[List[(Option[FieldDef], Json)]],
        decoders: Expr[Seq[Decoder[Any]]]
    ): Expr[Either[String, T]] =
      if c.isSingleton then
        val ctorExpr: Expr[T] =
          if c.ctorSym.flags.is(Flags.Module) then Ref(c.ctorSym.companionModule).asExprOf[T]
          else Ref(c.ctorSym).asExprOf[T]
        '{ Right($ctorExpr) }
      else
        // Build nested flatMap: decodeFieldValue(d_i, typeName, ctorName, vs(i)).flatMap(v_i => ...)
        val ctorNameExpr: Expr[String] = Expr(c.displayName)

        def nest(i: Int, acc: List[Term]): Expr[Either[String, T]] =
          if i == c.fieldNames.length then
            // Apply the constructor.
            val ctorSel: Term = Select(New(TypeIdent(c.ctorSym)), c.ctorSym.primaryConstructor)
            val ctorTyped: Term =
              tpe match
                case AppliedType(_, targs) =>
                  val targTrees = targs.map(t => TypeTree.of(using t.asType))
                  TypeApply(ctorSel, targTrees)
                case _ => ctorSel
            val applied = Apply(ctorTyped, acc)
            applied.asExprOf[T] match
              case e => '{ Right($e): Either[String, T] }
          else
            val ft = c.fieldTypes(i)
            val idx = allFieldTypes.indexWhere(_ =:= ft)
            val idxExpr = Expr(idx)
            val iExpr = Expr(i)
            ft.asType match
              case '[p] =>
                val fieldEither: Expr[Either[String, p]] = '{
                  decodeFieldValue[p](
                    $decoders.apply($idxExpr).asInstanceOf[Decoder[p]],
                    $typeDisplayNameExpr,
                    $ctorNameExpr,
                    $tcValuesExpr.apply($iExpr)
                  )
                }
                val funcExpr: Expr[p => Either[String, T]] = buildLambda[p, Either[String, T]]: vRef =>
                  nest(i + 1, acc :+ vRef)
                '{ $fieldEither.flatMap($funcExpr) }

        nest(0, Nil)

    // Helper to build a lambda Expr[A => B] from a function that turns a bound var reference into Expr[B].
    def buildLambda[A: Type, B: Type](body: Term => Expr[B]): Expr[A => B] =
      Lambda(
        Symbol.spliceOwner,
        MethodType(List("v"))(_ => List(TypeRepr.of[A]), _ => TypeRepr.of[B]),
        (meth, params) => body(params.head.asInstanceOf[Term]).asTerm.changeOwner(meth)
      ).asExprOf[A => B]

    // Build: (tc: ToConstructor) => tc.constructorName match { case "A" => ...; case _ => Left(...) }
    def buildBuildFn(decoders: Expr[Seq[Decoder[Any]]]): Expr[ToConstructor => Either[String, T]] =
      buildLambda[ToConstructor, Either[String, T]]: tcRef =>
        val tcExpr: Expr[ToConstructor] = tcRef.asExprOf[ToConstructor]
        val nameExpr: Expr[String] = '{ $tcExpr.constructorName }
        val valuesExpr: Expr[List[(Option[FieldDef], Json)]] = '{ $tcExpr.values }

        val cases: List[CaseDef] = ctorData.map: c =>
          val lit = Literal(StringConstant(c.displayName))
          val body = buildOneCtorApplication(c, valuesExpr, decoders)
          CaseDef(lit, None, body.asTerm)

        val defaultCase = CaseDef(
          Wildcard(),
          None,
          '{
            Left(s"cannot use this constructor to create an instance of type '${${ typeDisplayNameExpr }}': ${${ tcExpr }}"): Either[String, T]
          }.asTerm
        )

        Match(nameExpr.asTerm, cases :+ defaultCase).asExprOf[Either[String, T]]

    // ----- Assemble the closure -----

    val closure: Expr[Seq[Any] => Any] = '{ (args: Seq[Any]) =>
      val opts = args(0).asInstanceOf[JsonOptions]
      val cd = args(1).asInstanceOf[ConstructorsDecoder]
      val decoders: Seq[Decoder[Any]] = args.drop(2).asInstanceOf[Seq[Decoder[Any]]]
      val defs = $constructorDefsExpr
      val buildFn: ToConstructor => Either[String, T] = ${ buildBuildFn('decoders) }
      Decoder[T]: (value: Json) =>
        decodeFromDefinitions[T](opts, cd, defs, value, buildFn)
    }

    val entryExpr: Expr[Entry] =
      '{ Entry(${ Expr.ofList(inputTagExprs) }, $outputTagExpr, $closure) }

    val insTpe = buildTupleType(allInputTypes)
    insTpe.asType match
      case '[ins] =>
        '{ TypedEntry[ins & Tuple, Decoder[T]]($entryExpr) }

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
