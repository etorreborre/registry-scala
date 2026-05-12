package registry.circe

/** How values of a sum type (sealed trait / enum with multiple cases) are encoded. */
enum SumEncoding:
  /** `{"tag": "ConstructorName", "field1": ..., "field2": ...}` — or `contents` for unnamed fields. */
  case TaggedObject(tagFieldName: String, contentsFieldName: String)

  /** The tag is not present in the output; constructors are disambiguated by shape alone (decoding tries each). */
  case UntaggedValue

  /** `{"ConstructorName": {...}}` — single-key object where the key is the constructor name. */
  case ObjectWithSingleField

  /** `["ConstructorName", {...}]` — 2-element array [tag, contents]. */
  case TwoElemArray

/**
 * Options controlling how generated encoders/decoders shape the JSON output.
 *
 * Direct port of aeson's `Data.Aeson.Options`, with the same default values.
 *
 * The `encoder[T]` / `decoder[T]` macros emit the fully-qualified type name for each constructor
 * (e.g. `com.acme.MyType.Foo`). `constructorTagModifier` is the single hook for shortening, renaming,
 * or otherwise transforming that name before it lands in the JSON tag. The default is
 * [[JsonOptions.dropQualifier]] which keeps only the last segment, matching aeson's behavior. Override
 * with `identity` to keep the FQN, or with [[JsonOptions.lastTwoSegments]] for `MyType.Foo`-style names.
 */
final case class JsonOptions(
    fieldLabelModifier: String => String = identity,
    constructorTagModifier: String => String = JsonOptions.dropQualifier,
    allNullaryToStringTag: Boolean = true,
    omitNothingFields: Boolean = false,
    sumEncoding: SumEncoding = SumEncoding.TaggedObject("tag", "contents"),
    unwrapUnaryRecords: Boolean = false,
    tagSingleConstructors: Boolean = false,
    rejectUnknownFields: Boolean = false
)

object JsonOptions:
  /** Keep only the last segment of a dotted name: `com.acme.MyType.Foo` → `Foo`. */
  val dropQualifier: String => String = _.split('.').last

  /** Keep the last two segments: `com.acme.MyType.Foo` → `MyType.Foo`. */
  val lastTwoSegments: String => String = fq =>
    val parts = fq.split('.')
    if parts.length >= 2 then parts.takeRight(2).mkString(".") else fq

  val default: JsonOptions = JsonOptions()
