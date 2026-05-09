package registry.circe

/**
 * Data types used by [[EncoderSpec]] and [[DecoderSpec]]. Scala-port analogue of
 * `test/Test/Data/Registry/Aeson/DataTypes.hs`.
 *
 * Scala enums always give their parameters names, so sum-type cases like `ByEmail(email: Email)` and
 * `InPerson(person: Person, datetime: DateTime)` inline their fields into the tagged object instead
 * of using the aeson `contents` wrapper — this is a structural difference from the Haskell port.
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

  enum UntaggedValueSumEncoding:
    case UntaggedValueSumEncoding1(uvField1: Int)
    case UntaggedValueSumEncoding2(uvField2: String)

  enum ObjectWithSingleFieldSumEncoding:
    case ObjectWithSingleFieldSumEncoding1(owsfField1: Int)
    case ObjectWithSingleFieldSumEncoding2(owsfField2: Int)

  enum TwoElemArraySumEncoding:
    case TwoElemArraySumEncoding1(teaField1: Int)
    case TwoElemArraySumEncoding2(teaField2: Int)

  /** Test that two same-typed fields in a single constructor compile. */
  final case class Stats(s1: Int, s2: Int)

  final case class Name(name: String)

  // -- recursive types --

  /** Direct self-reference: the `next` field has type `Cons`. */
  enum Cons:
    case End
    case Item(value: Int, next: Cons)

  /** Recursion through a container: `children: List[Tree]`. */
  final case class Tree(value: Int, children: List[Tree])

  // -- example values --

  val email1: Email = Email("me@here.com")
  val person1: Person = Person(Identifier(123), email1)
  val datetime1: DateTime = DateTime("2022-04-18T00:00:12Z")

  val delivery0: Delivery = Delivery.NoDelivery
  val delivery1: Delivery = Delivery.ByEmail(email1)
  val delivery2: Delivery = Delivery.InPerson(person1, datetime1)
