package registry

import izumi.reflect.macrortti.LightTypeTag

/**
 * An entry in the registry.
 *
 * This is essentially a manually-typed version of a function value, where the input and output types are captured as LightTypeTags.
 * The `invoke` function is a dynamically-typed implementation of the function, which will be called with arguments
 * resolved from the registry and must produce a value of the output type produced by `fun` or `value`.
 */
final case class Entry(
    inputs: List[LightTypeTag],
    output: LightTypeTag,
    invoke: Seq[Any] => Any
)
