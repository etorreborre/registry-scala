package registry.cbor

import io.bullet.borer.{Decoder, Dom, Reader}
import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag
import registry.{Entry, Registry, TypedEntry}
import scala.quoted.*

/**
 * Generate a `Decoder[T]` for a case class, a sealed trait, or a Scala 3 enum.
 *
 *   `decoder[Person]` expands to an entry declaring inputs
 *   `(CborOptions, ConstructorsDecoder, Decoder[F1], Decoder[F2], …)` where `F1, F2, …` are the unique
 *   field types across all constructors of `T`. At runtime, the entry's closure reads the next CBOR
 *   data item as a `Dom.Element`, calls [[decodeFromDefinitions]] to pick the right constructor and
 *   decode each field, then applies the constructor to the decoded values.
 *
 *   Self-recursion is detected automatically (see [[encoder]] for the same scheme).
 */
transparent inline def decoder[T]: Registry[? <: Tuple, Decoder[T] *: EmptyTuple] =
  ${ DecoderMacro.impl[T] }

/**
 * Value-driven variant: `decoder(x)` for a function value `x`. Two shapes are accepted:
 *
 *   1. Single-arg `T => S` where `S` is **not** a `Decoder[_]` — registered as `map(f)`:
 *      inputs `Decoder[T]`, output `Decoder[S]`.
 *
 *   2. Function `(A1, …, An) => Decoder[S]` of any arity — registered as a `fun`-style entry: inputs are
 *      the raw parameter types `A1, …, An` resolved from the registry, output is `Decoder[S]`.
 */
