package registry.scalacheck

import izumi.reflect.Tag
import org.scalacheck.Gen
import scala.compiletime.summonAll
import scala.deriving.Mirror
import registry.{Entry, TypedEntry}

/**
 * Combine per-subtype generators into a `Gen[T]` for a sealed trait / sealed abstract class / Scala 3
 * `enum`: `genTrait[Animal]`.
 *
 * Requires `Mirror.SumOf[T]` (auto-derived for sealed hierarchies and enums).
 *
 * `genTrait[T]` consumes a [[Chooser]] plus one `Gen[Sub_i]` for each variant and produces a `Gen[T]`.
 * The `Chooser` is a plain registry value — swap the registered `Chooser` to change the picking strategy
 * (uniform, weighted, deterministic), or use `r.refine[Ctx, Chooser](...)` to scope a chooser to a
 * particular build context.
 *
 * Typical use: prepend `genTrait[T]` *above* the per-subtype `gen[Sub_i]` entries so that
 * `make[Gen[T]]` picks up the combined generator rather than subtype-matching the first `Gen[Sub_i]`.
 *
 * Analogous to the Haskell `registry-hedgehog`'s `makeGenerators` / `Chooser` combo.
 */
transparent inline def genTrait[T](using
    m: Mirror.SumOf[T]
): TypedEntry[Chooser *: Tuple.Map[m.MirroredElemTypes, Gen], Gen[T]] =
  val chooserTag = summon[Tag[Chooser]]
  val variantTags =
    summonAll[Tuple.Map[m.MirroredElemTypes, [s] =>> Tag[Gen[s]]]].toList.asInstanceOf[List[Tag[?]]]
  val outputTag = summon[Tag[Gen[T]]]
  TypedEntry(
    Entry(
      inputs = chooserTag.tag :: variantTags.map(_.tag),
      output = outputTag.tag,
      invoke = args => {
        // args(0) is the Chooser; args(1..n) are the Gen[Sub_i]. Covariance of Gen lets us upcast.
        val chooser = args.head.asInstanceOf[Chooser]
        val gens = args.tail.map(_.asInstanceOf[Gen[T]])
        chooser.pickOne(gens)
      }
    )
  )
