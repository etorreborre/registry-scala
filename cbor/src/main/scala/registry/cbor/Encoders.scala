package registry.cbor

import io.bullet.borer.{Cbor, Encoder, Writer}
import izumi.reflect.Tag
import registry.{Entry, TypedEntry, value}
import scala.collection.immutable.TreeMap

/**
 * Namespace for primitive `Encoder[A]` (= `io.bullet.borer.Encoder[A]`) entries and the registry
 * combinators that derive new encoders from existing ones.
 */
object Encoders:

  // ---- primitive built-ins ----

  /** Encode a `String` as a CBOR text string. */
  val string: Encoder[String] = Encoder.forString

  /** Encode an `Int` as a CBOR unsigned/negative integer. */
  val int: Encoder[Int] = Encoder.forInt

  /** Encode a `Long` as a CBOR unsigned/negative integer. */
  val long: Encoder[Long] = Encoder.forLong

  /** Encode a `Boolean` as a CBOR boolean. */
  val boolean: Encoder[Boolean] = Encoder.forBoolean

  /** Encode a `Double` as a CBOR double-precision float. */
  val double: Encoder[Double] = Encoder.forDouble

  /** Encode a `Unit` as a CBOR null (matches borer's default). */
  val unit: Encoder[Unit] = Encoder.forUnit

  /** Encode a `Byte` as a CBOR small integer. */
  val byte: Encoder[Byte] = Encoder.forByte

  /** Encode a `BigInt` as a CBOR big-num (tagged byte string). */
  val bigInt: Encoder[BigInt] = Encoder.forBigInt

  /**
   * Registry bundling every primitive `Encoder[T]` (Unit, String, Int, Long, Boolean, Double, Byte,
   * BigInt) as a single value.
   *
   * {{{
   * encoder[Foo] *: contramap((_:Wrapper).inner) *: Encoders.primitives *: defaultEncoderOptions
   * }}}
   */
  val primitives =
    value(unit) *:
      value(string) *:
      value(int) *:
      value(long) *:
      value(boolean) *:
      value(double) *:
      value(byte) *:
      value(bigInt)

  /** Render a value as a CBOR byte string. */
  def encodeByteString[A](e: Encoder[A], a: A): Array[Byte] =
    Cbor.encode(a)(using e).toByteArray

  /** Render a value as a hex-encoded CBOR string. */
  def encodeHex[A](e: Encoder[A], a: A): String =
    encodeByteString(e, a).map(b => f"${b & 0xff}%02x").mkString

  /** Build an `Encoder[A]` from a `Writer`-based function. */
  def fromWriter[A](f: (Writer, A) => Writer): Encoder[A] = Encoder[A]((w, a) => f(w, a))

  // ---- bridges ----

  /** Summon an `io.bullet.borer.Encoder[A]` and register it as a [[TypedEntry]]. */
  def encoderOf[A](using ce: Encoder[A], tag: Tag[Encoder[A]]): TypedEntry[EmptyTuple, Encoder[A]] =
    TypedEntry(Entry(Nil, tag.tag, _ => ce))

  // ---- combinators ----

  /**
   * `Encoder[Option[A]]` where `None` encodes as CBOR null and `Some(x)` as the encoded `x`. Note
   * this differs from borer's default `Encoder.forOption` (which uses an array wrapper) — the
   * null-based encoding plays well with `CborOptions.omitNothingFields`.
   */
  def encodeOptionOf[A](using
      tagIn: Tag[Encoder[A]],
      tagOut: Tag[Encoder[Option[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[Option[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args =>
          val e = args(0).asInstanceOf[Encoder[A]]
          Encoder[Option[A]]: (w, x) =>
            x match
              case Some(a) => e.write(w, a)
              case None    => w.writeNull()
      )
    )

  /** `Encoder[List[A]]` — writes a sized CBOR array (definite-length). */
  def encodeListOf[A](using
      tagIn: Tag[Encoder[A]],
      tagOut: Tag[Encoder[List[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[List[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args =>
          val e = args(0).asInstanceOf[Encoder[A]]
          Encoder[List[A]]: (w, xs) =>
            w.writeArrayHeader(xs.size)
            xs.foreach(a => e.write(w, a))
            w
      )
    )

  /** `Encoder[Seq[A]]`. */
  def encodeSeqOf[A](using
      tagIn: Tag[Encoder[A]],
      tagOut: Tag[Encoder[Seq[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[Seq[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args =>
          val e = args(0).asInstanceOf[Encoder[A]]
          Encoder[Seq[A]]((w, s) =>
            val it = s.iterator
            w.writeArrayHeader(s.size)
            while it.hasNext do e.write(w, it.next())
            w
          )
      )
    )

  /** `Encoder[Vector[A]]` — writes a sized CBOR array (definite-length). */
  def encodeVectorOf[A](using
      tagIn: Tag[Encoder[A]],
      tagOut: Tag[Encoder[Vector[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[Vector[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args =>
          val e = args(0).asInstanceOf[Encoder[A]]
          Encoder[Vector[A]]: (w, xs) =>
            w.writeArrayHeader(xs.size)
            var i = 0
            while i < xs.size do
              e.write(w, xs(i))
              i += 1
            w
      )
    )

  /** `Encoder[IArray[A]]`. */
  def encodeIArrayOf[A](using
      tagIn: Tag[Encoder[A]],
      tagOut: Tag[Encoder[IArray[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[IArray[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args =>
          val e = args(0).asInstanceOf[Encoder[A]]
          Encoder[IArray[A]]: (w, as) =>
            w.writeArrayHeader(as.length)
            var i = 0
            while i < as.length do
              e.write(w, as(i))
              i += 1
            w
      )
    )

  /** `Encoder[Set[A]]`. */
  def encodeSetOf[A](using
      tagIn: Tag[Encoder[A]],
      tagOut: Tag[Encoder[Set[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[Set[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args =>
          val e = args(0).asInstanceOf[Encoder[A]]
          Encoder[Set[A]]: (w, s) =>
            w.writeArrayHeader(s.size)
            s.foreach(a => e.write(w, a))
            w
      )
    )

  /** `Encoder[(A, B)]`. */
  def encodePairOf[A, B](using
      tagInA: Tag[Encoder[A]],
      tagInB: Tag[Encoder[B]],
      tagOut: Tag[Encoder[(A, B)]]
  ): TypedEntry[Encoder[A] *: Encoder[B] *: EmptyTuple, Encoder[(A, B)]] =
    TypedEntry(
      Entry(
        List(tagInA.tag, tagInB.tag),
        tagOut.tag,
        args =>
          val ea = args(0).asInstanceOf[Encoder[A]]
          val eb = args(1).asInstanceOf[Encoder[B]]
          Encoder[(A, B)]: (w, t) =>
            w.writeArrayHeader(2)
            ea.write(w, t._1)
            eb.write(w, t._2)
      )
    )

  /** `Encoder[(A, B, C)]`. */
  def encodeTripleOf[A, B, C](using
      tagInA: Tag[Encoder[A]],
      tagInB: Tag[Encoder[B]],
      tagInC: Tag[Encoder[C]],
      tagOut: Tag[Encoder[(A, B, C)]]
  ): TypedEntry[Encoder[A] *: Encoder[B] *: Encoder[C] *: EmptyTuple, Encoder[(A, B, C)]] =
    TypedEntry(
      Entry(
        List(tagInA.tag, tagInB.tag, tagInC.tag),
        tagOut.tag,
        args =>
          val ea = args(0).asInstanceOf[Encoder[A]]
          val eb = args(1).asInstanceOf[Encoder[B]]
          val ec = args(2).asInstanceOf[Encoder[C]]
          Encoder[(A, B, C)]: (w, t) =>
            w.writeArrayHeader(3)
            ea.write(w, t._1)
            eb.write(w, t._2)
            ec.write(w, t._3)
      )
    )

  /** `Encoder[Map[K, V]]` using an `Encoder[K]` for the map keys. */
  def encodeMapOf[K, V](using
      tagKey: Tag[Encoder[K]],
      tagVal: Tag[Encoder[V]],
      tagOut: Tag[Encoder[Map[K, V]]]
  ): TypedEntry[Encoder[K] *: Encoder[V] *: EmptyTuple, Encoder[Map[K, V]]] =
    TypedEntry(
      Entry(
        List(tagKey.tag, tagVal.tag),
        tagOut.tag,
        args =>
          val ek = args(0).asInstanceOf[Encoder[K]]
          val ev = args(1).asInstanceOf[Encoder[V]]
          Encoder[Map[K, V]]: (w, m) =>
            w.writeMapHeader(m.size)
            m.foreach { (k, v) =>
              ek.write(w, k)
              ev.write(w, v)
            }
            w
      )
    )

  /**
   * `Encoder[TreeMap[K, V]]` using an `Encoder[K]` for the map keys. Keys are emitted in sorted order
   *  (since `TreeMap.iterator` is sorted).
   */
  def encodeTreeMapOf[K, V](using
      tagKey: Tag[Encoder[K]],
      tagVal: Tag[Encoder[V]],
      tagOut: Tag[Encoder[TreeMap[K, V]]]
  ): TypedEntry[Encoder[K] *: Encoder[V] *: EmptyTuple, Encoder[TreeMap[K, V]]] =
    TypedEntry(
      Entry(
        List(tagKey.tag, tagVal.tag),
        tagOut.tag,
        args =>
          val ek = args(0).asInstanceOf[Encoder[K]]
          val ev = args(1).asInstanceOf[Encoder[V]]
          Encoder[TreeMap[K, V]]: (w, m) =>
            w.writeMapHeader(m.size)
            m.iterator.foreach { (k, v) =>
              ek.write(w, k)
              ev.write(w, v)
            }
            w
      )
    )
