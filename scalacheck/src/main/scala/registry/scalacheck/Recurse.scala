package registry.scalacheck

import izumi.reflect.Tag
import org.scalacheck.Gen
import registry.{Entry, Registry}

/**
 * Recursion helpers — register a `Gen[T]` whose body refers back to `Gen[T]` itself. Two inputs:
 *
 *   - `Gen[T]` — the base / leaf case, resolved *from the registry*. The runtime resolver's
 *     "skip already-in-flight entries" behaviour makes the recursive lookup pick another `Gen[T]`
 *     producer (typically a `gen(Leaf: Tree)` registered alongside).
 *
 *   - [[Sized]] — the sampling strategy: when to terminate, and how to shrink size on each
 *     recursive step. `genRec` bundles a `value(Sized.default)` so call sites need no
 *     changes; override by prepending your own `value(mySized)`.
 *
 * Returns a [[Registry]] (not a single `TypedEntry`) so the default `Sized` can travel with the
 * recursive entry. The registry's external `Ins` is just `Gen[T]` (Sized is provided
 * internally); its external `Outs` are `Gen[T]` and `Sized`, so `makeSafe` sees both as
 * available.
 */

/** Register a size-bounded recursive `Gen[T]` that uses the ambient ScalaCheck size. */
def genRec[T](grow: Gen[T] => Gen[T])(using
    tag: Tag[Gen[T]],
    sizedTag: Tag[Sized]
): Registry[Gen[T] *: EmptyTuple, Gen[T] *: Sized *: EmptyTuple] =
  buildRecursiveRegistry(maxSize = None, grow)

/**
 * Same as [[genRec]] but caps the starting size to `maxSize`, so termination is bounded
 * regardless of the outer ScalaCheck size parameter.
 */
def genRec[T](maxSize: Int)(grow: Gen[T] => Gen[T])(using
    tag: Tag[Gen[T]],
    sizedTag: Tag[Sized]
): Registry[Gen[T] *: EmptyTuple, Gen[T] *: Sized *: EmptyTuple] =
  buildRecursiveRegistry(maxSize = Some(maxSize), grow)

private def buildRecursiveRegistry[T](
    maxSize: Option[Int],
    grow: Gen[T] => Gen[T]
)(using
    tag: Tag[Gen[T]],
    sizedTag: Tag[Sized]
): Registry[Gen[T] *: EmptyTuple, Gen[T] *: Sized *: EmptyTuple] =
  Registry[Gen[T] *: EmptyTuple, Gen[T] *: Sized *: EmptyTuple](
    entries = List(
      buildRecursiveEntry(maxSize, grow),
      Entry(Nil, sizedTag.tag, _ => Sized.default)
    )
  )

private def buildRecursiveEntry[T](
    maxSize: Option[Int],
    grow: Gen[T] => Gen[T]
)(using tag: Tag[Gen[T]], sizedTag: Tag[Sized]): Entry =
  Entry(
    inputs = List(tag.tag, sizedTag.tag),
    output = tag.tag,
    invoke = args =>
      val base  = args(0).asInstanceOf[Gen[T]]
      val sized = args(1).asInstanceOf[Sized]
      val rec = Gen.recursive[T] { self =>
        Gen.sized { size =>
          sized.pickBase(size).flatMap { isBase =>
            if isBase then base
            else sized.nextSize(size).flatMap(n => Gen.resize(n, grow(self)))
          }
        }
      }
      maxSize.fold(rec)(n => Gen.resize(n, rec))
  )
