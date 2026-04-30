package registry.scalacheck

import scala.deriving.Mirror
import registry.Registry

/**
 * Convenience bundle for a sealed trait / sealed abstract class / Scala 3 `enum` `T`.
 *
 * `sum[T]` expands at compile time into a `Registry` containing:
 *   - `genTrait[T]` — combines per-variant `Gen`s into a `Gen[T]`
 *   - one entry per variant `V_i`:
 *       * `gen(theSingleton)` for no-arg variants (case object / no-arg enum case)
 *       * a generated `gen[V_i]`-style entry for parametrised variants
 *   - `value(Chooser.uniform)` — the default chooser
 *
 * Both phantom type parameters of the resulting registry are computed by [[SumMacro]] so the
 * compile-time tracker sees what's produced AND what variant-field inputs are still required:
 *   - `AllOuts` includes `Gen[T]`, every `Gen[V_i]`, and `Chooser`
 *   - `AllIns` is the deduplicated union of every variant constructor's `Gen[FieldType]` requirement,
 *     with the internally-produced types (Chooser, the variant Gens, `Gen[T]` itself) filtered out
 *
 * This means a downstream `+:` strict-prepend check correctly reports a missing variant field type
 * that the surrounding registry has not provided — for example `sum[Animal] +: Registry.empty`
 * fails to compile if `Animal.Dog`'s `String` / `Int` fields don't have registered `Gen`s elsewhere.
 *
 * To override the default chooser, prepend `value(Chooser.weighted(…))` above `sum[T]` —
 * LIFO means the most recent entry wins.
 */
transparent inline def sum[T](using m: Mirror.SumOf[T]): Registry[? <: Tuple, ? <: Tuple] =
  ${ SumMacro.impl[T] }
