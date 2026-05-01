package registry.scalacheck

import java.util.concurrent.atomic.AtomicInteger
import org.specs2.mutable.Specification
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import registry.*
import registry.scalacheck.*

/**
 * Demonstrates the effect of `Registry.memoizeAll` on Gen-producing entries.
 *
 * Setup: an entry whose `invoke` increments a counter, embedding the counter's value into the Gen
 * it produces. When the entry is memoized, `invoke` runs once and the resulting Gen is cached, so
 * every subsequent `makeGen[Int]` returns the same Gen (and the same sampled value). Without
 * memoization, `invoke` re-runs on each `makeGen`, giving a fresh Gen with a different captured
 * counter value.
 */
class MemoizeSpec extends Specification:

  "memoize on a Gen entry" should {

    "return the same Gen instance — and the same sampled value — across multiple makeGen calls" >> {
      val counter = new AtomicInteger(0)
      val r =
        gen(Gen.const(counter.incrementAndGet())).memoize +: Registry.empty

      val g1 = r.makeGen[Int]
      val g2 = r.makeGen[Int]
      val g3 = r.makeGen[Int]

      // The Gen instance is cached: subsequent makeGen calls don't re-run `invoke`.
      g1 must beTheSameAs(g2)
      g2 must beTheSameAs(g3)

      // Counter ran exactly once.
      counter.get === 1

      // All samples reflect that single counter value.
      val s1 = g1.pureApply(Gen.Parameters.default, Seed(7L))
      val s2 = g2.pureApply(Gen.Parameters.default, Seed(7L))
      val s3 = g3.pureApply(Gen.Parameters.default, Seed(99L))

      s1 === 1
      s2 === 1
      s3 === 1  // even with a different seed: Gen.const ignores the seed
    }

    "without memoize: `invoke` re-runs and the captured value differs each time" >> {
      val counter = new AtomicInteger(0)
      val r =
        gen(Gen.const(counter.incrementAndGet())) +: Registry.empty

      val g1 = r.makeGen[Int]
      val g2 = r.makeGen[Int]
      val g3 = r.makeGen[Int]

      // Counter ran on every makeGen call.
      counter.get === 3

      // Each makeGen got a different captured counter value.
      val s1 = g1.pureApply(Gen.Parameters.default, Seed(7L))
      val s2 = g2.pureApply(Gen.Parameters.default, Seed(7L))
      val s3 = g3.pureApply(Gen.Parameters.default, Seed(7L))

      Set(s1, s2, s3) === Set(1, 2, 3)
    }

    "standalone `memoize[T] +: entry`: wraps the matching Gen entry without modifying its construction" >> {
      val counter = new AtomicInteger(0)

      case class Version(n: Int)
      def genVersion: Gen[Version] = Gen.const(Version(counter.incrementAndGet()))

      val r = memoize[Version] +: gen(genVersion)

      r.makeGen[Version]
      r.makeGen[Version]
      r.makeGen[Version]
      counter.get === 1
    }

    "standalone `memoize[T] +: registry`: only matching entries are memoized, others untouched" >> {
      val pinnedCounter = new AtomicInteger(0)
      val freshCounter  = new AtomicInteger(0)

      case class Pinned(n: Int)
      case class Fresh(n: Int)

      val r =
        memoize[Pinned] +:
          (gen(Gen.const(Pinned(pinnedCounter.incrementAndGet()))) +:
            gen(Gen.const(Fresh(freshCounter.incrementAndGet()))))

      r.makeGen[Pinned]
      r.makeGen[Pinned]
      pinnedCounter.get === 1   // matched and memoized

      r.makeGen[Fresh]
      r.makeGen[Fresh]
      freshCounter.get === 2    // not matched: re-runs each call
    }
  }

  /**
   * Compares the four combinations of entry-level `.memoize` and `.share`:
   *
   *   - `.memoize` is **cross-call** caching of the entry's invoke output (the Gen instance).
   *   - `.share` is **within-call** pinning of the sampled value across consumers in a Bundle.
   *
   * `Bundle(a: User, b: User)` is the probe: a Gen[User] with a wide-range chooser. Without
   * sharing, the two positions sample independently and almost surely differ; with `.share`,
   * `makeGen` automatically applies the share build path and the two positions are equal.
   *
   * The two markers compose orthogonally and commute: `.memoize.share` and `.share.memoize`
   * produce identical observable behavior.
   */
  "interactions of .memoize and .share" should {

    case class User(id: Int)
    case class Bundle(a: User, b: User)

    val params  = Gen.Parameters.default
    val seed    = Seed(42L)
    val seed2   = Seed(43L)
    val widegen = Gen.choose(0, 1_000_000_000).map(User.apply)

    "baseline (neither): two positions sample independently within a Bundle" >> {
      val r = gen[Bundle] +: gen(widegen)
      val b = r.makeGen[Bundle].pureApply(params, seed)
      b.a.id !== b.b.id
    }

    ".memoize alone: invoke is cached across makes; positions inside a Bundle are still independent" >> {
      val invokes = new AtomicInteger(0)
      val r = gen[Bundle] +: gen { invokes.incrementAndGet(); widegen }.memoize

      r.makeGen[Bundle]
      r.makeGen[Bundle]
      r.makeGen[Bundle]
      invokes.get === 1

      val b = r.makeGen[Bundle].pureApply(params, seed)
      b.a.id !== b.b.id
    }

    ".share alone: positions are pinned within a Bundle (makeGen picks up the .share marker)" >> {
      val r = gen[Bundle] +: gen(widegen).share
      val b = r.makeGen[Bundle].pureApply(params, seed)
      b.a.id === b.b.id
    }

    ".memoize + .share: positions pinned within a Bundle, identical Bundles across calls with the same seed" >> {
      val r = gen[Bundle] +: gen(widegen).memoize.share

      val b1 = r.makeGen[Bundle].pureApply(params, seed)
      val b2 = r.makeGen[Bundle].pureApply(params, seed)

      b1.a.id === b1.b.id   // pinned within b1
      b2.a.id === b2.b.id   // pinned within b2
      b1 === b2             // same seed + cached entry ⇒ identical Bundle
    }

    ".const on entry: behaves like .share.memoize" >> {
      val invokes = new AtomicInteger(0)
      val r = gen[Bundle] +: gen { invokes.incrementAndGet(); widegen }.const

      val b1 = r.makeGen[Bundle].pureApply(params, seed)
      val b2 = r.makeGen[Bundle].pureApply(params, seed)

      invokes.get === 1
      b1.a.id === b1.b.id
      b1 === b2
    }

    "const[T] +: factory: applies share+memoize retroactively" >> {
      val invokes = new AtomicInteger(0)
      val r =
        const[User] +: (gen[Bundle] +: gen { invokes.incrementAndGet(); widegen })

      val b1 = r.makeGen[Bundle].pureApply(params, seed)
      val b2 = r.makeGen[Bundle].pureApply(params, seed)

      invokes.get === 1
      b1.a.id === b1.b.id
      b1 === b2
    }

    ".memoize.share and .share.memoize commute: identical Bundle outputs from the same seed" >> {
      val rA = gen[Bundle] +: gen(widegen).memoize.share
      val rB = gen[Bundle] +: gen(widegen).share.memoize

      val bA1 = rA.makeGen[Bundle].pureApply(params, seed)
      val bA2 = rA.makeGen[Bundle].pureApply(params, seed2)
      val bB1 = rB.makeGen[Bundle].pureApply(params, seed)
      val bB2 = rB.makeGen[Bundle].pureApply(params, seed2)

      bA1 === bB1
      bA2 === bB2
    }
  }
