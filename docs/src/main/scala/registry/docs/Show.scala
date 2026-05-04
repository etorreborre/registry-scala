package registry.docs

import org.scalacheck.{Gen, rng}

/**
 * Deterministic sample helper used in mdoc snippets so generator output is
 * reproducible across builds. Fixes the seed and parameters; never call
 * outside of documentation.
 */
def sample[A](g: Gen[A]): A =
  g.pureApply(Gen.Parameters.default, rng.Seed(42L))
