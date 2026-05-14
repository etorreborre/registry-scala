package registry.cbor

import io.bullet.borer.{Borer, Cbor, Decoder, Dom, Reader}
import izumi.reflect.Tag
import registry.{Entry, TypedEntry, value}
import scala.collection.immutable.TreeMap
import scala.reflect.ClassTag

/**
 * Namespace for primitive `Decoder[A]` (= `io.bullet.borer.Decoder[A]`) entries and the registry
 * combinators that derive new decoders from existing ones.
 */
object Decoders:

  // ---- primitive built-ins ----

  /** Decode a CBOR text string into a `String`. */
  val string: Decoder[String] = Decoder.forString

  /** Decode a CBOR integer into an `Int`. */
  val int: Decoder[Int] = Decoder.forInt

  /** Decode a CBOR integer into a `Long`. */
  val long: Decoder[Long] = Decoder.forLong

  /** Decode a CBOR boolean into a `Boolean`. */
  val boolean: Decoder[Boolean] = Decoder.forBoolean

  /** Decode a CBOR float into a `Double`. */
  val double: Decoder[Double] = Decoder.forDouble

  /** Decode a CBOR null/undefined into `()`. */
  val unit: Decoder[Unit] = Decoder.forUnit

  /** Decode a CBOR small integer into a `Byte`. */
  val byte: Decoder[Byte] = Decoder.forByte

  /** Decode a CBOR big-num (or integer) into a `BigInt`. */
  val bigInt: Decoder[BigInt] = Decoder.forBigInt

  /** Identity decoder — returns the next CBOR data item as a `Dom.Element` value. */
  val element: Decoder[Dom.Element] = summon[Decoder[Dom.Element]]

  /**
   * Registry bundling every primitive `Decoder[T]` (Unit, String, Int, Long, Boolean, Double, Byte,
   * BigInt, Dom.Element) as a single value. See [[Encoders.primitives]] for the symmetric encoder bundle.
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
      value(element)

  /** Decode a CBOR byte string with the given `Decoder`. */
  def decodeByteString[A](d: Decoder[A], bs: Array[Byte])(using tag: Tag[A]): Either[Borer.Error[?], A] =
    Cbor.decode(bs).to[A](using d).valueEither match
      case Right(a) => Right(a)
      case Left(e)  => Left(e)

  /** Decode a hex-encoded CBOR string with the given `Decoder`. */
  def decodeHex[A](d: Decoder[A], hex: String)(using tag: Tag[A]): Either[Borer.Error[?], A] =
    val cleaned = hex.replace(" ", "")
    val bytes = cleaned.grouped(2).map(s => Integer.parseInt(s, 16).toByte).toArray
    decodeByteString(d, bytes)

  /** Return a short Scala type name for a given `A` using its `Tag`. */
  def showType[A](using tag: Tag[A]): String =
    tag.tag.shortName

  // ---- bridges ----

  /** Summon an `io.bullet.borer.Decoder[A]` and register it as a [[TypedEntry]]. */
  def decoderOf[A](using cd: Decoder[A], tag: Tag[Decoder[A]]): TypedEntry[EmptyTuple, Decoder[A]] =
    TypedEntry(Entry(Nil, tag.tag, _ => cd))

  // ---- combinators ----

  /**
   * `Decoder[Option[A]]` where CBOR null decodes to `None`, anything else decodes via the wrapped
   * `Decoder[A]` into `Some(_)`. Symmetric counterpart of [[Encoders.encodeOptionOf]].
   */
  def decodeOptionOf[A](using
      tagIn: Tag[Decoder[A]],
      tagOut: Tag[Decoder[Option[A]]]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[Option[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args =>
          val d = args(0).asInstanceOf[Decoder[A]]
          Decoder[Option[A]]: r =>
            if r.tryReadNull() then None
            else Some(d.read(r))
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
        args => Decoder.fromFactory[A, List](using args(0).asInstanceOf[Decoder[A]], List.iterableFactory)
      )
    )

  /**
   * `Decoder[M[A]]` for any `M[X] <: LinearSeq[X]` (e.g. `List`, `Queue`, `LazyList`). Delegates to
   * borer's `Decoder.fromFactory`, so it accepts both sized and unsized CBOR arrays. Symmetric to
   * [[Encoders.encodeLinearSeqOf]].
   */
  def decodeLinearSeqOf[A, M[X] <: scala.collection.LinearSeq[X]](using
      tagIn: Tag[Decoder[A]],
      tagOut: Tag[Decoder[M[A]]],
      tagA: Tag[A],
      factory: scala.collection.Factory[A, M[A]]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[M[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args => Decoder.fromFactory[A, M](using args(0).asInstanceOf[Decoder[A]], factory)
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
        args => Decoder.fromFactory[A, Seq](using args(0).asInstanceOf[Decoder[A]], Seq.iterableFactory)
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
        args => Decoder.fromFactory[A, Vector](using args(0).asInstanceOf[Decoder[A]], Vector.iterableFactory)
      )
    )

  /** `Decoder[IArray[A]]`. */
  def decodeIArrayOf[A](using
      tagIn: Tag[Decoder[A]],
      tagOut: Tag[Decoder[IArray[A]]],
      tagA: Tag[A],
      classTag: ClassTag[A]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[IArray[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args =>
          val d = args(0).asInstanceOf[Decoder[A]]
          Decoder.fromFactory[A, List](using d, List.iterableFactory).map(xs => IArray.from(xs))
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
        args => Decoder.fromFactory[A, Set](using args(0).asInstanceOf[Decoder[A]], Set.iterableFactory)
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
          val da = args(0).asInstanceOf[Decoder[A]]
          val db = args(1).asInstanceOf[Decoder[B]]
          Decoder[(A, B)]: r =>
            if r.tryReadArrayHeader(2) then (da.read(r), db.read(r))
            else r.unexpectedDataItem(expected = "Array of length 2")
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
          val da = args(0).asInstanceOf[Decoder[A]]
          val db = args(1).asInstanceOf[Decoder[B]]
          val dc = args(2).asInstanceOf[Decoder[C]]
          Decoder[(A, B, C)]: r =>
            if r.tryReadArrayHeader(3) then (da.read(r), db.read(r), dc.read(r))
            else r.unexpectedDataItem(expected = "Array of length 3")
      )
    )

  /** `Decoder[Map[K, V]]` using a `Decoder[K]` for the map keys. */
  def decodeMapOf[K, V](using
      tagKey: Tag[Decoder[K]],
      tagVal: Tag[Decoder[V]],
      tagOut: Tag[Decoder[Map[K, V]]],
      tagK: Tag[K],
      tagV: Tag[V]
  ): TypedEntry[Decoder[K] *: Decoder[V] *: EmptyTuple, Decoder[Map[K, V]]] =
    TypedEntry(
      Entry(
        List(tagKey.tag, tagVal.tag),
        tagOut.tag,
        args =>
          val dk = args(0).asInstanceOf[Decoder[K]]
          val dv = args(1).asInstanceOf[Decoder[V]]
          Decoder[Map[K, V]]: r =>
            val size = r.readMapHeader()
            val b = Map.newBuilder[K, V]
            var i = 0L
            while i < size do
              b += dk.read(r) -> dv.read(r)
              i += 1L
            b.result()
      )
    )

  /**
   * `Decoder[TreeMap[K, V]]` using a `Decoder[K]` for the map keys. Requires `Ordering[K]` at
   *  registration time to build the `TreeMap`.
   */
  def decodeTreeMapOf[K, V](using
      tagKey: Tag[Decoder[K]],
      tagVal: Tag[Decoder[V]],
      tagOut: Tag[Decoder[TreeMap[K, V]]],
      tagK: Tag[K],
      tagV: Tag[V],
      ord: Ordering[K]
  ): TypedEntry[Decoder[K] *: Decoder[V] *: EmptyTuple, Decoder[TreeMap[K, V]]] =
    TypedEntry(
      Entry(
        List(tagKey.tag, tagVal.tag),
        tagOut.tag,
        args =>
          val dk = args(0).asInstanceOf[Decoder[K]]
          val dv = args(1).asInstanceOf[Decoder[V]]
          Decoder[TreeMap[K, V]]: r =>
            val size = r.readMapHeader()
            val b = TreeMap.newBuilder[K, V](using ord)
            var i = 0L
            while i < size do
              b += dk.read(r) -> dv.read(r)
              i += 1L
            b.result()
      )
    )
