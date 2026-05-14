package registry

import io.bullet.borer.{Decoder, Encoder}
import izumi.reflect.Tag
import registry.{Entry, Registry, TypedEntry, fun, value}

package object cbor:

  /** Re-export the two borer typeclasses so `import registry.cbor.*` is a one-stop shop. */
  export io.bullet.borer.{Encoder, Decoder, Codec, Dom}

  // -- Re-exports so users can write `import registry.cbor.*` and get a one-stop shop --

  /** Summon an `Encoder[A]` and register it. */
  inline def encoderOf[A](using ce: Encoder[A], tag: Tag[Encoder[A]]): TypedEntry[EmptyTuple, Encoder[A]] =
    Encoders.encoderOf[A]

  /** Summon a `Decoder[A]` and register it. */
  inline def decoderOf[A](using cd: Decoder[A], tag: Tag[Decoder[A]]): TypedEntry[EmptyTuple, Decoder[A]] =
    Decoders.decoderOf[A]

  // -- Primitive `Encoder[T]` / `Decoder[T]` entries, ready to `*:` into a registry --

  /** `Encoder[String]` as a registry entry. Equivalent to `value(Encoders.string)`. */
  val stringEncoder: TypedEntry[EmptyTuple, Encoder[String]] = value(Encoders.string)

  /** `Encoder[Int]` as a registry entry. */
  val intEncoder: TypedEntry[EmptyTuple, Encoder[Int]] = value(Encoders.int)

  /** `Encoder[Long]` as a registry entry. */
  val longEncoder: TypedEntry[EmptyTuple, Encoder[Long]] = value(Encoders.long)

  /** `Encoder[Boolean]` as a registry entry. */
  val booleanEncoder: TypedEntry[EmptyTuple, Encoder[Boolean]] = value(Encoders.boolean)

  /** `Encoder[Double]` as a registry entry. */
  val doubleEncoder: TypedEntry[EmptyTuple, Encoder[Double]] = value(Encoders.double)

  /** `Encoder[Byte]` as a registry entry. */
  val byteEncoder: TypedEntry[EmptyTuple, Encoder[Byte]] = value(Encoders.byte)

  /** `Encoder[BigInt]` as a registry entry. */
  val bigIntEncoder: TypedEntry[EmptyTuple, Encoder[BigInt]] = value(Encoders.bigInt)

  /** `Encoder[Unit]` as a registry entry — emits CBOR null. */
  val unitEncoder: TypedEntry[EmptyTuple, Encoder[Unit]] = value(Encoders.unit)

  /** `Decoder[String]` as a registry entry. */
  val stringDecoder: TypedEntry[EmptyTuple, Decoder[String]] = value(Decoders.string)

  /** `Decoder[Int]` as a registry entry. */
  val intDecoder: TypedEntry[EmptyTuple, Decoder[Int]] = value(Decoders.int)

  /** `Decoder[Long]` as a registry entry. */
  val longDecoder: TypedEntry[EmptyTuple, Decoder[Long]] = value(Decoders.long)

  /** `Decoder[Boolean]` as a registry entry. */
  val booleanDecoder: TypedEntry[EmptyTuple, Decoder[Boolean]] = value(Decoders.boolean)

  /** `Decoder[Double]` as a registry entry. */
  val doubleDecoder: TypedEntry[EmptyTuple, Decoder[Double]] = value(Decoders.double)

  /** `Decoder[Byte]` as a registry entry. */
  val byteDecoder: TypedEntry[EmptyTuple, Decoder[Byte]] = value(Decoders.byte)

  /** `Decoder[BigInt]` as a registry entry. */
  val bigIntDecoder: TypedEntry[EmptyTuple, Decoder[BigInt]] = value(Decoders.bigInt)

  /** `Decoder[Unit]` as a registry entry — accepts any CBOR value. */
  val unitDecoder: TypedEntry[EmptyTuple, Decoder[Unit]] = value(Decoders.unit)

  /**
   * Derive an `Encoder[S]` from a registered `Encoder[T]` by contramapping `f: S => T`.
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
   * Derive a `Decoder[S]` from a registered `Decoder[T]` by mapping `f: T => S`.
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

  /**
   * Derive a `Decoder[S]` from a registered `Decoder[T]` by mapping `f: T => Either[String, S]`.
   */
  def emap[S, T](f: T => Either[String, S])(using
      tagIn: Tag[Decoder[T]],
      tagOut: Tag[Decoder[S]]
  ): TypedEntry[Decoder[T] *: EmptyTuple, Decoder[S]] =
    TypedEntry(
      Entry(
        List(tagIn.tag),
        tagOut.tag,
        args =>
          val d = args(0).asInstanceOf[Decoder[T]]
          Decoder { r =>
            val t = d.read(r)
            f(t) match
              case Right(s) => s
              case Left(e)  => r.validationFailure(e)
          }
      )
    )

  // -- Combinator re-exports (Encoder side) --

  inline def encodeOptionOf[A](using
      Tag[Encoder[A]],
      Tag[Encoder[Option[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[Option[A]]] =
    Encoders.encodeOptionOf[A]

  inline def encodeListOf[A](using
      Tag[Encoder[A]],
      Tag[Encoder[List[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[List[A]]] =
    Encoders.encodeListOf[A]

  inline def encodeSeqOf[A](using
      Tag[Encoder[A]],
      Tag[Encoder[Seq[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[Seq[A]]] =
    Encoders.encodeSeqOf[A]

  inline def encodeVectorOf[A](using
      Tag[Encoder[A]],
      Tag[Encoder[Vector[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[Vector[A]]] =
    Encoders.encodeVectorOf[A]

  inline def encodeIArrayOf[A](using
      Tag[Encoder[A]],
      Tag[Encoder[IArray[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[IArray[A]]] =
    Encoders.encodeIArrayOf[A]

  inline def encodeSetOf[A](using
      Tag[Encoder[A]],
      Tag[Encoder[Set[A]]]
  ): TypedEntry[Encoder[A] *: EmptyTuple, Encoder[Set[A]]] =
    Encoders.encodeSetOf[A]

  inline def encodePairOf[A, B](using
      Tag[Encoder[A]],
      Tag[Encoder[B]],
      Tag[Encoder[(A, B)]]
  ): TypedEntry[Encoder[A] *: Encoder[B] *: EmptyTuple, Encoder[(A, B)]] =
    Encoders.encodePairOf[A, B]

  inline def encodeTripleOf[A, B, C](using
      Tag[Encoder[A]],
      Tag[Encoder[B]],
      Tag[Encoder[C]],
      Tag[Encoder[(A, B, C)]]
  ): TypedEntry[Encoder[A] *: Encoder[B] *: Encoder[C] *: EmptyTuple, Encoder[(A, B, C)]] =
    Encoders.encodeTripleOf[A, B, C]

  inline def encodeMapOf[K, V](using
      Tag[Encoder[K]],
      Tag[Encoder[V]],
      Tag[Encoder[Map[K, V]]]
  ): TypedEntry[Encoder[K] *: Encoder[V] *: EmptyTuple, Encoder[Map[K, V]]] =
    Encoders.encodeMapOf[K, V]

  inline def encodeTreeMapOf[K, V](using
      Tag[Encoder[K]],
      Tag[Encoder[V]],
      Tag[Encoder[scala.collection.immutable.TreeMap[K, V]]]
  ): TypedEntry[Encoder[K] *: Encoder[V] *: EmptyTuple, Encoder[scala.collection.immutable.TreeMap[K, V]]] =
    Encoders.encodeTreeMapOf[K, V]

  // -- Combinator re-exports (Decoder side) --

  inline def decodeOptionOf[A](using
      Tag[Decoder[A]],
      Tag[Decoder[Option[A]]]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[Option[A]]] =
    Decoders.decodeOptionOf[A]

  inline def decodeListOf[A](using
      Tag[Decoder[A]],
      Tag[Decoder[List[A]]],
      Tag[A]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[List[A]]] =
    Decoders.decodeListOf[A]

  inline def decodeSeqOf[A](using
      Tag[Decoder[A]],
      Tag[Decoder[Seq[A]]],
      Tag[A]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[Seq[A]]] =
    Decoders.decodeSeqOf[A]

  inline def decodeVectorOf[A](using
      Tag[Decoder[A]],
      Tag[Decoder[Vector[A]]],
      Tag[A]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[Vector[A]]] =
    Decoders.decodeVectorOf[A]

  inline def decodeIArrayOf[A](using
      Tag[Decoder[A]],
      Tag[Decoder[IArray[A]]],
      Tag[A],
      scala.reflect.ClassTag[A]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[IArray[A]]] =
    Decoders.decodeIArrayOf[A]

  inline def decodeSetOf[A](using
      Tag[Decoder[A]],
      Tag[Decoder[Set[A]]],
      Tag[A]
  ): TypedEntry[Decoder[A] *: EmptyTuple, Decoder[Set[A]]] =
    Decoders.decodeSetOf[A]

  inline def decodePairOf[A, B](using
      Tag[Decoder[A]],
      Tag[Decoder[B]],
      Tag[Decoder[(A, B)]],
      Tag[A],
      Tag[B]
  ): TypedEntry[Decoder[A] *: Decoder[B] *: EmptyTuple, Decoder[(A, B)]] =
    Decoders.decodePairOf[A, B]

  inline def decodeTripleOf[A, B, C](using
      Tag[Decoder[A]],
      Tag[Decoder[B]],
      Tag[Decoder[C]],
      Tag[Decoder[(A, B, C)]],
      Tag[A],
      Tag[B],
      Tag[C]
  ): TypedEntry[Decoder[A] *: Decoder[B] *: Decoder[C] *: EmptyTuple, Decoder[(A, B, C)]] =
    Decoders.decodeTripleOf[A, B, C]

  inline def decodeMapOf[K, V](using
      Tag[Decoder[K]],
      Tag[Decoder[V]],
      Tag[Decoder[Map[K, V]]],
      Tag[K],
      Tag[V]
  ): TypedEntry[Decoder[K] *: Decoder[V] *: EmptyTuple, Decoder[Map[K, V]]] =
    Decoders.decodeMapOf[K, V]

  inline def decodeTreeMapOf[K, V](using
      Tag[Decoder[K]],
      Tag[Decoder[V]],
      Tag[Decoder[scala.collection.immutable.TreeMap[K, V]]],
      Tag[K],
      Tag[V],
      Ordering[K]
  ): TypedEntry[Decoder[K] *: Decoder[V] *: EmptyTuple, Decoder[scala.collection.immutable.TreeMap[K, V]]] =
    Decoders.decodeTreeMapOf[K, V]

  /**
   * Default entries required by the `encoder[T]` macro: a `ConstructorEncoder` and `CborOptions`.
   */
  def defaultEncoderOptions =
    value(ConstructorEncoder.default) *:
      value(CborOptions.default)

  /**
   * Default entries required by the `decoder[T]` macro: a `ConstructorsDecoder` and `CborOptions`.
   */
  def defaultDecoderOptions =
    value(ConstructorsDecoder.default) *:
      value(CborOptions.default)

  /**
   * Resolve an `Encoder[T]` from a registry. Thin alias for `r.make[Encoder[T]]` — symmetrical
   * with `registry-circe`'s `makeEncoder` / `registry-scalacheck`'s `makeGen`.
   */
  extension [AllIns <: Tuple, AllOuts <: Tuple](r: Registry[AllIns, AllOuts])
    def makeEncoder[T](using tag: Tag[Encoder[T]]): Encoder[T] = r.make[Encoder[T]]
    def makeDecoder[T](using tag: Tag[Decoder[T]]): Decoder[T] = r.make[Decoder[T]]
