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
  tweaks still apply per consumer based on `want`. Specialized values are
  not cached: `go` returns `(value, specialized)` and propagates the flag
  up through every entry whose inputs touched a specialization, so a
  Logger built under `specialize[Server, String]` doesn't poison Worker's
  Logger lookup. Opt-out: `entry.fresh` / `Registry.fresh[A]`. 10 new core
  tests in `RuntimeRegistrySpec`'s "share within a make call" group cover:
  default sharing, cache reset between makes, subtype-shared lookup,
  `entry.fresh`, `Registry.fresh[A]`, tweak interaction (invoke once,
  tweak per consumer), spec-cache poisoning prevention, no-spec sharing,
  memoize composition, recursive entry base sharing. Memoization page
  rewritten. Files: `Entry.scala`, `Resolve.scala`, `TypedEntry.scala`,
  `Registry.scala`, `Fixtures.scala`, `RuntimeRegistrySpec.scala`,
  `docs/mdoc/concepts/memoization.md`.

- `[doc]` **`makeSafe` uses `=:=`; `make` uses `<:<`**. A registry can pass
  `make` (subtype match) but be rejected by `makeSafe` (exact equality), or
  vice versa. Already documented in `docs/mdoc/concepts/safety.md` —
  `MakeSafeMacro.scala:19-20` is the call site that should carry an internal
  comment pointing at the doc.

- `[test-gap]` **`Share[T]` marker has no dedicated test in core**.
  `Markers.scala:17` declares `Share[T]`; the only behavioral usage is in
  `Registry.scala:115` (sets `shared = true` on matching entries). Coverage
  exists indirectly in `registry-scalacheck` tests, but core itself never
  exercises the flag.

- `[done]` **Core tests for share-by-default added** — see the `[done]`
  entry above for the full list. Test-gap closed.

- `[ergonomic]` **`specializePath[(X,), T]` rejects 1-tuples at the call
  site**. Scala 3 type-level tuple syntax requires `*: EmptyTuple` or
  `Tuple1[X]` for a 1-element tuple; `(X,)` is a parser error. The
  single-element case is fully covered by `specialize[Ctx, T]`, so this
  isn't a bug — but a user trying `specializePath` uniformly will hit it.
  Worth a doc note (and possibly a friendlier compile error).

- `[ergonomic]` **`+:` on a lone entry needs `Registry.empty`**. A chain
  like `entry +: entry +: entry` resolves the rightmost as a `TypedEntry`,
  whose `+:` instance returns a `Registry`. A solo entry has no
  `Registry`-shaped neighbor and forces `entry +: Registry.empty`. Not a
  bug; surprise factor. Already used implicitly across the doc pages.

- `[refactor]` **Marker extension methods are duplicated between
  `Registry.scala:104-131` and `TypedEntry.scala:62-98`**. The `+:[T](m:
  Memoize[T])` / `+:[T](s: Share[T])` / `+:[T](c: Const[T])` blocks have
  identical predicates (`<:< targetTag`) and near-identical bodies. Could
  be unified by lifting the marker-application step into a shared helper.

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
