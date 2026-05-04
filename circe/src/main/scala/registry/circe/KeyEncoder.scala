package registry.circe

import izumi.reflect.Tag
import registry.{Entry, TypedEntry}

/**
 * Encode a value as a JSON object key (a plain `String`).
 *
 * Scala-port counterpart to aeson's `KeyEncoder`. Circe already has its own `io.circe.KeyEncoder[A]`;
 * we define our own wrapper so the registry can track it as a distinct type and users can override it
 * contextually. Use [[bridgeKeyEncoder]] to lift a circe `KeyEncoder[A]` into this one.
 */
final case class KeyEncoder[A](encodeAsKey: A => String):
  def contramap[B](f: B => A): KeyEncoder[B] = KeyEncoder(b => encodeAsKey(f(b)))

object KeyEncoder:

  /**
   * Build a `KeyEncoder[A]` from a plain `A => String` function and return it as a `TypedEntry`
   * ready to register.
   */
  def encodeKey[A](f: A => String)(using tag: Tag[KeyEncoder[A]]): TypedEntry[EmptyTuple, KeyEncoder[A]] =
    TypedEntry(Entry(Nil, tag.tag, _ => KeyEncoder(f)))

  /** Lift a circe `KeyEncoder[A]` into a registry-native `KeyEncoder[A]`. */
  def bridgeKeyEncoder[A](using
      ce: io.circe.KeyEncoder[A],
      tag: Tag[KeyEncoder[A]]
  ): TypedEntry[EmptyTuple, KeyEncoder[A]] =
    TypedEntry(Entry(Nil, tag.tag, _ => KeyEncoder[A](a => ce(a))))

  /** `KeyEncoder` for `String` (identity). */
  val stringKeyEncoder: KeyEncoder[String] = KeyEncoder(identity)
