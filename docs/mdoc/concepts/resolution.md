# Resolution

`r.make[T]` is the runtime side of the registry. It walks the entries
looking for one that produces `T`, recursively builds the inputs that entry
needs, and invokes it.

## The algorithm, in one paragraph

1. **Refinement check**: if any path-scoped override applies (see
   [Customization](customization.md)), use its value and skip the rest.
2. **Lookup**: scan the entries in order; pick the first whose output is a
   **subtype of `T`**. Entries already in flight on the current resolution
   path are skipped (this is what makes recursive entries possible).
3. If no candidate exists and `T` is already in flight, raise a **cycle**
   error. Otherwise, raise a **missing-type** error.
4. Recursively resolve each input of the chosen entry.
5. Invoke the entry and return the result.

## LIFO precedence

Entries are scanned head-first, and `+:` puts new entries at the head, so
the **most recently prepended entry wins**. This is the same rule that
`<+>` follows for merges (left operand's entries come first).

```scala mdoc:silent
import registry.*

case class Host(value: String)

val r =
  value(Host("override")) +:    // wins — most recently prepended
  value(Host("default"))
```

```scala mdoc
r.make[Host]
```

## Subtype-aware lookup

The match is `entry.output <:< T`, not `entry.output =:= T`. Concretely:
an entry that produces `Cat` satisfies a request for `Animal`.

```scala mdoc:silent
trait Animal { def name: String }
case class Cat(name: String) extends Animal

val zoo =
  fun[Cat] +:
  value("Felix")
```

```scala mdoc
zoo.make[Animal] // finds the Cat entry
```

This asymmetry — runtime `<:<` vs. compile-time `=:=` in `makeSafe` — is
covered in [Safety](safety.md).

## Cycles vs recursive entries

Two facts that look contradictory at first:

- An entry is **allowed to consume the type it produces** (a self-loop).
- A graph that loops back on itself with no way out raises a **cycle**
  error.

The reconciling rule sits in `Resolve.scala`:

```scala
val candidate = entries.find(e => (e.output <:< want) && !inFlightEntries.contains(e))
```

`inFlightEntries` tracks the **specific entry instances** already invoked on
the current path — not the types. When the resolver looks up a type, it
scans all entries but **skips the ones currently in flight**. So a
self-referential entry's recursive input is satisfied by *some other* entry
that also produces that type. If no such other entry exists, the lookup
fails and the resolver raises a cycle error.

### Case 1 — self-loop alone: cycle error

A single entry that consumes the type it produces, with no companion. The
only producer is in-flight, so the recursive lookup finds nothing:

```scala mdoc:silent
val loneSelfLoop =
  fun((n: Int) => n + 1) *: Registry.empty   // Int → Int, no base
```

```scala mdoc
try loneSelfLoop.make[Int] catch case e: Throwable => println(e.getMessage)
```

### Case 2 — self-loop with companion: works

Add another entry producing the same type, and the recursive input picks
that one (the lone-self entry is in-flight, the companion isn't):

```scala mdoc:silent
val withBase =
  fun((n: Int) => n + 1) *: value(0)   // recursive: Int → Int, base: 0
```

```scala mdoc
withBase.make[Int]   // resolves the recursive entry, then picks 0 for its input
```

This is the mechanism behind ScalaCheck's `genRec` — see
[modules/scalacheck.md](../modules/scalacheck.md). `genRec[T]`
registers an entry of type `Gen[T] → Gen[T]`; you also register
`gen(Leaf: Tree)` (or similar) as the base.

### Case 3 — multi-step cycle: cycle error

`A` needs `B`, `B` needs `A`. Each is the only producer of its output, so
once both are in flight there's no fallback:

```scala mdoc:silent
case class A(b: B)
case class B(a: A)

val cyclic =
  fun[A] *: fun[B]   // *: not +: — the strict prepend would refuse
```

```scala mdoc
try cyclic.make[A] catch case e: Throwable => println(e.getMessage)
```

The size of the loop doesn't matter — what matters is whether every type on
the path has at least one *other* producer somewhere in the registry.

## Missing types

Asking for a type the registry doesn't produce is the same shape of error,
with the available outputs listed:

```scala mdoc:silent
case class Empty()

val sparse = value(Host("only")) +: Registry.empty
```

```scala mdoc
try sparse.make[Empty] catch case e: Throwable => println(e.getMessage)
```
