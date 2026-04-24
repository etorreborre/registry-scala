package registry.circe

import izumi.reflect.Tag
import registry.{Entry, TypedEntry}

/**
 * Decode a JSON object key (a `String`) into a value of type `A`, or report an error.
 *
 * Scala-port counterpart to aeson's `KeyDecoder`. Returns `Either[String, A]` so errors can be surfaced
 * with a message rather than only `None`.
 */
final case class KeyDecoder[A](decodeKeyAs: String => Either[String, A]):
  def map[B](f: A => B): KeyDecoder[B] = KeyDecoder(s => decodeKeyAs(s).map(f))

object KeyDecoder:

  /** Build a `KeyDecoder[A]` from a parsing function and return it as a `TypedEntry` ready to register. */
  def decodeKey[A](f: String => Either[String, A])(using tag: Tag[KeyDecoder[A]]): TypedEntry[EmptyTuple, KeyDecoder[A]] =
    TypedEntry(Entry(Nil, tag.tag, _ => KeyDecoder(f)))

  /** Lift a circe `KeyDecoder[A]` into a registry-native `KeyDecoder[A]`. */
  def bridgeKeyDecoder[A](using cd: io.circe.KeyDecoder[A], tag: Tag[KeyDecoder[A]]): TypedEntry[EmptyTuple, KeyDecoder[A]] =
    TypedEntry(
      Entry(
        Nil,
        tag.tag,
        _ => KeyDecoder[A](s => cd(s).toRight(s"cannot decode the key '$s'"))
      )
    )

  /** `KeyDecoder` for `String` (identity). */
  val stringKeyDecoder: KeyDecoder[String] = KeyDecoder(s => Right(s))
