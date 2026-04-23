package registry

import izumi.reflect.Tag

/** Register a case class / plain class primary constructor by naming its type: `fun[Foo]`.
 *
 * Returns a [[TypedEntry]] whose `Ins` tuple is the constructor's parameter types in declaration order,
 * and whose `Out` is `T`. Works for case classes, plain classes, and classes with multi-parameter-list
 * or `using`/`implicit` constructors.
 */
transparent inline def fun[T]: TypedEntry[? <: Tuple, T] = ${ FunMacros.funTypeImpl[T] }

/** Register any function value, lambda, or eta-expanded method reference: `fun(Foo.apply)` or
 * `fun((a, b) => ...)`. The function's argument types become the `Ins` tuple; its return type is `Out`.
 */
transparent inline def fun[F](inline f: F): TypedEntry[? <: Tuple, ?] = ${ FunMacros.funValueImpl[F]('f) }

/** Register a constant value as a zero-input entry. */
def value[T](x: T)(using tag: Tag[T]): TypedEntry[EmptyTuple, T] =
  TypedEntry(Entry(Nil, tag.tag, _ => x))
