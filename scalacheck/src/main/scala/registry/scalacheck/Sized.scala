package registry.scalacheck

import org.scalacheck.Gen

/**
 * Configures `genRec`'s recursive sampling behavior. Two knobs:
 *
 *   - `pickBase(size)` — at the current ScalaCheck size, decide whether to use the registered
 *     base case (`true`) or to recurse via `grow(self)` (`false`). The default at `size <= 0`
 *     is "always base" (terminates); at higher sizes a 1:3 weighted random choice in favor of
 *     recursion. A fully deterministic `Sized` can return `Gen.const(false)` to always recurse
 *     (until the outer `maxSize` cap kicks in) or `Gen.const(true)` to always pick the base.
 *
 *   - `nextSize(size)` — compute the size to pass to `Gen.resize` for the recursive call. The
 *     default decrements by 1 (clamped at 0). Aggressive shrinks halve, etc.
 *
 * `genRec` always bundles a `value(Sized.default)` alongside the recursive entry, so
 * existing call sites need no changes. To use a different strategy, prepend your own
 * `value(mySized)` — LIFO selection picks it over the bundled default.
 */
final case class Sized(
    pickBase: Int => Gen[Boolean],
    nextSize: Int => Gen[Int]
)

object Sized:

  /**
   * Default behavior: 1:3 base/grow weighting (terminates at `size <= 0`), and `size / 2` shrink
   * per recursion level.
   *
   * Halving (vs. decrementing) is the safer floor for `grow` functions that fan out — e.g.
   * `Gen.listOf(self)` produces lists scaled by the ambient size, so a sub-linear depth shrink
   * lets total node count grow roughly factorially in the initial size and OOM the heap. With
   * `size / 2` total node count is bounded geometrically (`O(size^log size)`) and recursion is
   * capped at `log2(initialSize)` levels regardless of how the user wrote `grow`.
   *
   * Calls that need a slower shrink (e.g. linear chains, not trees) can still register their own
   * `value(Sized(...))` with `nextSize = size => Gen.const(size - 1)` to override this.
   */
  val default: Sized = Sized(
    pickBase = size =>
      if size <= 0 then Gen.const(true)
      else Gen.frequency(1 -> true, 3 -> false),
    nextSize = size => Gen.const(size / 2)
  )
