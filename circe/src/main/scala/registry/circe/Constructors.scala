package registry.circe

import io.circe.{ACursor, CursorOp, Decoder, DecodingFailure, HCursor, Json, JsonObject}

/**
 * Metadata for a data type constructor, used to drive JSON encoding/decoding based on [[JsonOptions]].
 *
 * A direct port of aeson's `ConstructorDef`. The names are captured twice so we can apply
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

  /** Overwrite the modified names by applying the `JsonOptions` label/tag modifiers. */
  def applyOptions(options: JsonOptions, c: ConstructorDef): ConstructorDef =
    c.copy(
      modifiedConstructorName = options.constructorTagModifier(c.constructorName),
      modifiedFieldNames = c.fieldNames.map(options.fieldLabelModifier)
    )

/**
 * Encoded constructor data passed to a [[ConstructorEncoder]]: the constructor's own name, the names
 * of all constructors for the type, and the already-encoded field values.
 *
 * A direct port of aeson's `FromConstructor`. The list of values pairs with the list of field names
 * (when the constructor is a record) or is shorter/unnamed (when the constructor is positional).
 */
final case class FromConstructor(
    constructorNames: List[String],
    constructorTypes: List[String],
    constructorName: String,
    fieldNames: List[String],
    values: List[Json]
)

/** Field metadata carried with a decoded JSON value (so error messages can include the field name and type). */
type FieldDef = (String, String)

/**
 * Data extracted from a JSON value for a particular constructor, ready to be handed back to the
 * `makeDecoder` macro's constructor-matching case.
 *
 * A direct port of aeson's `ToConstructor`. The per-field cursor carries circe's `CursorOp` history
 * so downstream `DecodingFailure`s can be displayed with the correct JSON path.
 */
final case class ToConstructor(
    constructorName: String,
    values: List[(Option[FieldDef], ACursor)]
)

/**
 * Produces a `Json` value from a [[FromConstructor]] using the given [[JsonOptions]]. Registered in a
 * registry so users can override the default behavior contextually.
 */
final case class ConstructorEncoder(encodeConstructor: (JsonOptions, FromConstructor) => Json)

/**
 * Extracts one or more [[ToConstructor]] candidates from a JSON value given the full list of
 * [[ConstructorDef]]s. Registered in a registry so users can override the default behavior contextually.
 */
final case class ConstructorsDecoder(decodeConstructors: (
    JsonOptions,
    List[ConstructorDef],
    HCursor
) => Either[DecodingFailure, List[ToConstructor]])

