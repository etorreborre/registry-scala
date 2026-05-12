package registry.circe

import io.circe.{Encoder, Json, JsonObject, KeyEncoder, Printer}
import izumi.reflect.Tag
import registry.{Entry, Registry, TypedEntry, value}
import scala.collection.immutable.TreeMap

/**
 * Namespace for primitive `Encoder[A]` (= `io.circe.Encoder[A]`) entries and the registry
 * combinators that derive new encoders from existing ones.
 */
object Encoders:

  // ---- primitive built-ins ----

  /** Encode a `String` as a JSON string. */
  val string: Encoder[String] = Encoder.encodeString

  /** Encode an `Int` as a JSON number. */
  val int: Encoder[Int] = Encoder.encodeInt

  /** Encode a `Long` as a JSON number. */
  val long: Encoder[Long] = Encoder.encodeLong

  /** Encode a `Boolean` as a JSON boolean. */
  val boolean: Encoder[Boolean] = Encoder.encodeBoolean

  /** Encode a `Double` as a JSON number, or `Json.Null` for `NaN`/`Infinity`. */
  val double: Encoder[Double] = Encoder.instance(d => Json.fromDoubleOrNull(d))

  /** Encode a `Unit` as an empty JSON object (matches aeson's default). */
  val unit: Encoder[Unit] = Encoder.instance(_ => Json.obj())

  /** Encode a `Byte` as a JSON number. */
  val byte: Encoder[Byte] = Encoder.instance(b => Json.fromInt(b.toInt))

  /** Encode a `BigInt` as a JSON number. */
  val bigInt: Encoder[BigInt] = Encoder.encodeBigInt

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

  private val printer: Printer = Printer.noSpaces.copy(dropNullValues = false)

  /** Render a value as a compact JSON byte string. */
  def encodeByteString[A](e: Encoder[A], a: A): Array[Byte] =
    printer.print(e(a)).getBytes("UTF-8")

  /** Render a value as a compact JSON string. */
  def encodeString[A](e: Encoder[A], a: A): String =
    printer.print(e(a))

  /** Return the `Json` produced by the encoder (alias for symmetry with aeson's `encodeValue`). */
  def encodeValue[A](e: Encoder[A], a: A): Json = e(a)

  /** Build an `Encoder[A]` from a function returning `Json`. */
  def fromValue[A](f: A => Json): Encoder[A] = Encoder.instance(f)

  // ---- bridges ----

  /** Summon an `io.circe.Encoder[A]` and register it as a [[TypedEntry]]. */
  def encoderOf[A](using ce: Encoder[A], tag: Tag[Encoder[A]]): TypedEntry[EmptyTuple, Encoder[A]] =
    TypedEntry(Entry(Nil, tag.tag, _ => ce))

  // ---- combinators ----

  /** `Encoder[Option[A]]` where `None` encodes as `Json.Null`. */
  def encodeOptionOf[A](using
      tagIn: Tag[Encoder[A]],
      tagOut: Tag[Encoder[Option[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[Option[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args => Encoder.encodeOption(using args(0).asInstanceOf[Encoder[A]])
      )
    )

  /** `Encoder[List[A]]`. */
  def encodeListOf[A](using
      tagIn: Tag[Encoder[A]],
      tagOut: Tag[Encoder[List[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[List[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args => Encoder.encodeList(using args(0).asInstanceOf[Encoder[A]])
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
        args => Encoder.encodeSeq(using args(0).asInstanceOf[Encoder[A]])
      )
    )

  /** `Encoder[Vector[A]]`. */
  def encodeVectorOf[A](using
      tagIn: Tag[Encoder[A]],
      tagOut: Tag[Encoder[Vector[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[Vector[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args => Encoder.encodeVector(using args(0).asInstanceOf[Encoder[A]])
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
          Encoder.instance[IArray[A]](as => Json.arr(as.toList.map(e(_))*))
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
        args => Encoder.encodeSet(using args(0).asInstanceOf[Encoder[A]])
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
          Encoder.encodeTuple2(using args(0).asInstanceOf[Encoder[A]], args(1).asInstanceOf[Encoder[B]])
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
          Encoder.encodeTuple3(using
            args(0).asInstanceOf[Encoder[A]],
            args(1).asInstanceOf[Encoder[B]],
            args(2).asInstanceOf[Encoder[C]]
          )
      )
    )

  /** `Encoder[Map[K, V]]` using a `KeyEncoder[K]` for the object keys. */
  def encodeMapOf[K, V](using
      tagKey: Tag[KeyEncoder[K]],
      tagVal: Tag[Encoder[V]],
      tagOut: Tag[Encoder[Map[K, V]]]
  ): TypedEntry[KeyEncoder[K] *: Encoder[V] *: EmptyTuple, Encoder[Map[K, V]]] =
    TypedEntry(
      Entry(
        List(tagKey.tag, tagVal.tag),
        tagOut.tag,
        args =>
          Encoder.encodeMap(using
            args(0).asInstanceOf[KeyEncoder[K]],
            args(1).asInstanceOf[Encoder[V]]
          )
      )
    )

  /** `Encoder[TreeMap[K, V]]` using a `KeyEncoder[K]` for the object keys. Keys are emitted in
    *  sorted order (since `TreeMap.iterator` is sorted). */
  def encodeTreeMapOf[K, V](using
      tagKey: Tag[KeyEncoder[K]],
      tagVal: Tag[Encoder[V]],
      tagOut: Tag[Encoder[TreeMap[K, V]]]
  ): TypedEntry[KeyEncoder[K] *: Encoder[V] *: EmptyTuple, Encoder[TreeMap[K, V]]] =
    TypedEntry(
      Entry(
        List(tagKey.tag, tagVal.tag),
        tagOut.tag,
        args =>
          val ek = args(0).asInstanceOf[KeyEncoder[K]]
          val ev = args(1).asInstanceOf[Encoder[V]]
          Encoder.instance[TreeMap[K, V]] { tm =>
            Json.fromJsonObject(JsonObject.fromIterable(tm.iterator.map((k, v) => ek(k) -> ev(v)).toList))
          }
      )
    )
