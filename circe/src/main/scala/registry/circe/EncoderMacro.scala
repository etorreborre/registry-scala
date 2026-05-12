package registry.circe

import io.circe.{Encoder, Json}
import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag
import registry.{Entry, Registry, TypedEntry}
import scala.quoted.*

/**
 * Generate an `Encoder[T]` for a case class, a sealed trait, or a Scala 3 enum.
 *
 *   `encoder[Person]` expands to an entry declaring inputs
 *   `(JsonOptions, ConstructorEncoder, Encoder[F1], Encoder[F2], …)` where `F1, F2, …` are the unique
 *   field types across all constructors of `T`. At runtime, the entry's closure matches on the runtime
 *   value and packs a [[FromConstructor]] that the [[ConstructorEncoder]] turns into `Json`.
 *
 *   For sum types (sealed traits / enums), one pattern-match branch is generated per child constructor.
 *
 *   The macro emits the fully-qualified constructor name. The presentation of that name in the JSON
 *   tag is controlled by [[JsonOptions.constructorTagModifier]] (default: drop the qualifier, matching
 *   aeson). Use `identity` to keep the FQN or [[JsonOptions.lastTwoSegments]] for a mid-ground.
 *
 *   Self-recursion is detected automatically (any field type whose `TypeRepr` mentions `T` — directly
 *   or wrapped, e.g. `List[T]` / `Option[T]`). The macro emits an extra forwarder entry that picks up
 *   the in-flight `Encoder[T]` resolution; the main entry back-patches a shared cell so the forwarder
 *   dispatches to the real encoder once it exists. Mutual recursion across distinct types is not
 *   handled.
 */
transparent inline def encoder[T]: Registry[? <: Tuple, Encoder[T] *: EmptyTuple] =
  ${ EncoderMacro.impl[T] }

/**
 * Value-driven variant: `encoder(x)` for a function value. Two shapes:
 *
 *   1. Single-arg `S => T` where `T` is **not** an `Encoder[_]` — registered as `contramap(f)`:
 *      input `Encoder[T]`, output `Encoder[S]`.
 *
 *   2. `(A1, …, An) => Encoder[S]` of any arity — registered as a `fun`-style entry: inputs are
 *      the raw parameter types resolved from the registry, output `Encoder[S]`.
 *
 * Anything else fails at compile time. For type-based derivation, use `encoder[T]`.
 */
