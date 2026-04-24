package registry.circe

import io.circe.{Json, JsonObject, Printer}
import izumi.reflect.Tag
import registry.{Entry, TypedEntry}

/**
 * An `Encoder[A]` converts a value of type `A` into a circe `Json` value.
 *
 * Scala-port counterpart to the Haskell `registry-aeson` `Encoder`. The Haskell version bundles a
 * streaming `Encoding` alongside the `Value`; circe has no such concept, so we produce `Json` only.
 */
final case class Encoder[A](encode: A => Json):
  def contramap[B](f: B => A): Encoder[B] = Encoder(b => encode(f(b)))

object Encoder:

  private val printer: Printer = Printer.noSpaces.copy(dropNullValues = false)

  /** Render a value as a compact JSON byte string. */
  def encodeByteString[A](e: Encoder[A], a: A): Array[Byte] =
    printer.print(e.encode(a)).getBytes("UTF-8")

  /** Render a value as a compact JSON string. */
  def encodeString[A](e: Encoder[A], a: A): String =
    printer.print(e.encode(a))

  /** Return the `Json` produced by the encoder (alias for symmetry with aeson's `encodeValue`). */
  def encodeValue[A](e: Encoder[A], a: A): Json = e.encode(a)

  /** Build an `Encoder[A]` from a function returning `Json`. */
  def fromValue[A](f: A => Json): Encoder[A] = Encoder(f)

  // ---- bridges ----

  /** Lift a circe `Encoder[A]` into a registry-native `Encoder[A]` ready to register. */
  def jsonEncoder[A](using ce: io.circe.Encoder[A], tag: Tag[Encoder[A]]): TypedEntry[EmptyTuple, Encoder[A]] =
    TypedEntry(Entry(Nil, tag.tag, _ => Encoder[A](a => ce(a))))

  /** A circe `Encoder[A]` → registry-native `Encoder[A]` as a plain value (not a `TypedEntry`). */
  def jsonEncoderOf[A](using ce: io.circe.Encoder[A]): Encoder[A] = Encoder(a => ce(a))

  // ---- combinators ----

  /** `Encoder[Option[A]]` where `None` encodes as `Json.Null`. */
  def encodeOptionOf[A](using tagIn: Tag[Encoder[A]], tagOut: Tag[Encoder[Option[A]]]): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[Option[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args =>
          val e = args(0).asInstanceOf[Encoder[A]]
          optionOfEncoder(e)
      )
    )

  def optionOfEncoder[A](e: Encoder[A]): Encoder[Option[A]] =
    Encoder:
      case None    => Json.Null
      case Some(a) => e.encode(a)

  /** `Encoder[List[A]]`. */
  def encodeListOf[A](using tagIn: Tag[Encoder[A]], tagOut: Tag[Encoder[List[A]]]): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[List[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args => listOfEncoder(args(0).asInstanceOf[Encoder[A]])
      )
    )

  def listOfEncoder[A](e: Encoder[A]): Encoder[List[A]] =
    Encoder(as => Json.arr(as.map(e.encode)*))

  /** `Encoder[Seq[A]]`. */
  def encodeSeqOf[A](using tagIn: Tag[Encoder[A]], tagOut: Tag[Encoder[Seq[A]]]): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[Seq[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args => seqOfEncoder(args(0).asInstanceOf[Encoder[A]])
      )
    )

  def seqOfEncoder[A](e: Encoder[A]): Encoder[Seq[A]] =
    Encoder(as => Json.arr(as.map(e.encode)*))

  /** `Encoder[Vector[A]]`. */
  def encodeVectorOf[A](using tagIn: Tag[Encoder[A]], tagOut: Tag[Encoder[Vector[A]]]): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[Vector[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args => vectorOfEncoder(args(0).asInstanceOf[Encoder[A]])
      )
    )

  def vectorOfEncoder[A](e: Encoder[A]): Encoder[Vector[A]] =
    Encoder(as => Json.arr(as.map(e.encode)*))

  /** `Encoder[Set[A]]`. */
  def encodeSetOf[A](using tagIn: Tag[Encoder[A]], tagOut: Tag[Encoder[Set[A]]]): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[Set[A]]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args => setOfEncoder(args(0).asInstanceOf[Encoder[A]])
      )
    )

  def setOfEncoder[A](e: Encoder[A]): Encoder[Set[A]] =
    Encoder(as => Json.arr(as.toList.map(e.encode)*))

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
          pairOfEncoder(
            args(0).asInstanceOf[Encoder[A]],
            args(1).asInstanceOf[Encoder[B]]
          )
      )
    )

  def pairOfEncoder[A, B](ea: Encoder[A], eb: Encoder[B]): Encoder[(A, B)] =
    Encoder { case (a, b) => Json.arr(ea.encode(a), eb.encode(b)) }

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
          tripleOfEncoder(
            args(0).asInstanceOf[Encoder[A]],
            args(1).asInstanceOf[Encoder[B]],
            args(2).asInstanceOf[Encoder[C]]
          )
      )
    )

  def tripleOfEncoder[A, B, C](ea: Encoder[A], eb: Encoder[B], ec: Encoder[C]): Encoder[(A, B, C)] =
    Encoder { case (a, b, c) => Json.arr(ea.encode(a), eb.encode(b), ec.encode(c)) }

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
          mapOfEncoder(
            args(0).asInstanceOf[KeyEncoder[K]],
            args(1).asInstanceOf[Encoder[V]]
          )
      )
    )

  def mapOfEncoder[K, V](ek: KeyEncoder[K], ev: Encoder[V]): Encoder[Map[K, V]] =
    Encoder(m => Json.fromJsonObject(JsonObject.fromIterable(m.toList.map((k, v) => ek.encodeAsKey(k) -> ev.encode(v)))))