transparent inline def decoder[X](inline x: X): Registry[? <: Tuple, ? <: Tuple] =
  ${ DecoderMacro.valueImpl[X]('x) }

private[cbor] object DecoderMacro:

  def valueImpl[X: Type](x: Expr[X])(using q: Quotes): Expr[Registry[? <: Tuple, ? <: Tuple]] =
    import q.reflect.*
    val xTpe = TypeRepr.of[X].dealias

    def isFunctionTycon(tycon: TypeRepr): Boolean =
      val name = tycon.typeSymbol.fullName
      name.startsWith("scala.Function") || name.startsWith("scala.ContextFunction")

    def asDecoderType(t: TypeRepr): Option[TypeRepr] =
      t.dealias match
        case AppliedType(tycon, s :: Nil) if tycon.typeSymbol.fullName == "io.bullet.borer.Decoder" =>
          Some(s)
        case _ => None

    xTpe match
      case AppliedType(tycon, params) if isFunctionTycon(tycon) && params.size >= 2 =>
        val paramTypes = params.init
        val returnType = params.last
        asDecoderType(returnType) match
          case Some(sTpe) =>
            val inputTagExprs: List[Expr[LightTypeTag]] = paramTypes.map: pt =>
              pt.asType match
                case '[p] => '{ summon[Tag[p]].tag }
            val outputTagExpr: Expr[LightTypeTag] = sTpe.asType match
              case '[s] => '{ summon[Tag[Decoder[s]]].tag }

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
              case '[s] => TypeRepr.of[Decoder[s] *: EmptyTuple]

            ((insTpe.asType, outsTpe.asType): @unchecked) match
              case ('[ins], '[outs]) =>
                '{
                  val theEntry = Entry(${ Expr.ofList(inputTagExprs) }, $outputTagExpr, $closure)
                  Registry[ins & Tuple, outs & Tuple](entries = List(theEntry))
                }.asInstanceOf[Expr[Registry[? <: Tuple, ? <: Tuple]]]

          case None =>
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
                  s"decoder(${xTpe.show}): multi-arg functions must return `Decoder[S]`. " +
                    "Single-arg `T => S` is accepted as `map(f)`."
                )

      case _ =>
        asDecoderType(xTpe) match
          case Some(sTpe) =>
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
              s"decoder(${xTpe.show}): expected a `Decoder[S]` or a function returning `Decoder[S]`. " +
                "For type-based derivation, use `decoder[T]` instead."
            )

  def impl[T: Type](using q: Quotes): Expr[Registry[? <: Tuple, Decoder[T] *: EmptyTuple]] =
    import q.reflect.*

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
        s"decoder: ${tpe.show} has no constructors to decode (not a case class, sealed hierarchy, or enum)"
      )

    final case class CtorData(
        ctorSym: Symbol,
        displayName: String,
        isSingleton: Boolean,
        fieldNames: List[String],
        fieldTypes: List[TypeRepr],
        valueListSizes: List[Int],
        usingLists: List[List[TypeRepr]]
    )

    def mkCtorData(childSym: Symbol): CtorData =
      val displayName = cleanFullName(childSym.fullName)
      val isModule = childSym.flags.is(Flags.Module) || childSym.isTerm
      if isModule then CtorData(childSym, displayName, true, Nil, Nil, Nil, Nil)
      else
        val ctor = childSym.primaryConstructor
        if ctor == Symbol.noSymbol then CtorData(childSym, displayName, true, Nil, Nil, Nil, Nil)
        else
          val nonTypeLists: List[List[Symbol]] = ctor.paramSymss.filterNot(_.headOption.exists(_.isType))
          val (usingParamLists, valueParamLists) =
            nonTypeLists.partition(_.exists(p => p.flags.is(Flags.Given) || p.flags.is(Flags.Implicit)))
          val flat: List[Symbol] = valueParamLists.flatten
          val fieldNames = flat.map(_.name)
          val valueListSizes = valueParamLists.map(_.size)

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

    val allFieldTypes: List[TypeRepr] = ctorData.flatMap(_.fieldTypes).foldLeft(List.empty[TypeRepr]): (acc, t) =>
      if acc.exists(_ =:= t) then acc else acc :+ t

    val decoderParamTypes: List[TypeRepr] = allFieldTypes.map: pt =>
      pt.asType match
        case '[p] => TypeRepr.of[Decoder[p]]

    val prefixInputTypes: List[TypeRepr] = List(TypeRepr.of[CborOptions], TypeRepr.of[ConstructorsDecoder])
    val allInputTypes: List[TypeRepr] = prefixInputTypes ++ decoderParamTypes

    val inputTagExprs: List[Expr[LightTypeTag]] = allInputTypes.map: t =>
      t.asType match
        case '[p] => '{ summon[Tag[p]].tag }

    val outputTagExpr: Expr[LightTypeTag] = '{ summon[Tag[Decoder[T]]].tag }

    val typeDisplayNameStr: String = cleanFullName(sym.fullName)
    val typeDisplayNameExpr: Expr[String] = Expr(typeDisplayNameStr)

    val constructorDefsExpr: Expr[List[ConstructorDef]] = {
      val pairs: List[Expr[ConstructorDef]] = ctorData.map: c =>
        val nameExpr = Expr(c.displayName)
        val fieldNamesExpr = Expr(c.fieldNames)
        val fieldTypesExpr = Expr(c.fieldTypes.map(typeDisplayName(_)))
        '{ ConstructorDef($nameExpr, $fieldNamesExpr, $fieldTypesExpr) }
      Expr.ofList(pairs)
    }

    def buildOneCtorApplication(
        c: CtorData,
        tcValuesExpr: Expr[List[(Option[FieldDef], Dom.Element)]],
        decoders: Expr[Seq[Decoder[Any]]]
    ): Expr[Either[CborError, T]] =
      if c.isSingleton then
        val ctorExpr: Expr[T] =
          if c.ctorSym.flags.is(Flags.Module) then Ref(c.ctorSym.companionModule).asExprOf[T]
          else Ref(c.ctorSym).asExprOf[T]
        '{ Right($ctorExpr) }
      else
        val ctorNameExpr: Expr[String] = Expr(c.displayName)

        def nest(i: Int, acc: List[Term]): Expr[Either[CborError, T]] =
          if i == c.fieldNames.length then
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
                      s"decoder[${tpe.show}]: cannot summon using-clause parameter of type ${uTpe.show}"
                    )
              }
              applied = Apply(applied, usingArgs)
            applied.asExprOf[T] match
              case e => '{ Right($e): Either[CborError, T] }
          else
            val ft = c.fieldTypes(i)
            val idx = allFieldTypes.indexWhere(_ =:= ft)
            val idxExpr = Expr(idx)
            val iExpr = Expr(i)
            ft.asType match
              case '[p] =>
                val fieldEither: Expr[Either[CborError, p]] = '{
                  decodeFieldValue[p](
                    $decoders.apply($idxExpr).asInstanceOf[Decoder[p]],
                    $typeDisplayNameExpr,
                    $ctorNameExpr,
                    $tcValuesExpr.apply($iExpr)
                  )
                }
                val funcExpr: Expr[p => Either[CborError, T]] = buildLambda[p, Either[CborError, T]]: vRef =>
                  nest(i + 1, acc :+ vRef)
                '{ $fieldEither.flatMap($funcExpr) }

        nest(0, Nil)

    def buildLambda[A: Type, B: Type](body: Term => Expr[B]): Expr[A => B] =
      Lambda(
        Symbol.spliceOwner,
        MethodType(List("v"))(_ => List(TypeRepr.of[A]), _ => TypeRepr.of[B]),
        (meth, params) => body(params.head.asInstanceOf[Term]).asTerm.changeOwner(meth)
      ).asExprOf[A => B]

    def buildBuildFn(decoders: Expr[Seq[Decoder[Any]]]): Expr[ToConstructor => Either[CborError, T]] =
      buildLambda[ToConstructor, Either[CborError, T]]: tcRef =>
        val tcExpr: Expr[ToConstructor] = tcRef.asExprOf[ToConstructor]
        val nameExpr: Expr[String] = '{ $tcExpr.constructorName }
        val valuesExpr: Expr[List[(Option[FieldDef], Dom.Element)]] = '{ $tcExpr.values }

        val cases: List[CaseDef] = ctorData.map: c =>
          val lit = Literal(StringConstant(c.displayName))
          val body = buildOneCtorApplication(c, valuesExpr, decoders)
          CaseDef(lit, None, body.asTerm)

        val defaultCase = CaseDef(
          Wildcard(),
          None,
          '{
            Left(
              CborError(
                s"cannot use this constructor to create an instance of type '${${ typeDisplayNameExpr }}': ${${ tcExpr }}"
              )
            ): Either[CborError, T]
          }.asTerm
        )

        Match(nameExpr.asTerm, cases :+ defaultCase).asExprOf[Either[CborError, T]]

    val closure: Expr[Seq[Any] => Any] = '{ (args: Seq[Any]) =>
      val opts = args(0).asInstanceOf[CborOptions]
      val cd = args(1).asInstanceOf[ConstructorsDecoder]
      val decoders: Seq[Decoder[Any]] = args.drop(2).asInstanceOf[Seq[Decoder[Any]]]
      val defs = $constructorDefsExpr
      val buildFn: ToConstructor => Either[CborError, T] = ${ buildBuildFn('decoders) }
      Decoder[T] { (r: Reader) =>
        val elem: Dom.Element = r.read[Dom.Element]()
        decodeFromDefinitions[T](opts, cd, defs, elem, buildFn) match
          case Right(a)  => a
          case Left(err) => r.validationFailure(err.toString)
      }
    }

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
                Decoder[T] { (r: Reader) =>
                  val cached = ref.get()
                  if cached eq null then
                    r.validationFailure(
                      s"Recursive Decoder[${$typeDisplayExpr}] forwarder invoked before its main entry was resolved."
                    )
                  else cached.read(r)
                }
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

  private def typeDisplayName(using q: Quotes)(tpe: q.reflect.TypeRepr): String =
    import q.reflect.*
    tpe.dealias match
      case AppliedType(tycon, args) =>
        val head = cleanFullName(tycon.typeSymbol.fullName)
        val tail = args.map(a => cleanFullName(a.typeSymbol.fullName)).mkString(" ")
        if args.isEmpty then head else s"$head $tail"
      case other => cleanFullName(other.typeSymbol.fullName)

  private def cleanFullName(fq: String): String =
    if fq.endsWith("$") then fq.dropRight(1) else fq

  private def buildTupleType(using q: Quotes)(types: List[q.reflect.TypeRepr]): q.reflect.TypeRepr =
    import q.reflect.*
    types.foldRight(TypeRepr.of[EmptyTuple]): (h, acc) =>
      ((h.asType, acc.asType): @unchecked) match
        case ('[ht], '[tt]) => TypeRepr.of[ht *: (tt & Tuple)]

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
   * and nowhere else.
   */
  private[cbor] def findCompanionGiven(using q: Quotes)(
      tTpe: q.reflect.TypeRepr,
      targetTpe: q.reflect.TypeRepr
  ): Option[q.reflect.Term] =
    import q.reflect.*
    val tSym = tTpe.dealias.typeSymbol
    val candidates: List[Symbol] =
      val companion = tSym.companionModule
      val owner = tSym.maybeOwner
      val ownerAsModule =
        if owner == Symbol.noSymbol then Symbol.noSymbol
        else if owner.flags.is(Flags.Module) then owner
        else owner.companionModule
      List(companion, ownerAsModule).filter(_ != Symbol.noSymbol).distinct

    candidates.iterator.flatMap { scope =>
      val members = scope.declaredFields ++ scope.declaredMethods
      members.iterator.flatMap { m =>
        if !(m.flags.is(Flags.Given) || m.flags.is(Flags.Implicit)) then None
        else
          val resType: Option[TypeRepr] = m.tree match
            case d: DefDef if d.paramss.isEmpty => Some(d.returnTpt.tpe)
            case v: ValDef                      => Some(v.tpt.tpe)
            case _                              => None
          resType.filter(_ <:< targetTpe).map(_ => Ref(m))
      }
    }.nextOption()
