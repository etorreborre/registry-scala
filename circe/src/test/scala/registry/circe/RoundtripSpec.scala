package registry.circe

import io.circe.Json
import org.specs2.mutable.Specification
import registry.{Person as _, *}
import registry.circe.DataTypes.*

/**
 * Encode → decode roundtrip for the core data types under each supported [[SumEncoding]] mode.
 *
 * Scala-port analogue of `test/Test/Data/Registry/Aeson/RoundtripSpec.hs`. We use a small fixed set
 * of sample values rather than property-based generators, which is enough to exercise every encoding
 * path and matches the granularity of the Haskell roundtrip spec.
 */
class RoundtripSpec extends Specification:

  private val samplesDelivery: List[Delivery] = List(
    Delivery.NoDelivery,
    Delivery.ByEmail(Email("me@here.com")),
    Delivery.InPerson(Person(Identifier(1), Email("1@2.3")), DateTime("2022-01-01T00:00:00Z"))
  )

  private val samplesPerson: List[Person] = List(
    Person(Identifier(0), Email("a@b.c")),
    Person(Identifier(42), Email("me@here.com"))
  )

  "TaggedObject roundtrip (default)" >> {
    val opts = JsonOptions.default
    samplesDelivery.foreach(d => roundtrip[Delivery](d, opts) === Right(d))
    success
  }

  "UntaggedValue roundtrip" >> {
    val opts = JsonOptions.default.copy(sumEncoding = SumEncoding.UntaggedValue)
    samplesDelivery.foreach(d => roundtrip[Delivery](d, opts) === Right(d))
    success
  }

  "ObjectWithSingleField roundtrip" >> {
    val opts = JsonOptions.default.copy(sumEncoding = SumEncoding.ObjectWithSingleField)
    samplesDelivery.foreach(d => roundtrip[Delivery](d, opts) === Right(d))
    success
  }

  "TwoElemArray roundtrip" >> {
    val opts = JsonOptions.default.copy(sumEncoding = SumEncoding.TwoElemArray)
    samplesDelivery.foreach(d => roundtrip[Delivery](d, opts) === Right(d))
    success
  }

  "Person roundtrip under fieldLabelModifier" >> {
    val opts = JsonOptions.default.copy(fieldLabelModifier = "__" + _)
    samplesPerson.foreach(p => roundtrip[Person](p, opts) === Right(p))
    success
  }

  "Person roundtrip with omitNothingFields on a type with Option" >> {
    val samples = List(
      Team("team1", samplesPerson, Some("leader")),
      Team("team2", Nil, None)
    )
    val opts = JsonOptions.default.copy(omitNothingFields = true)
    samples.foreach(t => roundtrip[Team](t, opts) === Right(t))
    success
  }

  private def roundtrip[T](value: T, opts: JsonOptions)(using
      eTag: izumi.reflect.Tag[Encoder[T]],
      dTag: izumi.reflect.Tag[Decoder[T]]
  ): Either[String, T] =
    val r = this.registry(opts)
    val e = r.make[Encoder[T]]
    val d = r.make[Decoder[T]]
    d.decode(e.encode(value))

  /** Registry with every encoder/decoder required to roundtrip any of the sample types. */
  private def registry(opts: JsonOptions) =
    value(opts) -:
      makeEncoder[Delivery] *:
      makeDecoder[Delivery] *:
      makeEncoder[Team] *:
      makeDecoder[Team] *:
      makeEncoder[Person] *:
      makeDecoder[Person] *:
      makeEncoder[Identifier] *:
      makeDecoder[Identifier] *:
      makeEncoder[Email] *:
      makeDecoder[Email] *:
      makeEncoder[DateTime] *:
      makeDecoder[DateTime] *:
      encodeListOf[Person] *:
      decodeListOf[Person] *:
      encodeOptionOf[String] *:
      decodeOptionOf[String] *:
      jsonEncoder[String] *:
      jsonDecoder[String] *:
      jsonEncoder[Int] *:
      jsonDecoder[Int] *:
      value(ConstructorEncoder.default) *:
      value(ConstructorsDecoder.default) *:
      value(KeyEncoder.stringKeyEncoder) *:
      value(KeyDecoder.stringKeyDecoder)
