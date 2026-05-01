package registry

import izumi.reflect.macrortti.LightTypeTag

/**
 * Markers prepended via `+:` to retroactively transform existing registered entries whose output
 * is a subtype of the carried target type. Each marker carries the target's [[LightTypeTag]];
 * downstream modules supply factories that pick the convention (e.g. scalacheck's `memoize[T]`
 * builds a `Memoize[T]` keyed on `Gen[T]` rather than `T`).
 *
 * `Const[T]` additionally carries a `memoizer` hook so downstream modules can plug in stronger
 * caching semantics. The default memoizer caches the entry's invoke result (the produced value);
 * scalacheck overrides it to also pin the *sampled* value across separate `makeGen` calls so
 * `const[T]` truly means "one fixed sample for the registry's lifetime".
 */
final case class Memoize[T] private[registry] (targetTag: LightTypeTag)
final case class Share[T] private[registry] (targetTag: LightTypeTag)
final case class Const[T] private[registry] (
    targetTag: LightTypeTag,
    memoizer: Entry => Entry = Registry.withMemoization
)
