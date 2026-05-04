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
  inline def bridgeKeyEncoder[A](using
      ce: io.circe.KeyEncoder[A],
      tag: Tag[KeyEncoder[A]]
  ): TypedEntry[EmptyTuple, KeyEncoder[A]] =
    KeyEncoder.bridgeKeyEncoder[A]

  /** Lift a circe `KeyDecoder[A]` into a registry-native `KeyDecoder[A]`. */
  inline def bridgeKeyDecoder[A](using
      cd: io.circe.KeyDecoder[A],
      tag: Tag[KeyDecoder[A]]
  ): TypedEntry[EmptyTuple, KeyDecoder[A]] =
    KeyDecoder.bridgeKeyDecoder[A]

  /** Build a `KeyEncoder[A]` from a plain function. */
  inline def encodeKey[A](f: A => String)(using tag: Tag[KeyEncoder[A]]): TypedEntry[EmptyTuple, KeyEncoder[A]] =
    KeyEncoder.encodeKey(f)

  /** Build a `KeyDecoder[A]` from a parsing function. */
  inline def decodeKey[A](f: String => Either[String, A])(using
      tag: Tag[KeyDecoder[A]]
  ): TypedEntry[EmptyTuple, KeyDecoder[A]] =
    KeyDecoder.decodeKey(f)

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
