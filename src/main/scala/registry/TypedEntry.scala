package registry

/** An [[Entry]] tagged with its input and output types at the type level.
 *
 * `Ins` is a tuple of the input types (in declaration order); `Out` is the output type.
 * Carried purely as phantom type information — at runtime this is just a wrapper around the untyped [[Entry]].
 */
final case class TypedEntry[Ins <: Tuple, Out](entry: Entry)
