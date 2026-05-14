package registry.cbor

import io.bullet.borer.{Dom, Tag}
import io.bullet.borer.Dom.*

/** Lightweight error type used by the CBOR `Constructors` machinery. */
final case class CborError(message: String, path: List[String] = Nil):
  def withPath(seg: String): CborError = copy(path = seg :: path)

  override def toString: String =
    if path.isEmpty then message else s"${path.reverse.mkString(".")}: $message"

object CborError:
  def apply(message: String): CborError = new CborError(message, Nil)

/**
 * Metadata for a data type constructor, used to drive CBOR encoding/decoding based on [[CborOptions]].
 *
 * Direct analog of the circe `ConstructorDef`. Names are captured twice so we can apply
 * `constructorTagModifier` / `fieldLabelModifier` at option-application time while still knowing the
 * original (unmodified) names for Scala-level reflection.
 */
final case class ConstructorDef(
    constructorName: String,
    modifiedConstructorName: String,
    fieldNames: List[String],
    modifiedFieldNames: List[String],
    fieldTypes: List[String]
)

object ConstructorDef:

  /** Build a [[ConstructorDef]] with identity-modified names (they get rewritten later via `applyOptions`). */
  def apply(name: String, fields: List[String], types: List[String]): ConstructorDef =
    ConstructorDef(name, name, fields, fields, types)

  /** Overwrite the modified names by applying the `CborOptions` label/tag modifiers. */
  def applyOptions(options: CborOptions, c: ConstructorDef): ConstructorDef =
    c.copy(
      modifiedConstructorName = options.constructorTagModifier(c.constructorName),
      modifiedFieldNames = c.fieldNames.map(options.fieldLabelModifier)
    )

/**
 * Encoded constructor data passed to a [[ConstructorEncoder]]. Mirrors the circe analog with an
 * additional `constructorIndex` so integer-tag sum encodings can emit the constructor's position.
 */
final case class FromConstructor(
    constructorNames: List[String],
    constructorTypes: List[String],
    constructorName: String,
    constructorIndex: Int,
    fieldNames: List[String],
    values: List[Element]
)

/** Field metadata carried with a decoded CBOR value (so error messages can include the field name and type). */
type FieldDef = (String, String)

/** Data extracted from a CBOR value for a particular constructor, ready to be handed to the `decoder` macro. */
final case class ToConstructor(
    constructorName: String,
    values: List[(Option[FieldDef], Element)]
)

/**
 * Produces a `Dom.Element` from a [[FromConstructor]] using the given [[CborOptions]]. Registered in a
 * registry so users can override the default behavior contextually.
 */
final case class ConstructorEncoder(encodeConstructor: (CborOptions, FromConstructor) => Element)

/**
 * Extracts one or more [[ToConstructor]] candidates from a `Dom.Element` given the full list of
 * [[ConstructorDef]]s. Registered in a registry so users can override the default behavior contextually.
 */
final case class ConstructorsDecoder(decodeConstructors: (
    CborOptions,
    List[ConstructorDef],
    Element
) => Either[CborError, List[ToConstructor]])

