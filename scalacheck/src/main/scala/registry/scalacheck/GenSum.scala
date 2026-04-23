package registry.scalacheck

import scala.compiletime.erasedValue
import scala.deriving.Mirror
import registry.{Registry, value}

/**
 * Convenience bundle for a sealed trait / sealed abstract class `T` whose variants are all case classes.
 *
 * `genSum[T]` expands at compile time into:
 *   `genTrait[T] *: genFun[Sub_0] *: genFun[Sub_1] *: … *: value(Chooser.uniform) *: Registry.empty`
 *
 * so you only need to add leaf generators for the fields:
 * {{{
 * val r = genSum[Animal] *:
 *         value(Gen.alphaStr: Gen[String]) *:
 *         value(Gen.choose(1, 9): Gen[Int]) *:
 *         Registry.empty
 * val gen = r.make[Gen[Animal]]
 * }}}
 *
 * To override the default chooser, prepend a `value(Chooser.weighted(…))` above `genSum[T]` — LIFO means
 * the most recent entry wins.
 *
 * Does NOT currently support variants that are case objects or no-arg enum cases. For those, use
 * `genTrait[T]` directly together with `value(Gen.const(caseInstance): Gen[Case.type])` per variant
 * (see `GenTraitSpec` for an example).
 */
transparent inline def genSum[T](using m: Mirror.SumOf[T]): Registry[EmptyTuple, EmptyTuple] =
  val base: Registry[EmptyTuple, EmptyTuple] = value(Chooser.uniform: Chooser) -: Registry.empty
  val withVariants: Registry[EmptyTuple, EmptyTuple] = addVariantEntries[m.MirroredElemTypes](base)
  genTrait[T].entry -: withVariants

/**
 * Recursively prepend a raw `Entry` for each variant's `genFun[V_i]`. We drop into raw `Entry`-based
 * prepends (`-:`) here because the `*:` / `+:` overloads need a concrete `Ins` tuple, which clashes
 * with the existential `? <: Tuple` returned by the transparent `genFun` macro inside this inline match.
 * The bundle is therefore untyped at the phantom level; once merged with a user registry via `*:`,
 * the user-side entries still get their type tracking.
 */
private transparent inline def addVariantEntries[Ts <: Tuple](
    acc: Registry[EmptyTuple, EmptyTuple]
): Registry[EmptyTuple, EmptyTuple] =
  inline erasedValue[Ts] match
    case _: EmptyTuple => acc
    case _: (h *: t)   => genFun[h].entry -: addVariantEntries[t](acc)
