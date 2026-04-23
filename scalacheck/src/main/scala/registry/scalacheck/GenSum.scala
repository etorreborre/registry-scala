package registry.scalacheck

import izumi.reflect.Tag
import org.scalacheck.Gen
import scala.compiletime.summonAll
import scala.deriving.Mirror
import registry.{Entry, TypedEntry}

/**
 * Register a sum type's generator by combining a `Gen[Sub_i]` for each subtype into a `Gen[T]` that picks
 * uniformly at random: `genSum[Animal]`.
 *
 * Requires `Mirror.SumOf[T]` — available automatically for sealed traits / sealed abstract classes whose
 * subtypes are case classes or case objects, and for Scala 3 `enum` types.
 *
 * Returns a [[registry.TypedEntry]] whose `Ins` is `(Gen[Sub_0], Gen[Sub_1], ...)` (the sum's cases each
 * wrapped in `Gen`) and whose `Out` is `Gen[T]`.
 *
 * Typical use: prepend `genSum[T]` *above* the per-subtype `genFun[Sub_i]` entries in the registry so that
 * `make[Gen[T]]` picks up the combined generator rather than the first subtype entry that subtype-matches.
 *
 * Analogous to the Haskell `registry-hedgehog`'s `makeGenerators` TH helper.
 */
transparent inline def genSum[T](using
    m: Mirror.SumOf[T]
): TypedEntry[Tuple.Map[m.MirroredElemTypes, Gen], Gen[T]] =
  val inputTags =
    summonAll[Tuple.Map[m.MirroredElemTypes, [s] =>> Tag[Gen[s]]]].toList.asInstanceOf[List[Tag[?]]]
  val outputTag = summon[Tag[Gen[T]]]
  TypedEntry(
    Entry(
      inputs = inputTags.map(_.tag),
      output = outputTag.tag,
      invoke = args => {
        // Each args(i) is a Gen[Sub_i]. Since Gen is covariant, Gen[Sub_i] <: Gen[T].
        val gens = args.map(_.asInstanceOf[Gen[T]])
        GenCombine.pickOne(gens)
      }
    )
  )
