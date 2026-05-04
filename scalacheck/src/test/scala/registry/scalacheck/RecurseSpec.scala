package registry.scalacheck

import org.specs2.mutable.Specification
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import registry.*
import registry.scalacheck.*

import scala.language.implicitConversions

class RecurseSpec extends Specification:

  "genRec[T]" should {
    "build a recursive Gen[T] that resolves its base case from the registry" >> {
      // Base case (Leaf) is registered as `gen(Leaf: Tree)` — `Gen.const` is applied internally.
      // genRec picks it up as input — the resolver skips genRec's own entry for that lookup.
      val r =
        genRec[Tree] { self =>
          Gen.zip(self, self).map((l, r) => Node(l, r): Tree)
        } +:
          gen(Leaf: Tree)

      val samples = (0 until 30).map(i =>
        r.makeGen[Tree].pureApply(Gen.Parameters.default.withSize(8), Seed(i.toLong))
      )
      samples must contain(Leaf)
      samples.exists(_.isInstanceOf[Node]) must beTrue
    }

    "respect the maxSize cap — deeper trees never appear when size is bounded" >> {
      val r =
        genRec[Tree](maxSize = 2) { self =>
          Gen.zip(self, self).map((l, r) => Node(l, r): Tree)
        } +:
          gen(Leaf: Tree)

      def depth(t: Tree): Int = t match
        case Leaf       => 0
        case Node(l, r) => 1 + math.max(depth(l), depth(r))

      val samples = (0 until 50).map(i =>
        r.makeGen[Tree].pureApply(Gen.Parameters.default.withSize(100), Seed(i.toLong))
      )
      samples.map(depth) must contain(be_<=(2)).foreach
    }

    "error clearly when no base case is registered (only the recursive entry)" >> {
      // `-:` bypasses the compile-time strict-prepend check — we want to exercise the *runtime*
      // cycle detection path when the only entry producing Gen[Tree] is the recursive one itself.
      val r = genRec[Tree] { self =>
        Gen.zip(self, self).map((l, r) => Node(l, r): Tree)
      } *: Registry.empty

      r.makeGen[Tree] must throwA[RuntimeException].like { case e =>
        e.getMessage must contain("cycle").or(contain("No entry"))
      }
    }

    "use Sized.default when no Sized is registered (bundled with genRec)" >> {
      // Sanity: the default Sized lets recursion happen, so deeper trees do appear.
      val r =
        genRec[Tree] { self =>
          Gen.zip(self, self).map((l, r) => Node(l, r): Tree)
        } +:
          gen(Leaf: Tree)

      val samples = (0 until 30).map(i =>
        r.makeGen[Tree].pureApply(Gen.Parameters.default.withSize(8), Seed(i.toLong))
      )
      samples.exists(_.isInstanceOf[Node]) must beTrue
    }

    "respect a user-supplied Sized that always picks the base — every sample is the leaf" >> {
      val onlyBase = Sized(
        pickBase = _ => Gen.const(true),
        nextSize = size => Gen.const((size - 1).max(0))
      )

      val r =
        value(onlyBase) +:                  // overrides Sized.default via LIFO
          genRec[Tree] { self =>
            Gen.zip(self, self).map((l, r) => Node(l, r): Tree)
          } +:
          gen(Leaf: Tree)

      val samples = (0 until 30).map(i =>
        r.makeGen[Tree].pureApply(Gen.Parameters.default.withSize(8), Seed(i.toLong))
      )
      samples must contain(beEqualTo(Leaf: Tree)).foreach
    }

    "respect a user-supplied Sized that always recurses until size 0 — produces deeper trees up to the cap" >> {
      // Always-recurse Sized — terminates only at size 0 (the user's responsibility).
      val alwaysGrow = Sized(
        pickBase = size => if size <= 0 then Gen.const(true) else Gen.const(false),
        nextSize = size => Gen.const((size - 1).max(0))
      )

      val r =
        value(alwaysGrow) +:
          genRec[Tree](maxSize = 4) { self =>
            Gen.zip(self, self).map((l, r) => Node(l, r): Tree)
          } +:
          gen(Leaf: Tree)

      val samples = (0 until 30).map(i =>
        r.makeGen[Tree].pureApply(Gen.Parameters.default.withSize(100), Seed(i.toLong))
      )
      // Depth is bounded by maxSize = 4, so every sampled tree fits within that budget.
      samples.map(depth) must contain(be_<=(4)).foreach
      // Some samples reach the maximum depth, demonstrating the Sized's "always recurse until 0" behavior.
      samples.map(depth).max === 4
    }
  }

  private def depth(t: Tree): Int = t match
    case Leaf       => 0
    case Node(l, r) => 1 + math.max(depth(l), depth(r))

sealed trait Tree
case object Leaf extends Tree
case class Node(left: Tree, right: Tree) extends Tree
