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
  valTo[Option, String]("Alice") *:
  valTo[Option, Int](30)

val result: Option[Person] = r.make[Option[Person]]
// Some(Person("Alice", 30))
```

With any `F[_]: Applicative` — `Option`, `Either[E, _]`, `Future`, cats-effect `IO`, `fs2.Stream`,
cats `Id`, `Validated[E, _]`, etc.:

```scala
val r =
  funTo[[a] =>> Either[String, a], Person] *:
  valTo[[a] =>> Either[String, a], String]("Alice") *:
  value(Left("bad age"): Either[String, Int])

r.make[Either[String, Person]]    // Left("bad age")
```

## Implemented

| Combinator       | Purpose                                                                                                                                                                       |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `funTo[F, T]`    | Lift a case class / plain class primary constructor into `F`. Returns `TypedEntry[(F[P0], F[P1], …), F[T]]`. Uses `Applicative[F].product` to sequence the per-field effects. |
| `valTo[F, T](x)` | Lift a pure value into `F` via `Applicative[F].pure`.                                                                                                                         |

`make[F[T]]` works unchanged — the registry treats `F[T]` as just another output type.

## Not yet implemented

| Feature                            | Haskell                          | Notes                                                                                                                                                       |
| ---------------------------------- | -------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `funTo(f)` for arbitrary functions | `funTo @m` on a `A -> B -> C`    | Currently only `funTo[F, T]` for class constructors. A value-driven form for lambdas / method references is a natural extension.                            |
| Sum-type effectful derivation      | —                                | `registry-scalacheck` has `genSum[T]`; a parallel `sumTo[F, T]` via `Mirror.SumOf` + cats `Alternative` or `NonEmptyList` choose would cover sealed traits. |
| Memoization                        | `memoize @m @A`, `memoizeAll @m` | Cache a resolved `F[A]` so every consumer shares the same effect instance. Distinct from `tweak` which re-applies per resolution.                           |
| `makeEither` / `makeValidated`     | `makeEither`                     | Wrap resolution errors in `Either` instead of throwing. Could be built on top of `make` by catching runtime exceptions.                                     |

## Running

```
sbt catsInterop/test
```

## Dependency

Depends on [`org.typelevel::cats-core`](https://typelevel.org/cats/).
Any library providing cats instances for its effect types is compatible.
