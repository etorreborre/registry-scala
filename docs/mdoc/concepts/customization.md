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

```scala mdoc:silent
import registry.*

case class Greeting(text: String)

val r =
  fun[Greeting] +:
    fun((s: String) => s.toUpperCase) +:
    value("hello")
```

```scala mdoc
r.make[Greeting]
```

Multiple wrappers compose by stacking — innermost runs first:

```scala mdoc
val n =
  fun((x: Int) => x * 2)  +: //  43 -> 86
    fun((x: Int) => x + 1) +: //  42 -> 43
    value(42)
n.make[Int]
```

Within a single `make` call the wrapper is invoked once and shared across
all consumers (per-make cache; see [Memoization](memoization.md)). To opt
out, mark the wrapping entry `.fresh`.

## `refine[Ctx, T]` — context-scoped override

When the resolver is currently building a value of type `Ctx`, return `v`
for `T` instead of doing normal lookup.

```scala mdoc:silent
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

```scala mdoc
app.make[Server].log.prefix
app.make[Worker].log.prefix
```

`refine[Server, String]("server-")` overrides `String` only when the
resolution stack passes through `Server`:

```scala mdoc:silent
val tagged = app.refine[Server, String]("server-")
```

```scala mdoc
tagged.make[Server].log.prefix
tagged.make[Worker].log.prefix
```

## `refinePath[(A, B, ...), T]` — multi-step paths

The resolution stack may contain `Server` for many reasons. To narrow
further, give a path of types that must appear **in order** (not necessarily
contiguous) in the stack.

```scala mdoc:silent
case class Outer(s: Server)

val withOuter =
  fun[Outer] +: app
```

```scala mdoc:silent
val pathed = withOuter.refinePath[(Outer, Server), String]("from-outer-")
```

```scala mdoc
pathed.make[Outer].s.log.prefix
pathed.make[Server].log.prefix     // not reached via Outer — default wins
```

## `refine` — refinements as standalone values

`refine[Path, T](v)` produces a `Refinement` value that you can prepend
with any of `+:`, `*:`, `-:`. It's the same machinery as the `refine` /
`refinePath` methods on `Registry` but expressed as a value, which is
sometimes cleaner when assembling registries from parts.

```scala mdoc:silent
val refined =
  refine[Server, String]("from-refinement-") +: app
```

```scala mdoc
refined.make[Server].log.prefix
```

A refinement adds no entries and doesn't change the type-level
`AllIns` / `AllOuts` accounting — `+:`, `*:`, `-:` all behave identically
for it.

## `erase` — drop type-level tracking

If you've assembled the registry through a series of `*:` chains and want to
hand it off as a "plain" registry without any `AllIns` / `AllOuts`
information, `erase` flattens it:

```scala mdoc:silent
val erased: Registry[EmptyTuple, EmptyTuple] = r.erase
```

`make` still works on the erased registry; `makeSafe` can no longer prove
anything. Useful for storing assembled registries behind a uniform type,
e.g. a map keyed by environment name.
