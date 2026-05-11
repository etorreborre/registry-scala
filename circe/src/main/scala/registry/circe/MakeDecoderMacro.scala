package registry.circe

import io.circe.Json
import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag
import registry.{Entry, Registry, TypedEntry}
import scala.quoted.*

/**
 * Generate a `Decoder[T]` for a case class, a sealed trait, or a Scala 3 enum.
 *
 *   `makeDecoder[Person]` expands to an entry declaring inputs
 *   `(JsonOptions, ConstructorsDecoder, Decoder[F1], Decoder[F2], …)` where `F1, F2, …` are the unique
 *   field types across all constructors of `T`. At runtime, the entry's closure builds a list of
 *   [[ConstructorDef]]s, calls [[decodeFromDefinitions]] to pick the right constructor and decode each
 *   field, and applies the constructor to the decoded values.
 *
 *   Self-recursion is detected automatically (see [[makeEncoder]] for the same scheme).
 */
transparent inline def makeDecoder[T]: Registry[? <: Tuple, Decoder[T] *: EmptyTuple] =
  ${ MakeDecoderMacro.implDropQualifier[T] }

/** Same as [[makeDecoder]] but keep the fully-qualified type name in `fieldTypes`. */
transparent inline def makeDecoderQualified[T]: Registry[? <: Tuple, Decoder[T] *: EmptyTuple] =
  ${ MakeDecoderMacro.implFullQualified[T] }

/** Same as [[makeDecoder]] but keep only the last package segment in the type name. */
transparent inline def makeDecoderQualifiedLast[T]: Registry[? <: Tuple, Decoder[T] *: EmptyTuple] =
  ${ MakeDecoderMacro.implLastQualifier[T] }

/**
 * Value-driven variant: `makeDecoder(x)` for a function value `x`. Two shapes are accepted:
 *
 *   1. Single-arg `T => S` where `S` is **not** a `Decoder[_]` — registered as `map(f)`:
 *      inputs `Decoder[T]`, output `Decoder[S]`. Same as the `map(...)` helper.
 *
 *   2. Function `(A1, …, An) => Decoder[S]` of any arity — registered as a `fun`-style entry:
 *      inputs are the raw parameter types `A1, …, An` resolved from the registry, output is
 *      `Decoder[S]`. Useful when the function itself produces a `Decoder` (e.g. via `emap`)
 *      and needs config or other non-Decoder dependencies pulled from the registry.
 *
 * Anything else (non-function values, multi-arg with non-Decoder return) fails at compile time.
 * For type-based derivation, use the type-parameter form `makeDecoder[T]`.
 */
