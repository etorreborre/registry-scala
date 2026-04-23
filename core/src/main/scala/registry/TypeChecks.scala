package registry

object TypeChecks:

  /** True iff `T` is an element of the tuple `Xs`. */
  type Contains[T, Xs <: Tuple] <: Boolean = Xs match
    case EmptyTuple => false
    case T *: _     => true
    case _ *: ts    => Contains[T, ts]

  /** True iff every element of `Ins` is contained in `Outs`. */
  type AllIn[Ins <: Tuple, Outs <: Tuple] <: Boolean = Ins match
    case EmptyTuple => true
    case i *: rest =>
      Contains[i, Outs] match
        case true  => AllIn[rest, Outs]
        case false => false

  /** Tuple concatenation. */
  type Concat[A <: Tuple, B <: Tuple] <: Tuple = A match
    case EmptyTuple => B
    case a *: as    => a *: Concat[as, B]
