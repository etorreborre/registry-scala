package registry.circe

import io.circe.{ACursor, CursorOp, DecodingFailure, HCursor, Json, ParsingFailure}
import io.circe.parser
import izumi.reflect.Tag
import registry.{Entry, Registry, TypedEntry, value}

/**
 * A `Decoder[A]` converts a circe `HCursor` into an `A` or returns a `DecodingFailure`.
 *
 * Scala-port counterpart to the Haskell `registry-aeson` `Decoder`. We follow circe's own model
 * (`HCursor => Either[DecodingFailure, A]`) so cursor history and IDE tooling work end-to-end.
 * Failure messages from this module embed field-path context the same way the Haskell port does,
 * but they ride on top of a real `CursorOp` history instead of replacing it.
 */
final case class Decoder[A](decode: HCursor => Decoder.Result[A]):
  def apply(c: HCursor): Decoder.Result[A] = decode(c)

  /** Decode a raw `Json` value by lifting it to an `HCursor` first. */
  def decodeJson(j: Json): Decoder.Result[A] = decode(j.hcursor)

  /** Decode from an `ACursor`; a failed cursor is surfaced as a `DecodingFailure`. */
  def tryDecode(c: ACursor): Decoder.Result[A] = c match
    case hc: HCursor => decode(hc)
    case _           => Left(DecodingFailure("Attempt to decode value on failed cursor", c.history))

  def map[B](f: A => B): Decoder[B] = Decoder(c => decode(c).map(f))
  def flatMap[B](f: A => Decoder[B]): Decoder[B] = Decoder(c => decode(c).flatMap(a => f(a).decode(c)))

  /**
   * Fallible map: decode `A`, then run `f` which may fail with an error message. Useful for
   * post-validation (e.g. constructing types whose factory returns `Either`).
   */
  def emap[B](f: A => Either[String, B]): Decoder[B] =
    Decoder(c => decode(c).flatMap(a => f(a).left.map(msg => DecodingFailure(msg, c.history))))

  /** Lift this registry-native `Decoder[A]` into an `io.circe.Decoder[A]` for use at API boundaries. */
  def asCirce: io.circe.Decoder[A] = io.circe.Decoder.instance(decode)

