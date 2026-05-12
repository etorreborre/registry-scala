package registry.circe

import io.circe.KeyDecoder
import izumi.reflect.Tag
import registry.{Entry, TypedEntry}

/**
 * Namespace for `KeyDecoder[A]` (= `io.circe.KeyDecoder[A]`) registry entries. No registry-specific
 * wrapper — circe's `KeyDecoder` returns `Option[A]` (not `Either[String, A]`), so failures lose the
 * explanatory message; that's the price of consolidating onto circe's types.
 */
object KeyDecoders:

  /** Build a `KeyDecoder[A]` from a parsing function and return it as a `TypedEntry` ready to register. */
  def decodeKey[A](f: String => Option[A])(using
      tag: Tag[KeyDecoder[A]]
  ): TypedEntry[EmptyTuple, KeyDecoder[A]] =
    TypedEntry(Entry(Nil, tag.tag, _ => KeyDecoder.instance(f)))

  /** Summon a `KeyDecoder[A]` and register it as a [[TypedEntry]]. */
  def keyDecoderOf[A](using
      cd: KeyDecoder[A],
      tag: Tag[KeyDecoder[A]]
  ): TypedEntry[EmptyTuple, KeyDecoder[A]] =
    TypedEntry(Entry(Nil, tag.tag, _ => cd))

  /** `KeyDecoder` for `String` (identity). */
  val stringKeyDecoder: KeyDecoder[String] = KeyDecoder.decodeKeyString
