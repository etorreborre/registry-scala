---
title: Modules
nav_order: 4
has_children: true
---

# Modules

The library is split into a small core and per-integration artifacts:

- [`registry`](modules/core.md) — the dependency-injection core: entries,
  prepend operators, `make` / `makeSafe`.
- [`registry-cats`](modules/cats.md) — lift constructors, functions,
  and values into any `Applicative[F]`; non-throwing `makeEither` /
  `makeValidated` resolution.
- [`registry-circe`](modules/circe.md) — derive `Encoder[T]` / `Decoder[T]`
  with configurable JSON options.
- [`registry-scalacheck`](modules/scalacheck.md) — derive
  `Gen[T]` instances from a registry.
