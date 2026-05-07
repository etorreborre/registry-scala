package registry.scalacheck

import izumi.reflect.macrortti.LightTypeTag
import registry.{Entry, Marker, Registry}

/**
 * Sampling-time markers consumed by `Registry.makeGen` (the extension defined in `Share.scala`).
 * Each carries the `LightTypeTag` of the target Gen output type; downstream factories build them
 * keyed on `Gen[T]` (rather than `T`) so `share[T] +: r` retroactively pins the sample of `Gen[T]`.
 *
 *   - [[Share]] flips the `shared` flag on every matching entry in a registry, promoting it to a
 *     [[GenEntry]] if needed. The flag is read by `makeGen` to install a per-build "sample once
 *     and pin" `Gen.flatMap` step.
 *   - [[Const]] is `Share` plus a custom `memoizer`: it both pins inside one `makeGen` build AND,
 *     via the memoizer's `AtomicReference`, pins ACROSS separate `makeGen` calls.
 *
 * Both extend core's [[Marker]] so they participate in `Registry.+:` / `TypedEntry.+:` without
 * needing additional `+:` overloads.
 *
 * Constructors are package-private; user-facing factories live in `Gen.scala`.
 */
final case class Share[T] private[scalacheck] (targetTag: LightTypeTag) extends Marker[T]:
  def transform(entry: Entry): Entry = GenEntry.from(entry).withShared(true)

final case class Const[T] private[scalacheck] (
    targetTag: LightTypeTag,
    memoizer: Entry => Entry = Registry.withMemoization
) extends Marker[T]:
  def transform(entry: Entry): Entry = memoizer(GenEntry.from(entry).withShared(true))
