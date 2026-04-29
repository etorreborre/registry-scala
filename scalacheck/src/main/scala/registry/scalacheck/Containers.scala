package registry.scalacheck

import izumi.reflect.Tag
import org.scalacheck.Gen
import registry.{Entry, TypedEntry}

/**
 * Container combinators — each is a `TypedEntry` that takes one or more element-level `Gen[T]` inputs
 * and produces a `Gen[F[T]]` output. Drop them into a registry with `*:` / `+:` / `-:` alongside your
 * `value(Gen.xxx)` leaves.
 *
 * Each helper exists as a single registered entry; ordinary registry resolution + subtype-aware lookup
 * wire the element generator in from elsewhere in the registry. Size-bounded variants take the bounds
 * as value arguments (`listOfN[Int](5)`, `listOfMinMax[Int](1, 5)`) and bake them into the closure.
 */

// ---- single-input containers ----------------------------------------------------------------------

/** Register `Gen[T] => Gen[List[T]]` — zero-or-more via `Gen.listOf`. */
def listOf[T](using
    inTag: Tag[Gen[T]],
    outTag: Tag[Gen[List[T]]]
): TypedEntry[Gen[T] *: EmptyTuple, Gen[List[T]]] =
  mk1[T, List[T]](Gen.listOf(_))

/** Register `Gen[T] => Gen[List[T]]` — one-or-more via `Gen.nonEmptyListOf`. */
def nonEmptyListOf[T](using
    inTag: Tag[Gen[T]],
    outTag: Tag[Gen[List[T]]]
): TypedEntry[Gen[T] *: EmptyTuple, Gen[List[T]]] =
  mk1[T, List[T]](Gen.nonEmptyListOf(_))

/** Register `Gen[T] => Gen[List[T]]` — exactly `n` elements via `Gen.listOfN`. */
def listOfN[T](n: Int)(using
    inTag: Tag[Gen[T]],
    outTag: Tag[Gen[List[T]]]
): TypedEntry[Gen[T] *: EmptyTuple, Gen[List[T]]] =
  mk1[T, List[T]](Gen.listOfN(n, _))

/** Register `Gen[T] => Gen[List[T]]` — exactly `n` elements, `n` required ≥ 1 for "non-empty" to be meaningful. */
def nonEmptyListOfN[T](n: Int)(using
    inTag: Tag[Gen[T]],
    outTag: Tag[Gen[List[T]]]
): TypedEntry[Gen[T] *: EmptyTuple, Gen[List[T]]] =
  require(n >= 1, s"nonEmptyListOfN requires n >= 1, got $n")
  mk1[T, List[T]](Gen.listOfN(n, _))

/** Register `Gen[T] => Gen[List[T]]` — size between `min` and `max` inclusive. */
def listOfMinMax[T](min: Int, max: Int)(using
    inTag: Tag[Gen[T]],
    outTag: Tag[Gen[List[T]]]
): TypedEntry[Gen[T] *: EmptyTuple, Gen[List[T]]] =
  mk1[T, List[T]](g => Gen.chooseNum(min, max).flatMap(Gen.listOfN(_, g)))

/** Register `Gen[T] => Gen[Option[T]]` via `Gen.option`. */
def optionOf[T](using
    inTag: Tag[Gen[T]],
    outTag: Tag[Gen[Option[T]]]
): TypedEntry[Gen[T] *: EmptyTuple, Gen[Option[T]]] =
  mk1[T, Option[T]](Gen.option(_))

/** Register `Gen[T] => Gen[IndexedSeq[T]]` — zero-or-more via `Gen.containerOf[Vector, T]`
 *  (an `IndexedSeq`). */
def indexedSeqOf[T](using
    inTag: Tag[Gen[T]],
    outTag: Tag[Gen[IndexedSeq[T]]]
): TypedEntry[Gen[T] *: EmptyTuple, Gen[IndexedSeq[T]]] =
  mk1[T, IndexedSeq[T]](Gen.containerOf[Vector, T](_))

/** Register `Gen[T] => Gen[IndexedSeq[T]]` — one-or-more via `Gen.nonEmptyContainerOf[Vector, T]`. */
def nonEmptyIndexedSeqOf[T](using
    inTag: Tag[Gen[T]],
    outTag: Tag[Gen[IndexedSeq[T]]]
): TypedEntry[Gen[T] *: EmptyTuple, Gen[IndexedSeq[T]]] =
  mk1[T, IndexedSeq[T]](Gen.nonEmptyContainerOf[Vector, T](_))

/** Register `Gen[T] => Gen[IndexedSeq[T]]` — exactly `n` elements via `Gen.containerOfN[Vector, T]`. */
def indexedSeqOfN[T](n: Int)(using
    inTag: Tag[Gen[T]],
    outTag: Tag[Gen[IndexedSeq[T]]]
): TypedEntry[Gen[T] *: EmptyTuple, Gen[IndexedSeq[T]]] =
  mk1[T, IndexedSeq[T]](Gen.containerOfN[Vector, T](n, _))

/** Register `Gen[T] => Gen[IndexedSeq[T]]` — size between `min` and `max` inclusive. */
def indexedSeqOfMinMax[T](min: Int, max: Int)(using
    inTag: Tag[Gen[T]],
    outTag: Tag[Gen[IndexedSeq[T]]]
): TypedEntry[Gen[T] *: EmptyTuple, Gen[IndexedSeq[T]]] =
  mk1[T, IndexedSeq[T]](g => Gen.chooseNum(min, max).flatMap(Gen.containerOfN[Vector, T](_, g)))

