package registry.scalacheck

import scala.compiletime.{erasedValue, summonFrom}
import scala.deriving.Mirror
import org.scalacheck.Gen
import registry.{Registry, value}

/**
 * Convenience bundle for a sealed trait / sealed abstract class / Scala 3 `enum` `T`.
 *
 * `genSum[T]` expands at compile time into:
 *   `genTrait[T] *: <per-variant entry> *: … *: value(Chooser.uniform) *: Registry.empty`
 *
 * where each `<per-variant entry>` is either:
 *   - `genFun[V_i]` if `V_i` is a case class / plain class with a primary constructor, or
 *   - `value(Gen.const(theSingleton): Gen[V_i])` if `V_i` is a no-arg variant (case object
 *     or no-arg enum case) — detected at compile time via `Mirror.Singleton`.
 *
 * You only need to add leaf generators for the fields of non-singleton variants:
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
 */
transparent inline def genSum[T](using m: Mirror.SumOf[T]): Registry[EmptyTuple, EmptyTuple] =
  val base: Registry[EmptyTuple, EmptyTuple] = value(Chooser.uniform: Chooser) -: Registry.empty
  val withVariants: Registry[EmptyTuple, EmptyTuple] = addVariantEntries[m.MirroredElemTypes](base)
  genTrait[T].entry -: withVariants

/**
 * Recursively prepend a raw `Entry` for each variant. For no-arg variants (case objects /
 * no-arg enum cases) we emit a `value(Gen.const(singleton))` entry — the `Mirror.Singleton`
 * instance IS the singleton value (it extends `Product`), so `fromProduct(EmptyTuple)` yields
 * it directly. For parametrised variants we fall through to `genFun[h]`.
 *
 * We drop into raw `Entry`-based prepends (`-:`) because the `*:` / `+:` overloads need a
 * concrete `Ins` tuple, which clashes with the existential `? <: Tuple` returned by the
 * transparent `genFun` macro inside this inline match. The bundle is therefore untyped at the
 * phantom level; once merged with a user registry via `*:`, the user-side entries still get
 * their type tracking.
 */
private transparent inline def addVariantEntries[Ts <: Tuple](
    acc: Registry[EmptyTuple, EmptyTuple]
): Registry[EmptyTuple, EmptyTuple] =
  inline erasedValue[Ts] match
    case _: EmptyTuple => acc
    case _: (h *: t)   =>
      summonFrom {
        case v: ValueOf[`h`] =>
          value(Gen.const(v.value): Gen[h]).entry -: addVariantEntries[t](acc)
        case _ =>
          genFun[h].entry -: addVariantEntries[t](acc)
      }
