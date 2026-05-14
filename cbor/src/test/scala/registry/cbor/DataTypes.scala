package registry.cbor

/**
 * Data types used by [[EncoderSpec]], [[DecoderSpec]], and [[RoundtripSpec]].
 *
 * Mirrors the registry-circe DataTypes — same shapes, so option-driven encoding behaviour can be
 * compared apples-to-apples between the two modules.
 */
object DataTypes:

  final case class Identifier(value: Int)
  final case class Email(email: String)
  final case class DateTime(datetime: String)
  final case class Person(identifier: Identifier, email: Email)
  final case class Team(name: String, members: List[Person], leaderName: Option[String])

  enum Delivery:
    case NoDelivery
    case ByEmail(email: Email)
    case InPerson(person: Person, datetime: DateTime)

  // -- option test types --

  enum AllNullary:
    case AllNullary1, AllNullary2

  enum FieldLabelModifier:
    case FieldLabelModifier1(field1: Int)
    case FieldLabelModifier2(field2: Int)

  enum ConstructorTagModifier:
    case ConstructorTagModifier1(ctField1: Int)
    case ConstructorTagModifier2(ctField2: Int)

  enum OmitNothingFields:
    case OmitNothingFields1(onField1: Option[Int], onField2: Int)
    case OmitNothingFields2(onField3: Int)

  final case class UnwrapUnaryRecords(uuField1: Int)

  final case class TagSingleConstructors(tsField1: Int)

  enum UntaggedSumEncoding:
    case UntaggedSumEncoding1(uvField1: Int)
    case UntaggedSumEncoding2(uvField2: String)

  enum SingleKeyMapSumEncoding:
    case SingleKeyMapSumEncoding1(owsfField1: Int)
    case SingleKeyMapSumEncoding2(owsfField2: Int)

  enum TwoElemArraySumEncoding:
    case TwoElemArraySumEncoding1(teaField1: Int)
    case TwoElemArraySumEncoding2(teaField2: Int)

  enum CborTaggedSumEncoding:
    case CborTaggedSumEncoding1(ctField1: Int)
    case CborTaggedSumEncoding2(ctField2: Int)

  /** Two same-typed fields in a single constructor compile. */
  final case class Stats(s1: Int, s2: Int)

  final case class Name(name: String)

  // -- recursive types --

  enum Cons:
    case End
    case Item(value: Int, next: Cons)

  final case class Tree(value: Int, children: List[Tree])

  // -- example values --

  val email1: Email = Email("me@here.com")
  val person1: Person = Person(Identifier(123), email1)
  val datetime1: DateTime = DateTime("2022-04-18T00:00:12Z")

  val delivery0: Delivery = Delivery.NoDelivery
  val delivery1: Delivery = Delivery.ByEmail(email1)
  val delivery2: Delivery = Delivery.InPerson(person1, datetime1)
