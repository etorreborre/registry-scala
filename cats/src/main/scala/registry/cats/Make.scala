package registry.cats

import izumi.reflect.Tag
import _root_.cats.data.Validated
import registry.Registry

/**
 * Non-throwing resolution variants. Any exception raised by the runtime resolver (missing input, cycle,
 * type mismatch, user code throwing inside a registered function) is caught and wrapped in the result
 * type.
 */
extension [AllIns <: Tuple, AllOuts <: Tuple](r: Registry[AllIns, AllOuts])

  /** Like `make[T]` but returns `Left(throwable)` on resolution failure instead of throwing. */
  def makeEither[T](using tag: Tag[T]): Either[Throwable, T] =
    try Right(r.make[T])
    catch case scala.util.control.NonFatal(t) => Left(t)

  /** Like `make[T]` but returns `Invalid(throwable)` on resolution failure instead of throwing.
   *
   * Note the error channel is a single `Throwable`, not a `NonEmptyList`: registry resolution produces
   * at most one error (the first missing input, cycle, or user exception). `Validated` is offered over
   * `Either` so users can combine multiple `makeValidated` calls applicatively at the call site.
   */
  def makeValidated[T](using tag: Tag[T]): Validated[Throwable, T] =
    Validated.catchNonFatal(r.make[T])
