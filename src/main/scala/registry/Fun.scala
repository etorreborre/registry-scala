package registry

import izumi.reflect.Tag

/** Register a case class primary constructor by naming its type: `fun[Foo]`. */
inline def fun[T]: Entry = ${ FunMacros.funTypeImpl[T] }

/** Register any function value, lambda, or eta-expanded method reference: `fun(Foo.apply)` or `fun((a, b) => ...)`. */
inline def fun[T](inline f: T): Entry = ${ FunMacros.funValueImpl[T]('f) }

/** Register a constant value as a zero-input entry. */
def value[T](x: T)(using tag: Tag[T]): Entry =
  Entry(Nil, tag.tag, _ => x)
