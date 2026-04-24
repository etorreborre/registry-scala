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
        genFun(Bundle.apply) +:
          value(Gen.choose(1, 1_000_000): Gen[Int])

      val gen = r.share[Int].make[Bundle]
      val sample = gen.pureApply(Gen.Parameters.default, Seed(1L))
      sample.a === sample.b
      sample.b === sample.c
    }

    "without share, consumers see independent samples (baseline)" >> {
      val r =
        genFun(Bundle.apply) +:
          value(Gen.choose(1, 1_000_000): Gen[Int])

      val sample = r.make[Gen[Bundle]].pureApply(Gen.Parameters.default, Seed(1L))
      (sample.a == sample.b && sample.b == sample.c) must beFalse
    }

    "produce different shared samples across different seeds" >> {
      val r =
        genFun(Bundle.apply) +:
          value(Gen.choose(1, 1_000_000): Gen[Int])
      val gen = r.share[Int].make[Bundle]

      val s1 = gen.pureApply(Gen.Parameters.default, Seed(1L))
      val s2 = gen.pureApply(Gen.Parameters.default, Seed(2L))
      s1.a !== s2.a
    }

    "chain multiple shared types — each pinned independently per sample" >> {
      val r =
        genFun(TwoKinds.apply) +:
          value(Gen.choose(1, 1_000_000): Gen[Int]) +:
          value(Gen.alphaStr: Gen[String])

      val gen = r.share[Int].share[String].make[TwoKinds]
      val sample = gen.pureApply(Gen.Parameters.default, Seed(3L))
      sample.i1 === sample.i2
      sample.s1 === sample.s2
    }
  }

  "entry.share (entry-level)" should {
    "share a leaf value entry when marked with .share" >> {
      val r =
        genFun(Bundle.apply) +:
          value(Gen.choose(1, 1_000_000): Gen[Int]).share

      val gen = r.shared.make[Bundle]
      val sample = gen.pureApply(Gen.Parameters.default, Seed(7L))
      sample.a === sample.b
      sample.b === sample.c
    }

    "share a genFun-built entry when marked with .share" >> {
      // `mkShareLeaf: Int => ShareLeaf` via genFun; with `.share` on that entry, every consumer
      // of `Gen[ShareLeaf]` in the same make sees the same sample.
      val r =
        genFun(TwoLeaves.apply) +:
          genFun((n: Int) => ShareLeaf(n)).share +:
          value(Gen.choose(1, 1_000_000): Gen[Int])

      val gen = r.shared.make[TwoLeaves]
      val sample = gen.pureApply(Gen.Parameters.default, Seed(11L))
      sample.x === sample.y
    }

    "entry-level and registry-level share compose" >> {
      // Int is shared at the entry site; String is shared at the registry site.
      val r =
        genFun(TwoKinds.apply) +:
          value(Gen.choose(1, 1_000_000): Gen[Int]).share +:
          value(Gen.alphaStr: Gen[String])

      val gen = r.share[String].make[TwoKinds]
      val sample = gen.pureApply(Gen.Parameters.default, Seed(13L))
      sample.i1 === sample.i2
      sample.s1 === sample.s2
    }

    "a plain Registry.make ignores the entry-level flag (sharing must go through SharedRegistry)" >> {
      val r =
        genFun(Bundle.apply) +:
          value(Gen.choose(1, 1_000_000): Gen[Int]).share

      // No `.shared` / `.share[_]` — sharing is inert.
      val sample = r.make[Gen[Bundle]].pureApply(Gen.Parameters.default, Seed(17L))
      (sample.a == sample.b && sample.b == sample.c) must beFalse
    }

    "de-duplicate overlapping entry-level and registry-level share of the same type" >> {
      val r =
        genFun(Bundle.apply) +:
          value(Gen.choose(1, 1_000_000): Gen[Int]).share

      // Request Int sharing both via .share (entry) and .share[Int] (registry).
      val gen = r.share[Int].make[Bundle]
      val sample = gen.pureApply(Gen.Parameters.default, Seed(19L))
      sample.a === sample.b
      sample.b === sample.c
    }
  }

case class Bundle(a: Int, b: Int, c: Int)
case class TwoKinds(i1: Int, i2: Int, s1: String, s2: String)
case class ShareLeaf(n: Int)
case class TwoLeaves(x: ShareLeaf, y: ShareLeaf)
