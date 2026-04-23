package registry

import registry.TypeChecks.Concat

/**
 * An [[Entry]] tagged with its input and output types at the type level.
 *
 * `Ins` is a tuple of the input types (in declaration order); `Out` is the output type.
 * Carried purely as phantom type information — at runtime this is just a wrapper around the untyped [[Entry]].
 */
final case class TypedEntry[Ins <: Tuple, Out](entry: Entry):

  /** `leftEntry +: rightEntry` — strict; the left entry's inputs must be covered by the right entry's output. */
  inline def +:[LIns <: Tuple, LOut](
      l: TypedEntry[LIns, LOut]
  ): Registry[Concat[LIns, Ins], LOut *: Out *: EmptyTuple] =
    ${ StrictPrependMacro.entryIntoEntry[LIns, LOut, Ins, Out]('this, 'l) }

  /** `leftRegistry +: rightEntry` — strict; the left registry's inputs must be covered by the right entry's output. */
  inline def +:[LIns <: Tuple, LOuts <: Tuple](
      l: Registry[LIns, LOuts]
  ): Registry[Concat[LIns, Ins], Concat[LOuts, Out *: EmptyTuple]] =
    ${ StrictPrependMacro.registryIntoEntry[LIns, LOuts, Ins, Out]('this, 'l) }

  // ---- tracked (*: — no compile-time check) ----

  /** `leftEntry *: rightEntry` — tracked, no check; both entries are combined into a 2-entry registry. */
  def *:[LIns <: Tuple, LOut](
      l: TypedEntry[LIns, LOut]
  ): Registry[Concat[LIns, Ins], LOut *: Out *: EmptyTuple] =
    Registry(l.entry :: entry :: Nil)

  /** `leftRegistry *: rightEntry` — tracked, no check; the left registry's entries come first. */
  def *:[LIns <: Tuple, LOuts <: Tuple](
      l: Registry[LIns, LOuts]
  ): Registry[Concat[LIns, Ins], Concat[LOuts, Out *: EmptyTuple]] =
    Registry(l.entries ++ (entry :: Nil))

  // ---- untracked (-: — receiver's types kept; left is invisible to makeSafe) ----

  /** `leftEntry -: rightEntry` — untracked left; result reflects only the receiver's types. */
  def -:[LIns <: Tuple, LOut](l: TypedEntry[LIns, LOut]): Registry[Ins, Out *: EmptyTuple] =
    Registry(l.entry :: entry :: Nil)

  /** `leftRegistry -: rightEntry` — untracked left; result reflects only the receiver's types. */
  def -:[LIns <: Tuple, LOuts <: Tuple](l: Registry[LIns, LOuts]): Registry[Ins, Out *: EmptyTuple] =
    Registry(l.entries ++ (entry :: Nil))

  /** `leftRawEntry -: rightEntry` — overload for manually-constructed `Entry` on the left. */
  def -:(l: Entry): Registry[Ins, Out *: EmptyTuple] =
    Registry(l :: entry :: Nil)
