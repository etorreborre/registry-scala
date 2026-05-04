package registry.scalacheck

import org.specs2.mutable.Specification
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import registry.*
import registry.scalacheck.*

class ShareSpec extends Specification:

  "share[T] +: registry (factory)" should {
    "make every Gen[T] consumer see the same sample per generated value" >> {
      val r =
        share[Int] +:
          gen(Bundle.apply) +:
          gen(Gen.choose(1, 1_000_000))

      val sample = r.makeGen[Bundle].pureApply(Gen.Parameters.default, Seed(1L))
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
        share[Int] +:
          gen(Bundle.apply) +:
          gen(Gen.choose(1, 1_000_000))

      val s1 = r.makeGen[Bundle].pureApply(Gen.Parameters.default, Seed(1L))
      val s2 = r.makeGen[Bundle].pureApply(Gen.Parameters.default, Seed(2L))
      s1.a !== s2.a
    }

    "chain multiple shared types — each pinned independently per sample" >> {
      val r =
        share[Int] +:
          share[String] +:
          gen(TwoKinds.apply) +:
          gen(Gen.choose(1, 1_000_000)) +:
          gen(Gen.alphaStr)

      val sample = r.makeGen[TwoKinds].pureApply(Gen.Parameters.default, Seed(3L))
      sample.i1 === sample.i2
      sample.s1 === sample.s2
    }
  }

  "entry.share (entry-level)" should {
    "share a leaf value entry when marked with .share — picked up automatically by makeGen" >> {
      val r =
        gen(Bundle.apply) +:
          gen(Gen.choose(1, 1_000_000)).share

      val sample = r.makeGen[Bundle].pureApply(Gen.Parameters.default, Seed(7L))
      sample.a === sample.b
      sample.b === sample.c
    }

    "share a gen-built entry when marked with .share" >> {
      val r =
        gen(TwoLeaves.apply) +:
          gen((n: Int) => ShareLeaf(n)).share +:
          gen(Gen.choose(1, 1_000_000))

      val sample = r.makeGen[TwoLeaves].pureApply(Gen.Parameters.default, Seed(11L))
      sample.x === sample.y
    }

    "entry-level `.share` and standalone `share[T] +:` compose" >> {
      val r =
        share[String] +:
          gen(TwoKinds.apply) +:
          gen(Gen.choose(1, 1_000_000)).share +:
          gen(Gen.alphaStr)

      val sample = r.makeGen[TwoKinds].pureApply(Gen.Parameters.default, Seed(13L))
      sample.i1 === sample.i2
      sample.s1 === sample.s2
    }

    "plain `make[Gen[T]]` does NOT apply sharing — only `makeGen[T]` does" >> {
      val r =
        gen(Bundle.apply) +:
          gen(Gen.choose(1, 1_000_000)).share

      val sample = r.make[Gen[Bundle]].pureApply(Gen.Parameters.default, Seed(17L))
      (sample.a == sample.b && sample.b == sample.c) must beFalse
    }
  }

  "share[T] for opaque types" should {
    "match the entry registered via gen(genOpaqueValue) defined in the same object" >> {
      // Reproducer for a real-world issue: a `Gen[OpaqueT]` defined inside the object that declares
      // the opaque type alias, then registered from outside via `gen(genOpaque)`, was not picked up
      // by `share[OpaqueT] +: r` — consumers saw independent samples.
      val r =
        share[Opaques.Major] +:
          gen(OpaquePair.apply) +:
          gen(Opaques.genMajor)

      val sample = r.makeGen[OpaquePair].pureApply(Gen.Parameters.default, Seed(31L))
      sample.a === sample.b
    }

    "match when the opaque generator flows through an intermediate consumer registered as a function" >> {
      // Closer to the real-world failure: an intermediate `gen(fn)` consumes `Gen[OpaqueT]` and the
      // `OpaquePair` only sees `OpaqueT` indirectly via two different paths.
      val r =
        share[Opaques.Major] +:
          gen(IndirectPair.apply) +:
          gen((m: Opaques.Major) => Wrapper(m)) +:
          gen(Opaques.genMajor)

      val sample = r.makeGen[IndirectPair].pureApply(Gen.Parameters.default, Seed(41L))
      sample.left.m === sample.right.m
    }

    "share[T] +: r marks the registered Gen[OpaqueT] entry as shared" >> {
      val r =
        share[Opaques.Major] +:
          gen(Opaques.genMajor)

      r.entries.count { case g: GenEntry => g.shared; case _ => false } === 1
    }
  }

  "const — pinning across separate makeGen calls" should {
    "make every makeGen[T] return the same sampled value of the const type" >> {
      // share[T] pins within ONE makeGen tree; const[T] should pin across ALL makeGen calls on
      // the registry, so two independent samples on the same registry observe the same value.
      val r =
        const[Int] +:
          gen(Bundle.apply) +:
          gen(Gen.choose(1, 1_000_000))

      val a = r.makeGen[Int].pureApply(Gen.Parameters.default, Seed(99L))
      val b = r.makeGen[Int].pureApply(Gen.Parameters.default, Seed(7L))
      a === b
    }

    "consumers across separate makeGen calls see the SAME pinned value" >> {
      val r =
        const[Int] +:
          gen(Bundle.apply) +:
          gen(Gen.choose(1, 1_000_000))

      val pinned = r.makeGen[Int].pureApply(Gen.Parameters.default, Seed(1L))
      val sample = r.makeGen[Bundle].pureApply(Gen.Parameters.default, Seed(2L))
      sample.a === pinned
      sample.b === pinned
    }
  }

  "share — dependent types must agree" should {
    "share both an aggregate type AND one of its inputs and have the aggregate use the pinned input" >> {
      // Reproducer for the actual hydrozoa bug. We have:
      //   share[OuterT]   — pinned across consumers
      //   share[InnerT]   — pinned across consumers
      //   gen(outerFn)    — Gen[OuterT] built by consuming Gen[InnerT]
      //   gen(twoInners.apply) — sees InnerT directly (so we can compare)
      //
      // After sharing both, the outer's INNER value MUST equal the directly-pinned inner.
      // If the share build samples in entries-list order rather than dependency order, it
      // can sample OuterT first (with a fresh InnerT) before pinning InnerT — producing a
      // mismatch even though both types are individually shared.
      val r =
        share[OuterDep] +:
          share[InnerDep] +:
          gen(DepBundle.apply) +:
          gen((i: InnerDep) => OuterDep(i, "label")) +:
          gen(InnerDep.gen)

      val sample = r.makeGen[DepBundle].pureApply(Gen.Parameters.default, Seed(101L))
      sample.outer.inner === sample.inner
    }
  }

case class Bundle(a: Int, b: Int, c: Int)
case class TwoKinds(i1: Int, i2: Int, s1: String, s2: String)
case class ShareLeaf(n: Int)
case class TwoLeaves(x: ShareLeaf, y: ShareLeaf)
case class OpaquePair(a: Opaques.Major, b: Opaques.Major)
case class Wrapper(m: Opaques.Major)
case class IndirectPair(left: Wrapper, right: Wrapper)

case class InnerDep(n: Int)

object InnerDep:
  val gen: Gen[InnerDep] = Gen.choose(1, 1_000_000).map(InnerDep(_))

case class OuterDep(inner: InnerDep, label: String)

case class DepBundle(outer: OuterDep, inner: InnerDep)

object Opaques:
  opaque type Major <: BigInt = BigInt
  def genMajor: Gen[Major] = Gen.choose(1L, 1_000_000L).map(BigInt(_))
