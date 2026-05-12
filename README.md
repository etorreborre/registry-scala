# registry

A small dependency-injection / wiring library for Scala 3.

A `Registry` is a list of functions and values. The `Registry` main function, `make[T]`, can be called to make
a value of type `T` by invoking the first function that returns a `T`.
The function parameters are retrieved by recursively calling `make` on the `Registry`. That's it!

## Install

```scala
libraryDependencies ++= Seq(
  "org.atnos" %% "registry"            % "0.1.5",
  "org.atnos" %% "registry-scalacheck" % "0.1.5", // optional
  "org.atnos" %% "registry-cats"       % "0.1.5", // optional
  "org.atnos" %% "registry-circe"      % "0.1.5"  // optional
)
```

Only `registry` is required; the others are integrations.

## Hello, registry

```scala
import registry.*

case class Host(value: String)
case class Port(value: Int)
case class DbConfig(host: Host, port: Port)
case class App(db: DbConfig)

val r =
  fun[App] +:
    fun[DbConfig] +:
    value(Host("localhost")) +:
    value(Port(5432))

val app = r.make[App]
// App(DbConfig(Host("localhost"), Port(5432)))
```

What happens here?

 - `fun[T]` registers `T`'s primary constructor.
 - `value(x)` registers a constant.
 - `+:` is the strict prepend: the compiler checks that every input on the left is produced by something on the right.
 - `make` creates the `App`.

## Modules

| Module                | Purpose                                                            |
| --------------------- | ------------------------------------------------------------------ |
| `registry`            | Core registry, entries, prepend operators, resolution.             |
| `registry-cats`       | Lift constructors into any `Applicative[F]` via `funTo[F, T]`.     |
| `registry-circe`      | Derive `Encoder[T]` / `Decoder[T]` with configurable JSON options. |
| `registry-scalacheck` | Derive ScalaCheck `Gen[T]` for case classes and sealed hierarchies.|

## Documentation

The rendered site lives at <https://etorreborre.github.io/registry-scala>.

Sources are under [`docs/mdoc/`](docs/mdoc/) and are typechecked at build time via [mdoc](https://scalameta.org/mdoc/):

To build the docs locally:

```
sbt "~docs/mdoc"     # file-watch loop while editing
```

The docs are written to `docs/target/mdoc/`.

## Building

This is a standard sbt project:

 - Compile with `sbt compile`.
 - Test with `sbt test` to run all specs2 suites.

## Releasing

Push a tag of the form `REGISTRY-X.Y.Z` to `main`. The CI workflow will
publish the jars to Maven Central and deploy the rendered docs to the `gh-pages` branch.

```
git tag REGISTRY-1.0.0
git push origin REGISTRY-1.0.0
```

Versions are computed from the tag by `sbt-dynver`. 
Publishing requires the following GitHub actions secrets:

 - `SONATYPE_USERNAME`
 - `SONATYPE_PASSWORD`
 - `PGP_KEY_ID`,
 - `PGP_PASSPHRASE`
 - `PGP_SECRET`
