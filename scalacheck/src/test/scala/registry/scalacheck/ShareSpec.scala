package registry.scalacheck

import org.specs2.mutable.Specification
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import registry.*
import registry.scalacheck.*

class ShareSpec extends Specification:

  "r.share[A] (registry-level)" should {
    "make every Gen[A] consumer see the same sample per generated value" >> {
      val r =
        gen(Bundle.apply) +:
          gen(Gen.choose(1, 1_000_000))

      val genB = r.share[Int].make[Bundle]
      val sample = genB.pureApply(Gen.Parameters.default, Seed(1L))
      sample.a === sample.b
      sample.b === sample.c
    }

    "without share, consumers see independent samples (baseline)" >> {
      val r =
        gen(Bundle.apply) +:
          gen(Gen.choose(1, 1_000_000))

      val sample = r.makeGen[Bundle].pureApply(Gen.Parameters.default, Seed(1L))
      (sample.a == sample.b && sample.b == sample.c) must beFalse
    }

    "produce different shared samples across different seeds" >> {
      val r =
        gen(Bundle.apply) +:
          gen(Gen.choose(1, 1_000_000))
      val genB = r.share[Int].make[Bundle]

      val s1 = genB.pureApply(Gen.Parameters.default, Seed(1L))
      val s2 = genB.pureApply(Gen.Parameters.default, Seed(2L))
      s1.a !== s2.a
    }

    "chain multiple shared types — each pinned independently per sample" >> {
      val r =
        gen(TwoKinds.apply) +:
          gen(Gen.choose(1, 1_000_000)) +:
          gen(Gen.alphaStr)

      val genTK = r.share[Int].share[String].make[TwoKinds]
      val sample = genTK.pureApply(Gen.Parameters.default, Seed(3L))
      sample.i1 === sample.i2
      sample.s1 === sample.s2
    }
  }

  "entry.share (entry-level)" should {
    "share a leaf value entry when marked with .share" >> {
      val r =
        gen(Bundle.apply) +:
          gen(Gen.choose(1, 1_000_000)).share

      val genB = r.shared.make[Bundle]
      val sample = genB.pureApply(Gen.Parameters.default, Seed(7L))
      sample.a === sample.b
      sample.b === sample.c
    }

    "share a gen-built entry when marked with .share" >> {
      // `mkShareLeaf: Int => ShareLeaf` via gen; with `.share` on that entry, every consumer
      // of `Gen[ShareLeaf]` in the same make sees the same sample.
      val r =
        gen(TwoLeaves.apply) +:
          gen((n: Int) => ShareLeaf(n)).share +:
          gen(Gen.choose(1, 1_000_000))

      val genTL = r.shared.make[TwoLeaves]
      val sample = genTL.pureApply(Gen.Parameters.default, Seed(11L))
      sample.x === sample.y
    }

    "entry-level and registry-level share compose" >> {
      // Int is shared at the entry site; String is shared at the registry site.
      val r =
        gen(TwoKinds.apply) +:
          gen(Gen.choose(1, 1_000_000)).share +:
          gen(Gen.alphaStr)

      val genTK = r.share[String].make[TwoKinds]
      val sample = genTK.pureApply(Gen.Parameters.default, Seed(13L))
      sample.i1 === sample.i2
      sample.s1 === sample.s2
    }

    "a plain Registry.make ignores the entry-level flag (sharing must go through SharedRegistry)" >> {
      val r =
        gen(Bundle.apply) +:
          gen(Gen.choose(1, 1_000_000)).share

      // No `.shared` / `.share[_]` — sharing is inert.
      val sample = r.makeGen[Bundle].pureApply(Gen.Parameters.default, Seed(17L))
      (sample.a == sample.b && sample.b == sample.c) must beFalse
    }

    "de-duplicate overlapping entry-level and registry-level share of the same type" >> {
      val r =
        gen(Bundle.apply) +:
          gen(Gen.choose(1, 1_000_000)).share

      // Request Int sharing both via .share (entry) and .share[Int] (registry).
      val genB = r.share[Int].make[Bundle]
      val sample = genB.pureApply(Gen.Parameters.default, Seed(19L))
      sample.a === sample.b
      sample.b === sample.c
    }
  }

case class Bundle(a: Int, b: Int, c: Int)
case class TwoKinds(i1: Int, i2: Int, s1: String, s2: String)
case class ShareLeaf(n: Int)
case class TwoLeaves(x: ShareLeaf, y: ShareLeaf)
