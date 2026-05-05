---
title: Customization
parent: Concepts
nav_order: 5
---

# Customization

Two knobs change how a registry resolves: **refinements** override what
gets returned for a given type when the resolver is on a particular path
through the graph, and **erase** drops type-level tracking entirely. A
third pattern — wrapping a registered value with a transforming `fun` —
isn't a separate combinator but worth calling out below.

## Transforming a value with a wrapping `fun`

Need to post-process a registered value? Prepend a `fun(t: T => T)` above
an existing `T` producer. LIFO selects the wrapper first; its recursive
`T` input pulls the underlying value from below; the wrapper transforms
it.

```scala
import registry.*

case class Greeting(text: String)

val r =
  fun[Greeting] +:
    fun((s: String) => s.toUpperCase) +:
    value("hello")
```

```scala
r.make[Greeting]
// res0: Greeting = Greeting("HELLO")
```

Multiple wrappers compose by stacking — innermost runs first:

```scala
val n =
  fun((x: Int) => x * 2)  +: //  43 -> 86
    fun((x: Int) => x + 1) +: //  42 -> 43
    value(42)
// n: Registry[*:[Int, *:[Int, EmptyTuple]], *:[Int, *:[Int, *:[Int, EmptyTuple]]]] = Registry(
//   entries = List(
//     Basic(
//       inputs = List(Int),
//       output = Int,
//       invoke = repl.MdocSession$MdocApp$$Lambda/0x0000007002654600@297e3aa4,
//       fresh = false
//     ),
//     Basic(
//       inputs = List(Int),
//       output = Int,
//       invoke = repl.MdocSession$MdocApp$$Lambda/0x00000070026549d8@95450cf,
//       fresh = false
//     ),
//     Basic(
//       inputs = List(),
//       output = Int,
//       invoke = registry.Fun$package$$$Lambda/0x00000070026515b0@129f7a5d,
//       fresh = false
//     )
//   ),
//   refinements = List()
// )
n.make[Int]
// res1: Int = 86
```

Within a single `make` call the wrapper is invoked once and shared across
all consumers (per-make cache; see [Memoization](memoization.md)). To opt
out, mark the wrapping entry `.fresh`.

## `refine[Path, T]` — path-scoped override

When the resolution stack contains the types of `Path` as a subsequence
(in order, not necessarily contiguous) and the resolver is looking for
`T`, return `v` instead of doing the normal lookup.

`Path` may be a **single type** (the override fires whenever that type
appears anywhere on the stack) or a **tuple** of types (the override fires
only when all those types appear in order on the stack). One combinator,
both shapes — selected by a `PathTags` match type.

### Single-type Path

```scala
case class Logger(prefix: String)
case class Server(log: Logger)
case class Worker(log: Logger)

val app =
  fun[Server] +:
    fun[Worker] +:
    fun[Logger] +:
    value("default") +:
    Registry.empty
```

By default both share the same `Logger`:

```scala
app.make[Server].log.prefix
// res2: String = "default"
app.make[Worker].log.prefix
// res3: String = "default"
```

`refine[Server, String]("server-")` overrides `String` only when the
resolution stack passes through `Server`:

```scala
val tagged = app.refine[Server, String]("server-")
```

```scala
tagged.make[Server].log.prefix
// res4: String = "server-"
tagged.make[Worker].log.prefix
// res5: String = "default"
```

### Tuple Path

The resolution stack may contain `Server` for many reasons. To narrow
further, give a path of types that must all appear, **in order**, in the
stack.

```scala
case class Outer(s: Server)

val withOuter =
  fun[Outer] +: app
```

```scala
val pathed = withOuter.refine[(Outer, Server), String]("from-outer-")
```

```scala
pathed.make[Outer].s.log.prefix
// res6: String = "from-outer-"
pathed.make[Server].log.prefix     // not reached via Outer — default wins
// res7: String = "default"
```

## `refine` — refinements as standalone values

`refine[Path, T](v)` (top-level, not on a `Registry`) produces a
`Refinement` value that you can prepend with any of `+:`, `*:`, `-:`. It's
the same machinery as the `refine` method on `Registry` but expressed as
a value, which is sometimes cleaner when assembling registries from parts.

```scala
val refined =
  refine[Server, String]("from-refinement-") +: app
```

```scala
refined.make[Server].log.prefix
// res8: String = "from-refinement-"
```

A refinement adds no entries and doesn't change the type-level
`AllIns` / `AllOuts` accounting — `+:`, `*:`, `-:` all behave identically
for it.

## `erase` — drop type-level tracking

If you've assembled the registry through a series of `*:` chains and want to
hand it off as a "plain" registry without any `AllIns` / `AllOuts`
information, `erase` flattens it:

```scala
val erased: Registry[EmptyTuple, EmptyTuple] = r.erase
```

`make` still works on the erased registry; `makeSafe` can no longer prove
anything. Useful for storing assembled registries behind a uniform type,
e.g. a map keyed by environment name.
