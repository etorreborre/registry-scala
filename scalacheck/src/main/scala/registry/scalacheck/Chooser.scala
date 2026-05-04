package registry.scalacheck

import org.scalacheck.Gen

/**
 * Pick one of several `Gen[T]` values.
 *
 * It is pluggable so users can swap uniform random for weighted, deterministic cycling, or anything else
 * without touching the generators themselves.
 *
 * A `Chooser` is registered as an ordinary value in the registry (`value(Chooser.uniform)`) and consumed
 * by `genTrait[T]` alongside the per-variant generators.
 */
trait Chooser:
  def pickOne[T](gens: Seq[Gen[T]]): Gen[T]

object Chooser:

  /** Uniform random pick. */
  val uniform: Chooser = new Chooser:
    def pickOne[T](gens: Seq[Gen[T]]): Gen[T] = GenCombine.pickOne(gens)

  /**
   * Weighted pick by position. `weights(i)` is the relative frequency of picking the i-th generator.
   * The order of generators matches the declaration order of the subtypes in `Mirror.SumOf[T].MirroredElemTypes`.
   */
  def weighted(weights: Int*): Chooser = new Chooser:
    def pickOne[T](gens: Seq[Gen[T]]): Gen[T] =
      require(
        gens.length == weights.length,
        s"Chooser.weighted: expected ${weights.length} generators, got ${gens.length}"
      )
      Gen.frequency(weights.zip(gens)*)

  /** Always pick the generator at the given index. Useful for deterministic tests of specific variants. */
  def only(index: Int): Chooser = new Chooser:
    def pickOne[T](gens: Seq[Gen[T]]): Gen[T] =
      require(index >= 0 && index < gens.length, s"Chooser.only($index): out of range for ${gens.length} gens")
      gens(index)
