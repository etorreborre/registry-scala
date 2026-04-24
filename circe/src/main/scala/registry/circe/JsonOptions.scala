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
 */
final case class JsonOptions(
    fieldLabelModifier: String => String = identity,
    constructorTagModifier: String => String = identity,
    allNullaryToStringTag: Boolean = true,
    omitNothingFields: Boolean = false,
    sumEncoding: SumEncoding = SumEncoding.TaggedObject("tag", "contents"),
    unwrapUnaryRecords: Boolean = false,
    tagSingleConstructors: Boolean = false,
    rejectUnknownFields: Boolean = false
)

object JsonOptions:
  val default: JsonOptions = JsonOptions()