object ConstructorEncoder:
  val default: ConstructorEncoder = ConstructorEncoder(makeEncoderFromConstructor)

  /**
   * Core encoding logic: pick the right JSON shape from the options and the constructor data.
   * Mirrors Haskell's `makeEncoderFromConstructor` branch-by-branch.
   */
  def makeEncoderFromConstructor(options: JsonOptions, fc: FromConstructor): Json =
    val adjusted = modifyFromConstructorWithOptions(options, fc)
    (adjusted.constructorNames, adjusted.constructorTypes, adjusted.fieldNames, adjusted.values) match
      // pure enumeration type (every constructor is nullary — i.e. no field types at all in the sum)
      case (_, Nil, Nil, Nil) =>
        if options.allNullaryToStringTag then Json.fromString(adjusted.constructorName)
        else makeSumEncoding(options, adjusted)
      // single constructor in the whole type
      case (_ :: Nil, _, names, values) =>
        if options.tagSingleConstructors then
          (names, values) match
            case (_, v :: Nil) if options.sumEncoding == SumEncoding.UntaggedValue && options.unwrapUnaryRecords => v
            case _ => makeSumEncoding(options, adjusted)
        else
          values match
            case (v :: Nil) =>
              if options.unwrapUnaryRecords || names.isEmpty then v
              else valuesToObject(names, values)
            case vs =>
              if names.isEmpty then Json.arr(vs*)
              else valuesToObject(names, vs)
      // sum constructor (mixed constructors, or all-nullary but options say tag them)
      case _ =>
        makeSumEncoding(options, adjusted)

  private def makeSumEncoding(options: JsonOptions, fc: FromConstructor): Json =
    val tag = fc.constructorName
    val fieldNames = fc.fieldNames
    val values = fc.values
    options.sumEncoding match
      case SumEncoding.UntaggedValue =>
        if fieldNames.isEmpty then
          values match
            case Nil      => Json.fromString(tag)
            case v :: Nil => v
            case vs       => Json.arr(vs*)
        else
          values match
            case v :: Nil if options.unwrapUnaryRecords => v
            case _                                      => valuesToObject(fieldNames, values)

      case SumEncoding.TwoElemArray =>
        if fieldNames.isEmpty then
          values match
            case Nil      => Json.fromString(tag)
            case v :: Nil => Json.arr(Json.fromString(tag), v)
            case vs       => Json.arr(Json.fromString(tag), Json.arr(vs*))
        else
          values match
            case v :: Nil if options.unwrapUnaryRecords =>
              Json.arr(Json.fromString(tag), v)
            case _ =>
              Json.arr(Json.fromString(tag), valuesToObject(fieldNames, values))

      case SumEncoding.ObjectWithSingleField =>
        if fieldNames.isEmpty then
          values match
            case Nil      => Json.fromString(tag)
            case v :: Nil => Json.fromJsonObject(JsonObject.singleton(tag, v))
            case vs       => Json.fromJsonObject(JsonObject.singleton(tag, Json.arr(vs*)))
        else
          values match
            case v :: Nil if options.unwrapUnaryRecords =>
              Json.fromJsonObject(JsonObject.singleton(tag, v))
            case _ =>
              Json.fromJsonObject(JsonObject.singleton(tag, valuesToObject(fieldNames, values)))

      case SumEncoding.TaggedObject(tagFieldName, contentsFieldName) =>
        if values.isEmpty then
          Json.fromJsonObject(JsonObject.singleton(tagFieldName, Json.fromString(tag)))
        else if fieldNames.isEmpty then
          values match
            case v :: Nil =>
              Json.fromJsonObject(
                JsonObject.fromIterable(
                  List(tagFieldName -> Json.fromString(tag), contentsFieldName -> v)
                )
              )
            case vs =>
              Json.fromJsonObject(
                JsonObject.fromIterable(
                  List(tagFieldName -> Json.fromString(tag), contentsFieldName -> Json.arr(vs*))
                )
              )
        else
          val pairs = (tagFieldName -> Json.fromString(tag)) :: fieldNames.zip(values)
          Json.fromJsonObject(JsonObject.fromIterable(pairs))

  /** Apply modifiers to the constructor name and field names, and drop null fields when requested. */
  private def modifyFromConstructorWithOptions(options: JsonOptions, fc: FromConstructor): FromConstructor =
    val (fn, fv) =
      if options.omitNothingFields && fc.fieldNames.length == fc.values.length then
        val kept = fc.fieldNames.zip(fc.values).filterNot((_, v) => v.isNull)
        (kept.map(_._1), kept.map(_._2))
      else (fc.fieldNames, fc.values)
    fc.copy(
      constructorName = options.constructorTagModifier(fc.constructorName),
      fieldNames = fn.map(options.fieldLabelModifier),
      values = fv
    )

  /** Build a JSON object from a list of field names + values. */
  private def valuesToObject(fieldNames: List[String], values: List[Json]): Json =
    Json.fromJsonObject(JsonObject.fromIterable(fieldNames.zip(values)))

