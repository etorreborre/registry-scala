package registry.scalacheck

import org.specs2.mutable.Specification
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import registry.*
import registry.scalacheck.*

class RecurseSpec extends Specification:

  "genRecursive[T]" should {
    "build a recursive Gen[T] that resolves its base case from the registry" >> {
      // Base case (Leaf) is registered as `gen(Leaf: Tree)` — `Gen.const` is applied internally.
      // genRecursive picks it up as input — the resolver skips genRecursive's own entry for that lookup.
      val r =
        genRecursive[Tree] { self =>
          Gen.zip(self, self).map((l, r) => Node(l, r): Tree)
        } +:
          gen(Leaf: Tree)

      val samples = (0 until 30).map(i =>
        r.make[Gen[Tree]].pureApply(Gen.Parameters.default.withSize(8), Seed(i.toLong))
      )
      samples must contain(Leaf)
      samples.exists(_.isInstanceOf[Node]) must beTrue
    }

    "respect the maxSize cap — deeper trees never appear when size is bounded" >> {
      val r =
        genRecursive[Tree](maxSize = 2) { self =>
          Gen.zip(self, self).map((l, r) => Node(l, r): Tree)
        } +:
          gen(Leaf: Tree)

      def depth(t: Tree): Int = t match
        case Leaf       => 0
        case Node(l, r) => 1 + math.max(depth(l), depth(r))

      val samples = (0 until 50).map(i =>
        r.make[Gen[Tree]].pureApply(Gen.Parameters.default.withSize(100), Seed(i.toLong))
      )
      samples.map(depth) must contain(be_<=(2)).foreach
    }

    "error clearly when no base case is registered (only the recursive entry)" >> {
      // `-:` bypasses the compile-time strict-prepend check — we want to exercise the *runtime*
      // cycle detection path when the only entry producing Gen[Tree] is the recursive one itself.
      val r = genRecursive[Tree] { self =>
        Gen.zip(self, self).map((l, r) => Node(l, r): Tree)
      } *: Registry.empty

      r.make[Gen[Tree]] must throwA[RuntimeException].like { case e =>
        e.getMessage must contain("cycle").or(contain("No entry"))
      }
    }
  }

sealed trait Tree
case object Leaf extends Tree
case class Node(left: Tree, right: Tree) extends Tree
