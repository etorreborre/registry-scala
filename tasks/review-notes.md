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

- `[ergonomic]` **`refinePath[(X,), T]` rejects 1-tuples at the call
  site**. Scala 3 type-level tuple syntax requires `*: EmptyTuple` or
  `Tuple1[X]` for a 1-element tuple; `(X,)` is a parser error. The
  single-element case is fully covered by `refine[Ctx, T]`, so this isn't
  a bug — but a user trying `refinePath` uniformly will hit it. Worth a
  doc note (and possibly a friendlier compile error).

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
  `refinePath`**. The Registry methods, the `refinements` field, the
  `refined` boolean in `Resolve.go`, comments, tests, and docs all use the
  refinement vocabulary now — consistent with the existing `Refinement`
  type and `refine[Path, T]` factory.

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