object ConstructorsDecoder:
  val default: ConstructorsDecoder = ConstructorsDecoder(makeToConstructors)

  /**
   * Core decoding logic: identify which constructor the JSON represents and pull out the field values.
   * Mirrors Haskell's `makeToConstructors` branch-by-branch.
   */
  def makeToConstructors(
      options: JsonOptions,
      cs: List[ConstructorDef],
      cursor: HCursor
  ): Either[DecodingFailure, List[ToConstructor]] =
    val constructors = cs.map(c => ConstructorDef.applyOptions(options, c))
    val isEnumeration = constructors.forall(_.fieldTypes.isEmpty)
    val value = cursor.value

    if isEnumeration && options.allNullaryToStringTag then
      value.asString match
        case Some(name) =>
          constructors.find(_.modifiedConstructorName == name) match
            case Some(c) => Right(List(ToConstructor(c.constructorName, Nil)))
            case None =>
              fail(cursor,
                s"expected one of ${constructors.map(_.modifiedConstructorName).mkString(", ")}. Got: \"$name\""
              )
        case None =>
          fail(cursor,
            s"expected one of ${constructors.map(_.constructorName).mkString(", ")}. Got: ${encodeAsText(value)}"
          )
    else
      constructors match
        // single constructor, no tagging required (and not a nullary that must be tagged)
        case c :: Nil if !options.tagSingleConstructors && !(isEnumeration && !options.allNullaryToStringTag) =>
          makeToConstructorFromValue(options, c, cursor).map(List(_))
        case _ =>
          checkSumEncoding(options, constructors, cursor) match
            case Some(err) => Left(err)
            case None =>
              options.sumEncoding match
                case SumEncoding.TaggedObject(tag, contents) =>
                  makeTaggedObject(options, tag, contents, constructors, cursor).map(List(_))
                case SumEncoding.UntaggedValue =>
                  makeUntaggedValue(options, constructors, cursor)
                case SumEncoding.ObjectWithSingleField =>
                  makeObjectWithSingleField(options, constructors, cursor).map(List(_))
                case SumEncoding.TwoElemArray =>
                  makeTwoElemArray(options, constructors, cursor).map(List(_))

  private def makeTaggedObject(
      options: JsonOptions,
      tagFieldName: String,
      contentsFieldName: String,
      constructors: List[ConstructorDef],
      cursor: HCursor
  ): Either[DecodingFailure, ToConstructor] =
    tryConstructors(cursor, constructors): c =>
      cursor.value.asObject match
        case Some(vs) =>
          vs(tagFieldName) match
            case Some(tagValue) =>
              val matches = tagValue.asString.contains(c.modifiedConstructorName)
              (c.modifiedFieldNames, c.fieldNames, c.fieldTypes) match
                // constructor with no fields
                case (Nil, Nil, Nil) if matches =>
                  Right(ToConstructor(c.constructorName, Nil))
                // constructor with one unnamed field
                case (Nil, Nil, _ :: Nil) if matches =>
                  vs(contentsFieldName) match
                    case Some(_) => Right(ToConstructor(c.constructorName, List((None, cursor.downField(contentsFieldName)))))
                    case None    => fail(cursor, s"field $contentsFieldName not found")
                // constructor with one named field
                case (mfn :: Nil, fn :: Nil, ft :: Nil) if matches =>
                  vs(mfn) match
                    case Some(_) => Right(ToConstructor(c.constructorName, List((Some((fn, ft)), cursor.downField(mfn)))))
                    case None    => fail(cursor, s"field $mfn not found")
                // omitNothingFields fallback
                case (_, _, _)
                    if matches && options.omitNothingFields &&
                      vs.keys.exists(k => c.modifiedFieldNames.contains(k)) =>
                  val rest = vs.filterKeys(_ != tagFieldName)
                  makeToConstructorFromValue(options, c, Json.fromJsonObject(rest).hcursor)
                // several named fields
                case (_, _ :: _, _) if matches =>
                  makeToConstructorFromValue(options, c, cursor)
                // no named fields
                case (_, _, _) if matches && vs.keys.exists(_ == contentsFieldName) =>
                  vs(contentsFieldName) match
                    case Some(_) =>
                      val contentsCursor = cursor.downField(contentsFieldName)
                      contentsCursor.success match
                        case Some(hc) => makeToConstructorFromValue(options, c, hc)
                        case None     => fail(cursor, s"contents field not found '$contentsFieldName'")
                    case None => fail(cursor, s"contents field not found '$contentsFieldName'")
                case _ =>
                  fail(cursor, s"failed to instantiate constructor: $c")
            case None =>
              fail(cursor, s"failed to instantiate constructor: $c. tag field not found: $tagFieldName")
        case None =>
          fail(cursor, s"failed to instantiate constructor: $c. Expected an Object")

  private def makeUntaggedValue(
      options: JsonOptions,
      constructors: List[ConstructorDef],
      cursor: HCursor
  ): Either[DecodingFailure, List[ToConstructor]] =
    val attempts = constructors.map(c => makeToConstructorFromValue(options, c, cursor))
    val (lefts, rights) = partitionEithers(attempts)
    (lefts, rights) match
      case (errs, Nil) =>
        if errs.isEmpty then Left(DecodingFailure("no constructors", cursor.history))
        else Left(errs.head)
      case (_, rs) => Right(rs)

  private def makeObjectWithSingleField(
      options: JsonOptions,
      constructors: List[ConstructorDef],
      cursor: HCursor
  ): Either[DecodingFailure, ToConstructor] =
    tryConstructors(cursor, constructors): c =>
      cursor.value.asObject match
        case Some(obj) if obj.size == 1 =>
          val (key, _) = obj.toList.head
          if key == c.modifiedConstructorName then
            val inner = cursor.downField(key)
            inner.success match
              case Some(hc) => makeToConstructorFromValue(options, c, hc)
              case None     => fail(cursor, s"failed to instantiate constructor: $c")
          else fail(cursor, s"failed to instantiate constructor: $c")
        case _ =>
          cursor.value.asString match
            case Some(v) if v == c.modifiedConstructorName => makeToConstructorFromValue(options, c, cursor)
            case _                                         => fail(cursor, s"failed to instantiate constructor: $c")

  private def makeTwoElemArray(
      options: JsonOptions,
      constructors: List[ConstructorDef],
      cursor: HCursor
  ): Either[DecodingFailure, ToConstructor] =
    tryConstructors(cursor, constructors): c =>
      cursor.value.asArray match
        case Some(arr) if arr.sizeIs == 2 =>
          arr(0).asString match
            case Some(tag) if tag == c.modifiedConstructorName =>
              cursor.downN(1).success match
                case Some(hc) => makeToConstructorFromValue(options, c, hc)
                case None     => fail(cursor, s"failed to instantiate constructor: $c")
            case _ => fail(cursor, s"failed to instantiate constructor: $c")
        case _ =>
          cursor.value.asString match
            case Some(v) if v == c.modifiedConstructorName => makeToConstructorFromValue(options, c, cursor)
            case _                                         => fail(cursor, s"failed to instantiate constructor: $c")

  /** Structural pre-check: the JSON value has the right *shape* for the configured [[SumEncoding]]. */
  private def checkSumEncoding(
      options: JsonOptions,
      constructors: List[ConstructorDef],
      cursor: HCursor
  ): Option[DecodingFailure] =
    val tags = constructors.map(_.modifiedConstructorName)
    val value = cursor.value
    options.sumEncoding match
      case SumEncoding.TaggedObject(tagFieldName, _) =>
        value.asObject match
          case Some(obj) =>
            obj(tagFieldName) match
              case None =>
                Some(DecodingFailure(s"tag field '$tagFieldName' not found", cursor.history))
              case Some(tagV) if tagV.asString.exists(tags.contains) => None
              case Some(tagV)                                        => unexpectedConstructor(cursor, tags, tagV)
          case None => Some(DecodingFailure("expected an Object for a TaggedObject sum encoding", cursor.history))
      case SumEncoding.UntaggedValue => None
      case SumEncoding.ObjectWithSingleField =>
        value.asObject match
          case Some(obj) if obj.size == 1 =>
            val (k, _) = obj.toList.head
            if tags.contains(k) then None else unexpectedConstructor(cursor, tags, Json.fromString(k))
          case _ =>
            value.asString match
              case Some(v) if tags.contains(v) => None
              case _ =>
                Some(DecodingFailure("expected an Object for an ObjectWithSingleField sum encoding", cursor.history))
      case SumEncoding.TwoElemArray =>
        value.asArray match
          case Some(arr) if arr.sizeIs == 2 =>
            arr(0).asString match
              case Some(v) if tags.contains(v) => None
              case _                           => unexpectedConstructor(cursor, tags, arr(0))
          case _ =>
            value.asString match
              case Some(v) if tags.contains(v) => None
              case _ =>
                Some(DecodingFailure("expected an Array with 2 elements for an TwoElemArray sum encoding", cursor.history))

  private def unexpectedConstructor(
      cursor: HCursor,
      expected: List[String],
      found: Json
  ): Option[DecodingFailure] =
    val foundText = found.asString.getOrElse(encodeAsText(found))
    Some(
      DecodingFailure(
        s"expected the tag field to be one of: ${expected.mkString(", ")}, found: $foundText",
        cursor.history
      )
    )

  /** Extract field values for a specific constructor from a JSON value. */
  private def makeToConstructorFromValue(
      options: JsonOptions,
      c: ConstructorDef,
      cursor: HCursor
  ): Either[DecodingFailure, ToConstructor] =
    val value = cursor.value
    (c.fieldNames, c.fieldTypes) match
      // no fields
      case (Nil, Nil) =>
        value.asString match
          case Some(v) =>
            if v == c.modifiedConstructorName then Right(ToConstructor(c.constructorName, Nil))
            else fail(cursor, s"incorrect constructor name, expected: ${c.modifiedConstructorName}. Got: $v")
          case None =>
            fail(cursor, s"incorrect constructor name, expected: ${c.modifiedConstructorName}. Got: ${encodeAsText(value)}")

      // one field, no field name (positional single-arg constructor) — e.g. newtype-wrapper
      case (Nil, _ :: Nil) =>
        Right(ToConstructor(c.constructorName, List((None, cursor))))

      // one field, one field name — record with a single field
      case (f :: Nil, t :: Nil) =>
        if options.unwrapUnaryRecords then Right(ToConstructor(c.constructorName, List((None, cursor))))
        else
          val mf = c.modifiedFieldNames.head
          value.asObject match
            case Some(obj) =>
              obj(mf) match
                case Some(_) =>
                  if options.rejectUnknownFields && obj.size > 1 then
                    val unknown = obj.keys.filter(_ != mf).toList
                    fail(cursor, s"unknown field${plural(unknown)}: ${unknown.mkString(", ")}")
                  else Right(ToConstructor(c.constructorName, List((Some((f, t)), cursor.downField(mf)))))
                case None =>
                  fail(cursor, s"field '$mf' not found" + (if mf == f then "" else s" (to create field '$f')"))
            case None =>
              fail(cursor, s"expected an object with field '$mf" + (if mf == f then "" else s" (to create field '$f')"))

      // positional constructor with multiple unnamed fields
      case (Nil, _) =>
        value.asArray match
          case Some(arr) =>
            val values = arr.toList.zipWithIndex.map((_, i) => (None: Option[FieldDef], cursor.downN(i)))
            Right(ToConstructor(c.constructorName, values))
          case None => Right(ToConstructor(c.constructorName, List((None, cursor))))

      // several fields, with names
      case _ =>
        val mfn = c.modifiedFieldNames
        val fieldTypes = c.fieldTypes
        val fieldNames = c.fieldNames
        value.asObject match
          case Some(obj) =>
            val objKeys = obj.keys.toList
            val fieldsNotFound = mfn.diff(objKeys)
            if !options.omitNothingFields && fieldsNotFound.nonEmpty then
              fieldsNotFound match
                case f :: Nil => fail(cursor, s"field '$f' not found")
                case fs       => fail(cursor, s"fields  not found: ${fs.mkString(",")}")
            else
              val tagNames = options.sumEncoding match
                case SumEncoding.TaggedObject(t, con) => List(t, con)
                case _                                => Nil
              val unknown = objKeys.diff(mfn).diff(tagNames)
              if options.rejectUnknownFields && unknown.nonEmpty then
                fail(cursor, s"unknown field${plural(unknown)}: ${unknown.mkString(", ")}")
              else
                val fields = fieldNames.zip(fieldTypes).zip(mfn)
                val vs = fields.flatMap { case ((fieldName, fieldType), modifiedFieldName) =>
                  obj(modifiedFieldName) match
                    case Some(_) => Some((Some((fieldName, fieldType)), cursor.downField(modifiedFieldName)))
                    case None =>
                      if options.omitNothingFields && fieldType.startsWith("Option") then
                        Some((Some((fieldName, fieldType)), Json.Null.hcursor: ACursor))
                      else None
                }
                Right(ToConstructor(c.constructorName, vs))
          case None =>
            value.asArray match
              case Some(arr) =>
                val values = arr.toList.zipWithIndex.map((_, i) => (None: Option[FieldDef], cursor.downN(i)))
                Right(ToConstructor(c.constructorName, values))
              case None =>
                Right(ToConstructor(c.constructorName, List((None, cursor))))

  private def tryConstructors(
      cursor: HCursor,
      constructors: List[ConstructorDef]
  )(f: ConstructorDef => Either[DecodingFailure, ToConstructor]): Either[DecodingFailure, ToConstructor] =
    foldEither(cursor, constructors.map(f))

  private def foldEither[A](
      cursor: HCursor,
      es: List[Either[DecodingFailure, A]]
  ): Either[DecodingFailure, A] =
    val (ls, rs) = partitionEithers(es)
    (ls, rs) match
      case (Nil, Nil)    => Left(DecodingFailure("no results", cursor.history))
      case (errors, Nil) =>
        Left(DecodingFailure(errors.map(_.message).mkString(" ->> "), cursor.history))
      case (_, r :: _) => Right(r)

  private def partitionEithers[A](es: List[Either[DecodingFailure, A]]): (List[DecodingFailure], List[A]) =
    val lefts = es.collect { case Left(e) => e }
    val rights = es.collect { case Right(a) => a }
    (lefts, rights)

  private def plural[A](as: List[A]): String = if as.sizeIs > 1 then "s" else ""

  private def fail[A](cursor: HCursor, msg: String): Either[DecodingFailure, A] =
    Left(DecodingFailure(msg, cursor.history))

