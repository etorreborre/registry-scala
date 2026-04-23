package registry.cats

import _root_.cats.Applicative
import izumi.reflect.Tag
import registry.{Entry, TypedEntry}

/**
 * Lift a class primary constructor into `F`: `funTo[F, Foo]`.
 *
 * Returns a [[registry.TypedEntry]] whose `Ins` tuple is `(F[P0], F[P1], …)` (the constructor's parameter
 * types, each wrapped in `F`) and whose `Out` is `F[Foo]`. At runtime the entry's closure sequences the
 * effects via `Applicative[F].product` (through [[Combine.combineF]]) and applies the primary constructor
 * to the collected sample values.
 *
 * Analogous to the Haskell `registry`'s `funTo @m`.
 */
transparent inline def funTo[F[_], T](using app: Applicative[F]): TypedEntry[? <: Tuple, F[T]] =
  ${ FunToMacro.typeImpl[F, T]('app) }

/** Lift a pure value into `F` via `Applicative[F].pure`: `valTo[F](x)`. */
def valTo[F[_], T](x: T)(using applicative: Applicative[F], tag: Tag[F[T]]): TypedEntry[EmptyTuple, F[T]] =
  TypedEntry(Entry(Nil, tag.tag, _ => applicative.pure(x)))
