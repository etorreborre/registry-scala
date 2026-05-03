package registry

import izumi.reflect.macrortti.LightTypeTag

/**
 * A marker prepended via `+:` to retroactively transform every entry whose output is a subtype
 * of the carried target type. Each concrete marker carries the [[LightTypeTag]] for the target
 * and supplies a `transform: Entry => Entry` step.
 *
 * `Marker` is non-sealed so downstream modules (e.g. `registry-scalacheck`'s `Share[T]` and
 * `Const[T]`) can add their own marker types without touching core.
 */
trait Marker[T]:
  def targetTag: LightTypeTag
  def transform(entry: Entry): Entry

/**
 * `memoize[A] +: r` — memoizes every entry whose output is a subtype of `A`. The entry's
 * `invoke` is wrapped in an `AtomicReference`-backed cache that survives subsequent
 * `+:` / `*:` / `-:` operations.
 */
final case class Memoize[T] private[registry] (targetTag: LightTypeTag) extends Marker[T]:
  def transform(entry: Entry): Entry = Registry.withMemoization(entry)