object ConstructorEncoder:
  val default: ConstructorEncoder = ConstructorEncoder(makeEncoderFromConstructor)

  /** Core encoding logic: pick the right CBOR shape from the options and the constructor data. */
  def makeEncoderFromConstructor(options: CborOptions, fc: FromConstructor): Element =
    val adjusted = modifyFromConstructorWithOptions(options, fc)
    (adjusted.constructorNames, adjusted.constructorTypes, adjusted.fieldNames, adjusted.values) match
      // pure enumeration type (every constructor is nullary)
      case (_, Nil, Nil, Nil) =>
        if options.allNullaryToTag then nullaryTagElem(options, adjusted)
        else makeSumEncoding(options, adjusted)
      // single constructor in the whole type
      case (_ :: Nil, _, names, values) =>
        if options.tagSingleConstructors then
          (names, values) match
            case (_, v :: Nil) if options.sumEncoding == SumEncoding.Untagged && options.unwrapUnaryRecords => v
            case _ => makeSumEncoding(options, adjusted)
        else
          values match
            case v :: Nil =>
              if options.unwrapUnaryRecords || names.isEmpty then v
              else valuesToMap(options, names, values)
            case vs =>
              if names.isEmpty then arrayElem(vs)
              else valuesToMap(options, names, vs)
      // sum constructor
      case _ =>
        makeSumEncoding(options, adjusted)

  /** Pure-enumeration tag element: integer or string depending on `constructorTagMode`. */
  private def nullaryTagElem(options: CborOptions, fc: FromConstructor): Element =
    options.constructorTagMode match
      case ConstructorTagMode.IntegerTags => IntElem(fc.constructorIndex)
      case ConstructorTagMode.StringTags  => StringElem(fc.constructorName)

  /** Tag element for a non-nullary sum (TwoElemArray / SingleKeyMap path). */
  private def sumTagElem(options: CborOptions, fc: FromConstructor): Element =
    options.constructorTagMode match
      case ConstructorTagMode.IntegerTags => IntElem(fc.constructorIndex)
      case ConstructorTagMode.StringTags  => StringElem(fc.constructorName)

  private def makeSumEncoding(options: CborOptions, fc: FromConstructor): Element =
    val tagElem = sumTagElem(options, fc)
    val fieldNames = fc.fieldNames
    val values = fc.values
    options.sumEncoding match
      case SumEncoding.Untagged =>
        if fieldNames.isEmpty then
          values match
            case Nil      => nullaryTagElem(options, fc)
            case v :: Nil => v
            case vs       => arrayElem(vs)
        else
          values match
            case v :: Nil if options.unwrapUnaryRecords => v
            case _                                      => valuesToMap(options, fieldNames, values)

      case SumEncoding.TwoElemArray =>
        if fieldNames.isEmpty then
          values match
            case Nil      => nullaryTagElem(options, fc)
            case v :: Nil => arrayElem(List(tagElem, v))
            case vs       => arrayElem(List(tagElem, arrayElem(vs)))
        else
          values match
            case v :: Nil if options.unwrapUnaryRecords =>
              arrayElem(List(tagElem, v))
            case _ =>
              arrayElem(List(tagElem, valuesToMap(options, fieldNames, values)))

      case SumEncoding.SingleKeyMap =>
        if fieldNames.isEmpty then
          values match
            case Nil      => nullaryTagElem(options, fc)
            case v :: Nil => MapElem.Sized(tagElem -> v)
            case vs       => MapElem.Sized(tagElem -> arrayElem(vs))
        else
          values match
            case v :: Nil if options.unwrapUnaryRecords =>
              MapElem.Sized(tagElem -> v)
            case _ =>
              MapElem.Sized(tagElem -> valuesToMap(options, fieldNames, values))

      case SumEncoding.CborTagged(baseTagNumber) =>
        val tagNum = baseTagNumber + fc.constructorIndex.toLong
        val inner: Element =
          if fieldNames.isEmpty then
            values match
              case Nil      => nullaryTagElem(options, fc)
              case v :: Nil => v
              case vs       => arrayElem(vs)
          else
            values match
              case v :: Nil if options.unwrapUnaryRecords => v
              case _                                      => valuesToMap(options, fieldNames, values)
        TaggedElem(Tag.Other(tagNum), inner)

  /**
   * Apply modifiers to the constructor name and field names. Note we deliberately do NOT drop null
   * fields here even when `omitNothingFields=true` — that happens inside [[valuesToMap]] so the
   * remaining fields' positions are preserved (critical for integer-keyed maps).
   */
  private def modifyFromConstructorWithOptions(options: CborOptions, fc: FromConstructor): FromConstructor =
    fc.copy(
      constructorName = options.constructorTagModifier(fc.constructorName),
      fieldNames = fc.fieldNames.map(options.fieldLabelModifier)
    )

  /**
   * Build a CBOR map from a list of (modified) field names + values, with keys per `fieldKeyMode`.
   * When `omitNothingFields=true`, nullish entries are dropped AFTER positions are assigned so the
   * remaining integer keys still reflect their original field position.
   */
  private def valuesToMap(options: CborOptions, fieldNames: List[String], values: List[Element]): Element =
    val pairs: List[(Element, Element)] = options.fieldKeyMode match
      case FieldKeyMode.IntegerKeys =>
        values.zipWithIndex.map((v, i) => (IntElem(i): Element) -> v)
      case FieldKeyMode.StringKeys =>
        fieldNames.zip(values).map((k, v) => (StringElem(k): Element) -> v)
    val filtered =
      if options.omitNothingFields then pairs.filterNot((_, v) => isNullish(v))
      else pairs
    MapElem.Sized(filtered*)

  private def arrayElem(vs: List[Element]): Element = ArrayElem.Sized(vs*)

  private def isNullish(e: Element): Boolean = e match
    case NullElem      => true
    case UndefinedElem => true
    case _             => false

