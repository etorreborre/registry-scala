package registry.scalacheck

import org.scalacheck.Gen

private[scalacheck] object GenCombine:
  /** Sequence a list of untyped `Gen[?]` into a `Gen[T]` by collecting their generated values and then
   * applying `build` to the vector of collected values. Used by the `genFun` macro-emitted closures. */
  def combineGens[T](gens: Seq[Gen[?]], build: Seq[Any] => T): Gen[T] =
    if gens.isEmpty then Gen.const(build(Nil))
    else
      gens
        .foldLeft(Gen.const(Vector.empty[Any]): Gen[Vector[Any]]) { (accGen, g) =>
          accGen.flatMap(acc => g.map(v => acc :+ v))
        }
        .map(values => build(values.toSeq))

  /** Uniformly pick one of `gens` and delegate to it. Used by `genSum` to combine per-case generators. */
  def pickOne[T](gens: Seq[Gen[T]]): Gen[T] =
    gens.length match
      case 0 => sys.error("genSum[T]: no subtype generators to pick from")
      case 1 => gens.head
      case n => Gen.choose(0, n - 1).flatMap(gens(_))
