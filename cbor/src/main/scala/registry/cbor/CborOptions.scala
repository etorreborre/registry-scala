package registry.cbor

/** How values of a sum type (sealed trait / enum with multiple cases) are encoded. */
enum SumEncoding:
  /** `[tag, contents]` — 2-element array, tag is an integer or text per `constructorTagMode`. */
  case TwoElemArray

  /** The tag is not present in the output; constructors are disambiguated by shape alone (decoding tries each). */
  case Untagged

  /** Single-key map `{tag: contents}` where the key is the constructor's tag. */
  case SingleKeyMap

  /** Use a CBOR semantic tag (major type 6): `Tag(baseTagNumber + constructorIndex, contents)`. */
  case CborTagged(baseTagNumber: Long)

/** How record fields are keyed inside the CBOR map produced for a case-class instance. */
enum FieldKeyMode:
  /** Integer keys 0..N-1 (positional, CBOR-native, compact). */
  case IntegerKeys

  /** Text-string keys using the original (possibly modified) field names. */
  case StringKeys

/** How a constructor is represented in the tag slot of a sum encoding. */
enum ConstructorTagMode:
  /** Integer constructor indices 0..N-1. */
  case IntegerTags

  /** Text-string constructor names (possibly modified via `constructorTagModifier`). */
  case StringTags

/**
 * Options controlling how generated encoders/decoders shape the CBOR output.
 *
 * Defaults follow CBOR-native conventions: record fields use integer keys, sum-type tags are integer
 * constructor indices, and pure enumerations (all-nullary sums) collapse to a single integer tag.
 * Override `fieldKeyMode` / `constructorTagMode` for JSON-like interop.
 */
final case class CborOptions(
    fieldKeyMode: FieldKeyMode = FieldKeyMode.IntegerKeys,
    constructorTagMode: ConstructorTagMode = ConstructorTagMode.IntegerTags,
    fieldLabelModifier: String => String = identity,
    constructorTagModifier: String => String = CborOptions.dropQualifier,
    allNullaryToTag: Boolean = true,
    omitNothingFields: Boolean = false,
    sumEncoding: SumEncoding = SumEncoding.TwoElemArray,
    unwrapUnaryRecords: Boolean = false,
    tagSingleConstructors: Boolean = false,
    rejectUnknownFields: Boolean = false
)

object CborOptions:
  /** Keep only the last segment of a dotted name: `com.acme.MyType.Foo` → `Foo`. */
  val dropQualifier: String => String = _.split('.').last

  /** Keep the last two segments: `com.acme.MyType.Foo` → `MyType.Foo`. */
  val lastTwoSegments: String => String = fq =>
    val parts = fq.split('.')
    if parts.length >= 2 then parts.takeRight(2).mkString(".") else fq

  val default: CborOptions = CborOptions()
