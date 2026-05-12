# registry

A small dependency-injection / wiring library for Scala 3, ported from the
Haskell [`registry`](https://github.com/etorreborre/registry) library.

A `Registry` is a list of value-producing functions ("entries") plus the
machinery to invoke them in the right order. You assemble a graph by
prepending entries, then ask for a value of some type — the library finds the
function that produces it and recursively builds its inputs.

## Install

```scala
libraryDependencies ++= Seq(
  "org.atnos" %% "registry"            % "0.1.5", // core
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

`fun[T]` registers `T`'s primary constructor. `value(x)` registers a constant.
`+:` is the strict prepend — the compiler checks that every input on the left
is produced by something on the right.

## Modules

| Module                | Purpose                                                            |
| --------------------- | ------------------------------------------------------------------ |
| `registry`            | Core registry, entries, prepend operators, resolution.             |
| `registry-scalacheck` | Derive ScalaCheck `Gen[T]` for case classes and sealed hierarchies.|
| `registry-cats`       | Lift constructors into any `Applicative[F]` via `funTo[F, T]`.     |
| `registry-circe`      | Derive `Encoder[T]` / `Decoder[T]` with configurable JSON options. |

## Documentation

The rendered site lives at <https://etorreborre.github.io/registry-scala/>.

Sources are under [`docs/mdoc/`](docs/mdoc/) and are typechecked at build time
via [mdoc](https://scalameta.org/mdoc/):

- [Index](docs/mdoc/index.md) — what the library is.
- [Getting started](docs/mdoc/getting-started.md) — install, first registry,
  `make` vs `makeSafe`.

To build the docs locally:

```
sbt docs/mdoc        # one-shot render
sbt "~docs/mdoc"     # file-watch loop while editing
```

Output is written to `docs/target/mdoc/`.

## Building

Standard sbt project, Scala 3.3.6, sbt 1.12.9. `sbt compile` from the root
builds every module; `sbt test` runs all specs2 suites.

## Releasing

Push a tag of the form `REGISTRY-X.Y.Z` to `main`. The CI workflow will
publish the four artifacts to Maven Central via the Sonatype Central Portal
and deploy the rendered docs to the `gh-pages` branch.

```
git tag REGISTRY-1.0.0
git push origin REGISTRY-1.0.0
```

Versions are computed from the tag by `sbt-dynver`. Required GitHub Actions
secrets: `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`, `PGP_KEY_ID`,
`PGP_PASSPHRASE`, `PGP_SECRET` (base64-encoded).
