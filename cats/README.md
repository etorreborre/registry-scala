# registry-cats

cats integration for the `registry` library — lets you build registries whose outputs are
wrapped in any effect `F[_]` that has a cats `Applicative[F]` instance.

Covers the `funTo @m` / `valTo @m` combinators from the Haskell `registry` library.

## Usage

```scala
import cats.implicits.*
import registry.*
import registry.cats.*

case class Person(name: String, age: Int)

val r =
  funTo[Option, Person] *:
  valTo[Option]("Alice") *:
  valTo[Option](30) *:
  Registry.empty

val result: Option[Person] = r.make[Option[Person]]
// Some(Person("Alice", 30))
```

With any `F[_]: Applicative` — `Option`, `Either[E, _]`, `Future`, cats-effect `IO`, `fs2.Stream`,
cats `Id`, `Validated[E, _]`, etc.:

```scala
val r =
  funTo[[a] =>> Either[String, a], Person] *:
  valTo[[a] =>> Either[String, a]]("Alice") *:
  value(Left("bad age"): Either[String, Int])

r.make[Either[String, Person]]    // Left("bad age")
```

## Memoizing effectful values

Core's [`memoize`](../core/README.md) caches the resolved `F[A]` *value* — every `make[F[A]]` returns
the same reference. Running that `F[A]` still re-executes the effect each time (the registry's cache
is at the *value* level, not the *result* level).

For result-level memoization, stack cats-effect's `IO.memoize` on top before registering:

```scala
import cats.effect.IO

val counter              = new java.util.concurrent.atomic.AtomicInteger(0)
val acquire: IO[Service] = IO.delay { counter.incrementAndGet(); new Service() }

// `acquire.memoize` is `IO[IO[Service]]` — runs the memoization setup inside IO. Run it once to
// materialize the shared memoization cell; the resulting `IO[Service]` then caches its run result.
val memoizedIO: IO[Service] = acquire.memoize.unsafeRunSync()

val r  = value(memoizedIO) +: Registry.empty
val io = r.make[IO[Service]]

io.unsafeRunSync() // counter = 1, returns Service@x
io.unsafeRunSync() // counter = 1, returns same Service@x
```

Full worked example in `cats/src/test/scala/registry/cats/MemoizeWithIOSpec.scala`.

## Implemented

| Combinator           | Purpose                                                                                                                                                                                                                         |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `funTo[F, T]`        | Lift a case class / plain class primary constructor into `F`. Returns `TypedEntry[(F[P0], F[P1], …), F[T]]`. Uses `Applicative[F].product` to sequence the per-field effects.                                                   |
| `funTo[F](f)`        | Lift an arbitrary function (lambda or eta-expanded method reference) into `F`. Argument and return types are inferred from `f`. Returns `TypedEntry[(F[P0], F[P1], …), F[R]]`. Same sequencing as `funTo[F, T]` under the hood. |
| `valTo[F](x)`        | Lift a pure value into `F` via `Applicative[F].pure`. `T` is inferred from `x`.                                                                                                                                                 |
| `r.makeEither[T]`    | Non-throwing `make`. Returns `Right(t)` on success, `Left(throwable)` on missing dep / cycle / user exception.                                                                                                                  |
| `r.makeValidated[T]` | Non-throwing `make`, returning cats `Validated[Throwable, T]`. Useful when combining several `makeValidated` calls applicatively.                                                                                               |

`make[F[T]]` works unchanged — the registry treats `F[T]` as just another output type. Memoization
via core's `memoize[F[T]]` / `memoizeAll` works too; see the section above for how it interacts with
`IO`.

## Running

```
sbt catsInterop/test
```

## Dependency

- `org.typelevel::cats-core` (runtime).
- `org.typelevel::cats-effect` (test only, for the `MemoizeWithIOSpec` demo).
