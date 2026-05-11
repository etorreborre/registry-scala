package registry.circe

import io.circe.Json
import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag
import registry.{Entry, Registry, TypedEntry}
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
 *
 *   Self-recursion is detected automatically (any field type whose `TypeRepr` mentions `T` — directly
 *   or wrapped, e.g. `List[T]` / `Option[T]`). The macro emits an extra forwarder entry that picks up
 *   the in-flight `Encoder[T]` resolution; the main entry back-patches a shared cell so the forwarder
 *   dispatches to the real encoder once it exists. Mutual recursion across distinct types is not
 *   handled.
 */
transparent inline def makeEncoder[T]: Registry[? <: Tuple, Encoder[T] *: EmptyTuple] =
  ${ MakeEncoderMacro.implDropQualifier[T] }

/** Same as [[makeEncoder]] but keep the fully-qualified type name in `fromConstructorTypes`. */
transparent inline def makeEncoderQualified[T]: Registry[? <: Tuple, Encoder[T] *: EmptyTuple] =
  ${ MakeEncoderMacro.implFullQualified[T] }

/** Same as [[makeEncoder]] but keep only the last package segment in the type name. */
transparent inline def makeEncoderQualifiedLast[T]: Registry[? <: Tuple, Encoder[T] *: EmptyTuple] =
  ${ MakeEncoderMacro.implLastQualifier[T] }

/**
 * Value-driven variant: `makeEncoder(x)` for a function value. Two shapes:
 *
 *   1. Single-arg `S => T` where `T` is **not** an `Encoder[_]` — registered as `contramap(f)`:
 *      input `Encoder[T]`, output `Encoder[S]`.
 *
 *   2. `(A1, …, An) => Encoder[S]` of any arity — registered as a `fun`-style entry: inputs are
 *      the raw parameter types resolved from the registry, output `Encoder[S]`.
 *
 * Anything else fails at compile time. For type-based derivation, use `makeEncoder[T]`.
 */
