package registry.scalacheck

import org.scalacheck.Gen

private[scalacheck] object GenCombine:

  /**
   * Sequence a list of untyped `Gen[?]` into a `Gen[T]` by collecting their generated values and then
   * applying `build` to the vector of collected values. Used by the `gen` macro-emitted closures.
   *
   * `passthrough(i) == true` means the `Gen[?]` at slot `i` is itself the value `build` expects —
   * include it as-is rather than sampling from it. This supports parameters whose declared type is
   * already `Gen[X]`: the registry resolves a `Gen[X]` value, and `build` wants that `Gen[X]`, not
   * a sampled `X`.
   */
  def combineGens[T](
      gens: Seq[Gen[?]],
      passthrough: Seq[Boolean],
      build: Seq[Any] => T
  ): Gen[T] =
    if gens.isEmpty then Gen.const(build(Nil))
    else
      gens
        .zip(passthrough)
        .foldLeft(Gen.const(Vector.empty[Any]): Gen[Vector[Any]]) {
          case (accGen, (g, true))  => accGen.map(_ :+ g)
          case (accGen, (g, false)) => accGen.flatMap(acc => g.map(v => acc :+ v))
        }
        .map(values => build(values.toSeq))

  /**
   * Same as [[combineGens]] but `build` returns a `Gen[T]` directly. The final step is a `flatMap`
   * rather than a `map`, yielding `Gen[T]` — avoiding the `Gen[Gen[T]]` double-wrap that would
   * result from `combineGens` applied to a Gen-returning builder. Used by `gen(f)` when `f`
   * returns `Gen[?]`.
   */
  def combineGensFlat[T](
      gens: Seq[Gen[?]],
      passthrough: Seq[Boolean],
      build: Seq[Any] => Gen[T]
  ): Gen[T] =
    if gens.isEmpty then build(Nil)
    else
      gens
        .zip(passthrough)
        .foldLeft(Gen.const(Vector.empty[Any]): Gen[Vector[Any]]) {
          case (accGen, (g, true))  => accGen.map(_ :+ g)
          case (accGen, (g, false)) => accGen.flatMap(acc => g.map(v => acc :+ v))
        }
        .flatMap(values => build(values.toSeq))

  /** Uniformly pick one of `gens` and delegate to it. Used by `sum` to combine per-case generators. */
  def pickOne[T](gens: Seq[Gen[T]]): Gen[T] =
    gens.length match
      case 0 => sys.error("sum[T]: no subtype generators to pick from")
      case 1 => gens.head
      case n => Gen.choose(0, n - 1).flatMap(gens(_))
