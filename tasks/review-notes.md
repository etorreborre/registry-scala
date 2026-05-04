# Code review notes

Findings logged during the doc-writing pass over each subproject. Tags:

- `[issue]` — likely bug or correctness concern
- `[doc]` — non-obvious behavior worth a doc callout (sometimes already
  surfaced in a concept page)
- `[refactor]` — code-smell / cleanup opportunity, no behavior change
- `[test-gap]` — public API has thin or missing test coverage
- `[ergonomic]` — works as designed but trips users; worth an API tweak

No fixes in this pass — fixes happen as separate follow-ups after triage.

## core

- `[done]` **Share-by-default within a `make` call**. Aligned with Haskell
  `registry`. `Resolve.go` now threads a per-make `Map[String, Any]` keyed
  on the chosen entry's `output.repr`. Pre-tweak invoke result is cached;
  tweaks still apply per consumer based on `want`. Refined values are not
  cached: `go` returns `(value, refined)` and propagates the flag up
  through every entry whose inputs touched a refinement, so a Logger built
  under `refine[Server, String]` doesn't poison Worker's Logger lookup.
  Opt-out: `entry.fresh` / `Registry.fresh[A]`. 10 new core tests in
  `RuntimeRegistrySpec`'s "share within a make call" group cover: default
  sharing, cache reset between makes, subtype-shared lookup, `entry.fresh`,
  `Registry.fresh[A]`, tweak interaction (invoke once, tweak per consumer),
  refinement-cache poisoning prevention, no-refinement sharing, memoize
  composition, recursive entry base sharing. Memoization page rewritten.
  Files: `Entry.scala`, `Resolve.scala`, `TypedEntry.scala`,
  `Registry.scala`, `Fixtures.scala`, `RuntimeRegistrySpec.scala`,
  `docs/mdoc/concepts/memoization.md`.

- `[doc]` **`makeSafe` uses `=:=`; `make` uses `<:<`**. A registry can pass
  `make` (subtype match) but be rejected by `makeSafe` (exact equality), or
  vice versa. Already documented in `docs/mdoc/concepts/safety.md` —
  `MakeSafeMacro.scala:19-20` is the call site that should carry an internal
  comment pointing at the doc.

- `[done]` **`Share[T]` no longer a core concern** — moved to
  `registry-scalacheck` as part of the Entry refactor; `ShareSpec` already
  covers it there.

- `[done]` **Core tests for share-by-default added** — see the `[done]`
  entry above for the full list. Test-gap closed.

- `[done]` **`refine` and `refinePath` unified**. `refinePath` is gone;
  `refine[Path, T]` (using the existing `PathTags` match type) accepts
  either a single type or a tuple. `r.refine[Server, String]("…")` and
  `r.refine[(Server, Db), String]("…")` both work. The `(X,)` 1-tuple
  ergonomic snag is moot — single-element callers use the bare type.

- `[ergonomic]` **`+:` on a lone entry needs `Registry.empty`**. A chain
  like `entry +: entry +: entry` resolves the rightmost as a `TypedEntry`,
  whose `+:` instance returns a `Registry`. A solo entry has no
  `Registry`-shaped neighbor and forces `entry +: Registry.empty`. Not a
  bug; surprise factor. Already used implicitly across the doc pages.

- `[done]` **Marker `+:` overloads unified**. Introduced a `Marker[T]`
  trait in core with `targetTag` + `transform(Entry): Entry`. Memoize
  extends it; `Share[T]` / `Const[T]` (now in scalacheck) extend it too.
  `Registry` and `TypedEntry` now have a single polymorphic `+:[T](m:
  Marker[T])` instead of three. Files: `Markers.scala`, `Registry.scala`,
  `TypedEntry.scala`, `scalacheck/Markers.scala`.

- `[done]` **Removed `shared` from core's `Entry`**. `Entry` is now a
  non-sealed trait with `Entry.Basic` as the default implementation;
  scalacheck adds `GenEntry extends Entry` with the `shared` flag.
  `Share`/`Const` markers and their `+:` extensions moved to scalacheck.
  Core no longer carries any scalacheck-specific concept. `withInvoke` /
  `withFresh` methods replace `entry.copy(...)` calls. Files:
  `Entry.scala`, `Markers.scala`, `Registry.scala`, `TypedEntry.scala`,
  `scalacheck/GenEntry.scala`, `scalacheck/Markers.scala`,
  `scalacheck/Gen.scala`, `scalacheck/Share.scala`, `scalacheck/ShareSpec.scala`.

- `[done]` **`specialize` / `specializePath` renamed to `refine` /
  `refinePath`** (later unified — see entry above). The `refinements`
  field, the `refined` boolean in `Resolve.go`, comments, tests, and docs
  all use the refinement vocabulary now — consistent with the existing
  `Refinement` type and `refine[Path, T]` factory.

- `[done]` **`tweak` removed**. The combinator was redundant: prepending
  `fun((t: T) => f(t))` above an existing `T` producer achieves the same
  post-resolution transformation via ordinary entry composition (LIFO
  selects the wrapper, recursive `T` lookup pulls the underlying value).
  Removed `Registry.tweak[A]`, the `tweaks` field, `Resolve.applyTweaks`,
  and the `tweaks` parameter from `Resolve.resolve` / `Resolve.go`.
  Internal use in `scalacheck/Share.scala` (where the share build pinned
  sampled `Gen[A]` values via a tweak) rewritten to prepend a value-style
  `Entry(Nil, head, _ => Gen.const(sample))` instead — same effect through
  LIFO selection. Tests: removed the dedicated "tweak" group; added a
  small "transformation via prepended fun" group covering the equivalent
  patterns. Docs (`customization.md`, `resolution.md`, `modules/core.md`,
  `memoization.md`, `scalacheck/README.md`, scaladoc on Share.scala)
  updated. Lost-by-removal: the rare "compose tweaks across `<+>`" use
  case (a transformation registered on an empty registry, then merged).

- `[doc]` **`Registry.memoize[A]` returns a new registry with a fresh
  cache**. Calling it twice on the same registry yields two independent
  caches. Documented in `concepts/memoization.md`; mentioning in the
  scaladoc on `Registry.memoize` itself would help.

## scalacheck

(pending)

## cats

(pending)

## circe

(pending — pre-seeded:)

- `[refactor]` `MakeEncoderMacro` and `MakeDecoderMacro` duplicate
  qualification-mode logic (drop / full / last). Lift into a shared helper.
- `[issue]` `JsonOptions.rejectUnknownFields` declared but possibly
  unimplemented; verify in the decoder macro.
- `[doc]` `Decoder` returns `String` errors instead of circe's
  `DecodingFailure`. Design choice mirroring Haskell — document so users
  don't expect interop.

## cross-cutting

- `[doc]` **`mdoc:crash` includes the full Scala stack trace by default**,
  which is too noisy for cycle/missing examples. Worked around by using
  `try ... catch case e: Throwable => println(e.getMessage)` in
  `concepts/resolution.md`. Not a registry issue; mdoc behavior. Note for
  future page authors.
