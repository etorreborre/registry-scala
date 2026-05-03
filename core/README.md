# registry

A Scala 3 port of the Haskell [`registry`](https://github.com/etorreborre/registry)
library — a small, runtime-dynamic dependency registry with compile-time-checked
composition. Register constructor-style functions by output type, then ask the
registry to build a value of any registered type; the registry recursively resolves
inputs from other registrations.

## Example

```scala
import registry.*

case class DbConfig(host: String, port: Int)
case class Db(config: DbConfig)
case class App(db: Db, name: String)

val r =
  fun[App] +:
    fun[Db] +:
    fun[DbConfig] +:
    value("localhost") +:
    value(5432) +:
    value("my-app")

val app: App = r.make[App]
```

## Implemented

### Registration

| API        | Purpose                                                                                                                                            |
| ---------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| `fun[T]`   | Register a class primary constructor (case class, plain class, multi-param-list, `using`, `implicit`). Macro-derived `TypedEntry[(P0, P1, …), T]`. |
| `fun(f)`   | Register a lambda, function value, or eta-expanded method reference (`fun(Foo.apply)`).                                                            |
| `value(x)` | Register a constant as a zero-input entry.                                                                                                         |

### Prepend operators (right-associative, chainable, polymorphic on both sides)

| Operator | Mode        | When it fails                                                                                                           |
| -------- | ----------- | ----------------------------------------------------------------------------------------------------------------------- |
| `+:`     | **strict**  | Compile error if the new entry's inputs aren't already produced by the rest of the registry. Forces bottom-up ordering. |
| `*:`     | **tracked** | Never fails at prepend. Type-level accounting accumulates; `makeSafe` catches gaps later.                               |
| `-:`     | **untyped** | Preserves only the receiver's types; the left side is invisible to `makeSafe`. Escape hatch for dynamic composition.    |

All three support four operand shapes:
- `entry OP registry` (prepend)
- `registry OP registry` (merge)
- `entry OP entry` (2-entry registry)
- `registry OP entry` (registry above an entry)

### Other combinators

| API                                   | Purpose                                                                                                                                                                          |
| ------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `<+>`                                 | Merge two registries (left wins on duplicate outputs, LIFO preserved).                                                                                                           |
| `erase`                               | Drop type-level tracking, keep entries for runtime use.                                                                                                                          |
| `tweak[A](f: A => A)`                 | Post-process every resolved `A` with `f` — including `A`s resolved as inputs of a larger build. Multiple tweaks compose in registration order.                                   |
| `specialize[Ctx, T](v)`               | Context-scoped override: whenever `Ctx` appears in the resolution stack and we're resolving `T`, return `v` instead of the default lookup.                                       |
| `specializePath[Path <: Tuple, T](v)` | Like `specialize` but the context is a sequence of types; fires only when the stack contains them in order. `specialize[Ctx, T](v)` ≡ `specializePath[Ctx *: EmptyTuple, T](v)`. |
| `Registry.empty`                      | Start-of-chain typed as `Registry[EmptyTuple, EmptyTuple]`.                                                                                                                      |

### Resolution

| API           | Behaviour                                                                                                     |
| ------------- | ------------------------------------------------------------------------------------------------------------- |
| `make[T]`     | Runtime resolution. Throws a `RuntimeException` naming the missing type or cycle path.                        |
| `makeSafe[T]` | Compile-time-checked resolution. Emits a friendly per-line error listing missing inputs and produced outputs. |

### Runtime behaviour

- **Subtype-aware lookup**: a registered `List[Int]` satisfies a request for `Seq[Int]`; a registered `Impl` satisfies a request for `Iface`. Backed by `izumi-reflect`'s `LightTypeTag.<:<`.
- **LIFO order**: the most recently prepended entry wins when multiple entries can satisfy a request.
- **Cycle detection** with the full dependency path in the error message.
- **Adaptive error formatting**: short errors stay on one line, long ones break into multiple lines with each type on its own line.

## Not yet implemented

Features from Haskell `registry` that haven't been ported.

| Feature                                | Haskell                          | Notes                                                                                                                                                                                                                            |
| -------------------------------------- | -------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Sum-type support**                   | (not directly in core)           | `registry-scalacheck` has `genSum[T]` derived via `Mirror.SumOf`. An equivalent in core — "register a sealed trait by combining entries for each case" — would mirror the scalacheck version but for plain values, not Gens.     |
| **Effectful resolution in core**       | `funTo @m`, `valTo @m`           | Implemented as a separate sub-module `registry-cats` (see `cats/README.md`). Requires a `cats.Applicative[F]` instance. A stdlib-only version would need a minimal `Applicative` typeclass in core.                              |
| **Memoization / singletons**           | `memoize @m @A`, `memoizeAll @m` | Cache a resolved `F[A]` so every consumer gets the same instance. Not in core or `registry-cats` yet.                                                                                                                            |
| **`makeEither` / non-throwing `make`** | `makeEither`, `makeUnsafe`       | Return `Either[RuntimeException, T]` instead of throwing on missing-dep / cycle. Thin wrapper around `make` — small follow-up.                                                                                                   |
| **Subtype-aware `makeSafe`**           | N/A                              | Runtime `make` handles subtypes; `makeSafe`'s compile-time checks use exact type equality via match types (`Contains`, `AllIn`). Generalizing these to recognize `<:<` at the type level is possible but non-trivial in Scala 3. |
| **Registry inspection**                | —                                | List all types a registry can produce, dump the implicit dependency graph, etc. Nice-to-have for debugging.                                                                                                                      |

## Build

Lives in the `core/` sub-module of the multi-project build.

```
sbt core/test
```

Single external dependency: [`dev.zio.izumi-reflect`](https://github.com/zio/izumi-reflect) for runtime `LightTypeTag`s (with subtype-aware equality).
