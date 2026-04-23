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

/**
 * Lift an arbitrary function value into `F`: `funTo[F]((a: A, b: B) => c)` — produces an entry whose
 * `Ins` is `(F[A], F[B])` and `Out` is `F[C]`. `F` is passed explicitly; the function's argument and
 * return types are inferred from `f`. Also works for eta-expanded method references
 * (`funTo[F](Foo.apply)`).
 */
def funTo[F[_]]: FunToValueBuilder[F] = FunToValueBuilder.instance[F]

final class FunToValueBuilder[F[_]]():

  transparent inline def apply[Fn](inline f: Fn)(using
      app: Applicative[F]
  ): TypedEntry[? <: Tuple, ?] =
    ${ FunToMacro.valueImpl[F, Fn]('f, 'app) }

object FunToValueBuilder:
  private val any = new FunToValueBuilder[[x] =>> Any]()
  def instance[F[_]]: FunToValueBuilder[F] = any.asInstanceOf[FunToValueBuilder[F]]

/**
 * Lift a pure value into `F` via `Applicative[F].pure`: `valTo[F](x)`.
 *
 * Only `F` is passed as an explicit type parameter; `T` is inferred from `x`. The two-step signature
 * (`valTo[F]` returns a partially-applied builder) is the standard Scala 3 workaround for the lack of
 * "infer some type params, not others" at a single call site.
 */
def valTo[F[_]]: ValToBuilder[F] = ValToBuilder.instance[F]

final class ValToBuilder[F[_]]():

  def apply[T](x: T)(using
      applicative: Applicative[F],
      tag: Tag[F[T]]
  ): TypedEntry[EmptyTuple, F[T]] =
    TypedEntry(Entry(Nil, tag.tag, _ => applicative.pure(x)))

object ValToBuilder:
  private val any = new ValToBuilder[[x] =>> Any]()
  def instance[F[_]]: ValToBuilder[F] = any.asInstanceOf[ValToBuilder[F]]