transparent inline def makeEncoder[X](inline x: X): Registry[? <: Tuple, ? <: Tuple] =
  ${ MakeEncoderMacro.valueImpl[X]('x) }

private[circe] object MakeEncoderMacro:

  private inline val DropQualifier = "drop"
  private inline val FullQualified = "full"
  private inline val LastQualifier = "last"

  def implDropQualifier[T: Type](using Quotes): Expr[Registry[? <: Tuple, Encoder[T] *: EmptyTuple]] = impl[T](DropQualifier)
  def implFullQualified[T: Type](using Quotes): Expr[Registry[? <: Tuple, Encoder[T] *: EmptyTuple]] = impl[T](FullQualified)
  def implLastQualifier[T: Type](using Quotes): Expr[Registry[? <: Tuple, Encoder[T] *: EmptyTuple]] = impl[T](LastQualifier)

  /**
   * Dispatch a value-based `makeEncoder(x)`. Two shapes:
   *   - Single-arg `S => T` (T not an `Encoder`) → contramap mode.
   *   - Multi-arg `(A1, …, An) => Encoder[S]` (any arity) → fun-style entry.
   */
  def valueImpl[X: Type](x: Expr[X])(using q: Quotes): Expr[Registry[? <: Tuple, ? <: Tuple]] =
    import q.reflect.*
    val xTpe = TypeRepr.of[X].dealias

    def isFunctionTycon(tycon: TypeRepr): Boolean =
      val name = tycon.typeSymbol.fullName
      name.startsWith("scala.Function") || name.startsWith("scala.ContextFunction")

    def asEncoderType(t: TypeRepr): Option[TypeRepr] =
      t.dealias match
        case AppliedType(tycon, s :: Nil) if tycon.typeSymbol.fullName == "registry.circe.Encoder" =>
          Some(s)
        case _ => None

    xTpe match
      case AppliedType(tycon, params) if isFunctionTycon(tycon) && params.size >= 2 =>
        val paramTypes = params.init
        val returnType = params.last
        asEncoderType(returnType) match
          case Some(sTpe) =>
            // Fun-style: inputs = raw param types, output = Encoder[s].
            val inputTagExprs: List[Expr[LightTypeTag]] = paramTypes.map: pt =>
              pt.asType match
                case '[p] => '{ summon[Tag[p]].tag }
            val outputTagExpr: Expr[LightTypeTag] = sTpe.asType match
              case '[s] => '{ summon[Tag[Encoder[s]]].tag }

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
              case '[s] => TypeRepr.of[Encoder[s] *: EmptyTuple]

            ((insTpe.asType, outsTpe.asType): @unchecked) match
              case ('[ins], '[outs]) =>
                '{
                  val theEntry = Entry(${ Expr.ofList(inputTagExprs) }, $outputTagExpr, $closure)
                  Registry[ins & Tuple, outs & Tuple](entries = List(theEntry))
                }.asInstanceOf[Expr[Registry[? <: Tuple, ? <: Tuple]]]

          case None =>
            // Contramap mode: single-arg only, S => T where T is not an Encoder.
            paramTypes match
              case sTpe :: Nil =>
                ((sTpe.asType, returnType.asType): @unchecked) match
                  case ('[s], '[t]) =>
                    val fExpr: Expr[s => t] = x.asExprOf[s => t]
                    '{
                      val tagIn = summon[Tag[Encoder[t]]]
                      val tagOut = summon[Tag[Encoder[s]]]
                      val entry = Entry(
                        List(tagIn.tag),
                        tagOut.tag,
                        args => args(0).asInstanceOf[Encoder[t]].contramap[s]($fExpr)
                      )
                      Registry[Encoder[t] *: EmptyTuple, Encoder[s] *: EmptyTuple](entries = List(entry))
                    }.asInstanceOf[Expr[Registry[? <: Tuple, ? <: Tuple]]]
              case _ =>
                report.errorAndAbort(
                  s"makeEncoder(${xTpe.show}): multi-arg functions must return `Encoder[S]`. " +
                    "Single-arg `S => T` is accepted as `contramap(f)`."
                )

      case _ =>
        report.errorAndAbort(
          s"makeEncoder(${xTpe.show}): expected a function value. " +
            "For type-based derivation, use `makeEncoder[T]` instead."
        )

  def impl[T: Type](mode: String)(using q: Quotes): Expr[Registry[? <: Tuple, Encoder[T] *: EmptyTuple]] =
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
          val fieldNames = flat.map(_.name)
          // Compute field types substituted with the applied type's arguments via
          // `substituteTypes` — `memberType` does not auto-substitute in Scala 3 reflect.
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
          // Use the applied form for the bind type so that field accessors return correctly
          // substituted types (e.g. `Wrapper[Int].value : Int`, not `A`).
          val childType: TypeRepr =
            tpe match
              case AppliedType(_, args) if c.ctorSym == tpe.typeSymbol => tpe
              case AppliedType(_, args) =>
                val classTypeParams = c.ctorSym.primaryConstructor.paramSymss
                  .find(ps => ps.headOption.exists(_.isType))
                  .getOrElse(Nil)
                if classTypeParams.size == args.size && classTypeParams.nonEmpty then
                  AppliedType(c.ctorSym.typeRef, args)
                else c.ctorSym.typeRef
              case _ => c.ctorSym.typeRef
          val bindSym: Symbol = Symbol.newBind(Symbol.spliceOwner, "a", Flags.EmptyFlags, childType)
          val bindRef: Term = Ref(bindSym)
          val patternTypeTree: TypeTree = childType.asType match
            case '[childT] => TypeTree.of[childT]
          val typedPat: Tree = Typed(Wildcard(), patternTypeTree)
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

    // Recursion detection: any field type whose TypeRepr tree contains the symbol `sym` of `T`
    // requires a forwarder entry so the cycle through `Encoder[T]` resolves through the in-flight
    // skip in `Resolve.go` instead of erroring out.
    val isRecursive: Boolean = ctorData.flatMap(_.fieldTypes).exists(ft => containsTypeSymbol(ft, sym))
    val typeDisplay: String = applyQualifierMode(sym.fullName, mode)
    val typeDisplayExpr: Expr[String] = Expr(typeDisplay)

    val insTpe = buildTupleType(allInputTypes)
    insTpe.asType match
      case '[ins] =>
        if isRecursive then
          '{
            val ref = new java.util.concurrent.atomic.AtomicReference[Encoder[T]]()
            val rawClosure: Seq[Any] => Any = $closure
            val mainEntry = Entry(
              ${ Expr.ofList(inputTagExprs) },
              $outputTagExpr,
              (args: Seq[Any]) =>
                val e = rawClosure(args).asInstanceOf[Encoder[T]]
                ref.set(e)
                e
            )
            val forwarderEntry = Entry(
              Nil,
              $outputTagExpr,
              (_: Seq[Any]) =>
                Encoder[T]: a =>
                  val cached = ref.get()
                  if cached eq null then
                    sys.error(
                      s"Recursive Encoder[${$typeDisplayExpr}] forwarder invoked before its main entry was resolved."
                    )
                  else cached.encode(a)
            )
            Registry[ins & Tuple, Encoder[T] *: EmptyTuple](entries = List(mainEntry, forwarderEntry))
          }
        else
          '{
            val theEntry = Entry(${ Expr.ofList(inputTagExprs) }, $outputTagExpr, $closure)
            Registry[ins & Tuple, Encoder[T] *: EmptyTuple](entries = List(theEntry))
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

