package registry.scalacheck

import org.scalacheck.Gen
import registry.TypedEntry

/**
 * Register a case class / plain class primary constructor as a ScalaCheck generator: `genFun[Foo]`.
 *
 * Returns a [[registry.TypedEntry]] whose `Ins` tuple is `(Gen[P0], Gen[P1], …)` (the constructor's
 * parameter types, each wrapped in `Gen`) and whose `Out` is `Gen[T]`. At runtime the entry's closure
 * sequences the generators via `flatMap` / `map` (through [[GenCombine.combineGens]]) and applies the
 * primary constructor to the collected sample values.
 *
 * Analogous to the Haskell `registry-hedgehog`'s `genFun = funTo @Gen`.
 */
transparent inline def genFun[T]: TypedEntry[? <: Tuple, Gen[T]] =
  ${ GenFunMacro.typeImpl[T] }

/**
 * Register an arbitrary function value as a ScalaCheck generator: `genFun((a: A, b: B) => c)`. The
 * function's parameter types become the entry's `Ins` (each wrapped in `Gen`); its return type becomes
 * the `Out` (wrapped in `Gen`). Also accepts eta-expanded method references (`genFun(Foo.apply)`).
 *
 * Mirrors `registry-cats`'s `funTo[F](f)` but with `Gen` hard-coded as the effect.
 */
transparent inline def genFun[Fn](inline f: Fn): TypedEntry[? <: Tuple, ?] =
  ${ GenFunMacro.valueImpl[Fn]('f) }
