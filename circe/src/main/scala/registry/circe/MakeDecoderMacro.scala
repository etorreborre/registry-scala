package registry.circe

import io.circe.{ACursor, Decoder, DecodingFailure, HCursor, Json}
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
        case AppliedType(tycon, s :: Nil) if tycon.typeSymbol.fullName == "io.circe.Decoder" =>
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
        asDecoderType(xTpe) match
          case Some(sTpe) =>
            // Zero-arg shape: makeDecoder(d: Decoder[S]) -> value entry.
            sTpe.asType match
              case '[s] =>
                val dExpr = x.asExprOf[Decoder[s]]
                '{
                  val tagOut = summon[Tag[Decoder[s]]]
                  val theEntry = Entry(Nil, tagOut.tag, _ => $dExpr)
                  Registry[EmptyTuple, Decoder[s] *: EmptyTuple](entries = List(theEntry))
                }.asInstanceOf[Expr[Registry[? <: Tuple, ? <: Tuple]]]
          case None =>
            report.errorAndAbort(
              s"makeDecoder(${xTpe.show}): expected a `Decoder[S]` or a function returning `Decoder[S]`. " +
                "For type-based derivation, use `makeDecoder[T]` instead."
            )

  def impl[T: Type](mode: String)(using q: Quotes): Expr[Registry[? <: Tuple, Decoder[T] *: EmptyTuple]] =
    import q.reflect.*

    // Fast path: if `T`'s companion declares a `given Decoder[T]`, register it directly
    // instead of generating a structural decoder. Companion-only (not full implicit scope) so
    // the choice doesn't silently depend on imports.
    findCompanionGiven(TypeRepr.of[T], TypeRepr.of[Decoder[T]]) match
      case Some(givenTerm) =>
        val givenExpr = givenTerm.asExprOf[Decoder[T]]
        return '{
          val tagOut = summon[Tag[Decoder[T]]]
          val theEntry = Entry(Nil, tagOut.tag, _ => $givenExpr)
          Registry[EmptyTuple, Decoder[T] *: EmptyTuple](entries = List(theEntry))
        }
      case None => ()

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
        fieldTypes: List[TypeRepr],
        // Sizes of the value parameter lists (using lists are excluded from fields). The
        // constructor invocation needs to apply each list separately so the compiler doesn't
        // auto-tuple a flat arg list into a synthetic tuple.
        valueListSizes: List[Int],
        // Types of using-clause parameters, list-by-list. Summoned at macro time and applied
        // after the value lists.
        usingLists: List[List[TypeRepr]]
    )

    def mkCtorData(childSym: Symbol): CtorData =
      val displayName = applyQualifierMode(childSym.fullName, mode)
      val isModule = childSym.flags.is(Flags.Module) || childSym.isTerm
      if isModule then
        CtorData(childSym, displayName, true, Nil, Nil, Nil, Nil)
      else
        val ctor = childSym.primaryConstructor
        if ctor == Symbol.noSymbol then
          CtorData(childSym, displayName, true, Nil, Nil, Nil, Nil)
        else
          val nonTypeLists: List[List[Symbol]] = ctor.paramSymss.filterNot(_.headOption.exists(_.isType))
          // Partition each list into value/using parts. A using clause has all parameters marked
          // Given (and/or Implicit). We discard the using lists from field discovery and remember
          // their types so we can summon them at constructor-application time.
          val (usingParamLists, valueParamLists) =
            nonTypeLists.partition(_.exists(p => p.flags.is(Flags.Given) || p.flags.is(Flags.Implicit)))
          val flat: List[Symbol] = valueParamLists.flatten
          val fieldNames = flat.map(_.name)
          val valueListSizes = valueParamLists.map(_.size)

          // See [[MakeEncoderMacro]] for the rationale of the three-way dispatch on `tpe`.
          val rawFieldTypes: List[TypeRepr] = flat.map(childSym.typeRef.memberType)
          val classTypeParams: List[Symbol] = childSym.primaryConstructor.paramSymss
            .find(ps => ps.headOption.exists(_.isType))
            .getOrElse(Nil)
          val maybeSubstitute: TypeRepr => TypeRepr = tpe match
            case AppliedType(_, args) if classTypeParams.size == args.size && classTypeParams.nonEmpty =>
              _.substituteTypes(classTypeParams, args)
            case _ => identity
          val fieldTypes: List[TypeRepr] = rawFieldTypes.map(maybeSubstitute)
          val usingLists: List[List[TypeRepr]] =
            usingParamLists.map(_.map(p => maybeSubstitute(childSym.typeRef.memberType(p))))

          CtorData(childSym, displayName, flat.isEmpty, fieldNames, fieldTypes, valueListSizes, usingLists)

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

    // ----- Build a (ToConstructor => Decoder.Result[T]) function by matching on
    //       tc.constructorName (a plain String) and applying the appropriate constructor.

    def buildOneCtorApplication(
        c: CtorData,
        tcValuesExpr: Expr[List[(Option[FieldDef], ACursor)]],
        decoders: Expr[Seq[Decoder[Any]]]
    ): Expr[Decoder.Result[T]] =
      if c.isSingleton then
        val ctorExpr: Expr[T] =
          if c.ctorSym.flags.is(Flags.Module) then Ref(c.ctorSym.companionModule).asExprOf[T]
          else Ref(c.ctorSym).asExprOf[T]
        '{ Right($ctorExpr) }
      else
        // Build nested flatMap: decodeFieldValue(d_i, typeName, ctorName, vs(i)).flatMap(v_i => ...)
        val ctorNameExpr: Expr[String] = Expr(c.displayName)

        def nest(i: Int, acc: List[Term]): Expr[Decoder.Result[T]] =
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
            // Apply each value parameter list separately so the compiler doesn't auto-tuple a
            // flattened arg list. Then apply each using list with macro-summoned instances.
            var applied: Term = ctorTyped
            var remaining = acc
            for size <- c.valueListSizes do
              val (args, rest) = remaining.splitAt(size)
              applied = Apply(applied, args)
              remaining = rest
            for usingList <- c.usingLists do
              val usingArgs: List[Term] = usingList.map { uTpe =>
                Implicits.search(uTpe) match
                  case iss: ImplicitSearchSuccess => iss.tree
                  case _: ImplicitSearchFailure =>
                    report.errorAndAbort(
                      s"makeDecoder[${tpe.show}]: cannot summon using-clause parameter of type ${uTpe.show}"
                    )
              }
              applied = Apply(applied, usingArgs)
            applied.asExprOf[T] match
              case e => '{ Right($e): Decoder.Result[T] }
          else
            val ft = c.fieldTypes(i)
            val idx = allFieldTypes.indexWhere(_ =:= ft)
            val idxExpr = Expr(idx)
            val iExpr = Expr(i)
            ft.asType match
              case '[p] =>
                val fieldEither: Expr[Decoder.Result[p]] = '{
                  decodeFieldValue[p](
                    $decoders.apply($idxExpr).asInstanceOf[Decoder[p]],
                    $typeDisplayNameExpr,
                    $ctorNameExpr,
                    $tcValuesExpr.apply($iExpr)
                  )
                }
                val funcExpr: Expr[p => Decoder.Result[T]] = buildLambda[p, Decoder.Result[T]]: vRef =>
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
    def buildBuildFn(decoders: Expr[Seq[Decoder[Any]]]): Expr[ToConstructor => Decoder.Result[T]] =
      buildLambda[ToConstructor, Decoder.Result[T]]: tcRef =>
        val tcExpr: Expr[ToConstructor] = tcRef.asExprOf[ToConstructor]
        val nameExpr: Expr[String] = '{ $tcExpr.constructorName }
        val valuesExpr: Expr[List[(Option[FieldDef], ACursor)]] = '{ $tcExpr.values }

        val cases: List[CaseDef] = ctorData.map: c =>
          val lit = Literal(StringConstant(c.displayName))
          val body = buildOneCtorApplication(c, valuesExpr, decoders)
          CaseDef(lit, None, body.asTerm)

        val defaultCase = CaseDef(
          Wildcard(),
          None,
          '{
            Left(
              DecodingFailure(
                s"cannot use this constructor to create an instance of type '${${ typeDisplayNameExpr }}': ${${ tcExpr }}",
                Nil
              )
            ): Decoder.Result[T]
          }.asTerm
        )

        Match(nameExpr.asTerm, cases :+ defaultCase).asExprOf[Decoder.Result[T]]

    // ----- Assemble the closure -----

    val closure: Expr[Seq[Any] => Any] = '{ (args: Seq[Any]) =>
      val opts = args(0).asInstanceOf[JsonOptions]
      val cd = args(1).asInstanceOf[ConstructorsDecoder]
      val decoders: Seq[Decoder[Any]] = args.drop(2).asInstanceOf[Seq[Decoder[Any]]]
      val defs = $constructorDefsExpr
      val buildFn: ToConstructor => Decoder.Result[T] = ${ buildBuildFn('decoders) }
      Decoder.instance[T]: (cursor: HCursor) =>
        decodeFromDefinitions[T](opts, cd, defs, cursor, buildFn)
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
                Decoder.instance[T]: c =>
                  val cached = ref.get()
                  if cached eq null then
                    Left(
                      DecodingFailure(
                        s"Recursive Decoder[${$typeDisplayExpr}] forwarder invoked before its main entry was resolved.",
                        c.history
                      )
                    )
                  else cached.apply(c)
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

  /**
   * Look for an implicit/given member of type `targetTpe` declared directly in `tTpe`'s companion
   * object — or, for an opaque type alias defined inside an `object`, in that enclosing object —
   * and nowhere else. Returns a `Term` that references the member, or `None` if no matching member
   * exists. Polymorphic givens (`given [A]: Foo[Bar[A]]`) are skipped: first-cut handles
   * monomorphic vals/defs only.
   */
  private[circe] def findCompanionGiven(using q: Quotes)(
      tTpe: q.reflect.TypeRepr,
      targetTpe: q.reflect.TypeRepr
  ): Option[q.reflect.Term] =
    import q.reflect.*
    val tSym = tTpe.dealias.typeSymbol
    // Candidate scopes: the proper companion module, plus the enclosing module for opaque types
    // (whose givens live in the surrounding `object`, not a separate companion).
    val candidates: List[Symbol] =
      val companion = tSym.companionModule
      val owner     = tSym.maybeOwner
      val ownerAsModule =
        if owner == Symbol.noSymbol then Symbol.noSymbol
        else if owner.flags.is(Flags.Module) then owner
        else owner.companionModule // module class -> module term, or noSymbol otherwise
      List(companion, ownerAsModule).filter(_ != Symbol.noSymbol).distinct

    candidates.iterator.flatMap { scope =>
      val members = scope.declaredFields ++ scope.declaredMethods
      members.iterator.flatMap { m =>
        if !(m.flags.is(Flags.Given) || m.flags.is(Flags.Implicit)) then None
        else
          val resType: Option[TypeRepr] = m.tree match
            case d: DefDef if d.paramss.isEmpty => Some(d.returnTpt.tpe)
            case v: ValDef                       => Some(v.tpt.tpe)
            case _                               => None
          resType.filter(_ <:< targetTpe).map(_ => Ref(m))
      }
    }.nextOption()
