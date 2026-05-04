---
title: Modules
nav_order: 4
has_children: true
---

# Modules

The library is split into a small core and per-integration artifacts:

- [`registry`](modules/core.md) — the dependency-injection core: entries,
  prepend operators, `make` / `makeSafe`.
- [`registry-scalacheck`](modules/scalacheck.md) — derive
  `Gen[T]` instances from a registry.
- `registry-cats` <span class="badge-soon">coming soon</span>
- `registry-circe` <span class="badge-soon">coming soon</span>
