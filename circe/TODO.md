# `registry-circe` — TODO

## History cursor for decoding failures

`registry.circe.Decoder[T]` currently operates on `io.circe.Json` and returns
`Either[String, T]`. Failures carry a path string built up by hand (see the
`>> 'field :: Type'` formatting in `Decoder` / `decodeFromDefinitions`), but
not a real `CursorOp` history. That means:

- When bridged to `io.circe.Decoder` via `asCirce`, the resulting
  `DecodingFailure` has an empty `history` — IDEs and tooling that surface
  cursor paths lose information.
- Error messages cannot be reformatted, JSON-pointer-style filtered, or
  combined with other circe decoders' histories.

Proposal: make `Decoder[T]` operate on `HCursor` (or pass an `HCursor`
alongside `Json` in the decoder type), and propagate `CursorOp` segments at
each step of `decodeFromDefinitions` / the combinator chain (`map`, `emap`,
`flatMap`, list/option/map/etc.). The `String` error path becomes derived
output rather than the primary representation.

Risks / open questions:

- API break for every existing `Decoder` instance and combinator — the
  function shape changes.
- Performance: `HCursor` allocations on every recursive call vs. the current
  raw `Json` walk.
- Bridging back to `Json`-only contexts (the printer / `decodeString`) needs a
  `HCursor.fromJson` entry point at the boundary.