transparent inline def makeDecoder[X](inline x: X): Registry[? <: Tuple, ? <: Tuple] =
  ${ MakeDecoderMacro.valueImpl[X]('x) }

private[circe] object MakeDecoderMacro:

  private inline val DropQualifier = "drop"
  private inline val FullQualified = "full"
  private inline val LastQualifier = "last"

  def implDropQualifier[T: Type](using Quotes): Expr[Registry[? <: Tuple, Decoder[T] *: EmptyTuple]] = impl[T](DropQualifier)
  def implFullQualified[T: Type](using Quotes): Expr[Registry[? <: Tuple, Decoder[T] *: EmptyTuple]] = impl[T](FullQualified)
  def implLastQualifier[T: Type](using Quotes): Expr[Registry[? <: Tuple, Decoder[T] *: EmptyTuple]] = impl[T](LastQualifier)

  /**
   * Dispatch a value-based `makeDecoder(x)`. Two shapes:
   *   - Single-arg `T => S` (S not a `Decoder`) → map mode.
   *   - Multi-arg `(A1, …, An) => Decoder[S]` (any arity, including 1) → fun-style entry.
   */
  def valueImpl[X: Type](x: Expr[X])(using q: Quotes): Expr[Registry[? <: Tuple, ? <: Tuple]] =
    import q.reflect.*
    val xTpe = TypeRepr.of[X].dealias

    def isFunctionTycon(tycon: TypeRepr): Boolean =
      val name = tycon.typeSymbol.fullName
      name.startsWith("scala.Function") || name.startsWith("scala.ContextFunction")

    def asDecoderType(t: TypeRepr): Option[TypeRepr] =
      t.dealias match
        case AppliedType(tycon, s :: Nil) if tycon.typeSymbol.fullName == "registry.circe.Decoder" =>
          Some(s)
        case _ => None

    xTpe match
      case AppliedType(tycon, params) if isFunctionTycon(tycon) && params.size >= 2 =>
        val paramTypes = params.init
        val returnType = params.last
        asDecoderType(returnType) match
          case Some(sTpe) =>
            // Fun-style entry: inputs = raw param types, output = Decoder[s].
            val inputTagExprs: List[Expr[LightTypeTag]] = paramTypes.map: pt =>
              pt.asType match
                case '[p] => '{ summon[Tag[p]].tag }
            val outputTagExpr: Expr[LightTypeTag] = sTpe.asType match
              case '[s] => '{ summon[Tag[Decoder[s]]].tag }

            val closure: Expr[Seq[Any] => Any] = '{ (args: Seq[Any]) =>
              ${
                val argTerms: List[Term] = paramTypes.zipWithIndex.map: (pt, i) =>
                  pt.asType match
                    case '[p] => '{ ${ 'args }.apply(${ Expr(i) }).asInstanceOf[p] }.asTerm
                val applyM: Term = Select.unique(x.asTerm, "apply")
                Apply(applyM, argTerms).asExprOf[Any]
              }
            }

            val insTpe = buildTupleType(paramTypes)
            val outsTpe: TypeRepr = sTpe.asType match
              case '[s] => TypeRepr.of[Decoder[s] *: EmptyTuple]

            ((insTpe.asType, outsTpe.asType): @unchecked) match
              case ('[ins], '[outs]) =>
                '{
                  val theEntry = Entry(${ Expr.ofList(inputTagExprs) }, $outputTagExpr, $closure)
                  Registry[ins & Tuple, outs & Tuple](entries = List(theEntry))
                }.asInstanceOf[Expr[Registry[? <: Tuple, ? <: Tuple]]]

          case None =>
            // Map mode: single-arg only, T => S where S is not a Decoder.
            paramTypes match
              case tTpe :: Nil =>
                ((tTpe.asType, returnType.asType): @unchecked) match
                  case ('[t], '[s]) =>
                    val fExpr: Expr[t => s] = x.asExprOf[t => s]
                    '{
                      val tagIn = summon[Tag[Decoder[t]]]
                      val tagOut = summon[Tag[Decoder[s]]]
                      val entry = Entry(
                        List(tagIn.tag),
                        tagOut.tag,
                        args => args(0).asInstanceOf[Decoder[t]].map[s]($fExpr)
                      )
                      Registry[Decoder[t] *: EmptyTuple, Decoder[s] *: EmptyTuple](entries = List(entry))
                    }.asInstanceOf[Expr[Registry[? <: Tuple, ? <: Tuple]]]
              case _ =>
                report.errorAndAbort(
                  s"makeDecoder(${xTpe.show}): multi-arg functions must return `Decoder[S]`. " +
                    "Single-arg `T => S` is accepted as `map(f)`."
                )

      case _ =>
        report.errorAndAbort(
          s"makeDecoder(${xTpe.show}): expected a function value. " +
            "For type-based derivation, use `makeDecoder[T]` instead."
        )

  def impl[T: Type](mode: String)(using q: Quotes): Expr[Registry[? <: Tuple, Decoder[T] *: EmptyTuple]] =
    import q.reflect.*

    val tpe = TypeRepr.of[T]
    val sym = tpe.typeSymbol

    val constructors: List[Symbol] = discoverConstructors(sym)
    if constructors.isEmpty then
      report.errorAndAbort(
        s"makeDecoder: ${tpe.show} has no constructors to decode (not a case class, sealed hierarchy, or enum)"
      )

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
          val fieldNames = flat.map(_.name)
          // See [[MakeEncoderMacro]] for the rationale of the three-way dispatch on `tpe`.
          // Compute field types substituted with the applied type's arguments. `memberType` does
          // not auto-substitute type parameters in Scala 3 reflect; we do it explicitly via
          // `substituteTypes` from the class's primary-constructor type-parameter symbols.
          val rawFieldTypes: List[TypeRepr] = flat.map(childSym.typeRef.memberType)
          val fieldTypes: List[TypeRepr] = tpe match
            case AppliedType(_, args) =>
              val classTypeParams: List[Symbol] = childSym.primaryConstructor.paramSymss
                .find(ps => ps.headOption.exists(_.isType))
                .getOrElse(Nil)
              if classTypeParams.size == args.size && classTypeParams.nonEmpty then
                rawFieldTypes.map(_.substituteTypes(classTypeParams, args))
              else rawFieldTypes
            case _ => rawFieldTypes
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
                case AppliedType(_, targs) if c.ctorSym == tpe.typeSymbol =>
                  val targTrees = targs.map(t => TypeTree.of(using t.asType))
                  TypeApply(ctorSel, targTrees)
                case AppliedType(_, targs) =>
                  val classTypeParams = c.ctorSym.primaryConstructor.paramSymss
                    .find(ps => ps.headOption.exists(_.isType))
                    .getOrElse(Nil)
                  if classTypeParams.size == targs.size && classTypeParams.nonEmpty then
                    val targTrees = targs.map(t => TypeTree.of(using t.asType))
                    TypeApply(ctorSel, targTrees)
                  else ctorSel
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
            Left(
              s"cannot use this constructor to create an instance of type '${${ typeDisplayNameExpr }}': ${${ tcExpr }}"
            ): Either[String, T]
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

    // Recursion detection: see MakeEncoderMacro for rationale.
    val isRecursive: Boolean = ctorData.flatMap(_.fieldTypes).exists(ft => containsTypeSymbol(ft, sym))
    val typeDisplayExpr: Expr[String] = Expr(typeDisplayNameStr)

    val insTpe = buildTupleType(allInputTypes)
    insTpe.asType match
      case '[ins] =>
        if isRecursive then
          '{
            val ref = new java.util.concurrent.atomic.AtomicReference[Decoder[T]]()
            val rawClosure: Seq[Any] => Any = $closure
            val mainEntry = Entry(
              ${ Expr.ofList(inputTagExprs) },
              $outputTagExpr,
              (args: Seq[Any]) =>
                val d = rawClosure(args).asInstanceOf[Decoder[T]]
                ref.set(d)
                d
            )
            val forwarderEntry = Entry(
              Nil,
              $outputTagExpr,
              (_: Seq[Any]) =>
                Decoder[T]: j =>
                  val cached = ref.get()
                  if cached eq null then
                    Left(
                      s"Recursive Decoder[${$typeDisplayExpr}] forwarder invoked before its main entry was resolved."
                    )
                  else cached.decode(j)
            )
            Registry[ins & Tuple, Decoder[T] *: EmptyTuple](entries = List(mainEntry, forwarderEntry))
          }
        else
          '{
            val theEntry = Entry(${ Expr.ofList(inputTagExprs) }, $outputTagExpr, $closure)
            Registry[ins & Tuple, Decoder[T] *: EmptyTuple](entries = List(theEntry))
          }

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

  /** True iff `tpe`'s type tree mentions the symbol `target` anywhere (head or any type argument). */
  private def containsTypeSymbol(using q: Quotes)(tpe: q.reflect.TypeRepr, target: q.reflect.Symbol): Boolean =
    import q.reflect.*
    def go(t: TypeRepr): Boolean =
      t.dealias match
        case AppliedType(tycon, args) =>
          tycon.typeSymbol == target || args.exists(go)
        case other => other.typeSymbol == target
    go(tpe)
