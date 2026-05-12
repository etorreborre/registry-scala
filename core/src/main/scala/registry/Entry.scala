package registry

import izumi.reflect.macrortti.LightTypeTag

/**
 * An entry in the registry — a manually-typed function value with input/output types captured as
 * `LightTypeTag`s and an `invoke` closure that takes resolved arguments and produces a value of
 * the output type.
 *
 * `fresh = true` opts out of the resolver's per-`make` value cache: every appearance of the
 * entry's output type in the dependency graph triggers a fresh `invoke` instead of reusing the
 * value computed earlier in the same call.
 *
 * `Entry` is a non-sealed trait so downstream modules (e.g. `registry-scalacheck`) can extend it
 * with module-specific metadata (`GenEntry` carries the sampling-time `shared` flag). Pattern-
 * match on the subtype to read that metadata; treat `Entry` itself as the universal API.
 */
trait Entry:
  def inputs: List[LightTypeTag]
  def output: LightTypeTag
  def invoke: Seq[Any] => Any
  def fresh: Boolean

  /**
   * Optional thunk that clears any mutable per-registry state attached to this entry —
   * `AtomicReference` caches from `memoize` / `const` / `share`-pinning, primarily.
   * `Registry.reset` walks entries and runs every entry's `resetFn`. Default no-op.
   *
   * Combinators that introduce state (`Registry.withMemoization`, scalacheck's
   * `withConstSampling`) build a fresh thunk that *additionally* runs the previous one, so layers
   * of state compose: a `share.memoize` entry's `resetFn` clears both the memoization cache and
   * the sample-pin in one call.
   */
  def resetFn: () => Unit = () => ()

  /**
   * Return a copy of this entry with `invoke` replaced. Used by memoization and other invoke-
   * wrapping combinators. The concrete subtype is preserved by the implementing case class.
   */
  def withInvoke(f: Seq[Any] => Any): Entry

  /** Return a copy of this entry with `fresh` set to `b` (default `true`). */
  def withFresh(b: Boolean = true): Entry

  /**
   * Return a copy of this entry whose `resetFn` runs the supplied callback ON TOP of the current
   * `resetFn`. Composing reset thunks lets multiple state-introducing wrappers all be cleared by
   * a single `Registry.reset` call.
   */
  def withResetFn(f: () => Unit): Entry

object Entry:

  /**
   * Default constructor — produces an [[Entry.Basic]]. Existing call sites
   * (`Entry(inputs, output, invoke)`, `Entry(... , fresh = true)`) keep working unchanged.
   */
  def apply(
      inputs: List[LightTypeTag],
      output: LightTypeTag,
      invoke: Seq[Any] => Any,
      fresh: Boolean = false
  ): Entry = Basic(inputs, output, invoke, fresh)

  /**
   * The plain core entry. Modules that need extra fields (e.g. `GenEntry` in scalacheck)
   * provide their own subtypes of [[Entry]].
   */
  final case class Basic(
      inputs: List[LightTypeTag],
      output: LightTypeTag,
      invoke: Seq[Any] => Any,
      fresh: Boolean = false,
      override val resetFn: () => Unit = () => ()
  ) extends Entry:
    def withInvoke(f: Seq[Any] => Any): Entry = copy(invoke = f)
    def withFresh(b: Boolean = true): Entry = copy(fresh = b)

    def withResetFn(f: () => Unit): Entry =
      val prev = resetFn
      copy(resetFn = () => { prev(); f() })
