package registry.circe

import io.circe.KeyEncoder
import izumi.reflect.Tag
import registry.{Entry, TypedEntry}

/**
 * Namespace for `KeyEncoder[A]` (= `io.circe.KeyEncoder[A]`) registry entries. No registry-specific
 * wrapper — `KeyEncoder[A]` is circe's typeclass directly.
 */
object KeyEncoders:

  /**
   * Build a `KeyEncoder[A]` from a plain `A => String` function and return it as a `TypedEntry`
   * ready to register.
   */
  def encodeKey[A](f: A => String)(using tag: Tag[KeyEncoder[A]]): TypedEntry[EmptyTuple, KeyEncoder[A]] =
    TypedEntry(Entry(Nil, tag.tag, _ => KeyEncoder.instance(f)))

  /** Summon a `KeyEncoder[A]` and register it as a [[TypedEntry]]. */
  def keyEncoderOf[A](using
      ce: KeyEncoder[A],
      tag: Tag[KeyEncoder[A]]
  ): TypedEntry[EmptyTuple, KeyEncoder[A]] =
    TypedEntry(Entry(Nil, tag.tag, _ => ce))

  /** `KeyEncoder` for `String` (identity). */
  val stringKeyEncoder: KeyEncoder[String] = KeyEncoder.encodeKeyString
