package registry.circe

import io.circe.{Decoder, DecodingFailure, HCursor, Json, KeyDecoder, ParsingFailure}
import io.circe.parser
import izumi.reflect.Tag
import registry.{Entry, Registry, TypedEntry, value}
import scala.collection.immutable.TreeMap

/**
 * Namespace for primitive `Decoder[A]` (= `io.circe.Decoder[A]`) entries and the registry
 * combinators that derive new decoders from existing ones.
 */
object Decoders:

  // ---- primitive built-ins ----

  /** Decode a JSON string into a `String`. */
  val string: Decoder[String] = Decoder.decodeString

  /** Decode a JSON number into an `Int`. */
  val int: Decoder[Int] = Decoder.decodeInt

  /** Decode a JSON number into a `Long`. */
  val long: Decoder[Long] = Decoder.decodeLong

  /** Decode a JSON boolean into a `Boolean`. */
  val boolean: Decoder[Boolean] = Decoder.decodeBoolean

  /** Decode a JSON number into a `Double`. */
  val double: Decoder[Double] = Decoder.decodeDouble

  /** Decode any JSON value into `()`. */
  val unit: Decoder[Unit] = Decoder.const(())

  /** Decode a JSON number into a `Byte`. */
  val byte: Decoder[Byte] = Decoder.decodeByte

  /** Decode a JSON number into a `BigInt`. */
  val bigInt: Decoder[BigInt] = Decoder.decodeBigInt

  /** Identity decoder — returns the raw `io.circe.Json` value as-is. */
  val json: Decoder[Json] = Decoder.instance(c => Right(c.value))

  /**
   * Registry bundling every primitive `Decoder[T]` (Unit, String, Int, Long, Boolean, Double, Byte,
   * BigInt, Json) as a single value. See [[Encoders.primitives]] for the symmetric encoder bundle.
   */
  val primitives =
    value(unit) *:
      value(string) *:
      value(int) *:
      value(long) *:
      value(boolean) *:
      value(double) *:
      value(byte) *:
      value(bigInt) *:
      value(json)

  /** Parse a JSON string and then decode it with the given `Decoder`. */
  def decodeString[A](d: Decoder[A], s: String)(using tag: Tag[A]): Either[io.circe.Error, A] =
    parser.parse(s) match
      case Left(pf: ParsingFailure) =>
        Left(ParsingFailure(s"Cannot parse the string as a Json: ${pf.message}. The string is: $s", pf.underlying))
      case Right(j) =>
        d.decodeJson(j) match
          case Right(a) => Right(a)
          case Left(df) =>
            Left(DecodingFailure(s"Cannot decode the type '${showType[A]}' >> ${df.message}", df.history))

  /** Parse a JSON byte string and then decode it with the given `Decoder`. */
  def decodeByteString[A](d: Decoder[A], bs: Array[Byte])(using tag: Tag[A]): Either[io.circe.Error, A] =
    decodeString(d, new String(bs, "UTF-8"))

  /** Return a short Scala type name for a given `A` using its `Tag`. */
  def showType[A](using tag: Tag[A]): String =
    val repr = tag.tag.shortName
    repr

  // ---- bridges ----

  /** Summon an `io.circe.Decoder[A]` and register it as a [[TypedEntry]]. */
  def decoderOf[A](using cd: Decoder[A], tag: Tag[Decoder[A]]): TypedEntry[EmptyTuple, Decoder[A]] =
    TypedEntry(Entry(Nil, tag.tag, _ => cd))

  // ---- combinators ----

  /** `Decoder[Option[A]]` where `Json.Null` decodes to `None`. */
  def decodeOptionOf[A](using
      tagIn: Tag[Decoder[A]],
      tagOut: Tag[Decoder[Option[A]]]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[Option[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args => Decoder.decodeOption(using args(0).asInstanceOf[Decoder[A]])
      )
    )

  /** `Decoder[List[A]]`. */
  def decodeListOf[A](using
      tagIn: Tag[Decoder[A]],
      tagOut: Tag[Decoder[List[A]]],
      tagA: Tag[A]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[List[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args => Decoder.decodeList(using args(0).asInstanceOf[Decoder[A]])
      )
    )

  /** `Decoder[Seq[A]]`. */
  def decodeSeqOf[A](using
      tagIn: Tag[Decoder[A]],
      tagOut: Tag[Decoder[Seq[A]]],
      tagA: Tag[A]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[Seq[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args => Decoder.decodeSeq(using args(0).asInstanceOf[Decoder[A]])
      )
    )

  /** `Decoder[Vector[A]]`. */
  def decodeVectorOf[A](using
      tagIn: Tag[Decoder[A]],
      tagOut: Tag[Decoder[Vector[A]]],
      tagA: Tag[A]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[Vector[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args => Decoder.decodeVector(using args(0).asInstanceOf[Decoder[A]])
      )
    )

  /** `Decoder[IArray[A]]`. */
  def decodeIArrayOf[A](using
      tagIn: Tag[Decoder[A]],
      tagOut: Tag[Decoder[IArray[A]]],
      tagA: Tag[A],
      classTag: scala.reflect.ClassTag[A]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[IArray[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args =>
          val d = args(0).asInstanceOf[Decoder[A]]
          Decoder.decodeList(using d).map(xs => IArray.from(xs))
      )
    )

  /** `Decoder[Set[A]]`. */
  def decodeSetOf[A](using
      tagIn: Tag[Decoder[A]],
      tagOut: Tag[Decoder[Set[A]]],
      tagA: Tag[A]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[Set[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args => Decoder.decodeSet(using args(0).asInstanceOf[Decoder[A]])
      )
    )

  /** `Decoder[(A, B)]`. */
  def decodePairOf[A, B](using
      tagInA: Tag[Decoder[A]],
      tagInB: Tag[Decoder[B]],
      tagOut: Tag[Decoder[(A, B)]],
      tagA: Tag[A],
      tagB: Tag[B]
  ): TypedEntry[Decoder[A] *: Decoder[B] *: EmptyTuple, Decoder[(A, B)]] =
    TypedEntry(
      Entry(
        List(tagInA.tag, tagInB.tag),
        tagOut.tag,
        args =>
          Decoder.decodeTuple2(using args(0).asInstanceOf[Decoder[A]], args(1).asInstanceOf[Decoder[B]])
      )
    )

  /** `Decoder[(A, B, C)]`. */
  def decodeTripleOf[A, B, C](using
      tagInA: Tag[Decoder[A]],
      tagInB: Tag[Decoder[B]],
      tagInC: Tag[Decoder[C]],
      tagOut: Tag[Decoder[(A, B, C)]],
      tagA: Tag[A],
      tagB: Tag[B],
      tagC: Tag[C]
  ): TypedEntry[Decoder[A] *: Decoder[B] *: Decoder[C] *: EmptyTuple, Decoder[(A, B, C)]] =
    TypedEntry(
      Entry(
        List(tagInA.tag, tagInB.tag, tagInC.tag),
        tagOut.tag,
        args =>
          Decoder.decodeTuple3(using
            args(0).asInstanceOf[Decoder[A]],
            args(1).asInstanceOf[Decoder[B]],
            args(2).asInstanceOf[Decoder[C]]
          )
      )
    )

  /** `Decoder[Map[K, V]]` using a `KeyDecoder[K]` for the object keys. */
  def decodeMapOf[K, V](using
      tagKey: Tag[KeyDecoder[K]],
      tagVal: Tag[Decoder[V]],
      tagOut: Tag[Decoder[Map[K, V]]],
      tagK: Tag[K],
      tagV: Tag[V]
  ): TypedEntry[KeyDecoder[K] *: Decoder[V] *: EmptyTuple, Decoder[Map[K, V]]] =
    TypedEntry(
      Entry(
        List(tagKey.tag, tagVal.tag),
        tagOut.tag,
        args =>
          Decoder.decodeMap(using
            args(0).asInstanceOf[KeyDecoder[K]],
            args(1).asInstanceOf[Decoder[V]]
          )
      )
    )

  /** `Decoder[TreeMap[K, V]]` using a `KeyDecoder[K]` for the object keys. Requires `Ordering[K]`
    *  at registration time to build the `TreeMap`. */
  def decodeTreeMapOf[K, V](using
      tagKey: Tag[KeyDecoder[K]],
      tagVal: Tag[Decoder[V]],
      tagOut: Tag[Decoder[TreeMap[K, V]]],
      tagK: Tag[K],
      tagV: Tag[V],
      ord: Ordering[K]
  ): TypedEntry[KeyDecoder[K] *: Decoder[V] *: EmptyTuple, Decoder[TreeMap[K, V]]] =
    TypedEntry(
      Entry(
        List(tagKey.tag, tagVal.tag),
        tagOut.tag,
        args =>
          Decoder
            .decodeMap(using
              args(0).asInstanceOf[KeyDecoder[K]],
              args(1).asInstanceOf[Decoder[V]]
            )
            .map(m => TreeMap.from(m)(using ord))
      )
    )
