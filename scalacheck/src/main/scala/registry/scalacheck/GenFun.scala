package registry.scalacheck

import org.scalacheck.Gen
import registry.TypedEntry

/**
 * Register a case class / plain class primary constructor as a ScalaCheck generator: `genFun[Foo]`.
 *
 * Returns a [[registry.TypedEntry]] whose `Ins` tuple is `(Gen[P0], Gen[P1], ...)` (the constructor's
 * parameter types, each wrapped in `Gen`) and whose `Out` is `Gen[T]`. At runtime the entry's closure
 * sequences the generators via `flatMap` / `map` (through [[GenCombine.combineGens]]) and applies the
 * primary constructor to the collected sample values.
 *
 * Analogous to the Haskell `registry-hedgehog`'s `genFun = funTo @Gen`.
 */
transparent inline def genFun[T]: TypedEntry[? <: Tuple, Gen[T]] =
  ${ GenFunMacro.typeImpl[T] }