object ConstructorsDecoder:
  val default: ConstructorsDecoder = ConstructorsDecoder(makeToConstructors)

  /** Core decoding logic: identify which constructor the CBOR represents and pull out the field values. */
  def makeToConstructors(
      options: CborOptions,
      cs: List[ConstructorDef],
      value: Element
  ): Either[CborError, List[ToConstructor]] =
    val constructors = cs.map(c => ConstructorDef.applyOptions(options, c))
    val isEnumeration = constructors.forall(_.fieldTypes.isEmpty)

    if isEnumeration && options.allNullaryToTag then
      tagOfValue(options, value) match
        case Right(tag) =>
          findByTag(options, constructors, tag) match
            case Some(c) => Right(List(ToConstructor(c.constructorName, Nil)))
            case None =>
              Left(CborError(
                s"expected one of ${constructorTagsString(options, constructors)}. Got: ${describeElem(value)}"
              ))
        case Left(err) =>
          Left(
            CborError(s"expected one of ${constructorTagsString(options, constructors)}. Got: ${describeElem(value)}")
          )
    else
      constructors match
        case c :: Nil if !options.tagSingleConstructors && !(isEnumeration && !options.allNullaryToTag) =>
          makeToConstructorFromValue(options, c, value).map(List(_))
        case _ =>
          checkSumEncoding(options, constructors, value) match
            case Some(err) => Left(err)
            case None =>
              options.sumEncoding match
                case SumEncoding.TwoElemArray =>
                  makeTwoElemArray(options, constructors, value).map(List(_))
                case SumEncoding.Untagged =>
                  makeUntagged(options, constructors, value)
                case SumEncoding.SingleKeyMap =>
                  makeSingleKeyMap(options, constructors, value).map(List(_))
                case SumEncoding.CborTagged(baseTagNumber) =>
                  makeCborTagged(options, constructors, baseTagNumber, value).map(List(_))

  // -- Sum-encoding shape pre-check (best-effort, mirrors circe's checkSumEncoding) --

  private def checkSumEncoding(
      options: CborOptions,
      constructors: List[ConstructorDef],
      value: Element
  ): Option[CborError] =
    options.sumEncoding match
      case SumEncoding.Untagged => None
      case SumEncoding.TwoElemArray =>
        asArray(value) match
          case Some(arr) if arr.sizeIs == 2 =>
            tagOfValue(options, arr(0)) match
              case Right(tag) if findByTag(options, constructors, tag).isDefined => None
              case _ => Some(unexpectedTag(options, constructors, arr(0)))
          case _ =>
            tagOfValue(options, value).toOption.flatMap(t => findByTag(options, constructors, t)) match
              case Some(_) => None
              case None    => Some(CborError("expected an Array with 2 elements for a TwoElemArray sum encoding"))
      case SumEncoding.SingleKeyMap =>
        asMap(value) match
          case Some(m) if m.size == 1 =>
            val (k, _) = m.members.next()
            tagOfValue(options, k) match
              case Right(tag) if findByTag(options, constructors, tag).isDefined => None
              case _ => Some(unexpectedTag(options, constructors, k))
          case _ =>
            tagOfValue(options, value).toOption.flatMap(t => findByTag(options, constructors, t)) match
              case Some(_) => None
              case None    => Some(CborError("expected a single-key Map for a SingleKeyMap sum encoding"))
      case SumEncoding.CborTagged(_) =>
        value match
          case TaggedElem(_, _) => None
          case _                => Some(CborError("expected a CBOR-tagged value for a CborTagged sum encoding"))

  private def makeTwoElemArray(
      options: CborOptions,
      constructors: List[ConstructorDef],
      value: Element
  ): Either[CborError, ToConstructor] =
    asArray(value) match
      case Some(arr) if arr.sizeIs == 2 =>
        tagOfValue(options, arr(0)) match
          case Right(tag) =>
            findByTag(options, constructors, tag) match
              case Some(c) => makeToConstructorFromValue(options, c, arr(1))
              case None    => Left(unexpectedTag(options, constructors, arr(0)))
          case Left(err) => Left(err)
      case _ =>
        // fallback: nullary tag
        tryConstructors(constructors): c =>
          tagOfValue(options, value) match
            case Right(tag) if tagMatches(options, c, tag) => Right(ToConstructor(c.constructorName, Nil))
            case _                                         => Left(CborError(s"failed to instantiate constructor: $c"))

  private def makeSingleKeyMap(
      options: CborOptions,
      constructors: List[ConstructorDef],
      value: Element
  ): Either[CborError, ToConstructor] =
    asMap(value) match
      case Some(m) if m.size == 1 =>
        val (k, v) = m.members.next()
        tagOfValue(options, k) match
          case Right(tag) =>
            findByTag(options, constructors, tag) match
              case Some(c) => makeToConstructorFromValue(options, c, v)
              case None    => Left(unexpectedTag(options, constructors, k))
          case Left(err) => Left(err)
      case _ =>
        tryConstructors(constructors): c =>
          tagOfValue(options, value) match
            case Right(tag) if tagMatches(options, c, tag) => Right(ToConstructor(c.constructorName, Nil))
            case _                                         => Left(CborError(s"failed to instantiate constructor: $c"))

  private def makeCborTagged(
      options: CborOptions,
      constructors: List[ConstructorDef],
      baseTagNumber: Long,
      value: Element
  ): Either[CborError, ToConstructor] =
    value match
      case TaggedElem(t, inner) =>
        val idx = (t.code - baseTagNumber).toInt
        if idx >= 0 && idx < constructors.size then
          val c = constructors(idx)
          makeToConstructorFromValue(options, c, inner)
        else Left(CborError(s"unexpected CBOR tag ${t.code} (base=$baseTagNumber, ${constructors.size} constructors)"))
      case _ => Left(CborError("expected a CBOR-tagged value for a CborTagged sum encoding"))

  private def makeUntagged(
      options: CborOptions,
      constructors: List[ConstructorDef],
      value: Element
  ): Either[CborError, List[ToConstructor]] =
    val attempts = constructors.map(c => makeToConstructorFromValue(options, c, value))
    val rights = attempts.collect { case Right(a) => a }
    val lefts = attempts.collect { case Left(e) => e }
    if rights.nonEmpty then Right(rights)
    else if lefts.nonEmpty then Left(lefts.head)
    else Left(CborError("no constructors"))

  /** Extract field values for a specific constructor from a `Dom.Element` value. */
  private def makeToConstructorFromValue(
      options: CborOptions,
      c: ConstructorDef,
      value: Element
  ): Either[CborError, ToConstructor] =
    (c.fieldNames, c.fieldTypes) match
      // no fields — nullary
      case (Nil, Nil) =>
        tagOfValue(options, value) match
          case Right(tag) if tagMatches(options, c, tag) => Right(ToConstructor(c.constructorName, Nil))
          case _ =>
            Left(CborError(
              s"incorrect constructor name, expected: ${c.modifiedConstructorName}. Got: ${describeElem(value)}"
            ))

      // one field, no field name (positional single-arg constructor) — newtype-wrapper
      case (Nil, _ :: Nil) =>
        Right(ToConstructor(c.constructorName, List((None, value))))

      // one field, one field name — record with a single field
      case (f :: Nil, t :: Nil) =>
        if options.unwrapUnaryRecords then
          Right(ToConstructor(c.constructorName, List((None, value))))
        else
          asMap(value) match
            case Some(m) =>
              val key = fieldKeyForLookup(options, 0, c.modifiedFieldNames.head)
              lookupMapKey(m, key) match
                case Some(_) =>
                  if options.rejectUnknownFields && m.size > 1 then
                    val expected = Set(key)
                    val unknown = m.members.map(_._1).filterNot(expected.contains).toList
                    Left(CborError(s"unknown field${plural(unknown)}: ${unknown.map(describeElem).mkString(", ")}"))
                  else
                    val v = lookupMapKey(m, key).get
                    Right(ToConstructor(c.constructorName, List((Some((f, t)), v))))
                case None =>
                  Left(CborError(s"field '${describeElem(key)}' not found"))
            case None =>
              Left(CborError(
                s"expected an object with field '${describeElem(fieldKeyForLookup(options, 0, c.modifiedFieldNames.head))}'"
              ))

      // positional constructor with multiple unnamed fields
      case (Nil, _) =>
        asArray(value) match
          case Some(arr) =>
            val values = arr.toList.map(v => (None: Option[FieldDef], v))
            Right(ToConstructor(c.constructorName, values))
          case None =>
            Right(ToConstructor(c.constructorName, List((None, value))))

      // several fields with names
      case _ =>
        val mfn = c.modifiedFieldNames
        val fieldTypes = c.fieldTypes
        val fieldNames = c.fieldNames
        asMap(value) match
          case Some(m) =>
            val expectedKeys: List[Element] = mfn.zipWithIndex.map { (mf, i) =>
              fieldKeyForLookup(options, i, mf)
            }
            val foundFlags = expectedKeys.map(k => lookupMapKey(m, k).isDefined)
            val missing = expectedKeys.zip(foundFlags).collect { case (k, false) => k }
            if !options.omitNothingFields && missing.nonEmpty then
              missing match
                case k :: Nil => Left(CborError(s"field '${describeElem(k)}' not found"))
                case ks       => Left(CborError(s"fields not found: ${ks.map(describeElem).mkString(", ")}"))
            else
              if options.rejectUnknownFields then
                val expectedSet = expectedKeys.toSet
                val unknown = m.members.map(_._1).filterNot(expectedSet.contains).toList
                if unknown.nonEmpty then
                  Left(CborError(s"unknown field${plural(unknown)}: ${unknown.map(describeElem).mkString(", ")}"))
                else extractFields(c, expectedKeys, m, options)
              else extractFields(c, expectedKeys, m, options)
          case None =>
            asArray(value) match
              case Some(arr) =>
                val values = arr.toList.map(v => (None: Option[FieldDef], v))
                Right(ToConstructor(c.constructorName, values))
              case None =>
                Right(ToConstructor(c.constructorName, List((None, value))))

  private def extractFields(
      c: ConstructorDef,
      expectedKeys: List[Element],
      m: MapElem,
      options: CborOptions
  ): Either[CborError, ToConstructor] =
    val fields = c.fieldNames.zip(c.fieldTypes).zip(expectedKeys)
    val vs = fields.flatMap { case ((fieldName, fieldType), key) =>
      lookupMapKey(m, key) match
        case Some(v) => Some((Some((fieldName, fieldType)), v))
        case None =>
          if options.omitNothingFields && isOptionType(fieldType) then Some((Some((fieldName, fieldType)), NullElem))
          else None
    }
    Right(ToConstructor(c.constructorName, vs))

  // -- helpers --

  private def fieldKeyForLookup(options: CborOptions, position: Int, modifiedName: String): Element =
    options.fieldKeyMode match
      case FieldKeyMode.IntegerKeys => IntElem(position)
      case FieldKeyMode.StringKeys  => StringElem(modifiedName)

  /** Look up an element in a `MapElem`, tolerating Int/Long key encoding differences. */
  private[cbor] def lookupMapKey(m: MapElem, key: Element): Option[Element] =
    val target = key match
      case IntElem(i)  => Some(i.toLong)
      case LongElem(l) => Some(l)
      case _           => None
    m.members.collectFirst {
      case (k, v) if k == key                           => v
      case (IntElem(i), v) if target.contains(i.toLong) => v
      case (LongElem(l), v) if target.contains(l)       => v
    }

  /** Extract the tag value from a CBOR element. Returns either an int-tag (Long) or a string-tag. */
  private def tagOfValue(options: CborOptions, value: Element): Either[CborError, Either[Long, String]] =
    value match
      case IntElem(i)    => Right(Left(i.toLong))
      case LongElem(l)   => Right(Left(l))
      case StringElem(s) => Right(Right(s))
      case _             => Left(CborError(s"expected a tag (int or string), got: ${describeElem(value)}"))

  private def findByTag(
      options: CborOptions,
      constructors: List[ConstructorDef],
      tag: Either[Long, String]
  ): Option[ConstructorDef] =
    val withIndex = constructors.zipWithIndex
    options.constructorTagMode match
      case ConstructorTagMode.IntegerTags =>
        tag match
          case Left(i)  => withIndex.find(_._2 == i.toInt).map(_._1)
          case Right(_) => None
      case ConstructorTagMode.StringTags =>
        tag match
          case Right(s) => constructors.find(_.modifiedConstructorName == s)
          case Left(_)  => None

  private def tagMatches(options: CborOptions, c: ConstructorDef, tag: Either[Long, String]): Boolean =
    options.constructorTagMode match
      case ConstructorTagMode.IntegerTags =>
        tag match
          case Left(_)  => true // index match is handled by findByTag
          case Right(_) => false
      case ConstructorTagMode.StringTags =>
        tag match
          case Right(s) => s == c.modifiedConstructorName
          case Left(_)  => false

  private def constructorTagsString(options: CborOptions, cs: List[ConstructorDef]): String =
    options.constructorTagMode match
      case ConstructorTagMode.IntegerTags => cs.indices.mkString(", ")
      case ConstructorTagMode.StringTags  => cs.map(_.modifiedConstructorName).mkString(", ")

  private def unexpectedTag(
      options: CborOptions,
      cs: List[ConstructorDef],
      found: Element
  ): CborError =
    CborError(s"expected the tag to be one of: ${constructorTagsString(options, cs)}, found: ${describeElem(found)}")

  private def tryConstructors[A](
      constructors: List[ConstructorDef]
  )(f: ConstructorDef => Either[CborError, A]): Either[CborError, A] =
    val results = constructors.map(f)
    results.collectFirst { case Right(a) => a } match
      case Some(a) => Right(a)
      case None =>
        val errs = results.collect { case Left(e) => e }
        if errs.isEmpty then Left(CborError("no constructors"))
        else Left(CborError(errs.map(_.message).mkString(" ->> ")))

  private def asMap(e: Element): Option[MapElem] = e match
    case m: MapElem => Some(m)
    case _          => None

  private def asArray(e: Element): Option[Vector[Element]] = e match
    case ArrayElem.Sized(v)   => Some(v)
    case ArrayElem.Unsized(v) => Some(v)
    case _                    => None

  private def plural[A](as: List[A]): String = if as.sizeIs > 1 then "s" else ""

  /** Recognize `Option`-typed fields from the macro's `typeDisplayName` output (FQN-based). */
  private def isOptionType(fieldType: String): Boolean =
    fieldType.startsWith("scala.Option") || fieldType.startsWith("Option")

