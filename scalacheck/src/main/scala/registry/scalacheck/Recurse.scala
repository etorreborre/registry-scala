package registry.scalacheck

import izumi.reflect.Tag
import org.scalacheck.Gen
import registry.{Entry, TypedEntry}

/**
 * Recursion helpers — register a `Gen[T]` whose body refers back to `Gen[T]` itself. The helper's
 * single input is another `Gen[T]` *from the registry* (the base / leaf case); its output is a
 * size-bounded `Gen[T]` built via `Gen.recursive` + `Gen.sized`. At size ≤ 0 the base is returned;
 * at deeper sizes it picks between `base` (weight 1) and `grow(self)` (weight 3), decrementing the
 * size before recursing.
 *
 * Relies on the runtime resolver's "skip already-in-flight entries" behaviour — resolving the
 * helper's `Gen[T]` input skips the helper itself and falls through to the next entry producing
 * `Gen[T]` (typically a `value(Gen.const(...))` leaf registered elsewhere in the registry).
 */

/** Register a size-bounded recursive `Gen[T]` that uses the ambient ScalaCheck size. */
def genRecursive[T](grow: Gen[T] => Gen[T])(using
    tag: Tag[Gen[T]]
): TypedEntry[Gen[T] *: EmptyTuple, Gen[T]] =
  mkRecursive[T](maxSize = None)(grow)

/** Same as [[genRecursive]] but caps the starting size to `maxSize`, so termination is bounded
 * regardless of the outer ScalaCheck size parameter. */
def genRecursive[T](maxSize: Int)(grow: Gen[T] => Gen[T])(using
    tag: Tag[Gen[T]]
): TypedEntry[Gen[T] *: EmptyTuple, Gen[T]] =
  mkRecursive[T](maxSize = Some(maxSize))(grow)

private def mkRecursive[T](
    maxSize: Option[Int]
)(grow: Gen[T] => Gen[T])(using tag: Tag[Gen[T]]): TypedEntry[Gen[T] *: EmptyTuple, Gen[T]] =
  TypedEntry(
    Entry(
      inputs = List(tag.tag),
      output = tag.tag,
      invoke = args =>
        val base = args(0).asInstanceOf[Gen[T]]
        val rec = Gen.recursive[T] { self =>
          Gen.sized { size =>
            if size <= 0 then base
            else
              Gen.frequency(
                1 -> base,
                3 -> Gen.resize(size - 1, grow(self))
              )
          }
        }
        maxSize.fold(rec)(n => Gen.resize(n, rec))
    )
  )
