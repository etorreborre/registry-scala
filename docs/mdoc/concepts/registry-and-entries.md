# Registry and entries

A **`Registry`** is a list of **entries**. An entry knows what types it
consumes (its inputs), what type it produces (its output), and how to combine
the inputs into a value of the output. Building a value of type `T` means
finding an entry that produces `T` and recursively building each of its
inputs.

## Entries

Three factories cover the typical cases:

```scala mdoc:silent
import registry.*

case class Host(value: String)
case class Port(value: Int)
case class DbConfig(host: Host, port: Port)

// fun[T] — register T's primary constructor.
val dbConfigEntry = fun[DbConfig]

// fun(f) — register a function value, lambda, or eta-expanded reference.
val portEntry = fun((n: Int) => Port(n))

// value(x) — register a constant (zero inputs).
val hostEntry = value(Host("localhost"))
val rawPort   = value(5432)
```

Each factory returns a `TypedEntry[Ins, Out]` — a thin wrapper that carries
the input tuple and output type at the type level. At runtime an entry is
just `(inputs, output, invoke)` keyed by [izumi-reflect][izumi] tags; the
phantom type info exists only to power the compile-time-checked combinators.

[izumi]: https://github.com/zio/izumi-reflect

## Building a registry

Entries are joined with prepend operators. There are four of them, in
decreasing strictness:

### `+:` — strict prepend

The compiler checks that every input on the left side is produced by
something on the right. This forces bottom-up construction: leaves first.

```scala mdoc:silent
val r =
  fun[DbConfig] +:
    value(Host("localhost")) +:
    value(Port(5432))
```

If you flip the order — leaf-consumer-leaf — `+:` rejects the program at
compile time. (See [Safety](safety.md).)

### `*:` — tracked prepend

Same type-level accounting as `+:`, but no compile-time check at the prepend
site. Useful while you're sketching; defer the check to `makeSafe`:

```scala mdoc:silent
val tracked =
  fun[DbConfig] *:
    value(Host("localhost")) *:
    value(Port(5432))
```

### `-:` — untracked prepend

Adds the entry at runtime but **does not** update the type-level
`AllIns` / `AllOuts`. The entry is invisible to `makeSafe`. Escape hatch when
you genuinely need to register something dynamically.

```scala mdoc:silent
val withDynamicOverride =
  value(Host("override")) -: r
```

### `<+>` — merge

Combine two registries. The left operand's entries come first, so on
duplicate outputs the **left wins** (consistent with the LIFO rule below).

```scala mdoc:silent
val left =
  fun[DbConfig] +:
    value(Host("left")) +:
    value(Port(5432))

val right =
  fun[DbConfig] +:
    value(Host("right")) +:
    value(Port(9999))

val merged = left <+> right
```

```scala mdoc
merged.make[Host] // left wins
```

## LIFO precedence

When two entries produce the same type, the one registered **closest to the
head** of the chain wins. Read `+:` as `cons` for registries — the head is
the most recently prepended entry.

```scala mdoc:silent
val firstWins =
  value(Host("override")) +:    // head — wins for Host
    fun[DbConfig] +:
      value(Host("default")) +:  // shadowed
      value(Port(5432))
```

```scala mdoc
firstWins.make[Host]
```

## Resolving values

Two extractors:

```scala mdoc
r.make[DbConfig]      // runtime resolution
r.makeSafe[DbConfig]  // compile-time-checked
```

`make[T]` throws on missing dependencies or cycles; `makeSafe[T]` refuses to
compile when `T` is not produced or some declared input is uncovered. Both
are covered in detail in [Resolution](resolution.md) and
[Safety](safety.md).