/** Render a `Dom.Element` value as a short string for error messages. */
private[cbor] def describeElem(e: Element): String = e match
  case NullElem             => "null"
  case UndefinedElem        => "undefined"
  case BooleanElem(v)       => v.toString
  case IntElem(i)           => i.toString
  case LongElem(l)          => l.toString
  case StringElem(s)        => s"\"$s\""
  case ArrayElem.Sized(v)   => v.map(describeElem).mkString("[", ",", "]")
  case ArrayElem.Unsized(v) => v.map(describeElem).mkString("[", ",", "]")
  case _: MapElem           => "{...}"
  case TaggedElem(t, _)     => s"tag(${t.code})"
  case other                => other.toString

/**
 * Decode a field with a given [[io.bullet.borer.Decoder]], using the constructor + type metadata to
 * prefix error messages.
 */
def decodeFieldValue[A](
    d: io.bullet.borer.Decoder[A],
    typeName: String,
    constructorName: String,
    field: (Option[FieldDef], Element)
): Either[CborError, A] =
  val (fieldDef, e) = field
  decodeElement(d, e) match
    case Right(a) => Right(a)
    case Left(msg) =>
      val constructor = if typeName == constructorName then "" else s"($constructorName) "
      val fieldPart = fieldDef match
        case Some((fn, ft)) => s"$constructor'$fn :: $ft' >> "
        case None           => constructor
      Left(CborError(fieldPart + msg))

