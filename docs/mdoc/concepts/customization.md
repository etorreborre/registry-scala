# Customization

Three knobs change how a registry resolves: **tweaks** transform values
post-resolution, **refinements** override what gets returned for a given
type when the resolver is on a particular path through the graph, and
**erase** drops type-level tracking entirely.

## `tweak[A]` — transform on the way out

`tweak[A](f)` registers a function applied to **every** resolved `A`,
whether it's the top-level value or an input to another entry. Multiple
tweaks compose in registration order.

```scala mdoc:silent
import registry.*

case class Greeting(text: String)

val r =
  fun[Greeting] +:
  value("hello")
```

```scala mdoc
r.make[Greeting]
r.tweak[String](_.toUpperCase).make[Greeting]
```

Tweaks are by-type (`Tag[A]`-keyed), so they fire wherever an `A` appears in
the dependency graph. They don't move entries around — they wrap values.

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