transparent inline def encoder[X](inline x: X): Registry[? <: Tuple, ? <: Tuple] =
  ${ EncoderMacro.valueImpl[X]('x) }

private[circe] object EncoderMacro:

  /**
   * Dispatch a value-based `encoder(x)`. Two shapes:
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
        case AppliedType(tycon, s :: Nil) if tycon.typeSymbol.fullName == "io.circe.Encoder" =>
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
                    case '[p] => '{ args.apply(${ Expr(i) }).asInstanceOf[p] }.asTerm
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
                  s"encoder(${xTpe.show}): multi-arg functions must return `Encoder[S]`. " +
                    "Single-arg `S => T` is accepted as `contramap(f)`."
                )

      case _ =>
        asEncoderType(xTpe) match
          case Some(sTpe) =>
            // Zero-arg shape: encoder(e: Encoder[S]) -> value entry.
            sTpe.asType match
              case '[s] =>
                val eExpr = x.asExprOf[Encoder[s]]
                '{
                  val tagOut = summon[Tag[Encoder[s]]]
                  val theEntry = Entry(Nil, tagOut.tag, _ => $eExpr)
                  Registry[EmptyTuple, Encoder[s] *: EmptyTuple](entries = List(theEntry))
                }.asInstanceOf[Expr[Registry[? <: Tuple, ? <: Tuple]]]
          case None =>
            report.errorAndAbort(
              s"encoder(${xTpe.show}): expected an `Encoder[S]` or a function returning `Encoder[S]`. " +
                "For type-based derivation, use `encoder[T]` instead."
            )

  def impl[T: Type](using q: Quotes): Expr[Registry[? <: Tuple, Encoder[T] *: EmptyTuple]] =
    import q.reflect.*

    // Fast path: if `T`'s companion declares a `given Encoder[T]`, register it directly
    // instead of generating a structural encoder. Companion-only (not full implicit scope) so
    // the choice doesn't silently depend on imports.
    DecoderMacro.findCompanionGiven(TypeRepr.of[T], TypeRepr.of[Encoder[T]]) match
      case Some(givenTerm) =>
        val givenExpr = givenTerm.asExprOf[Encoder[T]]
        return '{
          val tagOut = summon[Tag[Encoder[T]]]
          val theEntry = Entry(Nil, tagOut.tag, _ => $givenExpr)
          Registry[EmptyTuple, Encoder[T] *: EmptyTuple](entries = List(theEntry))
        }
      case None => ()

    val tpe = TypeRepr.of[T]
    val sym = tpe.typeSymbol

    val constructors: List[Symbol] = discoverConstructors(sym)
    if constructors.isEmpty then
      report.errorAndAbort(
        s"encoder: ${tpe.show} has no constructors to encode (not a case class, sealed hierarchy, or enum)"
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
      val displayName = cleanFullName(childSym.fullName)
      val isModule = childSym.flags.is(Flags.Module) || childSym.isTerm
      if isModule then
        CtorData(childSym, displayName, true, Nil, Nil)
      else
        val ctor = childSym.primaryConstructor
        if ctor == Symbol.noSymbol then
          CtorData(childSym, displayName, true, Nil, Nil)
        else
          val nonTypeLists: List[List[Symbol]] = ctor.paramSymss.filterNot(_.headOption.exists(_.isType))
          // Drop using-clause parameter lists — they aren't part of the JSON shape and aren't
          // accessible via the standard case-class field accessors used by the encoder.
          val valueParamLists: List[List[Symbol]] =
            nonTypeLists.filterNot(_.exists(p => p.flags.is(Flags.Given) || p.flags.is(Flags.Implicit)))
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
    val constructorTypesListExpr: Expr[List[String]] = Expr(allFieldTypes.map(typeDisplayName(_)))

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
            '{ ${ encoders }.apply(${ encIdxExpr }).apply(${ fieldExpr }) }
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

      Encoder.instance[T]: (value: T) =>
        val fc: FromConstructor = ${ buildMatch('value, 'encoders) }
        ce.encodeConstructor(opts, fc)
    }

    // Recursion detection: any field type whose TypeRepr tree contains the symbol `sym` of `T`
    // requires a forwarder entry so the cycle through `Encoder[T]` resolves through the in-flight
    // skip in `Resolve.go` instead of erroring out.
    val isRecursive: Boolean = ctorData.flatMap(_.fieldTypes).exists(ft => containsTypeSymbol(ft, sym))
    val typeDisplay: String = cleanFullName(sym.fullName)
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
                Encoder.instance[T]: a =>
                  val cached = ref.get()
                  if cached eq null then
                    sys.error(
                      s"Recursive Encoder[${$typeDisplayExpr}] forwarder invoked before its main entry was resolved."
                    )
                  else cached.apply(a)
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

  private def typeDisplayName(using q: Quotes)(tpe: q.reflect.TypeRepr): String =
    import q.reflect.*
    tpe.dealias match
      case AppliedType(tycon, args) =>
        val head = cleanFullName(tycon.typeSymbol.fullName)
        val tail = args.map(a => cleanFullName(a.typeSymbol.fullName)).mkString(" ")
        if args.isEmpty then head else s"$head $tail"
      case other => cleanFullName(other.typeSymbol.fullName)

  /** Strip the trailing `$` that companion module symbols carry in their `fullName`. */
  private def cleanFullName(fq: String): String =
    if fq.endsWith("$") then fq.dropRight(1) else fq

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
