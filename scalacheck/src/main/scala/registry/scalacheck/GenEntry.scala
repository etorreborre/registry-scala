package registry.scalacheck

import izumi.reflect.macrortti.LightTypeTag
import registry.Entry

/**
 * A `registry-scalacheck`–specific [[Entry]] that carries the sampling-time `shared` flag.
 *
 * Core's [[Entry]] is intentionally generic: it knows nothing about samplers or generators. The
 * flag we need for share-aware `makeGen` builds is therefore stored on this subtype, and core
 * stays unaware of scalacheck concerns. `Share.makeGen` reads the flag by pattern-matching on
 * `GenEntry`; non-`GenEntry` entries in the same registry are treated as not-shared.
 *
 * The `.share` / `.const` extension methods on Gen-output `TypedEntry`s and the
 * `share[T] +:` / `const[T] +:` registry-level operators promote any matching [[Entry]] to a
 * [[GenEntry]] via [[GenEntry.from]] before flipping the flag — so user-facing call sites still
 * accept any TypedEntry whose output is `Gen[T]`.
 */
final case class GenEntry(
    inputs: List[LightTypeTag],
    output: LightTypeTag,
    invoke: Seq[Any] => Any,
    fresh: Boolean = false,
    shared: Boolean = false,
    override val resetFn: () => Unit = () => ()
) extends Entry:
  def withInvoke(f: Seq[Any] => Any): Entry = copy(invoke = f)
  def withFresh(b: Boolean = true): Entry = copy(fresh = b)
  def withShared(b: Boolean = true): GenEntry = copy(shared = b)

  def withResetFn(f: () => Unit): Entry =
    val prev = resetFn
    copy(resetFn = () => { prev(); f() })

object GenEntry:

  /**
   * Promote any [[Entry]] to a [[GenEntry]]. If the input is already a `GenEntry`, return it
   * unchanged; otherwise wrap its data in a new `GenEntry` with `shared = false`. The carried
   * `resetFn` is preserved either way so subsequent `Registry.reset` calls still clear cached
   * state established before promotion.
   */
  def from(e: Entry): GenEntry = e match
    case g: GenEntry => g
    case _           => GenEntry(e.inputs, e.output, e.invoke, e.fresh, shared = false, e.resetFn)