object Decoder:

  /** Same alias as `io.circe.Decoder.Result`. */
  type Result[A] = Either[DecodingFailure, A]

  // ---- primitive built-ins ----

  /** Decode a JSON string into a `String`. */
  val string: Decoder[String] =
    Decoder(c => c.value.asString.toRight(DecodingFailure("String", c.history)))

  /** Decode a JSON number into an `Int` (fails if the number does not fit). */
  val int: Decoder[Int] =
    Decoder(c => c.value.asNumber.flatMap(_.toInt).toRight(DecodingFailure("Int", c.history)))

  /** Decode a JSON number into a `Long` (fails if the number does not fit). */
  val long: Decoder[Long] =
    Decoder(c => c.value.asNumber.flatMap(_.toLong).toRight(DecodingFailure("Long", c.history)))

  /** Decode a JSON boolean into a `Boolean`. */
  val boolean: Decoder[Boolean] =
    Decoder(c => c.value.asBoolean.toRight(DecodingFailure("Boolean", c.history)))

  /** Decode a JSON number into a `Double`. */
  val double: Decoder[Double] =
    Decoder(c => c.value.asNumber.map(_.toDouble).toRight(DecodingFailure("Double", c.history)))

  /** Decode any JSON value into `()`. */
  val unit: Decoder[Unit] = Decoder(_ => Right(()))

  /** Decode a JSON number into a `Byte` (fails if outside `Byte`'s range). */
  val byte: Decoder[Byte] =
    Decoder(c => c.value.asNumber.flatMap(_.toByte).toRight(DecodingFailure("Byte", c.history)))

  /** Decode a JSON number into a `BigInt`. */
  val bigInt: Decoder[BigInt] =
    Decoder(c => c.value.asNumber.flatMap(_.toBigInt).toRight(DecodingFailure("BigInt", c.history)))

  /** Identity decoder — returns the raw `io.circe.Json` value as-is. */
  val json: Decoder[Json] = Decoder(c => Right(c.value))

  /**
   * Registry bundling every primitive `Decoder[T]` (Unit, String, Int, Long, Boolean, Double, Byte,
   * BigInt, Json) as a single value. See [[Encoder.primitives]] for the symmetric encoder bundle.
   * The return type is left to inference so the precise `AllOuts` tuple is exposed to strict `+:`
   * checks.
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
        d.decode(j.hcursor) match
          case Right(a) => Right(a)
          case Left(df) => Left(DecodingFailure(s"Cannot decode the type '${showType[A]}' >> ${df.message}", df.history))

  /** Parse a JSON byte string and then decode it with the given `Decoder`. */
  def decodeByteString[A](d: Decoder[A], bs: Array[Byte])(using tag: Tag[A]): Either[io.circe.Error, A] =
    decodeString(d, new String(bs, "UTF-8"))

  /** Return a short Scala type name for a given `A` using its `Tag`. */
  def showType[A](using tag: Tag[A]): String =
    val repr = tag.tag.shortName
    repr

  // ---- bridges ----

  /** Lift a circe `Decoder[A]` into a registry-native `Decoder[A]` ready to register. */
  def jsonDecoder[A](using cd: io.circe.Decoder[A], tag: Tag[Decoder[A]]): TypedEntry[EmptyTuple, Decoder[A]] =
    TypedEntry(Entry(Nil, tag.tag, _ => Decoder[A](cd.apply)))

  /** A circe `Decoder[A]` → registry-native `Decoder[A]` as a plain value. */
  def jsonDecoderOf[A](using cd: io.circe.Decoder[A]): Decoder[A] =
    Decoder(cd.apply)

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
        args => optionOfDecoder(args(0).asInstanceOf[Decoder[A]])
      )
    )

  def optionOfDecoder[A](d: Decoder[A]): Decoder[Option[A]] =
    Decoder: c =>
      if c.value.isNull then Right(None)
      else d.decode(c).map(Some(_))

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
        args => listOfDecoder(args(0).asInstanceOf[Decoder[A]])
      )
    )

  def listOfDecoder[A](d: Decoder[A])(using tagA: Tag[A]): Decoder[List[A]] =
    Decoder: c =>
      if c.value.isArray then decodeArrayElements(c, d)
      else Left(DecodingFailure(s"not a list of ${showType[A]}", c.history))

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
        args => seqOfDecoder(args(0).asInstanceOf[Decoder[A]])
      )
    )

  def seqOfDecoder[A](d: Decoder[A])(using tagA: Tag[A]): Decoder[Seq[A]] =
    Decoder: c =>
      if c.value.isArray then decodeArrayElements(c, d).map(_.toSeq)
      else Left(DecodingFailure(s"not a list of ${showType[A]}", c.history))

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
        args => vectorOfDecoder(args(0).asInstanceOf[Decoder[A]])
      )
    )

  def vectorOfDecoder[A](d: Decoder[A])(using tagA: Tag[A]): Decoder[Vector[A]] =
    Decoder: c =>
      if c.value.isArray then decodeArrayElements(c, d).map(_.toVector)
      else Left(DecodingFailure(s"not a list of ${showType[A]}", c.history))

  /** `Decoder[IArray[A]]`. Requires `ClassTag[A]` at the call site to build the underlying array. */
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
        args => iArrayOfDecoder(args(0).asInstanceOf[Decoder[A]])
      )
    )

  def iArrayOfDecoder[A](d: Decoder[A])(using
      tagA: Tag[A],
      classTag: scala.reflect.ClassTag[A]
  ): Decoder[IArray[A]] =
    Decoder: c =>
      if c.value.isArray then decodeArrayElements(c, d).map(xs => IArray.from(xs))
      else Left(DecodingFailure(s"not a list of ${showType[A]}", c.history))

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
        args => setOfDecoder(args(0).asInstanceOf[Decoder[A]])
      )
    )

  def setOfDecoder[A](d: Decoder[A])(using tagA: Tag[A]): Decoder[Set[A]] =
    Decoder: c =>
      if c.value.isArray then decodeArrayElements(c, d).map(_.toSet)
      else Left(DecodingFailure(s"not a set of ${showType[A]}", c.history))

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
          pairOfDecoder(
            args(0).asInstanceOf[Decoder[A]],
            args(1).asInstanceOf[Decoder[B]]
          )
      )
    )

  def pairOfDecoder[A, B](da: Decoder[A], db: Decoder[B])(using tagA: Tag[A], tagB: Tag[B]): Decoder[(A, B)] =
    Decoder: c =>
      c.value.asArray match
        case Some(vs) if vs.sizeIs == 2 =>
          for
            a <- da.tryDecode(c.downN(0))
            b <- db.tryDecode(c.downN(1))
          yield (a, b)
        case _ => Left(DecodingFailure(s"not a pair of ${showType[A]}, ${showType[B]}", c.history))

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
          tripleOfDecoder(
            args(0).asInstanceOf[Decoder[A]],
            args(1).asInstanceOf[Decoder[B]],
            args(2).asInstanceOf[Decoder[C]]
          )
      )
    )

  def tripleOfDecoder[A, B, C](da: Decoder[A], db: Decoder[B], dc: Decoder[C])(using
      tagA: Tag[A],
      tagB: Tag[B],
      tagC: Tag[C]
  ): Decoder[(A, B, C)] =
    Decoder: c =>
      c.value.asArray match
        case Some(vs) if vs.sizeIs == 3 =>
          for
            a <- da.tryDecode(c.downN(0))
            b <- db.tryDecode(c.downN(1))
            cc <- dc.tryDecode(c.downN(2))
          yield (a, b, cc)
        case _ => Left(DecodingFailure(s"not a triple of ${showType[A]}, ${showType[B]}, ${showType[C]}", c.history))

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
          mapOfDecoder(
            args(0).asInstanceOf[KeyDecoder[K]],
            args(1).asInstanceOf[Decoder[V]]
          )
      )
    )

  def mapOfDecoder[K, V](dk: KeyDecoder[K], dv: Decoder[V])(using tagK: Tag[K], tagV: Tag[V]): Decoder[Map[K, V]] =
    Decoder: c =>
      c.value.asObject match
        case Some(obj) =>
          val keys = obj.keys.toList
          sequenceEither(keys.map { k =>
            for
              key   <- dk.decodeKeyAs(k).left.map(msg => DecodingFailure(msg, CursorOp.DownField(k) :: c.history))
              value <- dv.tryDecode(c.downField(k))
            yield key -> value
          }).map(_.toMap)
        case None => Left(DecodingFailure(s"not a map of ${showType[K]} ${showType[V]}", c.history))

  /** Decode the JSON array currently under `c` element-by-element using `d`. */
  private def decodeArrayElements[A](c: HCursor, d: Decoder[A]): Result[List[A]] =
    val buf = scala.collection.mutable.ListBuffer.empty[A]
    var err: Option[DecodingFailure] = None
    var ac: ACursor = c.downArray
    while ac.succeeded && err.isEmpty do
      d.tryDecode(ac) match
        case Right(a) => buf += a
        case Left(e)  => err = Some(e)
      ac = ac.right
    err match
      case Some(e) => Left(e)
      case None    => Right(buf.toList)

  /** Sequence a list of decode results, short-circuiting on the first failure. */
  private[circe] def sequenceEither[A](ls: List[Result[A]]): Result[List[A]] =
    val buf = scala.collection.mutable.ListBuffer.empty[A]
    var err: Option[DecodingFailure] = None
    val it = ls.iterator
    while it.hasNext && err.isEmpty do
      it.next() match
        case Right(a) => buf += a
        case Left(e)  => err = Some(e)
    err match
      case Some(e) => Left(e)
      case None    => Right(buf.toList)