/** Register `Gen[T] => Gen[Set[T]]` via `Gen.containerOf[Set, T]`. */
def setOf[T](using
    inTag: Tag[Gen[T]],
    outTag: Tag[Gen[Set[T]]]
): TypedEntry[Gen[T] *: EmptyTuple, Gen[Set[T]]] =
  mk1[T, Set[T]](Gen.containerOf[Set, T](_))

/** Register `Gen[T] => Gen[Set[T]]` — of exactly `n` distinct elements (ScalaCheck retries if
 * duplicates are generated). */
def setOfN[T](n: Int)(using
    inTag: Tag[Gen[T]],
    outTag: Tag[Gen[Set[T]]]
): TypedEntry[Gen[T] *: EmptyTuple, Gen[Set[T]]] =
  mk1[T, Set[T]](Gen.containerOfN[Set, T](n, _))

// ---- multi-input containers -----------------------------------------------------------------------

/** Register `(Gen[L], Gen[R]) => Gen[Either[L, R]]` — uniform left/right via `Gen.either`. */
def eitherOf[L, R](using
    lTag: Tag[Gen[L]],
    rTag: Tag[Gen[R]],
    outTag: Tag[Gen[Either[L, R]]]
): TypedEntry[Gen[L] *: Gen[R] *: EmptyTuple, Gen[Either[L, R]]] =
  mk2[L, R, Either[L, R]](Gen.either(_, _))

/** Register `(Gen[S], Gen[T]) => Gen[(S, T)]`. */
def pairOf[S, T](using
    sTag: Tag[Gen[S]],
    tTag: Tag[Gen[T]],
    outTag: Tag[Gen[(S, T)]]
): TypedEntry[Gen[S] *: Gen[T] *: EmptyTuple, Gen[(S, T)]] =
  mk2[S, T, (S, T)]((gS, gT) => gS.flatMap(s => gT.map(t => (s, t))))

/** Register `(Gen[S], Gen[T], Gen[U]) => Gen[(S, T, U)]`. */
def tripleOf[S, T, U](using
    sTag: Tag[Gen[S]],
    tTag: Tag[Gen[T]],
    uTag: Tag[Gen[U]],
    outTag: Tag[Gen[(S, T, U)]]
): TypedEntry[Gen[S] *: Gen[T] *: Gen[U] *: EmptyTuple, Gen[(S, T, U)]] =
  TypedEntry(
    Entry(
      inputs = List(sTag.tag, tTag.tag, uTag.tag),
      output = outTag.tag,
      invoke = args =>
        val gS = args(0).asInstanceOf[Gen[S]]
        val gT = args(1).asInstanceOf[Gen[T]]
        val gU = args(2).asInstanceOf[Gen[U]]
        gS.flatMap(s => gT.flatMap(t => gU.map(u => (s, t, u))))
    )
  )

/** Register `(Gen[K], Gen[V]) => Gen[Map[K, V]]` via `Gen.mapOf`. */
def mapOf[K, V](using
    kTag: Tag[Gen[K]],
    vTag: Tag[Gen[V]],
    outTag: Tag[Gen[Map[K, V]]]
): TypedEntry[Gen[K] *: Gen[V] *: EmptyTuple, Gen[Map[K, V]]] =
  TypedEntry(
    Entry(
      inputs = List(kTag.tag, vTag.tag),
      output = outTag.tag,
      invoke = args =>
        val gK = args(0).asInstanceOf[Gen[K]]
        val gV = args(1).asInstanceOf[Gen[V]]
        Gen.mapOf(gK.flatMap(k => gV.map(v => (k, v))))
    )
  )

/** Register `(Gen[K], Gen[V]) => Gen[Map[K, V]]` — of exactly `n` entries via `Gen.mapOfN`. */
def mapOfN[K, V](n: Int)(using
    kTag: Tag[Gen[K]],
    vTag: Tag[Gen[V]],
    outTag: Tag[Gen[Map[K, V]]]
): TypedEntry[Gen[K] *: Gen[V] *: EmptyTuple, Gen[Map[K, V]]] =
  TypedEntry(
    Entry(
      inputs = List(kTag.tag, vTag.tag),
      output = outTag.tag,
      invoke = args =>
        val gK = args(0).asInstanceOf[Gen[K]]
        val gV = args(1).asInstanceOf[Gen[V]]
        Gen.mapOfN(n, gK.flatMap(k => gV.map(v => (k, v))))
    )
  )

// ---- internal helpers -----------------------------------------------------------------------------

private def mk1[In, Out](
    impl: Gen[In] => Gen[Out]
)(using inTag: Tag[Gen[In]], outTag: Tag[Gen[Out]]): TypedEntry[Gen[In] *: EmptyTuple, Gen[Out]] =
  TypedEntry(
    Entry(
      inputs = List(inTag.tag),
      output = outTag.tag,
      invoke = args => impl(args(0).asInstanceOf[Gen[In]])
    )
  )

private def mk2[A, B, Out](impl: (Gen[A], Gen[B]) => Gen[Out])(using
    aTag: Tag[Gen[A]],
    bTag: Tag[Gen[B]],
    outTag: Tag[Gen[Out]]
): TypedEntry[Gen[A] *: Gen[B] *: EmptyTuple, Gen[Out]] =
  TypedEntry(
    Entry(
      inputs = List(aTag.tag, bTag.tag),
      output = outTag.tag,
      invoke = args =>
        val gA = args(0).asInstanceOf[Gen[A]]
        val gB = args(1).asInstanceOf[Gen[B]]
        impl(gA, gB)
    )
  )