/** Decode a single `Dom.Element` with the given borer `Decoder`, materializing CBOR bytes in the middle. */
private[cbor] def decodeElement[A](d: io.bullet.borer.Decoder[A], e: Element): Either[String, A] =
  io.bullet.borer.Cbor.encode(e).toByteArrayEither match
    case Left(err) => Left(err.toString)
    case Right(bs) =>
      io.bullet.borer.Cbor.decode(bs).to[A](using d).valueEither match
        case Right(a)  => Right(a)
        case Left(err) => Left(err.toString)

/**
 * Drive a decode by trying every constructor definition, returning the first successful `ToConstructor`
 * whose `build` function succeeds — or an aggregated error otherwise.
 */
def decodeFromDefinitions[A](
    options: CborOptions,
    cd: ConstructorsDecoder,
    defs: List[ConstructorDef],
    value: Element,
    build: ToConstructor => Either[CborError, A]
): Either[CborError, A] =
  cd.decodeConstructors(options, defs, value) match
    case Left(e) => Left(e)
    case Right(toConstructors) =>
      val results = toConstructors.map(build)
      results.collectFirst { case Right(a) => a } match
        case Some(a) => Right(a)
        case None =>
          val errs = results.collect { case Left(e) => e }
          if errs.isEmpty then Left(CborError("no results"))
          else Left(CborError(errs.map(_.message).mkString(" ->> ")))
