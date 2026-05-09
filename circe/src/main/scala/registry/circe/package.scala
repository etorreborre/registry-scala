package registry

import izumi.reflect.Tag
import registry.{Registry, TypedEntry, fun, value}

package object circe:

  // -- Re-exports so users can write `import registry.circe.*` and get a one-stop shop --

  /** Lift a circe `Encoder[A]` into a registry-native `Encoder[A]` ready to register. */
  inline def jsonEncoder[A](using ce: io.circe.Encoder[A], tag: Tag[Encoder[A]]): TypedEntry[EmptyTuple, Encoder[A]] =
    Encoder.jsonEncoder[A]

  /** Lift a circe `Decoder[A]` into a registry-native `Decoder[A]` ready to register. */
  inline def jsonDecoder[A](using cd: io.circe.Decoder[A], tag: Tag[Decoder[A]]): TypedEntry[EmptyTuple, Decoder[A]] =
    Decoder.jsonDecoder[A]

  /** Lift a circe `KeyEncoder[A]` into a registry-native `KeyEncoder[A]`. */
  inline def keyEncoder[A](using
      ce: io.circe.KeyEncoder[A],
      tag: Tag[KeyEncoder[A]]
  ): TypedEntry[EmptyTuple, KeyEncoder[A]] =
    KeyEncoder.keyEncoder[A]

  /** Lift a circe `KeyDecoder[A]` into a registry-native `KeyDecoder[A]`. */
  inline def keyDecoder[A](using
      cd: io.circe.KeyDecoder[A],
      tag: Tag[KeyDecoder[A]]
  ): TypedEntry[EmptyTuple, KeyDecoder[A]] =
    KeyDecoder.keyDecoder[A]

  /** Build a `KeyEncoder[A]` from a plain function. */
  inline def encodeKey[A](f: A => String)(using tag: Tag[KeyEncoder[A]]): TypedEntry[EmptyTuple, KeyEncoder[A]] =
    KeyEncoder.encodeKey(f)

  /** Build a `KeyDecoder[A]` from a parsing function. */
  inline def decodeKey[A](f: String => Either[String, A])(using
      tag: Tag[KeyDecoder[A]]
  ): TypedEntry[EmptyTuple, KeyDecoder[A]] =
    KeyDecoder.decodeKey(f)

  // -- Primitive `Encoder[T]` / `Decoder[T]` entries, ready to `*:` into a registry --

  /** `Encoder[String]` as a registry entry. Equivalent to `value(Encoder.string)`. */
  val stringEncoder: TypedEntry[EmptyTuple, Encoder[String]] = value(Encoder.string)

  /** `Encoder[Int]` as a registry entry. */
  val intEncoder: TypedEntry[EmptyTuple, Encoder[Int]] = value(Encoder.int)

  /** `Encoder[Long]` as a registry entry. */
  val longEncoder: TypedEntry[EmptyTuple, Encoder[Long]] = value(Encoder.long)

  /** `Encoder[Boolean]` as a registry entry. */
  val booleanEncoder: TypedEntry[EmptyTuple, Encoder[Boolean]] = value(Encoder.boolean)

  /** `Encoder[Double]` as a registry entry. */
  val doubleEncoder: TypedEntry[EmptyTuple, Encoder[Double]] = value(Encoder.double)

  /** `Encoder[Byte]` as a registry entry. */
  val byteEncoder: TypedEntry[EmptyTuple, Encoder[Byte]] = value(Encoder.byte)

  /** `Encoder[Unit]` as a registry entry — emits an empty JSON object. */
  val unitEncoder: TypedEntry[EmptyTuple, Encoder[Unit]] = value(Encoder.unit)

  /** `Decoder[String]` as a registry entry. */
  val stringDecoder: TypedEntry[EmptyTuple, Decoder[String]] = value(Decoder.string)

  /** `Decoder[Int]` as a registry entry. */
  val intDecoder: TypedEntry[EmptyTuple, Decoder[Int]] = value(Decoder.int)

  /** `Decoder[Long]` as a registry entry. */
  val longDecoder: TypedEntry[EmptyTuple, Decoder[Long]] = value(Decoder.long)

  /** `Decoder[Boolean]` as a registry entry. */
  val booleanDecoder: TypedEntry[EmptyTuple, Decoder[Boolean]] = value(Decoder.boolean)

  /** `Decoder[Double]` as a registry entry. */
  val doubleDecoder: TypedEntry[EmptyTuple, Decoder[Double]] = value(Decoder.double)

  /** `Decoder[Byte]` as a registry entry. */
  val byteDecoder: TypedEntry[EmptyTuple, Decoder[Byte]] = value(Decoder.byte)

  /** `Decoder[Unit]` as a registry entry — accepts any JSON. */
  val unitDecoder: TypedEntry[EmptyTuple, Decoder[Unit]] = value(Decoder.unit)

  /**
   * Derive an `Encoder[S]` from a registered `Encoder[T]` by contramapping `f: S => T`.
   *
   *   `contramap[RequestId, Long](_.asI64) *: rest` registers an `Encoder[RequestId]` whose body
   *   pulls `Encoder[Long]` from the rest of the registry and adapts it. Saves having to write a
   *   named local val plus a `value(...)` entry just to register a primitive newtype encoder.
   */
  def contramap[S, T](f: S => T)(using
      tagIn: Tag[Encoder[T]],
      tagOut: Tag[Encoder[S]]
  ): TypedEntry[Encoder[T] *: EmptyTuple, Encoder[S]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args => args(0).asInstanceOf[Encoder[T]].contramap(f)
      )
    )

  /**
   * Derive a `Decoder[S]` from a registered `Decoder[T]` by mapping `f: T => S`. Dual of
   * [[contramap]] for the decoder side.
   */
  def map[S, T](f: T => S)(using
      tagIn: Tag[Decoder[T]],
      tagOut: Tag[Decoder[S]]
  ): TypedEntry[Decoder[T] *: EmptyTuple, Decoder[S]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args => args(0).asInstanceOf[Decoder[T]].map(f)
      )
    )

  // -- Combinator re-exports (Encoder side) --

  inline def encodeOptionOf[A](using
      Tag[Encoder[A]],
      Tag[Encoder[Option[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[Option[A]]] =
    Encoder.encodeOptionOf[A]

  inline def encodeListOf[A](using
      Tag[Encoder[A]],
      Tag[Encoder[List[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[List[A]]] =
    Encoder.encodeListOf[A]

  inline def encodeSeqOf[A](using
      Tag[Encoder[A]],
      Tag[Encoder[Seq[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[Seq[A]]] =
    Encoder.encodeSeqOf[A]

  inline def encodeVectorOf[A](using
      Tag[Encoder[A]],
      Tag[Encoder[Vector[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[Vector[A]]] =
    Encoder.encodeVectorOf[A]

  inline def encodeIArrayOf[A](using
      Tag[Encoder[A]],
      Tag[Encoder[IArray[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[IArray[A]]] =
    Encoder.encodeIArrayOf[A]

  inline def encodeSetOf[A](using
      Tag[Encoder[A]],
      Tag[Encoder[Set[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[Set[A]]] =
    Encoder.encodeSetOf[A]

  inline def encodePairOf[A, B](using
      Tag[Encoder[A]],
      Tag[Encoder[B]],
      Tag[Encoder[(A, B)]]
  ): TypedEntry[Encoder[A] *: Encoder[B] *: EmptyTuple, Encoder[(A, B)]] =
    Encoder.encodePairOf[A, B]

  inline def encodeTripleOf[A, B, C](using
      Tag[Encoder[A]],
      Tag[Encoder[B]],
      Tag[Encoder[C]],
      Tag[Encoder[(A, B, C)]]
  ): TypedEntry[Encoder[A] *: Encoder[B] *: Encoder[C] *: EmptyTuple, Encoder[(A, B, C)]] =
    Encoder.encodeTripleOf[A, B, C]

  inline def encodeMapOf[K, V](using
      Tag[KeyEncoder[K]],
      Tag[Encoder[V]],
      Tag[Encoder[Map[K, V]]]
  ): TypedEntry[KeyEncoder[K] *: Encoder[V] *: EmptyTuple, Encoder[Map[K, V]]] =
    Encoder.encodeMapOf[K, V]

  // -- Combinator re-exports (Decoder side) --

  inline def decodeOptionOf[A](using
      Tag[Decoder[A]],
      Tag[Decoder[Option[A]]]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[Option[A]]] =
    Decoder.decodeOptionOf[A]

  inline def decodeListOf[A](using
      Tag[Decoder[A]],
      Tag[Decoder[List[A]]],
      Tag[A]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[List[A]]] =
    Decoder.decodeListOf[A]

  inline def decodeSeqOf[A](using
      Tag[Decoder[A]],
      Tag[Decoder[Seq[A]]],
      Tag[A]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[Seq[A]]] =
    Decoder.decodeSeqOf[A]

  inline def decodeVectorOf[A](using
      Tag[Decoder[A]],
      Tag[Decoder[Vector[A]]],
      Tag[A]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[Vector[A]]] =
    Decoder.decodeVectorOf[A]

  inline def decodeIArrayOf[A](using
      Tag[Decoder[A]],
      Tag[Decoder[IArray[A]]],
      Tag[A],
      scala.reflect.ClassTag[A]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[IArray[A]]] =
    Decoder.decodeIArrayOf[A]

  inline def decodeSetOf[A](using
      Tag[Decoder[A]],
      Tag[Decoder[Set[A]]],
      Tag[A]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[Set[A]]] =
    Decoder.decodeSetOf[A]

  inline def decodePairOf[A, B](using
      Tag[Decoder[A]],
      Tag[Decoder[B]],
      Tag[Decoder[(A, B)]],
      Tag[A],
      Tag[B]
  ): TypedEntry[Decoder[A] *: Decoder[B] *: EmptyTuple, Decoder[(A, B)]] =
    Decoder.decodePairOf[A, B]

  inline def decodeTripleOf[A, B, C](using
      Tag[Decoder[A]],
      Tag[Decoder[B]],
      Tag[Decoder[C]],
      Tag[Decoder[(A, B, C)]],
      Tag[A],
      Tag[B],
      Tag[C]
  ): TypedEntry[Decoder[A] *: Decoder[B] *: Decoder[C] *: EmptyTuple, Decoder[(A, B, C)]] =
    Decoder.decodeTripleOf[A, B, C]

  inline def decodeMapOf[K, V](using
      Tag[KeyDecoder[K]],
      Tag[Decoder[V]],
      Tag[Decoder[Map[K, V]]],
      Tag[K],
      Tag[V]
  ): TypedEntry[KeyDecoder[K] *: Decoder[V] *: EmptyTuple, Decoder[Map[K, V]]] =
    Decoder.decodeMapOf[K, V]

  /**
   * Default entries required by the `makeEncoder[T]` macro: a `ConstructorEncoder`, a `JsonOptions`,
   * and a built-in `KeyEncoder[String]`.
   */
  def defaultEncoderOptions =
    value(ConstructorEncoder.default) *:
      value(KeyEncoder.stringKeyEncoder) *:
      value(JsonOptions.default)

  /**
   * Default entries required by the `makeDecoder[T]` macro: a `ConstructorsDecoder`, a `JsonOptions`,
   * and a built-in `KeyDecoder[String]`.
   */
  def defaultDecoderOptions =
    value(ConstructorsDecoder.default) *:
      value(KeyDecoder.stringKeyDecoder) *:
      value(JsonOptions.default)