/** Shared helper: render a JSON value as compact text. */
private[circe] def encodeAsText(j: Json): String = j.noSpaces

/** Decode a field with a given [[Decoder]], using the constructor + type metadata to prefix error messages. */
def decodeFieldValue[A](
    d: Decoder[A],
    typeName: String,
    constructorName: String,
    field: (Option[FieldDef], ACursor)
): Decoder.Result[A] =
  val (fieldDef, c) = field
  d.tryDecode(c) match
    case Right(a) => Right(a)
    case Left(df) =>
      val constructor = if typeName == constructorName then "" else s"($constructorName) "
      val fieldPart = fieldDef match
        case Some((fn, ft)) => s"$constructor'$fn :: $ft' >> "
        case None           => constructor
      Left(DecodingFailure(fieldPart + df.message, df.history))

/**
 * Drive a decode by trying every constructor definition, returning the first successful `ToConstructor`
 * whose `build` function succeeds — or an aggregated error otherwise.
 */
def decodeFromDefinitions[A](
    options: JsonOptions,
    cd: ConstructorsDecoder,
    defs: List[ConstructorDef],
    cursor: HCursor,
    build: ToConstructor => Decoder.Result[A]
): Decoder.Result[A] =
  cd.decodeConstructors(options, defs, cursor) match
    case Left(e) => Left(e)
    case Right(toConstructors) =>
      val results = toConstructors.map(build)
      results.collectFirst { case Right(a) => a } match
        case Some(a) => Right(a)
        case None =>
          results match
            case Nil => Left(DecodingFailure("no results", cursor.history))
            case lefts =>
              val message = lefts.collect { case Left(e) => e.message }.mkString(" ->> ")
              Left(DecodingFailure(message, cursor.history))
