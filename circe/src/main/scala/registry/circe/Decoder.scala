package registry.circe

import io.circe.{Json, ParsingFailure}
import io.circe.parser
import izumi.reflect.Tag
import registry.{Entry, TypedEntry}

/**
 * A `Decoder[A]` converts a circe `Json` value into an `A` or returns an error message.
 *
 * Scala-port counterpart to the Haskell `registry-aeson` `Decoder`. `Either[String, A]` is used rather
 * than circe's cursor-based `DecodingFailure` so error messages can be composed into field-path chains
 * the same way the Haskell port does.
 */
final case class Decoder[A](decode: Json => Either[String, A]):
  def map[B](f: A => B): Decoder[B] = Decoder(j => decode(j).map(f))
  def flatMap[B](f: A => Decoder[B]): Decoder[B] = Decoder(j => decode(j).flatMap(a => f(a).decode(j)))

object Decoder:

  /** Parse a JSON string and then decode it with the given `Decoder`. */
  def decodeString[A](d: Decoder[A], s: String)(using tag: Tag[A]): Either[String, A] =
    parser.parse(s) match
      case Left(ParsingFailure(msg, _)) =>
        Left(s"Cannot parse the string as a Json: $msg. The string is: $s")
      case Right(j) =>
        d.decode(j) match
          case Right(a) => Right(a)
          case Left(e)  => Left(s"Cannot decode the type '${showType[A]}' >> $e")

  /** Parse a JSON byte string and then decode it with the given `Decoder`. */
  def decodeByteString[A](d: Decoder[A], bs: Array[Byte])(using tag: Tag[A]): Either[String, A] =
    decodeString(d, new String(bs, "UTF-8"))

  /** Return a short Scala type name for a given `A` using its `Tag`. */
  def showType[A](using tag: Tag[A]): String =
    val repr = tag.tag.shortName
    repr

  // ---- bridges ----

  /** Lift a circe `Decoder[A]` into a registry-native `Decoder[A]` ready to register. */
  def jsonDecoder[A](using cd: io.circe.Decoder[A], tag: Tag[Decoder[A]]): TypedEntry[EmptyTuple, Decoder[A]] =
    TypedEntry(Entry(Nil, tag.tag, _ => Decoder[A](j => cd.decodeJson(j).left.map(f => f.message))))

  /** A circe `Decoder[A]` → registry-native `Decoder[A]` as a plain value. */
  def jsonDecoderOf[A](using cd: io.circe.Decoder[A]): Decoder[A] =
    Decoder(j => cd.decodeJson(j).left.map(_.message))

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
    Decoder: j =>
      if j.isNull then Right(None)
      else d.decode(j).map(Some(_))

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
    Decoder: j =>
      j.asArray match
        case Some(vs) => sequenceEither(vs.toList.map(d.decode))
        case None     => Left(s"not a list of ${showType[A]}")

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
    Decoder: j =>
      j.asArray match
        case Some(vs) => sequenceEither(vs.toList.map(d.decode)).map(_.toSeq)
        case None     => Left(s"not a list of ${showType[A]}")

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
    Decoder: j =>
      j.asArray match
        case Some(vs) => sequenceEither(vs.toList.map(d.decode)).map(_.toVector)
        case None     => Left(s"not a list of ${showType[A]}")

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
    Decoder: j =>
      j.asArray match
        case Some(vs) => sequenceEither(vs.toList.map(d.decode)).map(_.toSet)
        case None     => Left(s"not a set of ${showType[A]}")

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
    Decoder: j =>
      j.asArray match
        case Some(vs) if vs.sizeIs == 2 =>
          for
            a <- da.decode(vs(0))
            b <- db.decode(vs(1))
          yield (a, b)
        case _ => Left(s"not a pair of ${showType[A]}, ${showType[B]}")

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
    Decoder: j =>
      j.asArray match
        case Some(vs) if vs.sizeIs == 3 =>
          for
            a <- da.decode(vs(0))
            b <- db.decode(vs(1))
            c <- dc.decode(vs(2))
          yield (a, b, c)
        case _ => Left(s"not a triple of ${showType[A]}, ${showType[B]}, ${showType[C]}")

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
    Decoder: j =>
      j.asObject match
        case Some(obj) =>
          sequenceEither(obj.toList.map { (k, v) =>
            for
              key <- dk.decodeKeyAs(k)
              value <- dv.decode(v)
            yield key -> value
          }).map(_.toMap)
        case None => Left(s"not a map of ${showType[K]} ${showType[V]}")

  /** Sequence a list of `Either`s, short-circuiting on the first `Left`. */
  private[circe] def sequenceEither[A](ls: List[Either[String, A]]): Either[String, List[A]] =
    val buf = scala.collection.mutable.ListBuffer.empty[A]
    var err: Option[String] = None
    val it = ls.iterator
    while it.hasNext && err.isEmpty do
      it.next() match
        case Right(a) => buf += a
        case Left(e)  => err = Some(e)
    err match
      case Some(e) => Left(e)
      case None    => Right(buf.toList)
