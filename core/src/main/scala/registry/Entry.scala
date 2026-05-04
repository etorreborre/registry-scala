package registry

import izumi.reflect.macrortti.LightTypeTag

/**
 * An entry in the registry — a manually-typed function value with input/output types captured as
 * [[LightTypeTag]]s and an `invoke` closure that takes resolved arguments and produces a value of
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
   * Return a copy of this entry with `invoke` replaced. Used by memoization and other invoke-
   * wrapping combinators. The concrete subtype is preserved by the implementing case class.
   */
  def withInvoke(f: Seq[Any] => Any): Entry

  /** Return a copy of this entry with `fresh` set to `b` (default `true`). */
  def withFresh(b: Boolean = true): Entry

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
      fresh: Boolean = false
  ) extends Entry:
    def withInvoke(f: Seq[Any] => Any): Entry = copy(invoke = f)
    def withFresh(b: Boolean = true): Entry = copy(fresh = b)
