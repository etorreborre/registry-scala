---
title: Concepts
nav_order: 3
has_children: true
---

# Concepts

Background reading on how a `Registry` works:

- [Registry and entries](concepts/registry-and-entries.md) — what an entry
  is, the four prepend operators, and LIFO precedence.
- [Resolution](concepts/resolution.md) — how `make[T]` walks the graph.
- [Safety](concepts/safety.md) — what `+:` and `makeSafe[T]` actually
  check.
- [Memoization](concepts/memoization.md) — `share` vs `const` and per-call
  vs per-registry caching.
- [Customization](concepts/customization.md) — refinements, `erase`,
  and wrapping `fun`s.
